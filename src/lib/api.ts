import type { Song } from '@/types';

// API client for the self-hosted Harmonicast server.
// All requests go to the same origin (the server serves both API and static files).

const API_BASE = import.meta.env.VITE_API_BASE || '';

function getToken(): string | null {
  return localStorage.getItem('harmonicast_token');
}

export function setToken(token: string): void {
  localStorage.setItem('harmonicast_token', token);
}

export function clearToken(): void {
  localStorage.removeItem('harmonicast_token');
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
  plexOAuth: boolean;
}

export async function getAuthConfig(): Promise<AuthConfig> {
  return request<AuthConfig>('/api/auth/config');
}

export function plexSignInUrl(returnTo?: '/kiosk'): string {
  const query = returnTo ? `?return_to=${encodeURIComponent(returnTo)}` : '';
  return `${API_BASE}/api/auth/plex${query}`;
}

export async function signOut(): Promise<void> {
  try { await request('/api/auth/signout', { method: 'POST' }); } catch { /* local sign-out still succeeds */ }
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
  needsPlexSetup?: boolean;
  isSetupOwner?: boolean;
}

export async function getConnection(): Promise<ConnectionInfo> {
  return request<ConnectionInfo>('/api/connection');
}

/** Search the selected music source. Plex-backed deployments use `/api/search`. */
export async function searchLibrary(query: string): Promise<Song[]> {
  return request(`/api/search?q=${encodeURIComponent(query)}`);
}

/** A small rotating set for visual kiosk browsing; search covers the library. */
export interface DiscoveryShelf { id: string; title: string; subtitle: string; songs: Song[]; }
export async function discoverLibrary(): Promise<{ shelves: DiscoveryShelf[] }> {
  return request('/api/discover');
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

export interface PlexSourceInfo {
  configured: boolean;
  server?: { machineIdentifier: string; name: string; version: string | null };
  libraries?: { key: string; title: string; uuid: string | null }[];
  selectedLibraryKey?: string | null;
}

export async function getPlexSource(): Promise<PlexSourceInfo> {
  return request<PlexSourceInfo>('/api/plex/source');
}

export interface PlexTrackDetails {
  id: string;
  title: string;
  artist: string;
  album: string;
  duration: number;
  coverArt: string;
  rating: number | null;
  playCount: number;
  skipCount: number;
  lastPlayedAt: string | null;
}

export async function getPlexTrackDetails(id: string): Promise<PlexTrackDetails> {
  return request(`/api/plex/tracks/${encodeURIComponent(id)}`);
}

export interface PlexArtistDiscovery {
  name: string;
  bio: string;
  genres: string[];
  similarArtists: string[];
  album: { name: string; year: number | null; summary: string };
}

/** Fetch artist-facing Plex metadata only when the listener opens discovery. */
export async function getPlexArtistDiscovery(id: string): Promise<PlexArtistDiscovery> {
  return request(`/api/plex/tracks/${encodeURIComponent(id)}/discovery`);
}

export async function getServerVersion(): Promise<string> {
  const result = await request<{ version: string }>('/api/version');
  return result.version;
}

export interface PlexSetupServer { machineIdentifier: string; name: string }
export interface PlexSetupLibrary { key: string; title: string; uuid: string | null }

export async function getPlexSetupServers(): Promise<{ servers: PlexSetupServer[] }> {
  return request('/api/setup/plex/servers');
}

export async function getPlexSetupLibraries(machineIdentifier: string): Promise<{
  server: PlexSetupServer; libraries: PlexSetupLibrary[];
}> {
  return request(`/api/setup/plex/servers/${encodeURIComponent(machineIdentifier)}/libraries`);
}

export async function selectPlexSetupSource(machineIdentifier: string, libraryKey: string): Promise<void> {
  await request('/api/setup/plex/select', {
    method: 'POST', body: JSON.stringify({ machineIdentifier, libraryKey }),
  });
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

type WsMessageHandler = (type: string, data?: unknown) => void;

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
      const msg: unknown = JSON.parse(event.data);
      if (typeof msg !== 'object' || msg === null) return;
      const { type, data } = msg as Record<string, unknown>;
      if (typeof type === 'string') onMessage(type, data);
    } catch { /* ignore malformed WebSocket messages */ }
  };

  return ws;
}
