import express from 'express';
import cors from 'cors';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { readFileSync } from 'node:fs';
import { initDb } from './db.js';
import {
  authMiddleware, requireAuth, createSession, deleteSession,
  getUserByEmail,
  getUserByPlexId, createUserFromPlex, updatePlexUserInfo,
  userCount,
  getPlexOAuthConfig, isPlexOAuthConfigured,
  createOAuthState, consumeOAuthState, purgeExpiredSessions,
  parseDeviceName, listActiveSessions, setSessionDeviceName, getSessionDeviceName,
} from './auth.js';
import { ping, getRandomSongs, search, scrobble, getSettings, getConnection } from './subsonic.js';
import {
  recordPlayEvent, fetchStatsFor, fetchTopRated, fetchMostPlayed, fetchRecentlyPlayed,
  fetchQueue, addToQueue, dequeueNext, clearQueue, clearAutoQueue,
  getNowPlaying, updateNowPlaying, updatePlaybackPosition, voteOnCurrent, getVoteCounts, clearOldVotes,
  getCooldownMinutes, setCooldownMinutes, getMaxRequestsPerUser, setMaxRequestsPerUser,
  getJukeboxMode, setJukeboxMode,
  saveConnection, clearConnection, isHost, getHostUserId, autoConfigureFromEnv, assignHostIfUnset,
  cleanupStaleState,
  getActivePlayerSession, setActivePlayerSession, isActivePlayerSession,
} from './store.js';
import { initWebSocket, broadcastQueue, broadcastNowPlaying, broadcastVotes, broadcastPlayerSession, broadcastForceSkip, broadcastJukebox, broadcastPlaybackPosition } from './realtime.js';
import {
  beginPlexSetup, buildPlexAuthUrl, canAccessConfiguredPlexLibrary, clearPlexSetup, connectOwnedPlexServer,
  createPlexPin, getActivePlexSource, getPersistedPlexSource, getPlexAccount, getPlexPin, getPlexRandomTracks, getPlexRelatedTracks, getPlexServerInfo, getPlexTrack,
  getPlexSetup, getPlexTrackArtworkUrl, getPlexTrackStreamUrl, listOwnedPlexServers, listPlexMusicLibraries,
  plexHeaders, ratePlexTrack, savePersistedPlexSource, scrobblePlexTrack, searchPlexTracks,
} from './plex.js';

const __dirname = dirname(fileURLToPath(import.meta.url));
const PORT = process.env.PORT || 3001;
const SERVER_VERSION = process.env.RESONANCE_VERSION || '1.0.0';

initDb();
// Retain the legacy Subsonic environment fallback for upgrades. A normal
// Plex-only deployment has none of these variables and enters first-run setup.
autoConfigureFromEnv();
purgeExpiredSessions();
cleanupStaleState();

const app = express();

// Restrict cross-origin access to the app's own public URL when one is
// configured. A wildcard origin on an authenticated API lets any site the user
// visits read API responses if a token ever leaks.
const PUBLIC_URL = process.env.PUBLIC_URL?.trim().replace(/\/+$/, '') || null;
app.use(cors(PUBLIC_URL ? { origin: PUBLIC_URL } : { origin: false }));
app.use(express.json({ limit: '256kb' }));
app.use(authMiddleware);

function requireActivePlayer(req, res, next) {
  if (!isHost(req.user.id)) {
    return res.status(403).json({ error: 'Only the host can control playback' });
  }
  if (!req.token || !isActivePlayerSession(req.token)) {
    return res.status(409).json({ error: 'This device is not the active player' });
  }
  next();
}

// ── Jukebox Auto-fill ────────────────────────────────────────────────

let jukeboxFillInProgress = false;

function plexJukeboxWeight(song) {
  const rating = song.userRating ?? 5;
  const ratingWeight = Math.pow(Math.max(0.1, rating), 1.6);
  const hoursSincePlayed = song.lastViewedAt
    ? Math.max(0, (Date.now() - new Date(song.lastViewedAt).getTime()) / 3_600_000)
    : Infinity;
  const recencyWeight = Math.min(1, hoursSincePlayed / 6);
  const playWeight = 1 + Math.log(song.viewCount + 1) * 0.15;
  const skipWeight = 1 / (1 + song.skipCount * 0.1);
  return ratingWeight * recencyWeight * playWeight * skipWeight;
}

function choosePlexJukeboxTracks(songs, count) {
  const pool = [...songs];
  const selected = [];
  while (pool.length && selected.length < count) {
    const total = pool.reduce((sum, song) => sum + plexJukeboxWeight(song), 0);
    let pick = Math.random() * total;
    let index = pool.length - 1;
    for (let i = 0; i < pool.length; i++) {
      pick -= plexJukeboxWeight(pool[i]);
      if (pick <= 0) { index = i; break; }
    }
    selected.push(pool.splice(index, 1)[0]);
  }
  return selected;
}

