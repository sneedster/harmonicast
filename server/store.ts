import { db } from './db.js';
import { randomUUID } from 'node:crypto';

// ── Stats ────────────────────────────────────────────────────────────

export function recordPlayEvent({ song_id, title, artist, album, duration, cover_art, event, progress }) {
  db.prepare(`
    INSERT INTO song_stats (song_id, title, artist, album, duration, cover_art)
    VALUES (?, ?, ?, ?, ?, ?)
    ON CONFLICT(song_id) DO UPDATE SET
      title = COALESCE(NULLIF(?, ''), song_stats.title),
      artist = COALESCE(NULLIF(?, ''), song_stats.artist),
      album = COALESCE(NULLIF(?, ''), song_stats.album),
      duration = MAX(song_stats.duration, ?),
      cover_art = COALESCE(NULLIF(?, ''), song_stats.cover_art)
  `).run(song_id, title, artist, album, duration, cover_art, title, artist, album, duration, cover_art);

  const p = Math.max(0, Math.min(1, progress || 0));

  if (event === 'complete') {
    const row = db.prepare('SELECT play_count FROM song_stats WHERE song_id = ?').get(song_id);
    const delta = 0.05 * (1 + Math.log((row?.play_count || 0) + 1));
    db.prepare(`
      UPDATE song_stats SET rating = MIN(10, rating + ?), play_count = play_count + 1,
        last_played_at = datetime('now'), updated_at = datetime('now')
      WHERE song_id = ?
    `).run(delta, song_id);
  } else if (event === 'skip') {
    const delta = 0.3 * (1 - p);
    db.prepare(`
      UPDATE song_stats SET rating = MAX(0, rating - ?), skip_count = skip_count + 1,
        last_played_at = datetime('now'), updated_at = datetime('now')
      WHERE song_id = ?
    `).run(delta, song_id);
  } else if (event === 'thumbs_up') {
    db.prepare(`
      UPDATE song_stats SET rating = MIN(10, rating + 1.0), thumbs_up = thumbs_up + 1,
        updated_at = datetime('now') WHERE song_id = ?
    `).run(song_id);
  } else if (event === 'thumbs_down') {
    db.prepare(`
      UPDATE song_stats SET rating = MAX(0, rating - 1.0), thumbs_down = thumbs_down + 1,
        updated_at = datetime('now') WHERE song_id = ?
    `).run(song_id);
  }

  const id = randomUUID();
  db.prepare(`
    INSERT INTO play_history (id, song_id, title, artist, event, progress)
    VALUES (?, ?, ?, ?, ?, ?)
  `).run(id, song_id, title, artist, event, p);

  return db.prepare('SELECT * FROM song_stats WHERE song_id = ?').get(song_id);
}

export function fetchStatsFor(songIds) {
  if (!songIds.length) return [];
  const placeholders = songIds.map(() => '?').join(',');
  return db.prepare(`SELECT * FROM song_stats WHERE song_id IN (${placeholders})`).all(...songIds);
}

export function fetchTopRated(limit = 50, minRating = 5.05) {
  return db.prepare('SELECT * FROM song_stats WHERE rating > ? ORDER BY rating DESC LIMIT ?').all(minRating, limit);
}

export function fetchMostPlayed(limit = 50) {
  return db.prepare('SELECT * FROM song_stats WHERE play_count > 0 ORDER BY play_count DESC LIMIT ?').all(limit);
}

export function fetchRecentlyPlayed(limit = 30) {
  return db.prepare('SELECT * FROM play_history ORDER BY created_at DESC LIMIT ?').all(limit);
}

// ── Queue ────────────────────────────────────────────────────────────

export function fetchQueue() {
  return db.prepare('SELECT * FROM queue ORDER BY position ASC').all();
}

export function fetchQueueSongs() {
  return db.prepare(`
    SELECT song_id as id, title, artist, album, duration, cover_art as coverArt
    FROM queue ORDER BY position ASC
  `).all();
}

