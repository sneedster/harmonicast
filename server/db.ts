import Database from 'better-sqlite3';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { mkdirSync } from 'node:fs';

const __dirname = dirname(fileURLToPath(import.meta.url));
const DATA_DIR = process.env.DATA_DIR || join(__dirname, '..', 'data');
mkdirSync(DATA_DIR, { recursive: true });
const DB_PATH = join(DATA_DIR, 'resonance.db');

export const db = new Database(DB_PATH);
db.pragma('journal_mode = WAL');
db.pragma('foreign_keys = ON');

export function initDb() {
  db.exec(`
    CREATE TABLE IF NOT EXISTS users (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      email TEXT NOT NULL UNIQUE,
      password_hash TEXT NOT NULL DEFAULT '',
      google_id TEXT,
      plex_id TEXT,
      name TEXT,
      created_at TEXT NOT NULL DEFAULT (datetime('now'))
    );

    CREATE TABLE IF NOT EXISTS allowed_emails (
      email TEXT PRIMARY KEY,
      added_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
      created_at TEXT NOT NULL DEFAULT (datetime('now'))
    );

    CREATE TABLE IF NOT EXISTS sessions (
      token TEXT PRIMARY KEY,
      user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      created_at TEXT NOT NULL DEFAULT (datetime('now')),
      expires_at TEXT NOT NULL DEFAULT (datetime('now', '+30 days')),
      device_name TEXT NOT NULL DEFAULT ''
    );

    CREATE TABLE IF NOT EXISTS settings (
      id INTEGER PRIMARY KEY DEFAULT 1,
      base_url TEXT NOT NULL DEFAULT '',
      username TEXT NOT NULL DEFAULT '',
      password TEXT NOT NULL DEFAULT '',
      server_name TEXT,
      plex_client_identifier TEXT,
      host_user_id INTEGER,
      cooldown_minutes INTEGER NOT NULL DEFAULT 30,
      max_requests_per_user INTEGER NOT NULL DEFAULT 5,
      active_player_session TEXT,
      updated_at TEXT NOT NULL DEFAULT (datetime('now')),
      CHECK (id = 1)
    );

    INSERT OR IGNORE INTO settings (id) VALUES (1);

    CREATE TABLE IF NOT EXISTS song_stats (
      song_id TEXT PRIMARY KEY,
      title TEXT NOT NULL DEFAULT '',
      artist TEXT NOT NULL DEFAULT '',
      album TEXT NOT NULL DEFAULT '',
      duration INTEGER NOT NULL DEFAULT 0,
      cover_art TEXT NOT NULL DEFAULT '',
      rating REAL NOT NULL DEFAULT 5,
      play_count INTEGER NOT NULL DEFAULT 0,
      skip_count INTEGER NOT NULL DEFAULT 0,
      thumbs_up INTEGER NOT NULL DEFAULT 0,
      thumbs_down INTEGER NOT NULL DEFAULT 0,
      last_played_at TEXT,
      created_at TEXT NOT NULL DEFAULT (datetime('now')),
      updated_at TEXT NOT NULL DEFAULT (datetime('now'))
    );

    CREATE INDEX IF NOT EXISTS idx_song_stats_rating ON song_stats(rating DESC);
    CREATE INDEX IF NOT EXISTS idx_song_stats_play_count ON song_stats(play_count DESC);

    CREATE TABLE IF NOT EXISTS play_history (
      id TEXT PRIMARY KEY,
      song_id TEXT NOT NULL,
      title TEXT NOT NULL DEFAULT '',
      artist TEXT NOT NULL DEFAULT '',
      event TEXT NOT NULL,
      progress REAL NOT NULL DEFAULT 0,
      created_at TEXT NOT NULL DEFAULT (datetime('now'))
    );

    CREATE INDEX IF NOT EXISTS idx_play_history_created ON play_history(created_at DESC);

    CREATE TABLE IF NOT EXISTS queue (
      id TEXT PRIMARY KEY,
      song_id TEXT NOT NULL,
      title TEXT NOT NULL DEFAULT '',
      artist TEXT NOT NULL DEFAULT '',
      album TEXT NOT NULL DEFAULT '',
      duration INTEGER NOT NULL DEFAULT 0,
      cover_art TEXT NOT NULL DEFAULT '',
      position REAL NOT NULL DEFAULT 0,
      added_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
      added_by_email TEXT NOT NULL DEFAULT '',
      is_manual INTEGER NOT NULL DEFAULT 1,
      created_at TEXT NOT NULL DEFAULT (datetime('now'))
    );

    CREATE INDEX IF NOT EXISTS idx_queue_position ON queue(position);

    CREATE TABLE IF NOT EXISTS now_playing (
      id INTEGER PRIMARY KEY DEFAULT 1,
      song_id TEXT,
      title TEXT NOT NULL DEFAULT '',
      artist TEXT NOT NULL DEFAULT '',
      album TEXT NOT NULL DEFAULT '',
      duration INTEGER NOT NULL DEFAULT 0,
      cover_art TEXT NOT NULL DEFAULT '',
      is_playing INTEGER NOT NULL DEFAULT 0,
      is_auto_queue INTEGER NOT NULL DEFAULT 0,
      updated_at TEXT NOT NULL DEFAULT (datetime('now')),
      CHECK (id = 1)
    );

    INSERT OR IGNORE INTO now_playing (id) VALUES (1);

    CREATE TABLE IF NOT EXISTS votes (
      id TEXT PRIMARY KEY,
      song_id TEXT NOT NULL,
      user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      vote TEXT NOT NULL CHECK (vote IN ('up','down')),
      created_at TEXT NOT NULL DEFAULT (datetime('now')),
      UNIQUE (song_id, user_id)
    );
  `);

  // Migrate existing tables: add columns that may not exist in older databases.
  const userColumns = db.prepare("PRAGMA table_info(users)").all() as { name: string }[];
  const userColNames = userColumns.map((c) => c.name);
  if (!userColNames.includes('google_id')) {
    db.exec('ALTER TABLE users ADD COLUMN google_id TEXT');
  }
  if (!userColNames.includes('plex_id')) {
    db.exec('ALTER TABLE users ADD COLUMN plex_id TEXT');
  }
  db.exec('CREATE UNIQUE INDEX IF NOT EXISTS idx_users_plex_id ON users(plex_id) WHERE plex_id IS NOT NULL');
  if (!userColNames.includes('name')) {
    db.exec('ALTER TABLE users ADD COLUMN name TEXT');
  }
  if (userColNames.includes('password_hash')) {
    db.exec("UPDATE users SET password_hash = '' WHERE password_hash IS NULL");
  }

  const sessionColumns = db.prepare("PRAGMA table_info(sessions)").all() as { name: string }[];
  const sessionColNames = sessionColumns.map((c) => c.name);
  if (!sessionColNames.includes('expires_at')) {
    // Existing rows get an already-elapsed expiry so stale tokens are rejected.
    db.exec("ALTER TABLE sessions ADD COLUMN expires_at TEXT NOT NULL DEFAULT '1970-01-01 00:00:00'");
  }
  if (!sessionColNames.includes('device_name')) {
    db.exec("ALTER TABLE sessions ADD COLUMN device_name TEXT NOT NULL DEFAULT ''");
  }

  const settingsColumns = db.prepare("PRAGMA table_info(settings)").all() as { name: string }[];
  const settingsColNames = settingsColumns.map((c) => c.name);
  if (!settingsColNames.includes('active_player_session')) {
    db.exec('ALTER TABLE settings ADD COLUMN active_player_session TEXT');
  }
  if (!settingsColNames.includes('jukebox_mode')) {
    db.exec('ALTER TABLE settings ADD COLUMN jukebox_mode INTEGER NOT NULL DEFAULT 0');
  }
  if (!settingsColNames.includes('plex_client_identifier')) {
    db.exec('ALTER TABLE settings ADD COLUMN plex_client_identifier TEXT');
  }

  const npColumns = db.prepare("PRAGMA table_info(now_playing)").all() as { name: string }[];
  const npColNames = npColumns.map((c) => c.name);
  if (!npColNames.includes('is_auto_queue')) {
    db.exec('ALTER TABLE now_playing ADD COLUMN is_auto_queue INTEGER NOT NULL DEFAULT 0');
  }
  if (!npColNames.includes('playback_position')) {
    db.exec('ALTER TABLE now_playing ADD COLUMN playback_position REAL NOT NULL DEFAULT 0');
  }
}