async function fillJukeboxQueue() {
  if (jukeboxFillInProgress) return;
  jukeboxFillInProgress = true;
  try {
    await fillJukeboxQueueOnce();
  } finally {
    jukeboxFillInProgress = false;
  }
}

async function fillJukeboxQueueOnce() {
  if (!getJukeboxMode()) return;

  const currentQueue = fetchQueue();
  console.log(`Jukebox auto-fill check: queue size is ${currentQueue.length}`);
  if (currentQueue.length >= 5) return;

  const plexSource = getActivePlexSource();
  const conn = plexSource ? null : getConnection();
  if (!plexSource && !conn) {
    console.log('Jukebox auto-fill: no connection configured');
    return;
  }

  try {
    const target = 5 - currentQueue.length;
    let added = 0;

    if (plexSource) {
      // Plex is the canonical shared taste/history store. Its metadata is
      // already scoped to the Plex owner token used by this proxy.
      const randomSongs = await getPlexRandomTracks(plexSource, 100);
      for (const song of choosePlexJukeboxTracks(randomSongs, target)) {
        try {
          addToQueue({ song, userId: null, userEmail: 'Jukebox', isManual: false });
          added++;
        } catch { /* skip duplicates/cooldown */ }
      }
    } else {
      // Legacy Subsonic fallback retains the pre-Plex local-stat behaviour.
      const topRated = fetchTopRated(50, 5.0);
      if (topRated.length > 0) {
      console.log(`Jukebox auto-fill: found ${topRated.length} rated songs to pick from`);
      // Shuffle them locally
      const shuffled = topRated.sort(() => Math.random() - 0.5);
      for (const row of shuffled) {
        if (added >= target) break;
        try {
          const song = {
            id: row.song_id, title: row.title, artist: row.artist,
            album: row.album, duration: row.duration, coverArt: row.cover_art
          };
          addToQueue({ song, userId: null, userEmail: 'Jukebox', isManual: false });
          added++;
        } catch { /* skip duplicates/cooldown */ }
      }
      }

      if (added < target) {
        const randomSongs = await getRandomSongs(conn!, 20);
        console.log(`Jukebox auto-fill: music source returned ${randomSongs.length} random songs`);
        for (const song of randomSongs) {
          if (added >= target) break;
          try {
            addToQueue({ song, userId: null, userEmail: 'Jukebox', isManual: false });
            added++;
          } catch { /* skip duplicates/cooldown */ }
        }
      }
    }

    if (added > 0) {
      console.log(`Jukebox auto-fill: added ${added} songs to queue`);
      broadcastQueue();
    } else {
      console.log('Jukebox auto-fill: no songs could be added (all on cooldown or duplicate?)');
    }
  } catch (err) {
    console.error('Failed to fill jukebox queue:', err);
  }
}

// ── Auth config (public) ───────────────────────────────────────────────

interface PendingPlexPin {
  id: number;
  code: string;
  expiresAt: number;
}

const pendingPlexPins = new Map<string, PendingPlexPin>();

function prunePendingPlexPins(): void {
  const now = Date.now();
  for (const [state, pin] of pendingPlexPins) {
    if (pin.expiresAt <= now) pendingPlexPins.delete(state);
  }
}

function consumePendingPlexPin(state: unknown): PendingPlexPin | null {
  if (typeof state !== 'string' || !state) return null;
  const pin = pendingPlexPins.get(state);
  pendingPlexPins.delete(state);
  return pin && pin.expiresAt > Date.now() ? pin : null;
}

app.get('/api/auth/config', (req, res) => {
  res.json({
    plexOAuth: isPlexOAuthConfigured(),
    plexSourceConfigured: !!getActivePlexSource(),
    setupInProgress: !!getPlexSetup(),
  });
});

app.get('/api/version', requireAuth, (req, res) => {
  res.json({ version: SERVER_VERSION });
});

// ── Plex source discovery (owner only) ───────────────────────────────

app.get('/api/plex/source', requireAuth, async (req, res) => {
  if (!isHost(req.user.id)) return res.status(403).json({ error: 'Only the host can inspect the Plex source' });
  const source = getActivePlexSource();
  if (!source) return res.json({ configured: false });
  try {
    const server = await getPlexServerInfo(source);
    const libraries = await listPlexMusicLibraries(source);
    res.json({
      configured: true,
      server,
      libraries,
      selectedLibraryKey: source.libraryKey,
    });
  } catch (err) {
    // The underlying error may mention a private LAN address; retain it only
    // in server logs, not in a response visible to browsers.
    console.error('Plex source discovery error:', err);
    res.status(502).json({ error: 'Could not reach the configured Plex server' });
  }
});

app.get('/api/plex/tracks/:id', requireAuth, async (req, res) => {
  const source = getActivePlexSource();
  if (!source) return res.status(404).json({ error: 'No Plex source configured' });
  try {
    const track = await getPlexTrack(source, req.params.id);
    res.json({
      id: track.id,
      title: track.title,
      artist: track.artist,
      album: track.album,
      duration: track.duration,
      coverArt: track.coverArt,
      rating: track.userRating,
      playCount: track.viewCount,
      skipCount: track.skipCount,
      lastPlayedAt: track.lastViewedAt,
    });
  } catch (err) {
    console.error('Plex track metadata error:', err);
    res.status(502).json({ error: 'Could not load Plex track metadata' });
  }
});