export function addToQueue({ song, userId, userEmail, isManual = true }) {
  // Check duplicate
  const existing = db.prepare('SELECT COUNT(*) as c FROM queue WHERE song_id = ?').get(song.id);
  if (existing.c > 0) throw new Error('This song is already in the queue');

  // Cooldown check
  const settings = db.prepare('SELECT cooldown_minutes FROM settings WHERE id = 1').get();
  if (settings.cooldown_minutes > 0) {
    const recent = db.prepare(`
      SELECT COUNT(*) as c FROM play_history
      WHERE song_id = ? AND event IN ('complete','skip')
        AND created_at > datetime('now', ?)
    `).get(song.id, `-${settings.cooldown_minutes} minutes`);
    if (recent.c > 0) throw new Error('This song was played recently and is on cooldown');
  }

  // Per-user limit
  if (isManual) {
    const maxReq = db.prepare('SELECT max_requests_per_user FROM settings WHERE id = 1').get();
    const userCount = db.prepare('SELECT COUNT(*) as c FROM queue WHERE added_by = ? AND is_manual = 1').get(userId);
    if (userCount.c >= maxReq.max_requests_per_user) {
      throw new Error(`You have reached the limit of ${maxReq.max_requests_per_user} songs in the queue`);
    }
  }

  // Insert position
  let insertPos;
  if (isManual) {
    const minAuto = db.prepare('SELECT MIN(position) as p FROM queue WHERE is_manual = 0').get();
    if (minAuto.p !== null) {
      insertPos = minAuto.p - 1;
    } else {
      const maxPos = db.prepare('SELECT COALESCE(MAX(position), 0) as p FROM queue').get();
      insertPos = maxPos.p + 1;
    }
  } else {
    const maxPos = db.prepare('SELECT COALESCE(MAX(position), 0) as p FROM queue').get();
    insertPos = maxPos.p + 1;
  }

  const id = randomUUID();
  db.prepare(`
    INSERT INTO queue (id, song_id, title, artist, album, duration, cover_art, position, added_by, added_by_email, is_manual)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `).run(id, song.id, song.title, song.artist, song.album, Math.round(song.duration) || 0, song.coverArt,
    insertPos, userId, userEmail || '', isManual ? 1 : 0);

  // Round-robin reorder for manual adds
  if (isManual) reorderRoundRobin();

  return id;
}

function reorderRoundRobin() {
  const manual = db.prepare(`
    SELECT id, added_by, ROW_NUMBER() OVER (PARTITION BY added_by ORDER BY position) as user_seq
    FROM queue WHERE is_manual = 1
    ORDER BY user_seq, added_by
  `).all();

  let pos = 1;
  const maxSeq = manual.length > 0 ? Math.max(...manual.map(r => r.user_seq)) : 0;
  for (let seq = 1; seq <= maxSeq; seq++) {
    for (const row of manual.filter(r => r.user_seq === seq).sort((a, b) => a.added_by - b.added_by)) {
      db.prepare('UPDATE queue SET position = ? WHERE id = ?').run(pos, row.id);
      pos++;
    }
  }

  const auto = db.prepare('SELECT id FROM queue WHERE is_manual = 0 ORDER BY position').all();
  for (const row of auto) {
    db.prepare('UPDATE queue SET position = ? WHERE id = ?').run(pos, row.id);
    pos++;
  }
}

export function dequeueNext() {
  const head = db.prepare('SELECT * FROM queue ORDER BY position ASC LIMIT 1').get();
  if (!head) return null;
  db.prepare('DELETE FROM queue WHERE id = ?').run(head.id);
  return head;
}

export function clearQueue() {
  db.prepare('DELETE FROM queue').run();
}

export function clearAutoQueue() {
  db.prepare('DELETE FROM queue WHERE is_manual = 0').run();
}

// ── Now Playing ───────────────────────────────────────────────────────

export function getNowPlaying() {
  return db.prepare('SELECT * FROM now_playing WHERE id = 1').get();
}

export function updatePlaybackPosition(position: number) {
  db.prepare("UPDATE now_playing SET playback_position = ?, updated_at = datetime('now') WHERE id = 1")
    .run(Math.max(0, position));
}

export function updateNowPlaying(song, isPlaying, isAutoQueue = false) {
  db.prepare(`
    UPDATE now_playing SET
      song_id = ?, title = ?, artist = ?, album = ?, duration = ?, cover_art = ?,
      is_playing = ?, is_auto_queue = ?, playback_position = 0, updated_at = datetime('now')
    WHERE id = 1
  `).run(
    song?.id ?? null, song?.title ?? '', song?.artist ?? '', song?.album ?? '',
    song?.duration ?? 0, song?.coverArt ?? '', isPlaying ? 1 : 0, isAutoQueue ? 1 : 0
  );
}

// ── Votes ─────────────────────────────────────────────────────────────

/*
 * Records a vote for the currently playing song and applies the matching
 * rating change. The rating delta lives here, behind the UNIQUE(song_id,
 * user_id) constraint, so one user can shift a song's rating at most once.
 * It deliberately does NOT live on /api/stats/play-event, which has no such
 * constraint and could be replayed to pin a rating at 0 or 10.
 */
