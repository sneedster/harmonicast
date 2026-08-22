import express from 'express';
import cors from 'cors';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { readFileSync } from 'node:fs';
import { initDb } from './db.js';
import {
  authMiddleware, requireAuth, createSession, deleteSession,
  getUserByEmail, getUserByGoogleId, createUserFromGoogle, updateGoogleUserInfo,
  isEmailAllowed, addAllowedEmail, removeAllowedEmail, listAllowedEmails,
  isGoogleOAuthConfigured, buildGoogleAuthUrl, exchangeGoogleCode, userCount,
  createOAuthState, consumeOAuthState, deleteSessionsForEmail, purgeExpiredSessions,
  parseDeviceName, listActiveSessions, setSessionDeviceName, getSessionDeviceName,
} from './auth.js';
import { ping, getRandomSongs, search, scrobble, getSettings, getConnection } from './subsonic.js';
import {
  recordPlayEvent, fetchStatsFor, fetchTopRated, fetchMostPlayed, fetchRecentlyPlayed,
  fetchQueue, fetchQueueSongs, addToQueue, dequeueNext, clearQueue, clearAutoQueue,
  getNowPlaying, updateNowPlaying, updatePlaybackPosition, voteOnCurrent, getVoteCounts, clearOldVotes,
  getCooldownMinutes, setCooldownMinutes, getMaxRequestsPerUser, setMaxRequestsPerUser,
  getJukeboxMode, setJukeboxMode,
  saveConnection, clearConnection, isHost, getHostUserId, autoConfigureFromEnv, assignHostIfUnset,
  assignHostFromAdminEmail, cleanupStaleState,
  getActivePlayerSession, setActivePlayerSession, isActivePlayerSession,
} from './store.js';
import { initWebSocket, broadcastQueue, broadcastNowPlaying, broadcastVotes, broadcastPlayerSession, broadcastForceSkip, broadcastJukebox, broadcastPlaybackPosition } from './realtime.js';

const __dirname = dirname(fileURLToPath(import.meta.url));
const PORT = process.env.PORT || 3001;

initDb();
autoConfigureFromEnv();
purgeExpiredSessions();
cleanupStaleState();
assignHostFromAdminEmail();

const app = express();

// Restrict cross-origin access to the app's own public URL when one is
// configured. A wildcard origin on an authenticated API lets any site the user
// visits read API responses if a token ever leaks.
const PUBLIC_URL = process.env.PUBLIC_URL?.trim().replace(/\/+$/, '') || null;
app.use(cors(PUBLIC_URL ? { origin: PUBLIC_URL } : { origin: false }));
app.use(express.json({ limit: '256kb' }));
app.use(authMiddleware);

// Ensure env-var auto-configuration is applied on every request,
// not just startup (handles cases where the volume persists old data).
app.use((req, _res, next) => {
  if (req.path.startsWith('/api/')) {
    autoConfigureFromEnv();
    assignHostFromAdminEmail();
  }
  next();
});

// ── Auth config (public) ───────────────────────────────────────────────

app.get('/api/auth/config', (req, res) => {
  const adminEmail = process.env.ADMIN_EMAIL?.trim() || null;
  const hasUsers = userCount() > 0;
  res.json({
    googleOAuth: isGoogleOAuthConfigured(),
    adminEmail,
    needsAdmin: !adminEmail && !hasUsers,
    hasUsers,
  });
});

// ── Google OAuth ─────────────────────────────────────────────────────

app.get('/api/auth/google', (req, res) => {
  if (!isGoogleOAuthConfigured()) {
    return res.status(400).json({ error: 'Google OAuth is not configured. Set GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET, and PUBLIC_URL environment variables.' });
  }
  res.redirect(buildGoogleAuthUrl(createOAuthState()));
});