// ── Plex OAuth (PIN/forwarding flow) ─────────────────────────────────

app.get('/api/auth/plex', async (req, res) => {
  if (!isPlexOAuthConfigured()) {
    return res.status(400).json({ error: 'Plex sign-in needs PUBLIC_URL to be configured.' });
  }
  const mobileRedirect = req.query.mobile_redirect === 'resonance://auth'
    ? 'resonance://auth'
    : null;
  const state = createOAuthState(mobileRedirect);
  try {
    const pin = await createPlexPin();
    const { publicUrl } = getPlexOAuthConfig();
    prunePendingPlexPins();
    pendingPlexPins.set(state, { id: pin.id, code: pin.code, expiresAt: Date.now() + 10 * 60 * 1000 });
    const forwardUrl = `${publicUrl}/api/auth/plex/callback?state=${encodeURIComponent(state)}`;
    res.redirect(buildPlexAuthUrl(pin, forwardUrl));
  } catch (err) {
    console.error('Plex OAuth start error:', err);
    res.redirect('/?auth_error=oauth_failed');
  }
});

app.get('/api/auth/plex/callback', async (req, res) => {
  const pendingState = consumeOAuthState(req.query.state);
  if (!pendingState) return res.redirect('/?auth_error=invalid_state');

  const pin = consumePendingPlexPin(req.query.state);
  if (!pin) return res.redirect('/?auth_error=invalid_state');

  // The authorization PIN and resulting Plex access token remain server-side.
  // The callback URL contains only our short-lived opaque state value.
  try {
    const claimedPin = await getPlexPin(pin);
    if (!claimedPin.authToken) return res.redirect('/?auth_error=oauth_failed');
    const account = await getPlexAccount(claimedPin.authToken);
    const isFirstUser = userCount() === 0;
    const plexSource = getActivePlexSource();

    // Once a source is selected, Plex library sharing—not a Resonance email
    // allowlist—decides whether a guest may sign in.
    if (plexSource && !isFirstUser && !(await canAccessConfiguredPlexLibrary(claimedPin.authToken))) {
      return res.redirect('/?auth_error=not_shared');
    }
    if (!plexSource && !isFirstUser) {
      const setup = getPlexSetup();
      const setupUser = getUserByPlexId(account.id) ?? getUserByEmail(account.email);
      if (!setup || !setupUser || setup.userId !== setupUser.id) {
        return res.redirect('/?auth_error=setup_required');
      }
    }

    let user = getUserByPlexId(account.id);
    if (!user) {
      user = getUserByEmail(account.email);
      if (user) {
        updatePlexUserInfo(user.id, account.id, account.name);
      } else {
        const userId = createUserFromPlex(account.id, account.email, account.name);
        user = { id: userId, email: account.email };
      }
    }

    if (!plexSource) beginPlexSetup(user.id, claimedPin.authToken);
    else if (isFirstUser) assignHostIfUnset(user.id);

    const token = createSession(user.id, parseDeviceName(req.headers['user-agent']));
    const target = pendingState.mobileRedirect || '/';
    if (target === 'resonance://auth') {
      return res.redirect(`${target}?auth_token=${token}&auth_email=${encodeURIComponent(user.email)}`);
    }
    return res.redirect(`${target}#auth_token=${token}&auth_email=${encodeURIComponent(user.email)}`);
  } catch (err) {
    console.error('Plex OAuth callback error:', err);
    return res.redirect('/?auth_error=oauth_failed');
  }
});

// ── First-run Plex source selection ──────────────────────────────────

function requirePlexSetupOwner(req, res): { token: string } | null {
  if (getActivePlexSource()) {
    res.status(409).json({ error: 'A Plex source is already configured' });
    return null;
  }
  const setup = getPlexSetup();
  if (!setup || setup.userId !== req.user.id) {
    res.status(403).json({ error: 'Only the first Plex account can complete setup' });
    return null;
  }
  return { token: setup.token };
}

app.get('/api/setup/plex/servers', requireAuth, async (req, res) => {
  const setup = requirePlexSetupOwner(req, res);
  if (!setup) return;
  try {
    const servers = await listOwnedPlexServers(setup.token);
    res.json({ servers: servers.map(({ machineIdentifier, name }) => ({ machineIdentifier, name })) });
  } catch (err) {
    console.error('Plex setup server discovery error:', err);
    res.status(502).json({ error: 'Could not retrieve owned Plex servers' });
  }
});