export function voteOnCurrent(userId, vote) {
  if (vote !== 'up' && vote !== 'down') throw new Error('Invalid vote type');
  const np = getNowPlaying();
  if (!np || !np.song_id) throw new Error('No song is currently playing');

  const songId = np.song_id;
  const previous = db.prepare('SELECT vote FROM votes WHERE song_id = ? AND user_id = ?')
    .get(songId, userId) as any;

  if (previous?.vote === vote) {
    // Same vote again: no double counting.
    return db.prepare('SELECT * FROM song_stats WHERE song_id = ?').get(songId) ?? null;
  }

  db.prepare(`
    INSERT INTO votes (id, song_id, user_id, vote) VALUES (?, ?, ?, ?)
    ON CONFLICT(song_id, user_id) DO UPDATE SET vote = excluded.vote, created_at = datetime('now')
  `).run(randomUUID(), songId, userId, vote);

  // Make sure a stats row exists for this song before adjusting it.
  db.prepare(`
    INSERT INTO song_stats (song_id, title, artist, album, duration, cover_art)
    VALUES (?, ?, ?, ?, ?, ?)
    ON CONFLICT(song_id) DO NOTHING
  `).run(songId, np.title ?? '', np.artist ?? '', np.album ?? '', np.duration ?? 0, np.cover_art ?? '');

  // Switching sides undoes the previous delta as well as applying the new one.
  const delta = (vote === 'up' ? 1.0 : -1.0) - (previous ? (previous.vote === 'up' ? 1.0 : -1.0) : 0);
  const counterColumn = vote === 'up' ? 'thumbs_up' : 'thumbs_down';

  db.prepare(`
    UPDATE song_stats
    SET rating = MAX(0, MIN(10, rating + ?)),
        ${counterColumn} = ${counterColumn} + 1,
        updated_at = datetime('now')
    WHERE song_id = ?
  `).run(delta, songId);

  db.prepare(`
    INSERT INTO play_history (id, song_id, title, artist, event, progress)
    VALUES (?, ?, ?, ?, ?, 0)
  `).run(randomUUID(), songId, np.title ?? '', np.artist ?? '', vote === 'up' ? 'thumbs_up' : 'thumbs_down');

  return db.prepare('SELECT * FROM song_stats WHERE song_id = ?').get(songId) ?? null;
}

export function getVoteCounts(songId) {
  const row = db.prepare(`
    SELECT
      SUM(CASE WHEN vote = 'up' THEN 1 ELSE 0 END) as up,
      SUM(CASE WHEN vote = 'down' THEN 1 ELSE 0 END) as down
    FROM votes WHERE song_id = ?
  `).get(songId);
  return { up: row.up || 0, down: row.down || 0 };
}

export function clearOldVotes(songId) {
  db.prepare('DELETE FROM votes WHERE song_id = ?').run(songId);
}

// ── Settings ──────────────────────────────────────────────────────────

export function getCooldownMinutes() {
  return db.prepare('SELECT cooldown_minutes FROM settings WHERE id = 1').get().cooldown_minutes;
}

export function setCooldownMinutes(minutes) {
  db.prepare('UPDATE settings SET cooldown_minutes = ?, updated_at = datetime(\'now\') WHERE id = 1').run(minutes);
}

export function getMaxRequestsPerUser() {
  return db.prepare('SELECT max_requests_per_user FROM settings WHERE id = 1').get().max_requests_per_user;
}

export function setMaxRequestsPerUser(limit) {
  db.prepare('UPDATE settings SET max_requests_per_user = ?, updated_at = datetime(\'now\') WHERE id = 1').run(limit);
}

export function getJukeboxMode() {
  return db.prepare('SELECT jukebox_mode FROM settings WHERE id = 1').get().jukebox_mode === 1;
}

export function setJukeboxMode(enabled: boolean) {
  db.prepare("UPDATE settings SET jukebox_mode = ?, updated_at = datetime('now') WHERE id = 1").run(enabled ? 1 : 0);
}

export function saveConnection({ baseUrl, username, password, serverName, hostUserId }) {
  db.prepare(`
    UPDATE settings SET
      base_url = ?, username = ?, password = ?, server_name = ?, host_user_id = ?,
      updated_at = datetime('now')
    WHERE id = 1
  `).run(baseUrl, username, password, serverName ?? null, hostUserId);
}

