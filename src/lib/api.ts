// API client for the self-hosted Resonance server.
// All requests go to the same origin (the server serves both API and static files).

const API_BASE = import.meta.env.VITE_API_BASE || '';

function getToken(): string | null {
  return localStorage.getItem('resonance_token');
}

export function setToken(token: string): void {
  localStorage.setItem('resonance_token', token);
}

export function clearToken(): void {
  localStorage.removeItem('resonance_token');
}

export async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const token = getToken();
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options.headers as Record<string, string> || {}),
  };
  if (token) headers['Authorization'] = `Bearer ${token}`;

  const res = await fetch(`${API_BASE}${path}`, { ...options, headers });

  if (res.status === 401) {
    clearToken();
    throw new Error('Authentication required');
  }

  const data = await res.json().catch(() => null);
  if (!res.ok) {
    throw new Error(data?.error || `Request failed (${res.status})`);
  }
  return data as T;
}

// ── Auth ──────────────────────────────────────────────────────────────

export interface AuthUser { id: number; email: string; name?: string | null; }

export interface AuthConfig {
  googleOAuth: boolean;
}

export async function getAuthConfig(): Promise<AuthConfig> {
  return request<AuthConfig>('/api/auth/config');
}

export function googleSignInUrl(): string {
  return `${API_BASE}/api/auth/google`;
}

export async function signOut(): Promise<void> {
  try { await request('/api/auth/signout', { method: 'POST' }); } catch {}
  clearToken();
}

export async function getMe(): Promise<AuthUser | null> {
  const token = getToken();
  if (!token) return null;
  try {
    const result = await request<{ user: AuthUser }>('/api/auth/me');
    return result.user;
  } catch {
    return null;
  }
}

// ── Invites ─────────────────────────────────────────────────────────────

export interface Invite { email: string; created_at: string; }

export async function listInvites(): Promise<Invite[]> {
  return request<Invite[]>('/api/invites');
}

export async function addInvite(email: string): Promise<void> {
  await request('/api/invites', { method: 'POST', body: JSON.stringify({ email }) });
}

export async function removeInvite(email: string): Promise<void> {
  await request('/api/invites', { method: 'DELETE', body: JSON.stringify({ email }) });
}

// ── Connection ────────────────────────────────────────────────────────

export interface ConnectionInfo {
  configured: boolean;
  baseUrl?: string;
  username?: string;
  serverName?: string;
  isHost?: boolean;
  isActivePlayer?: boolean;
  hasActivePlayer?: boolean;
  activePlayerDeviceName?: string | null;
}

export async function getConnection(): Promise<ConnectionInfo> {
  return request<ConnectionInfo>('/api/connection');
}

export async function saveConnection(conn: {
  baseUrl: string; username: string; password: string; serverName?: string;
}): Promise<void> {
  await request('/api/connection', {
    method: 'POST', body: JSON.stringify(conn),
  });
}

export async function deleteConnection(): Promise<void> {
  await request('/api/connection', { method: 'DELETE' });
}

// ── Active playback device ────────────────────────────────────────────

export async function claimPlayer(token?: string): Promise<void> {
  await request('/api/player/claim', {
    method: 'POST',
    body: JSON.stringify(token ? { token } : {}),
  });
}

export async function getPlayerStatus(): Promise<{ isActivePlayer: boolean; hasActivePlayer: boolean }> {
  return request('/api/player/status');
}

export async function savePlaybackPosition(position: number): Promise<void> {
  await request('/api/now-playing/position', {
    method: 'PUT',
    body: JSON.stringify({ position }),
  });
}

export interface SessionDevice {
  token: string;
  deviceName: string;
  createdAt: string;
  isActivePlayer: boolean;
}

export async function listSessions(): Promise<SessionDevice[]> {
  return request<SessionDevice[]>('/api/sessions');
}

export async function setDeviceName(deviceName: string): Promise<void> {
  await request('/api/session/device-name', {
    method: 'PUT',
    body: JSON.stringify({ deviceName }),
  });
}

// ── Settings ───────────────────────────────────────────────────────────

export interface SettingsInfo {
  cooldownMinutes: number;
  maxRequestsPerUser: number;
  jukeboxMode: boolean;
  isHost: boolean;
}

export async function getSettings(): Promise<SettingsInfo> {
  return request<SettingsInfo>('/api/settings');
}

export async function updateSettings(settings: Partial<Pick<SettingsInfo, 'cooldownMinutes' | 'maxRequestsPerUser'>>): Promise<void> {
  await request('/api/settings', { method: 'PUT', body: JSON.stringify(settings) });
}

export async function setJukeboxModeApi(enabled: boolean): Promise<void> {
  await request('/api/jukebox', { method: 'POST', body: JSON.stringify({ enabled }) });
}

// ── WebSocket ─────────────────────────────────────────────────────────

type WsMessageHandler = (type: string, data?: any) => void;

export function connectWebSocket(onMessage: WsMessageHandler): WebSocket | null {
  const token = getToken();
  if (!token) return null;

  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const host = API_BASE ? new URL(API_BASE).host : window.location.host;
  // Pass the session token as the subprotocol; the server rejects unauthenticated
  // sockets. Keeps the token out of the URL and therefore out of access logs.
  const ws = new WebSocket(`${protocol}//${host}/ws`, [token]);

  ws.onmessage = (event) => {
    try {
      const msg = JSON.parse(event.data);
      onMessage(msg.type, msg.data);
    } catch {}
  };

  return ws;
}