app.get('/api/setup/plex/servers/:machineIdentifier/libraries', requireAuth, async (req, res) => {
  const setup = requirePlexSetupOwner(req, res);
  if (!setup) return;
  try {
    const server = (await listOwnedPlexServers(setup.token)).find((item) => item.machineIdentifier === req.params.machineIdentifier);
    if (!server) return res.status(404).json({ error: 'Plex server not found or not owned by this account' });
    const connection = await connectOwnedPlexServer(setup.token, server);
    const libraries = await listPlexMusicLibraries(connection);
    res.json({ server: { machineIdentifier: server.machineIdentifier, name: server.name }, libraries });
  } catch (err) {
    console.error('Plex setup library discovery error:', err);
    res.status(502).json({ error: 'Could not reach that Plex server' });
  }
});

app.post('/api/setup/plex/select', requireAuth, async (req, res) => {
  const setup = requirePlexSetupOwner(req, res);
  if (!setup) return;
  const machineIdentifier = typeof req.body?.machineIdentifier === 'string' ? req.body.machineIdentifier : '';
  const libraryKey = typeof req.body?.libraryKey === 'string' ? req.body.libraryKey : '';
  if (!machineIdentifier || !/^\d+$/.test(libraryKey)) return res.status(400).json({ error: 'A Plex server and Music library are required' });
  try {
    const server = (await listOwnedPlexServers(setup.token)).find((item) => item.machineIdentifier === machineIdentifier);
    if (!server) return res.status(404).json({ error: 'Plex server not found or not owned by this account' });
    const connection = await connectOwnedPlexServer(setup.token, server);
    const libraries = await listPlexMusicLibraries(connection);
    const library = libraries.find((item) => item.key === libraryKey);
    if (!library) return res.status(400).json({ error: 'Choose a Music library from the selected Plex server' });
    savePersistedPlexSource({ ...connection, machineIdentifier: server.machineIdentifier, serverName: server.name, libraryKey, libraryName: library.title });
    clearPlexSetup();
    assignHostIfUnset(req.user.id);
    res.json({ ok: true, serverName: server.name, libraryName: library.title });
  } catch (err) {
    console.error('Plex setup selection error:', err);
    res.status(502).json({ error: 'Could not save the Plex source selection' });
  }
});

app.post('/api/auth/signout', requireAuth, (req, res) => {
  deleteSession(req.token);
  res.json({ ok: true });
});

app.get('/api/auth/me', requireAuth, (req, res) => {
  res.json({ user: { id: req.user.id, email: req.user.email, name: req.user.name } });
});

// ── Connection ────────────────────────────────────────────────────────

app.get('/api/connection', requireAuth, (req, res) => {
  const plexSource = getActivePlexSource();
  const s = getSettings();
  const hostUserId = s?.host_user_id;
  const activeSession = getActivePlayerSession();
  const activeDeviceName = activeSession ? getSessionDeviceName(activeSession) : null;
  if (plexSource) {
    const persistedSource = getPersistedPlexSource();
    const sourceName = persistedSource
      ? `${persistedSource.serverName} · ${persistedSource.libraryName}`
      : 'Plex Music';
    return res.json({
      configured: true,
      baseUrl: plexSource.baseUrl,
      serverName: sourceName,
      isHost: hostUserId === req.user.id,
      isActivePlayer: req.token === activeSession,
      hasActivePlayer: !!activeSession,
      activePlayerDeviceName: activeDeviceName,
    });
  }
  if (!s || !s.base_url) {
    const setup = getPlexSetup();
    return res.json({ configured: false, needsPlexSetup: !!setup, isSetupOwner: setup?.userId === req.user.id });
  }
  res.json({
    configured: true,
    baseUrl: s.base_url,
    username: s.username,
    serverName: s.server_name,
    isHost: hostUserId === req.user.id,
    isActivePlayer: req.token === activeSession,
    hasActivePlayer: !!activeSession,
    activePlayerDeviceName: activeDeviceName,
  });
});

app.post('/api/connection', requireAuth, async (req, res) => {
  // Only the existing host may change the connection. When no host has been
  // assigned yet, the first caller performs the initial setup and becomes host.
  const currentHostId = getHostUserId();
  if (currentHostId !== null && currentHostId !== req.user.id) {
    return res.status(403).json({ error: 'Only the host can change the music server connection' });
  }
  if (getActivePlexSource()) {
    return res.status(409).json({ error: 'Plex is configured through Resonance setup' });
  }

  const { baseUrl, username, password, serverName } = req.body;
  if (!baseUrl || !username || !password) {
    return res.status(400).json({ error: 'Server address, username, and password are required' });
  }

  const normalized = baseUrl.trim().replace(/\/+$/, '');
  const conn = { baseUrl: normalized, username: username.trim(), password, serverName };

  try {
    await ping(conn);
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    console.error('Connection test failed:', msg);
    return res.status(400).json({ error: msg || 'Could not connect to the music server' });
  }

  saveConnection({ ...conn, hostUserId: currentHostId ?? req.user.id });
  res.json({ ok: true, isHost: true });
});

app.delete('/api/connection', requireAuth, (req, res) => {
  if (!isHost(req.user.id)) return res.status(403).json({ error: 'Only the host can disconnect' });
  setActivePlayerSession(null);
  clearConnection();
  broadcastQueue();
  broadcastNowPlaying();
  broadcastPlayerSession();
  res.json({ ok: true });
});