app.get('/api/auth/google/callback', async (req, res) => {
  const { code, error, state } = req.query;
  if (error) {
    return res.redirect(`/?auth_error=${encodeURIComponent(String(error))}`);
  }
  // Reject callbacks that did not originate from a sign-in we started.
  if (!consumeOAuthState(state)) {
    return res.redirect('/?auth_error=invalid_state');
  }
  if (!code || typeof code !== 'string') {
    return res.redirect('/?auth_error=missing_code');
  }

  try {
    const userInfo = await exchangeGoogleCode(code);
    if (!userInfo || !userInfo.email_verified) {
      return res.redirect('/?auth_error=unverified_email');
    }

    const email = userInfo.email.toLowerCase();
    const adminEmail = process.env.ADMIN_EMAIL?.trim().toLowerCase();
    const isFirstUser = userCount() === 0;

    // If ADMIN_EMAIL is set, only the admin can create the first account.
    // After that, the admin (or other invited users) can invite more people.
    if (isFirstUser) {
      if (adminEmail && email !== adminEmail) {
        return res.redirect('/?auth_error=not_admin');
      }
    } else if (!isEmailAllowed(email)) {
      return res.redirect('/?auth_error=not_invited');
    }

    let user = getUserByGoogleId(userInfo.sub);
    if (!user) {
      user = getUserByEmail(email);
      if (user) {
        updateGoogleUserInfo(user.id, userInfo.sub, userInfo.name || null);
      } else {
        const userId = createUserFromGoogle(userInfo.sub, email, userInfo.name || null);
        user = { id: userId, email };
        if (isFirstUser) {
          addAllowedEmail(email, userId);
        }
      }
    }

    // If ADMIN_EMAIL is set and matches this user, always ensure they are host
    if (adminEmail && email === adminEmail) {
      const becameHost = assignHostFromAdminEmail();
      console.log(`Admin login: email=${email}, adminEmail=${adminEmail}, becameHost=${becameHost}, userId=${user.id}`);
    } else if (isFirstUser) {
      // Fallback: first user becomes host if no ADMIN_EMAIL configured
      assignHostIfUnset(user.id);
    }

    const deviceName = parseDeviceName(req.headers['user-agent']);
    const token = createSession(user.id, deviceName);
    // Return the token in the URL fragment, not the query string: fragments are
    // never transmitted to a server, so the token stays out of access logs,
    // proxy logs and Referer headers.
    res.redirect(`/#auth_token=${token}&auth_email=${encodeURIComponent(user.email)}`);
  } catch (err) {
    console.error('OAuth callback error:', err);
    res.redirect('/?auth_error=oauth_failed');
  }
});

app.post('/api/auth/signout', requireAuth, (req, res) => {
  deleteSession(req.token);
  res.json({ ok: true });
});

app.get('/api/auth/me', requireAuth, (req, res) => {
  res.json({ user: { id: req.user.id, email: req.user.email, name: req.user.name } });
});

// ── Invites (host only) ────────────────────────────────────────────────

app.get('/api/invites', requireAuth, (req, res) => {
  if (!isHost(req.user.id)) return res.status(403).json({ error: 'Only the host can manage invites' });
  res.json(listAllowedEmails());
});

app.post('/api/invites', requireAuth, (req, res) => {
  if (!isHost(req.user.id)) return res.status(403).json({ error: 'Only the host can manage invites' });
  const { email } = req.body;
  if (!email || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    return res.status(400).json({ error: 'A valid email address is required' });
  }
  addAllowedEmail(email, req.user.id);
  res.json({ ok: true });
});

app.delete('/api/invites', requireAuth, (req, res) => {
  if (!isHost(req.user.id)) return res.status(403).json({ error: 'Only the host can manage invites' });
  const { email } = req.body;
  if (!email || typeof email !== 'string') {
    return res.status(400).json({ error: 'Email is required' });
  }
  removeAllowedEmail(email);
  // Revoking an invite must also end that person's active sessions, otherwise
  // they keep full access until the server happens to restart.
  deleteSessionsForEmail(email);
  res.json({ ok: true });
});

