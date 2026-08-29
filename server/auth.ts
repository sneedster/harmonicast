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

/** Revokes every session belonging to an email, e.g. when their invite is removed. */
export function deleteSessionsForEmail(email: string): void {
  db.prepare(
    'DELETE FROM sessions WHERE user_id IN (SELECT id FROM users WHERE email = ?)'
  ).run(email.toLowerCase());
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

export function getUserByGoogleId(googleId: string) {
  return db.prepare('SELECT * FROM users WHERE google_id = ?').get(googleId);
}

export function getUserByPlexId(plexId: string) {
  return db.prepare('SELECT * FROM users WHERE plex_id = ?').get(plexId);
}

export function userCount(): number {
  const row = db.prepare('SELECT COUNT(*) as count FROM users').get() as { count: number };
  return row.count;
}

export function createUserFromGoogle(googleId: string, email: string, name: string | null): number {
  const result = db.prepare(
    'INSERT INTO users (email, google_id, name, password_hash) VALUES (?, ?, ?, ?)'
  ).run(email.toLowerCase(), googleId, name, '');
  return Number(result.lastInsertRowid);
}

export function updateGoogleUserInfo(userId: number, googleId: string, name: string | null): void {
  db.prepare('UPDATE users SET google_id = ?, name = ? WHERE id = ?').run(googleId, name, userId);
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

// ── Invite list ───────────────────────────────────────────────────────

export function isEmailAllowed(email: string): boolean {
  const row = db.prepare('SELECT 1 FROM allowed_emails WHERE email = ?').get(email.toLowerCase());
  return !!row;
}

export function addAllowedEmail(email: string, addedBy: number | null): void {
  db.prepare(
    'INSERT OR IGNORE INTO allowed_emails (email, added_by) VALUES (?, ?)'
  ).run(email.toLowerCase(), addedBy);
}

export function removeAllowedEmail(email: string): void {
  db.prepare('DELETE FROM allowed_emails WHERE email = ?').run(email.toLowerCase());
}

export function listAllowedEmails(): { email: string; created_at: string }[] {
  return db.prepare('SELECT email, created_at FROM allowed_emails ORDER BY created_at DESC').all();
}

// ── OAuth state (CSRF protection for the sign-in flow) ────────────────
//
// Each sign-in attempt gets a single-use, short-lived random value that Google
// echoes back to the callback. Without this check, an attacker can hand a
// victim a callback URL carrying the attacker's own authorization code and
// silently sign the victim's browser into the attacker's account.

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

// ── Google OAuth helpers ──────────────────────────────────────────────

export function getGoogleOAuthConfig() {
  const clientId = process.env.GOOGLE_CLIENT_ID;
  const clientSecret = process.env.GOOGLE_CLIENT_SECRET;
  const publicUrl = (process.env.PUBLIC_URL || '').replace(/\/+$/, '');
  return { clientId, clientSecret, publicUrl };
}

export function isGoogleOAuthConfigured(): boolean {
  const { clientId, clientSecret, publicUrl } = getGoogleOAuthConfig();
  return !!(clientId && clientSecret && publicUrl);
}

/** Plex's OAuth-style PIN flow needs a public return URL but no client secret. */
export function getPlexOAuthConfig() {
  const publicUrl = (process.env.PUBLIC_URL || '').replace(/\/+$/, '');
  return { publicUrl };
}

export function isPlexOAuthConfigured(): boolean {
  return !!getPlexOAuthConfig().publicUrl;
}

export function buildGoogleAuthUrl(state: string): string {
  const { clientId, publicUrl } = getGoogleOAuthConfig();
  const redirectUri = `${publicUrl}/api/auth/google/callback`;
  const params = new URLSearchParams({
    client_id: clientId!,
    redirect_uri: redirectUri,
    response_type: 'code',
    scope: 'openid email profile',
    state,
  });
  return `https://accounts.google.com/o/oauth2/v2/auth?${params.toString()}`;
}

export async function exchangeGoogleCode(code: string): Promise<{
  sub: string; email: string; email_verified: boolean; name?: string;
} | null> {
  const { clientId, clientSecret, publicUrl } = getGoogleOAuthConfig();
  const redirectUri = `${publicUrl}/api/auth/google/callback`;

  const tokenRes = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      code,
      client_id: clientId!,
      client_secret: clientSecret!,
      redirect_uri: redirectUri,
      grant_type: 'authorization_code',
    }),
  });

  if (!tokenRes.ok) return null;
  const tokenData = await tokenRes.json() as { access_token: string; id_token: string };

  const userRes = await fetch('https://www.googleapis.com/oauth2/v3/userinfo', {
    headers: { Authorization: `Bearer ${tokenData.access_token}` },
  });
  if (!userRes.ok) return null;
  const userInfo = await userRes.json() as {
    sub: string; email: string; email_verified: boolean; name?: string;
  };
  return userInfo;
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