// ── Active playback device ────────────────────────────────────────────

// Claim a session token (this device or another) as the active player. The
// host can switch playback to any of their own logged-in devices by passing its
// token; omitting the token defaults to the calling device.
app.post('/api/player/claim', requireAuth, (req, res) => {
  if (!isHost(req.user.id)) return res.status(403).json({ error: 'Only the host can claim playback' });
  const targetToken = (typeof req.body?.token === 'string' && req.body.token) || req.token;
  // When switching to another device, verify the target is one of the host's
  // own active sessions. For the calling device (no token passed), trust the
  // authenticated session directly — it already passed requireAuth.
  if (targetToken !== req.token) {
    const owned = listActiveSessions(req.user.id).some(s => s.token === targetToken);
    if (!owned) return res.status(400).json({ error: 'Unknown device' });
  }
  setActivePlayerSession(targetToken);
  broadcastPlayerSession();
  res.json({ ok: true, isActivePlayer: targetToken === req.token });
});

app.get('/api/player/status', requireAuth, (req, res) => {
  const activeSession = getActivePlayerSession();
  res.json({
    isActivePlayer: req.token === activeSession,
    hasActivePlayer: !!activeSession,
  });
});

// List the host's active (non-expired) login sessions, each tagged with the
// device label captured at sign-in. Lets the host pick a playback device from a
// dropdown instead of walking over to that device and clicking a button.
app.get('/api/sessions', requireAuth, (req, res) => {
  if (!isHost(req.user.id)) return res.status(403).json({ error: 'Only the host can list sessions' });
  const activeSession = getActivePlayerSession();
  const sessions = listActiveSessions(req.user.id).map(s => ({
    token: s.token,
    deviceName: s.deviceName || 'Unknown device',
    createdAt: s.createdAt,
    isActivePlayer: s.token === activeSession,
  }));
  res.json(sessions);
});

// Let the host rename the current device's label so the dropdown stays readable
// after a browser/OS change or a generic "Chrome · macOS" default.
app.put('/api/session/device-name', requireAuth, (req, res) => {
  const { deviceName } = req.body || {};
  if (typeof deviceName !== 'string' || !deviceName.trim()) {
    return res.status(400).json({ error: 'A device name is required' });
  }
  setSessionDeviceName(req.token, deviceName.trim().slice(0, 100));
  res.json({ ok: true });
});

// ── Subsonic proxy ────────────────────────────────────────────────────

app.post('/api/subsonic', requireAuth, async (req, res) => {
  const conn = getConnection();
  if (!conn) return res.status(400).json({ error: 'No music server configured' });

  const { endpoint, query } = req.body;
  if (!endpoint || !/^[a-zA-Z0-9]+$/.test(endpoint)) {
    return res.status(400).json({ error: 'Invalid endpoint' });
  }

  try {
    const { authParams } = await import('./subsonic.js');
    const auth = authParams(conn);
    const params = new URLSearchParams({ ...auth, f: 'json' });
    for (const [k, v] of Object.entries(query || {})) {
      if (v !== undefined && v !== null) params.set(k, String(v));
    }

    const url = `${conn.baseUrl.replace(/\/+$/, '')}/rest/${endpoint}.view?${params.toString()}`;
    const upstream = await fetch(url, { headers: { Accept: 'application/json' } });
    const text = await upstream.text();
    let parsed;
    try { parsed = JSON.parse(text); } catch {
      return res.status(502).json({ error: `Server returned invalid response (status ${upstream.status})` });
    }
    res.json(parsed);
  } catch (err) {
    // Upstream errors embed the private music server address; log, do not leak.
    console.error('Subsonic proxy error:', err);
    res.status(502).json({ error: 'Could not reach the music server' });
  }
});

// ── Queue ─────────────────────────────────────────────────────────────

app.get('/api/queue', requireAuth, (req, res) => {
  const rows = fetchQueue();
  res.json(rows.map(r => ({
    id: r.song_id, title: r.title, artist: r.artist, album: r.album,
    duration: r.duration, coverArt: r.cover_art,
    addedByEmail: r.added_by_email, isManual: !!r.is_manual,
  })));
});