// ── Connection ────────────────────────────────────────────────────────

app.get('/api/connection', requireAuth, (req, res) => {
  autoConfigureFromEnv();
  assignHostFromAdminEmail();
  const s = getSettings();
  if (!s || !s.base_url) return res.json({ configured: false });

  const hostUserId = s.host_user_id;
  const activeSession = getActivePlayerSession();
  const activeDeviceName = activeSession ? getSessionDeviceName(activeSession) : null;
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

app.post('/api/queue/auto', requireAuth, (req, res) => {
  if (!isHost(req.user.id)) return res.status(403).json({ error: 'Only the host can add auto songs' });
  const song = req.body.song || req.body;
  try {
    addToQueue({ song, userId: req.user.id, userEmail: req.user.email, isManual: false });
    broadcastQueue();
    res.json({ ok: true });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.post('/api/queue/dequeue', requireAuth, (req, res) => {
  if (!isHost(req.user.id)) return res.status(403).json({ error: 'Only the host can dequeue' });
  const head = dequeueNext();
  broadcastQueue();
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
app.put('/api/now-playing/position', requireAuth, (req, res) => {
  if (!isHost(req.user.id)) return res.status(403).json({ error: 'Only the host can update playback position' });
  const { position } = req.body || {};
  const n = Number(position);
  if (!Number.isFinite(n) || n < 0) return res.status(400).json({ error: 'Invalid position' });
  updatePlaybackPosition(n);
  broadcastPlaybackPosition(n);
  res.json({ ok: true });
});

app.post('/api/now-playing', requireAuth, (req, res) => {
  if (!isHost(req.user.id)) return res.status(403).json({ error: 'Only the host can update now playing' });
  const { song, isPlaying, isAutoQueue } = req.body;
  updateNowPlaying(song, isPlaying, isAutoQueue ?? false);
  broadcastNowPlaying();
  res.json({ ok: true });
});

// ── Votes ─────────────────────────────────────────────────────────────

app.post('/api/vote', requireAuth, (req, res) => {
  const { vote } = req.body || {};
  try {
    // The rating change is applied here, guarded by one-vote-per-user.
    const stats = voteOnCurrent(req.user.id, vote);
    broadcastVotes();
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

app.post('/api/votes/clear/:songId', requireAuth, (req, res) => {
  if (!isHost(req.user.id)) return res.status(403).json({ error: 'Only the host can clear votes' });
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

app.post('/api/stats/play-event', requireAuth, (req, res) => {
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

app.post('/api/jukebox', requireAuth, (req, res) => {
  if (!isHost(req.user.id)) return res.status(403).json({ error: 'Only the host can toggle jukebox mode' });
  const { enabled } = req.body || {};
  const next = !!enabled;
  setJukeboxMode(next);
  broadcastJukebox();
  res.json({ ok: true, jukeboxMode: next });
});

app.get('/api/search', requireAuth, async (req, res) => {
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

app.post('/api/scrobble', requireAuth, async (req, res) => {
  const conn = getConnection();
  if (!conn) return res.status(400).json({ error: 'No music server configured' });
  const { id, submission } = req.body;
  await scrobble(conn, id, submission);
  res.json({ ok: true });
});

// ── Stream & cover art passthrough ────────────────────────────────────

app.get('/api/stream/:id', requireAuth, async (req, res) => {
  const conn = getConnection();
  if (!conn) return res.status(400).json({ error: 'No music server configured' });
  const { streamUrl } = await import('./subsonic.js');
  const url = streamUrl(conn, req.params.id);
  try {
    const upstream = await fetch(url);
    res.setHeader('Content-Type', upstream.headers.get('content-type') || 'audio/mpeg');
    const buf = Buffer.from(await upstream.arrayBuffer());
    res.send(buf);
  } catch {
    res.status(502).json({ error: 'Could not stream from music server' });
  }
});

app.get('/api/cover-art/:id', requireAuth, async (req, res) => {
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