export function clearConnection() {
  db.prepare('DELETE FROM queue').run();
  db.prepare('DELETE FROM votes').run();
  db.prepare('UPDATE now_playing SET song_id = NULL, title = \'\', artist = \'\', album = \'\', duration = 0, cover_art = \'\', is_playing = 0, is_auto_queue = 0, playback_position = 0, updated_at = datetime(\'now\') WHERE id = 1').run();
  db.prepare('UPDATE settings SET base_url = \'\', username = \'\', password = \'\', server_name = NULL, host_user_id = NULL, active_player_session = NULL, updated_at = datetime(\'now\') WHERE id = 1').run();
}

export function isHost(userId) {
  const row = db.prepare('SELECT host_user_id FROM settings WHERE id = 1').get();
  return row.host_user_id === userId;
}

export function getHostUserId() {
  const row = db.prepare('SELECT host_user_id FROM settings WHERE id = 1').get() as any;
  return row?.host_user_id ?? null;
}

export function getActivePlayerSession() {
  const row = db.prepare('SELECT active_player_session FROM settings WHERE id = 1').get() as any;
  return row?.active_player_session ?? null;
}

export function setActivePlayerSession(token: string | null) {
  db.prepare("UPDATE settings SET active_player_session = ?, updated_at = datetime('now') WHERE id = 1").run(token);
}

export function isActivePlayerSession(token: string) {
  return getActivePlayerSession() === token;
}

export function autoConfigureFromEnv() {
  const baseUrl = process.env.MUSIC_SERVER_URL?.trim().replace(/\/+$/, '');
  const username = process.env.MUSIC_SERVER_USER?.trim();
  const password = process.env.MUSIC_SERVER_PASSWORD;
  const serverName = process.env.MUSIC_SERVER_NAME?.trim() || null;

  if (!baseUrl || !username || !password) {
    return false;
  }

  const existing = db.prepare('SELECT base_url, username, password FROM settings WHERE id = 1').get();
  if (existing.base_url === baseUrl && existing.username === username && existing.password === password) {
    return false;
  }

  db.prepare(`
    UPDATE settings SET
      base_url = ?, username = ?, password = ?, server_name = ?,
      updated_at = datetime('now')
    WHERE id = 1
  `).run(baseUrl, username, password, serverName);

  console.log('Auto-configured music server from environment variables:', baseUrl);
  return true;
}

// ── Admin / host assignment ────────────────────────────────────────────

export function assignHostFromAdminEmail() {
  const adminEmail = process.env.ADMIN_EMAIL?.trim().toLowerCase();
  if (!adminEmail) return false;

  const user = db.prepare('SELECT id FROM users WHERE email = ?').get(adminEmail);
  if (!user) return false;

  const current = db.prepare('SELECT host_user_id FROM settings WHERE id = 1').get();
  if (current.host_user_id === user.id) return false;

  db.prepare("UPDATE settings SET host_user_id = ?, updated_at = datetime('now') WHERE id = 1").run(user.id);
  console.log(`Admin user set from ADMIN_EMAIL: ${adminEmail} (user id ${user.id})`);
  return true;
}

export function assignHostIfUnset(userId) {
  const row = db.prepare('SELECT host_user_id FROM settings WHERE id = 1').get();
  if (row.host_user_id === null) {
    db.prepare('UPDATE settings SET host_user_id = ?, updated_at = datetime(\'now\') WHERE id = 1').run(userId);
    return true;
  }
  return false;
}

// ── Startup cleanup ───────────────────────────────────────────────────

export function cleanupStaleState() {
  // Sessions are no longer wiped wholesale on restart: they carry an explicit
  // expires_at which getUserByToken enforces, and purgeExpiredSessions() clears
  // the lapsed rows. Wiping every session here would log everyone out on each
  // deploy while doing nothing about a token stolen mid-uptime.

  // Clear stale active player session
  const active = getActivePlayerSession();
  if (active) {
    setActivePlayerSession(null);
    console.log('Cleared stale active player session on startup');
  }

  // Reset now-playing to stopped
  db.prepare(`
    UPDATE now_playing SET is_playing = 0, updated_at = datetime('now')
    WHERE id = 1 AND song_id IS NOT NULL
  `).run();

  // If host_user_id points to a user that no longer exists, clear it
  // so assignHostFromAdminEmail can reassign on next login.
  const settings = db.prepare('SELECT host_user_id FROM settings WHERE id = 1').get();
  if (settings.host_user_id !== null) {
    const user = db.prepare('SELECT 1 FROM users WHERE id = ?').get(settings.host_user_id);
    if (!user) {
      db.prepare("UPDATE settings SET host_user_id = NULL, updated_at = datetime('now') WHERE id = 1").run();
      console.log('Cleared stale host_user_id on startup (user no longer exists)');
    }
  }
}