app.post('/api/queue', requireAuth, (req, res) => {
  const song = req.body.song || req.body;
  try {
    addToQueue({ song, userId: req.user.id, userEmail: req.user.email, isManual: true });
    broadcastQueue();
    res.json({ ok: true });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.post('/api/queue/auto', requireAuth, requireActivePlayer, (req, res) => {
  const song = req.body.song || req.body;
  try {
    addToQueue({ song, userId: req.user.id, userEmail: req.user.email, isManual: false });
    broadcastQueue();
    res.json({ ok: true });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.post('/api/queue/dequeue', requireAuth, requireActivePlayer, async (req, res) => {
  const head = dequeueNext();
  broadcastQueue();
  await fillJukeboxQueue();
  if (head) {
    res.json({
      song: { id: head.song_id, title: head.title, artist: head.artist, album: head.album, duration: head.duration, coverArt: head.cover_art },
      isManual: !!head.is_manual,
    });
  } else {
    res.json({ song: null, isManual: false });
  }
});

app.delete('/api/queue', requireAuth, (req, res) => {
  if (!isHost(req.user.id)) return res.status(403).json({ error: 'Only the host can clear the queue' });
  clearQueue();
  broadcastQueue();
  res.json({ ok: true });
});

app.delete('/api/queue/auto', requireAuth, (req, res) => {
  if (!isHost(req.user.id)) return res.status(403).json({ error: 'Only the host can clear auto songs' });
  clearAutoQueue();
  broadcastQueue();
  res.json({ ok: true });
});

app.post('/api/queue/similar', requireAuth, requireActivePlayer, async (req, res) => {
  const np = getNowPlaying() as any;
  const source = getActivePlexSource();
  if (!source) return res.status(409).json({ error: 'Similar-track playback requires Plex' });
  if (!np?.song_id) return res.status(400).json({ error: 'No song is currently playing' });
  try {
    const songs = await getPlexRelatedTracks(source, np.song_id, 20);
    clearAutoQueue();
    let added = 0;
    for (const song of songs) {
      try {
        addToQueue({ song, userId: null, userEmail: 'Similar tracks', isManual: false });
        added++;
      } catch { /* skip duplicates and cooldown entries */ }
    }
    broadcastQueue();
    res.json({ ok: true, added });
  } catch (err) {
    console.error('Similar tracks failed:', err);
    res.status(502).json({ error: 'Could not load Plex Track Radio' });
  }
});

// ── Now Playing ───────────────────────────────────────────────────────

app.get('/api/now-playing', requireAuth, (req, res) => {
  const np = getNowPlaying();
  if (!np || !np.song_id) return res.json({ song: null, isPlaying: false, isAutoQueue: false, playbackPosition: 0 });
  res.json({
    song: { id: np.song_id, title: np.title, artist: np.artist, album: np.album, duration: np.duration, coverArt: np.cover_art },
    isPlaying: !!np.is_playing,
    isAutoQueue: !!np.is_auto_queue,
    playbackPosition: np.playback_position || 0,
  });
});

// Active player saves its playback position periodically so a device
// switch can resume from the same spot (hot-swap).
app.put('/api/now-playing/position', requireAuth, requireActivePlayer, (req, res) => {
  const { position } = req.body || {};
  const n = Number(position);
  if (!Number.isFinite(n) || n < 0) return res.status(400).json({ error: 'Invalid position' });
  updatePlaybackPosition(n);
  broadcastPlaybackPosition(n);
  res.json({ ok: true });
});

app.post('/api/now-playing', requireAuth, requireActivePlayer, (req, res) => {
  const { song, isPlaying, isAutoQueue } = req.body;
  updateNowPlaying(song, isPlaying, isAutoQueue ?? false);
  broadcastNowPlaying();
  res.json({ ok: true });
});

// ── Votes ─────────────────────────────────────────────────────────────

app.post('/api/vote', requireAuth, async (req, res) => {
  const { vote } = req.body || {};
  try {
    if (vote !== 'up' && vote !== 'down') throw new Error('Invalid vote');
    const plexSource = getActivePlexSource();
    if (plexSource) {
      const np = getNowPlaying() as any;
      if (!np?.song_id) throw new Error('No song is currently playing');
      const track = await getPlexTrack(plexSource, np.song_id);
      const rating = await ratePlexTrack(plexSource, np.song_id, (track.userRating ?? 5) + (vote === 'up' ? 1 : -1));
      if (vote === 'down' && np.is_auto_queue) broadcastForceSkip();
      return res.json({ ok: true, rating });
    }

    // Legacy Subsonic fallback only.
    const stats = voteOnCurrent(req.user.id, vote);
    if (vote === 'down') {
      const np = getNowPlaying() as any;
      if (np?.is_auto_queue) broadcastForceSkip();
    }
    res.json({ ok: true, stats });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.get('/api/votes/:songId', requireAuth, (req, res) => {
  res.json(getVoteCounts(req.params.songId));
});

app.post('/api/votes/clear/:songId', requireAuth, requireActivePlayer, (req, res) => {
  clearOldVotes(req.params.songId);
  broadcastVotes();
  res.json({ ok: true });
});

// ── Stats ─────────────────────────────────────────────────────────────

// Playback outcomes only. Thumbs events are deliberately NOT accepted here:
// they belong to POST /api/vote, which enforces one vote per user per song via
// a unique constraint. Allowing them here would let any guest replay the
// request to pin a rating at its maximum or drive it to zero.
const PLAY_EVENTS = new Set(['complete', 'skip']);

app.post('/api/stats/play-event', requireAuth, requireActivePlayer, (req, res) => {
  // Plex-backed deployments keep playback history and counts in Plex. This
  // endpoint remains only for the legacy Subsonic migration path.
  if (getActivePlexSource()) return res.json({ ok: true });
  const { song_id, title, artist, album, duration, cover_art, event, progress } = req.body || {};

  if (typeof song_id !== 'string' || !song_id.trim()) {
    return res.status(400).json({ error: 'A song id is required' });
  }
  if (typeof event !== 'string' || !PLAY_EVENTS.has(event)) {
    return res.status(400).json({ error: 'Unsupported play event' });
  }

  const str = (v) => (typeof v === 'string' ? v.slice(0, 500) : '');
  const numericProgress = Number(progress);
  const numericDuration = Number(duration);

  const result = recordPlayEvent({
    song_id: song_id.trim().slice(0, 200),
    title: str(title),
    artist: str(artist),
    album: str(album),
    cover_art: str(cover_art),
    duration: Number.isFinite(numericDuration) ? Math.max(0, Math.min(86400, Math.round(numericDuration))) : 0,
    event,
    progress: Number.isFinite(numericProgress) ? Math.max(0, Math.min(1, numericProgress)) : 0,
  });
  res.json(result);
});

app.post('/api/stats/batch', requireAuth, (req, res) => {
  const { songIds } = req.body;
  res.json(fetchStatsFor(songIds));
});

app.get('/api/stats/top-rated', requireAuth, (req, res) => {
  res.json(fetchTopRated(Number(req.query.limit) || 50, Number(req.query.minRating) || 5.05));
});

app.get('/api/stats/most-played', requireAuth, (req, res) => {
  res.json(fetchMostPlayed(Number(req.query.limit) || 50));
});

app.get('/api/stats/recent', requireAuth, (req, res) => {
  res.json(fetchRecentlyPlayed(Number(req.query.limit) || 30));
});

// ── Settings ───────────────────────────────────────────────────────────

app.get('/api/settings', requireAuth, (req, res) => {
  res.json({
    cooldownMinutes: getCooldownMinutes(),
    maxRequestsPerUser: getMaxRequestsPerUser(),
    jukeboxMode: getJukeboxMode(),
    isHost: isHost(req.user.id),
  });
});

app.put('/api/settings', requireAuth, (req, res) => {
  if (!isHost(req.user.id)) return res.status(403).json({ error: 'Only the host can change settings' });
  const { cooldownMinutes, maxRequestsPerUser } = req.body || {};

  // Bound both values server-side. A negative or non-numeric limit would make
  // the queue gate in addToQueue reject every request.
  if (cooldownMinutes !== undefined) {
    const n = Number(cooldownMinutes);
    if (!Number.isFinite(n) || n < 0 || n > 10080) {
      return res.status(400).json({ error: 'Cooldown must be between 0 and 10080 minutes' });
    }
    setCooldownMinutes(Math.round(n));
  }
  if (maxRequestsPerUser !== undefined) {
    const n = Number(maxRequestsPerUser);
    if (!Number.isFinite(n) || n < 1 || n > 100) {
      return res.status(400).json({ error: 'Max requests per user must be between 1 and 100' });
    }
    setMaxRequestsPerUser(Math.round(n));
  }
  res.json({ ok: true });
});

app.post('/api/jukebox', requireAuth, requireActivePlayer, async (req, res) => {
  const { enabled } = req.body || {};
  if (typeof enabled !== 'boolean') return res.status(400).json({ error: 'enabled must be a boolean' });
  const next = enabled;
  setJukeboxMode(next);
  broadcastJukebox();
  if (next) await fillJukeboxQueue();
  res.json({ ok: true, jukeboxMode: next });
});

app.get('/api/search', requireAuth, async (req, res) => {
  const plexSource = getActivePlexSource();
  if (plexSource) {
    try {
      return res.json(await searchPlexTracks(plexSource, String(req.query.q || '')));
    } catch (err) {
      console.error('Plex search failed:', err);
      return res.status(502).json({ error: 'Could not search the Plex music library' });
    }
  }
  const conn = getConnection();
  if (!conn) return res.status(400).json({ error: 'No music server configured' });
  try {
    const songs = await search(conn, req.query.q || '');
    res.json(songs);
  } catch (err) {
    console.error('Search failed:', err);
    res.status(502).json({ error: 'Could not search the music library' });
  }
});

app.get('/api/random-songs', requireAuth, async (req, res) => {
  const plexSource = getActivePlexSource();
  if (plexSource) {
    try {
      return res.json(await getPlexRandomTracks(plexSource, Number(req.query.size) || 100));
    } catch (err) {
      console.error('Plex random songs failed:', err);
      return res.status(502).json({ error: 'Could not load tracks from the Plex music library' });
    }
  }
  const conn = getConnection();
  if (!conn) return res.status(400).json({ error: 'No music server configured' });
  try {
    const songs = await getRandomSongs(conn, Number(req.query.size) || 100);
    res.json(songs);
  } catch (err) {
    console.error('Random songs failed:', err);
    res.status(502).json({ error: 'Could not load songs from the music library' });
  }
});

app.post('/api/scrobble', requireAuth, requireActivePlayer, async (req, res) => {
  const { id, submission } = req.body || {};
  if (typeof id !== 'string' || !id) return res.status(400).json({ error: 'A track id is required' });
  const plexSource = getActivePlexSource();
  if (plexSource) {
    // The initial playback notification is not a completion. Plex's scrobble
    // endpoint is called only after a track reaches its end. Jukebox uses the
    // resulting Plex play count and last-played metadata as neutral listening
    // signals; ratings remain an explicit thumbs-only preference.
    if (submission) {
      await scrobblePlexTrack(plexSource, id);
    }
    return res.json({ ok: true });
  }
  const conn = getConnection();
  if (!conn) return res.status(400).json({ error: 'No music server configured' });
  await scrobble(conn, id, submission);
  res.json({ ok: true });
});

// ── Stream & cover art passthrough ────────────────────────────────────

app.get('/api/stream/:id', requireAuth, requireActivePlayer, async (req, res) => {
  const plexSource = getActivePlexSource();
  if (plexSource) {
    try {
      const url = await getPlexTrackStreamUrl(plexSource, req.params.id);
      const headers: Record<string, string> = plexHeaders(undefined, plexSource.token);
      if (typeof req.headers.range === 'string') headers.Range = req.headers.range;
      const upstream = await fetch(url, { headers });
      if (!upstream.ok) return res.status(502).json({ error: 'Plex rejected the stream request' });
      for (const header of ['content-type', 'content-length', 'content-range', 'accept-ranges']) {
        const value = upstream.headers.get(header);
        if (value) res.setHeader(header, value);
      }
      res.status(upstream.status);
      if (!upstream.body) return res.end();
      const { Readable } = await import('node:stream');
      Readable.fromWeb(upstream.body as never).pipe(res);
      return;
    } catch (err) {
      console.error('Plex stream failed:', err);
      return res.status(502).json({ error: 'Could not stream from the Plex music library' });
    }
  }
  const conn = getConnection();
  if (!conn) return res.status(400).json({ error: 'No music server configured' });
  const { streamUrl } = await import('./subsonic.js');
  const url = streamUrl(conn, req.params.id);
  try {
    const headers: Record<string, string> = {};
    if (typeof req.headers.range === 'string') headers.Range = req.headers.range;
    const upstream = await fetch(url, { headers });
    if (!upstream.ok) {
      return res.status(502).json({ error: 'Music server rejected the stream request' });
    }
    for (const header of ['content-type', 'content-length', 'content-range', 'accept-ranges']) {
      const value = upstream.headers.get(header);
      if (value) res.setHeader(header, value);
    }
    res.status(upstream.status);
    if (!upstream.body) return res.end();
    const { Readable } = await import('node:stream');
    Readable.fromWeb(upstream.body as never).pipe(res);
  } catch {
    res.status(502).json({ error: 'Could not stream from music server' });
  }
});

app.get('/api/cover-art/:id', requireAuth, async (req, res) => {
  const plexSource = getActivePlexSource();
  if (plexSource) {
    try {
      const url = await getPlexTrackArtworkUrl(plexSource, req.params.id);
      if (!url) return res.status(404).json({ error: 'No cover art' });
      const upstream = await fetch(url, { headers: plexHeaders(undefined, plexSource.token) });
      if (!upstream.ok) return res.status(502).json({ error: 'Plex rejected the artwork request' });
      res.setHeader('Content-Type', upstream.headers.get('content-type') || 'image/jpeg');
      res.setHeader('Cache-Control', 'public, max-age=3600');
      return res.send(Buffer.from(await upstream.arrayBuffer()));
    } catch (err) {
      console.error('Plex cover art failed:', err);
      return res.status(502).json({ error: 'Could not fetch Plex artwork' });
    }
  }
  const conn = getConnection();
  if (!conn) return res.status(400).json({ error: 'No music server configured' });
  const { coverArtUrl } = await import('./subsonic.js');
  const url = coverArtUrl(conn, req.params.id, Number(req.query.size) || 300);
  if (!url) return res.status(404).json({ error: 'No cover art' });
  try {
    const upstream = await fetch(url);
    res.setHeader('Content-Type', upstream.headers.get('content-type') || 'image/jpeg');
    res.setHeader('Cache-Control', 'public, max-age=3600');
    const buf = Buffer.from(await upstream.arrayBuffer());
    res.send(buf);
  } catch {
    res.status(502).json({ error: 'Could not fetch cover art' });
  }
});

// ── Serve static frontend ─────────────────────────────────────────────

const distPath = join(__dirname, '..', 'dist');
try {
  readFileSync(join(distPath, 'index.html'));
  app.use(express.static(distPath));
  app.get('*', (req, res) => {
    if (req.path.startsWith('/api') || req.path.startsWith('/ws')) return;
    res.sendFile(join(distPath, 'index.html'));
  });
} catch {
  // dist not built yet — server runs in API-only mode
}

const server = app.listen(PORT, () => {
  console.log(`Resonance server running on port ${PORT}`);
});

initWebSocket(server);
