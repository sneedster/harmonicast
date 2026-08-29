import { randomUUID } from 'node:crypto';
import { db } from './db.js';

const PLEX_API_BASE_URL = 'https://plex.tv';
const PLEX_PRODUCT = 'Resonance';
const PLEX_VERSION = '0.1.0';
const PLEX_PLATFORM = 'Resonance Server';

export interface PlexPin {
  id: number;
  code: string;
  expiresAt: string | null;
  authToken: string | null;
}

export interface PlexAccount {
  id: string;
  email: string;
  name: string | null;
}

export interface PlexConnection {
  baseUrl: string;
  token: string;
}

export interface PlexServerInfo {
  machineIdentifier: string;
  name: string;
  version: string | null;
}

export interface PlexMusicLibrary {
  key: string;
  title: string;
  uuid: string | null;
}

export interface PlexFetch {
  (input: string | URL, init?: RequestInit): Promise<Response>;
}

/**
 * Plex requires clients to use a stable opaque identifier. A deployment may
 * supply one explicitly, but generated identifiers are persisted in the data
 * volume so server restarts do not create a new Plex client/device.
 */
export function getPlexClientIdentifier(): string {
  const configured = process.env.PLEX_CLIENT_IDENTIFIER?.trim();
  if (configured) return configured;

  const row = db.prepare('SELECT plex_client_identifier FROM settings WHERE id = 1').get() as
    { plex_client_identifier?: string | null } | undefined;
  if (row?.plex_client_identifier) return row.plex_client_identifier;

  const identifier = randomUUID();
  db.prepare(
    "UPDATE settings SET plex_client_identifier = ?, updated_at = datetime('now') WHERE id = 1"
  ).run(identifier);
  return identifier;
}

export function plexHeaders(clientIdentifier = getPlexClientIdentifier(), token?: string): Record<string, string> {
  const headers: Record<string, string> = {
    Accept: 'application/json',
    'X-Plex-Client-Identifier': clientIdentifier,
    'X-Plex-Product': PLEX_PRODUCT,
    'X-Plex-Version': PLEX_VERSION,
    'X-Plex-Platform': PLEX_PLATFORM,
  };
  if (token) headers['X-Plex-Token'] = token;
  return headers;
}

function plexServerBaseUrl(raw: string): string {
  const url = new URL(raw.trim());
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new Error('Plex server URL must use HTTP or HTTPS');
  }
  // Plex's web app commonly lives at /web while the server API is rooted at
  // the server base. Preserve any reverse-proxy prefix other than /web.
  url.pathname = url.pathname.replace(/\/web\/?$/, '') || '/';
  url.search = '';
  url.hash = '';
  return url.toString().replace(/\/$/, '');
}

/** Returns the configured Plex source without ever returning its token to an API client. */
export function getPlexConnectionFromEnv(): PlexConnection | null {
  const baseUrl = process.env.PLEX_SERVER_URL?.trim();
  const token = process.env.PLEX_SERVER_TOKEN?.trim();
  if (!baseUrl || !token) return null;
  return { baseUrl: plexServerBaseUrl(baseUrl), token };
}

function serverUrl(connection: PlexConnection, path: string): string {
  if (!path.startsWith('/')) throw new Error('Plex API path must be root-relative');
  return `${connection.baseUrl}${path}`;
}

async function plexServerJson(
  connection: PlexConnection,
  path: string,
  fetcher: PlexFetch = fetch,
): Promise<Record<string, unknown>> {
  const response = await fetcher(serverUrl(connection, path), {
    headers: plexHeaders(undefined, connection.token),
  });
  if (!response.ok) throw new Error(`Plex server request failed (${response.status})`);
  const body = await response.json() as Record<string, unknown>;
  const container = body.MediaContainer;
  if (!container || typeof container !== 'object') throw new Error('Plex server returned an invalid JSON response');
  return container as Record<string, unknown>;
}

/** Verify an authenticated Plex Media Server connection. */
export async function getPlexServerInfo(
  connection: PlexConnection,
  fetcher: PlexFetch = fetch,
): Promise<PlexServerInfo> {
  const container = await plexServerJson(connection, '/', fetcher);
  const machineIdentifier = typeof container.machineIdentifier === 'string' ? container.machineIdentifier : '';
  const name = typeof container.friendlyName === 'string' ? container.friendlyName : '';
  if (!machineIdentifier || !name) throw new Error('Plex server response is missing identity information');
  return {
    machineIdentifier,
    name,
    version: typeof container.version === 'string' ? container.version : null,
  };
}

