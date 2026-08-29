import { randomBytes } from 'node:crypto';
import { db } from './db.js';

// ── Session management ───────────────────────────────────────────────

const SESSION_TTL_DAYS = 30;

export function createSession(userId: number, deviceName = ''): string {
  const token = randomBytes(32).toString('hex');
  db.prepare(
    `INSERT INTO sessions (token, user_id, expires_at, device_name)
     VALUES (?, ?, datetime('now', ?), ?)`
  ).run(token, userId, `+${SESSION_TTL_DAYS} days`, deviceName);
  return token;
}

/** Best-effort friendly label from a User-Agent string, e.g. "Chrome · macOS". */
export function parseDeviceName(userAgent: string | undefined): string {
  if (!userAgent) return 'Unknown device';
  const ua = userAgent;

  let browser = 'Browser';
  if (/Edg\//.test(ua)) browser = 'Edge';
  else if (/Chrome\//.test(ua) && !/Chromium\//.test(ua)) browser = 'Chrome';
  else if (/Firefox\//.test(ua)) browser = 'Firefox';
  else if (/Safari\//.test(ua) && !/Chrome\//.test(ua)) browser = 'Safari';

  let os = 'Unknown OS';
  if (/Windows/.test(ua)) os = 'Windows';
  else if (/Android/.test(ua)) os = 'Android';
  else if (/iPhone|iPad|iPod/.test(ua)) os = 'iOS';
  else if (/Mac OS X|Macintosh/.test(ua)) os = 'macOS';
  else if (/Linux/.test(ua)) os = 'Linux';

  return `${browser} · ${os}`;
}

export interface SessionInfo {
  token: string;
  deviceName: string;
  createdAt: string;
  expiresAt: string;
}

/** Returns active (non-expired) sessions for a user, oldest first. */
export function listActiveSessions(userId: number): SessionInfo[] {
  return db.prepare(
    `SELECT token, device_name AS deviceName, created_at AS createdAt, expires_at AS expiresAt
     FROM sessions
     WHERE user_id = ? AND expires_at > datetime('now')
     ORDER BY created_at ASC`
  ).all(userId) as SessionInfo[];
}

export function setSessionDeviceName(token: string, deviceName: string): void {
  db.prepare('UPDATE sessions SET device_name = ? WHERE token = ?').run(deviceName, token);
}

export function getSessionDeviceName(token: string): string | null {
  const row = db.prepare('SELECT device_name FROM sessions WHERE token = ?').get(token) as { device_name: string } | undefined;
  return row?.device_name ?? null;
}

export function deleteSession(token: string): void {
  db.prepare('DELETE FROM sessions WHERE token = ?').run(token);
}

export function purgeExpiredSessions(): void {
  db.prepare("DELETE FROM sessions WHERE expires_at <= datetime('now')").run();
}

export function getUserByToken(token: string) {
  if (!token) return null;
  // Expiry is enforced here, not just swept in the background, so an expired
  // token stops working the moment it lapses.
  const row = db.prepare(`
    SELECT u.* FROM sessions s
    JOIN users u ON s.user_id = u.id
    WHERE s.token = ? AND s.expires_at > datetime('now')
  `).get(token);
  return row || null;
}

// ── User management ───────────────────────────────────────────────────

export function getUserByEmail(email: string) {
  return db.prepare('SELECT * FROM users WHERE email = ?').get(email.toLowerCase());
}

export function getUserByPlexId(plexId: string) {
  return db.prepare('SELECT * FROM users WHERE plex_id = ?').get(plexId);
}

export function userCount(): number {
  const row = db.prepare('SELECT COUNT(*) as count FROM users').get() as { count: number };
  return row.count;
}

export function createUserFromPlex(plexId: string, email: string, name: string | null): number {
  const result = db.prepare(
    'INSERT INTO users (email, plex_id, name, password_hash) VALUES (?, ?, ?, ?)'
  ).run(email.toLowerCase(), plexId, name, '');
  return Number(result.lastInsertRowid);
}

export function updatePlexUserInfo(userId: number, plexId: string, name: string | null): void {
  db.prepare('UPDATE users SET plex_id = ?, name = ? WHERE id = ?').run(plexId, name, userId);
}

// ── OAuth state (CSRF protection for the sign-in flow) ────────────────
//
// Each sign-in attempt gets a single-use, short-lived random value carried in
// the Plex forwarding URL. It prevents a callback from a different browser
// session from being accepted.

const OAUTH_STATE_TTL_MS = 10 * 60 * 1000;
interface PendingOAuthState {
  expiresAt: number;
  mobileRedirect: string | null;
}

const pendingOAuthStates = new Map<string, PendingOAuthState>();

function pruneOAuthStates(): void {
  const now = Date.now();
  for (const [value, pending] of pendingOAuthStates) {
    if (pending.expiresAt <= now) pendingOAuthStates.delete(value);
  }
}

export function createOAuthState(mobileRedirect: string | null = null): string {
  pruneOAuthStates();
  const state = randomBytes(32).toString('hex');
  pendingOAuthStates.set(state, { expiresAt: Date.now() + OAUTH_STATE_TTL_MS, mobileRedirect });
  return state;
}

/** Verifies and consumes a state value. Returns the pending app redirect if any. */
export function consumeOAuthState(state: unknown): PendingOAuthState | null {
  if (typeof state !== 'string' || !state) return null;
  pruneOAuthStates();
  const pending = pendingOAuthStates.get(state);
  if (!pending) return null;
  pendingOAuthStates.delete(state);
  return pending.expiresAt > Date.now() ? pending : null;
}

/** Plex's OAuth-style PIN flow needs a public return URL but no client secret. */
export function getPlexOAuthConfig() {
  const publicUrl = (process.env.PUBLIC_URL || '').replace(/\/+$/, '');
  return { publicUrl };
}

export function isPlexOAuthConfigured(): boolean {
  return !!getPlexOAuthConfig().publicUrl;
}

// ── Express middleware ────────────────────────────────────────────────

export function authMiddleware(req: any, _res: any, next: any) {
  // The Authorization header is the primary auth path. A token in the query
  // string would normally be written to server and proxy access logs on every
  // request, turning any log entry into a lasting account takeover.
  const header = req.headers.authorization;
  let token = header && header.startsWith('Bearer ') ? header.slice(7) : null;

  // Browser media elements (<audio>, <img>) cannot set custom headers, so the
  // stream and cover-art endpoints also accept the token as a query parameter.
  // This is limited to those two paths to keep the exposure narrow.
  if (!token && (req.path.startsWith('/api/stream/') || req.path.startsWith('/api/cover-art/'))) {
    const queryToken = typeof req.query?.token === 'string' ? req.query.token : null;
    if (queryToken) token = queryToken;
  }

  req.token = token;
  req.user = token ? getUserByToken(token) : null;
  next();
}

export function requireAuth(req: any, res: any, next: any) {
  if (!req.user) {
    return res.status(401).json({ error: 'Authentication required' });
  }
  next();
}
