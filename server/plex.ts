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
  const response = await fetcher(`${PLEX_API_BASE_URL}/api/v2/pins?strong=true`, {
    method: 'POST',
    headers: plexHeaders(),
  });
  if (!response.ok) throw new Error(`Plex PIN request failed (${response.status})`);
  return asPlexPin(await response.json());
}

/** Read the PIN result after Plex completes authorization. */
export async function getPlexPin(pinId: number, fetcher: PlexFetch = fetch): Promise<PlexPin> {
  if (!Number.isInteger(pinId) || pinId <= 0) throw new Error('Invalid Plex PIN id');
  const response = await fetcher(`${PLEX_API_BASE_URL}/api/v2/pins/${pinId}`, {
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