/** List only Music libraries; callers select one before music APIs are enabled. */
export async function listPlexMusicLibraries(
  connection: PlexConnection,
  fetcher: PlexFetch = fetch,
): Promise<PlexMusicLibrary[]> {
  const container = await plexServerJson(connection, '/library/sections', fetcher);
  const directories = Array.isArray(container.Directory) ? container.Directory : [];
  return directories.flatMap((library): PlexMusicLibrary[] => {
    if (!library || typeof library !== 'object') return [];
    const item = library as Record<string, unknown>;
    // Plex represents music libraries as type=artist (or type=8 in older
    // responses). Do not assume a library called "Music" is musical content.
    if (item.type !== 'artist' && item.type !== 8) return [];
    const key = String(item.key ?? '');
    const title = typeof item.title === 'string' ? item.title : '';
    if (!key || !title) return [];
    return [{ key, title, uuid: typeof item.uuid === 'string' ? item.uuid : null }];
  });
}

function asPlexPin(payload: unknown): PlexPin {
  const pin = payload as Record<string, unknown>;
  const id = Number(pin.id);
  const code = typeof pin.code === 'string' ? pin.code : '';
  if (!Number.isInteger(id) || id <= 0 || !code) {
    throw new Error('Plex returned an invalid PIN response');
  }
  return {
    id,
    code,
    expiresAt: typeof pin.expiresAt === 'string' ? pin.expiresAt : null,
    authToken: typeof pin.authToken === 'string' && pin.authToken ? pin.authToken : null,
  };
}

/** Create a one-time Plex authorization PIN. The token is never sent to a client. */
export async function createPlexPin(fetcher: PlexFetch = fetch): Promise<PlexPin> {
  const response = await fetcher(`${PLEX_API_BASE_URL}/api/v2/pins`, {
    method: 'POST',
    headers: {
      ...plexHeaders(),
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    body: new URLSearchParams({ strong: 'true' }),
  });
  if (!response.ok) throw new Error(`Plex PIN request failed (${response.status})`);
  return asPlexPin(await response.json());
}

/** Read the PIN result after Plex completes authorization. */
export async function getPlexPin(pin: Pick<PlexPin, 'id' | 'code'>, fetcher: PlexFetch = fetch): Promise<PlexPin> {
  if (!Number.isInteger(pin.id) || pin.id <= 0 || !pin.code) throw new Error('Invalid Plex PIN');
  const params = new URLSearchParams({ code: pin.code });
  const response = await fetcher(`${PLEX_API_BASE_URL}/api/v2/pins/${pin.id}?${params}`, {
    headers: plexHeaders(),
  });
  if (!response.ok) throw new Error(`Plex PIN lookup failed (${response.status})`);
  return asPlexPin(await response.json());
}

/** Build the Plex-hosted authorization URL for a server-created PIN. */
export function buildPlexAuthUrl(pin: Pick<PlexPin, 'code'>, forwardUrl: string): string {
  if (!pin.code) throw new Error('Plex PIN code is required');
  const params = new URLSearchParams({
    clientID: getPlexClientIdentifier(),
    code: pin.code,
    forwardUrl,
    'context[device][product]': PLEX_PRODUCT,
  });
  return `https://app.plex.tv/auth#?${params.toString()}`;
}

/** Validate a Plex access token and return the minimum identity Resonance needs. */
export async function getPlexAccount(token: string, fetcher: PlexFetch = fetch): Promise<PlexAccount> {
  if (!token) throw new Error('Plex access token is required');
  const response = await fetcher(`${PLEX_API_BASE_URL}/api/v2/user`, {
    headers: plexHeaders(undefined, token),
  });
  if (!response.ok) throw new Error(`Plex account lookup failed (${response.status})`);
  const account = await response.json() as Record<string, unknown>;
  const id = String(account.id ?? account.uuid ?? '');
  const email = typeof account.email === 'string' ? account.email.trim().toLowerCase() : '';
  const displayName = typeof account.title === 'string'
    ? account.title
    : typeof account.username === 'string' ? account.username : null;
  if (!id || !email) throw new Error('Plex account response is missing an id or email');
  return { id, email, name: displayName };
}
