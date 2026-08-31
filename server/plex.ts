import { randomUUID } from 'node:crypto';
import { db } from './db.js';

const PLEX_API_BASE_URL = 'https://plex.tv';
const PLEX_PRODUCT = 'Harmonicast';
const PLEX_VERSION = '0.1.0';
const PLEX_PLATFORM = 'Harmonicast Server';

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

export interface PlexOwnedServer {
  machineIdentifier: string;
  name: string;
  connections: { uri: string; local: boolean; relay: boolean }[];
}

export interface PlexSource extends PlexConnection {
  libraryKey: string;
}

export interface PersistedPlexSource extends PlexSource {
  machineIdentifier: string;
  serverName: string;
  libraryName: string;
}

export interface PlexSong {
  id: string;
  title: string;
  artist: string;
  album: string;
  duration: number;
  coverArt: string;
  // These are shared Plex-account fields. Harmonicast intentionally does not
  // maintain a second per-user stats model.
  userRating: number | null;
  viewCount: number;
  skipCount: number;
  lastViewedAt: string | null;
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

/** A Plex source is active only once an explicit Music library has been chosen. */
export function getPlexSourceFromEnv(): PlexSource | null {
  const connection = getPlexConnectionFromEnv();
  const libraryKey = process.env.PLEX_LIBRARY_KEY?.trim();
  if (!connection || !libraryKey || !/^\d+$/.test(libraryKey)) return null;
  return { ...connection, libraryKey };
}

/** Returns a completed first-run source selection from local app data. */
export function getPersistedPlexSource(): PersistedPlexSource | null {
  const row = db.prepare(`
    SELECT plex_server_url, plex_server_machine_id, plex_server_name,
           plex_library_key, plex_library_name, plex_owner_token
    FROM settings WHERE id = 1
  `).get() as Record<string, unknown> | undefined;
  const baseUrl = typeof row?.plex_server_url === 'string' ? row.plex_server_url : '';
  const token = typeof row?.plex_owner_token === 'string' ? row.plex_owner_token : '';
  const libraryKey = typeof row?.plex_library_key === 'string' ? row.plex_library_key : '';
  const machineIdentifier = typeof row?.plex_server_machine_id === 'string' ? row.plex_server_machine_id : '';
  const serverName = typeof row?.plex_server_name === 'string' ? row.plex_server_name : '';
  const libraryName = typeof row?.plex_library_name === 'string' ? row.plex_library_name : '';
  if (!baseUrl || !token || !libraryKey || !machineIdentifier || !serverName || !libraryName) return null;
  return { baseUrl: plexServerBaseUrl(baseUrl), token, libraryKey, machineIdentifier, serverName, libraryName };
}

/** Environment variables remain a short-lived upgrade fallback. */
export function getActivePlexSource(): PlexSource | null {
  return getPersistedPlexSource() ?? getPlexSourceFromEnv();
}

export function savePersistedPlexSource(source: PersistedPlexSource): void {
  db.prepare(`
    UPDATE settings SET
      plex_server_url = ?, plex_server_machine_id = ?, plex_server_name = ?,
      plex_library_key = ?, plex_library_name = ?, plex_owner_token = ?,
      updated_at = datetime('now')
    WHERE id = 1
  `).run(
    plexServerBaseUrl(source.baseUrl), source.machineIdentifier, source.serverName,
    source.libraryKey, source.libraryName, source.token,
  );
}

export function getPlexSetup(): { userId: number; token: string } | null {
  const row = db.prepare('SELECT plex_setup_user_id, plex_setup_token FROM settings WHERE id = 1').get() as
    { plex_setup_user_id?: number | null; plex_setup_token?: string | null } | undefined;
  if (!row?.plex_setup_user_id || !row.plex_setup_token) return null;
  return { userId: row.plex_setup_user_id, token: row.plex_setup_token };
}

/** The token is retained only until the first owner chooses a Plex source. */
export function beginPlexSetup(userId: number, token: string): void {
  db.prepare(`UPDATE settings SET plex_setup_user_id = ?, plex_setup_token = ?, updated_at = datetime('now') WHERE id = 1`)
    .run(userId, token);
}

export function clearPlexSetup(): void {
  db.prepare(`UPDATE settings SET plex_setup_user_id = NULL, plex_setup_token = NULL, updated_at = datetime('now') WHERE id = 1`).run();
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

function sourceId(machineIdentifier: string, ratingKey: string): string {
  return `plex:${encodeURIComponent(machineIdentifier)}:${ratingKey}`;
}

function parseSourceId(id: string): string {
  const match = /^plex:([^:]+):(\d+)$/.exec(id);
  if (!match) throw new Error('Invalid Plex track id');
  // Resolve the configured server identity on every privileged media request;
  // an id from another Plex server must never be accepted accidentally.
  return match[2];
}

function metadataArray(container: Record<string, unknown>): Record<string, unknown>[] {
  return Array.isArray(container.Metadata)
    ? container.Metadata.filter((item): item is Record<string, unknown> => !!item && typeof item === 'object')
    : [];
}

function mapPlexSong(metadata: Record<string, unknown>, machineIdentifier: string): PlexSong | null {
  if (metadata.type !== 'track' && metadata.type !== 10) return null;
  const ratingKey = String(metadata.ratingKey ?? '');
  const title = typeof metadata.title === 'string' ? metadata.title : '';
  if (!ratingKey || !title) return null;
  return {
    id: sourceId(machineIdentifier, ratingKey),
    title,
    artist: typeof metadata.grandparentTitle === 'string' ? metadata.grandparentTitle
      : typeof metadata.originalTitle === 'string' ? metadata.originalTitle : 'Unknown artist',
    album: typeof metadata.parentTitle === 'string' ? metadata.parentTitle : '',
    // Plex reports milliseconds; Harmonicast exposes seconds.
    duration: Math.max(0, Math.round(Number(metadata.duration ?? 0) / 1000)),
    coverArt: sourceId(machineIdentifier, ratingKey),
    userRating: Number.isFinite(Number(metadata.userRating)) ? Number(metadata.userRating) : null,
    viewCount: Math.max(0, Number(metadata.viewCount) || 0),
    skipCount: Math.max(0, Number(metadata.skipCount) || 0),
    lastViewedAt: Number.isFinite(Number(metadata.lastViewedAt))
      ? new Date(Number(metadata.lastViewedAt) * 1000).toISOString()
      : null,
  };
}

async function plexSourceMachineIdentifier(source: PlexSource, fetcher: PlexFetch): Promise<string> {
  return (await getPlexServerInfo(source, fetcher)).machineIdentifier;
}

/** Search the configured Plex Music library by track, artist, and album name. */
export async function searchPlexTracks(
  source: PlexSource,
  query: string,
  fetcher: PlexFetch = fetch,
): Promise<PlexSong[]> {
  const search = query.trim();
  if (!search) return [];
  const trackParams = new URLSearchParams({ query: search, type: '10', limit: '40' });
  const artistParams = new URLSearchParams({ query: search, type: '8', limit: '8' });
  const albumParams = new URLSearchParams({ query: search, type: '9', limit: '8' });
  const [machineIdentifier, trackContainer, artistContainer, albumContainer] = await Promise.all([
    plexSourceMachineIdentifier(source, fetcher),
    plexServerJson(source, `/library/sections/${source.libraryKey}/search?${trackParams}`, fetcher),
    plexServerJson(source, `/library/sections/${source.libraryKey}/search?${artistParams}`, fetcher),
    plexServerJson(source, `/library/sections/${source.libraryKey}/search?${albumParams}`, fetcher),
  ]);
  const collectionMatches = [
    ...metadataArray(artistContainer)
      .filter((metadata) => metadata.type === 'artist' || metadata.type === 8)
      .map((metadata) => ({ ratingKey: String(metadata.ratingKey ?? ''), kind: 'artist' as const })),
    ...metadataArray(albumContainer)
      .filter((metadata) => metadata.type === 'album' || metadata.type === 9)
      .map((metadata) => ({ ratingKey: String(metadata.ratingKey ?? ''), kind: 'album' as const })),
  ].filter((match) => Boolean(match.ratingKey));
  const collectionTrackContainers = await Promise.all(
    collectionMatches.map(({ ratingKey, kind }) => plexServerJson(
      source,
      // Plex exposes an album's playable tracks through /children. Artist
      // results remain hierarchical, so /allLeaves is the appropriate route.
      kind === 'album'
        ? `/library/metadata/${ratingKey}/children?type=10&limit=40`
        : `/library/metadata/${ratingKey}/allLeaves?type=10&limit=40`,
      fetcher,
    )),
  );
  const songs = [
    ...metadataArray(trackContainer),
    ...collectionTrackContainers.flatMap(metadataArray),
  ].flatMap((metadata) => {
    const song = mapPlexSong(metadata, machineIdentifier);
    return song ? [song] : [];
  });
  return [...new Map(songs.map((song) => [song.id, song])).values()].slice(0, 40);
}

/** Return an exact artist match and a bounded catalog suitable for album browsing. */
export async function browsePlexArtist(source: PlexSource, query: string, fetcher: PlexFetch = fetch): Promise<{ name: string; songs: PlexSong[] } | null> {
  const search = query.trim();
  if (!search) return null;
  const params = new URLSearchParams({ query: search, type: '8', limit: '8' });
  const [machineIdentifier, container] = await Promise.all([
    plexSourceMachineIdentifier(source, fetcher),
    plexServerJson(source, `/library/sections/${source.libraryKey}/search?${params}`, fetcher),
  ]);
  const normalized = (value: string) => value.normalize('NFKD').toLowerCase().replace(/[^a-z0-9]+/g, '');
  const artist = metadataArray(container).find((item) => (item.type === 'artist' || item.type === 8)
    && typeof item.title === 'string' && normalized(item.title) === normalized(search));
  const ratingKey = typeof artist?.ratingKey === 'string' || typeof artist?.ratingKey === 'number' ? String(artist.ratingKey) : '';
  if (!ratingKey || typeof artist?.title !== 'string') return null;
  const tracks = await plexServerJson(source, `/library/metadata/${ratingKey}/allLeaves?type=10&limit=300`, fetcher);
  return { name: artist.title, songs: metadataArray(tracks).flatMap((metadata) => {
    const song = mapPlexSong(metadata, machineIdentifier);
    return song ? [song] : [];
  }) };
}

/** Fetch a bounded random page of tracks from the configured Music library. */
export async function getPlexRandomTracks(
  source: PlexSource,
  size = 100,
  fetcher: PlexFetch = fetch,
): Promise<PlexSong[]> {
  const params = new URLSearchParams({ type: '10', sort: 'random', limit: String(Math.max(1, Math.min(size, 100))) });
  const [machineIdentifier, container] = await Promise.all([
    plexSourceMachineIdentifier(source, fetcher),
    plexServerJson(source, `/library/sections/${source.libraryKey}/all?${params}`, fetcher),
  ]);
  return metadataArray(container).flatMap((metadata) => {
    const song = mapPlexSong(metadata, machineIdentifier);
    return song ? [song] : [];
  });
}

/** Fetch the newest tracks without indexing the whole library. */
export async function getPlexRecentlyAddedTracks(source: PlexSource, size = 12, fetcher: PlexFetch = fetch): Promise<PlexSong[]> {
  const params = new URLSearchParams({ type: '10', sort: 'addedAt:desc', limit: String(Math.max(1, Math.min(size, 100))) });
  const [machineIdentifier, container] = await Promise.all([
    plexSourceMachineIdentifier(source, fetcher),
    plexServerJson(source, `/library/sections/${source.libraryKey}/all?${params}`, fetcher),
  ]);
  return metadataArray(container).flatMap((metadata) => {
    const song = mapPlexSong(metadata, machineIdentifier);
    return song ? [song] : [];
  });
}

/**
 * Build a Plex Track Radio queue from Sonic Analysis. This is the same
 * cross-artist similarity source used by Plexamp's “Play Track Radio”, rather
 * than a title/artist metadata match or an artist's own catalog.
 */
export async function getPlexRelatedTracks(
  source: PlexSource,
  id: string,
  size = 20,
  fetcher: PlexFetch = fetch,
): Promise<PlexSong[]> {
  const machineIdentifier = await plexSourceMachineIdentifier(source, fetcher);
  const ratingKey = parseSourceId(id);
  const params = new URLSearchParams({ limit: String(size), maxDistance: '0.25' });
  const container = await plexServerJson(source, `/library/metadata/${ratingKey}/nearest?${params}`, fetcher);
  return metadataArray(container)
    .flatMap((item) => {
      const song = mapPlexSong(item, machineIdentifier);
      return song && song.id !== id ? [song] : [];
    })
    .slice(0, size);
}

async function getConfiguredPlexTrack(
  source: PlexSource,
  id: string,
  fetcher: PlexFetch = fetch,
): Promise<Record<string, unknown>> {
  const ratingKey = parseSourceId(id);
  const [machineIdentifier, container] = await Promise.all([
    plexSourceMachineIdentifier(source, fetcher),
    plexServerJson(source, `/library/metadata/${ratingKey}`, fetcher),
  ]);
  if (!id.startsWith(`plex:${encodeURIComponent(machineIdentifier)}:`)) {
    throw new Error('Plex track does not belong to the configured server');
  }
  const metadata = metadataArray(container)[0];
  if (!metadata || String(metadata.librarySectionID ?? '') !== source.libraryKey) {
    throw new Error('Plex track does not belong to the configured Music library');
  }
  return metadata;
}

function firstPartKey(metadata: Record<string, unknown>): string | null {
  const media = Array.isArray(metadata.Media) ? metadata.Media : [];
  for (const item of media) {
    if (!item || typeof item !== 'object') continue;
    const rawParts = (item as Record<string, unknown>).Part;
    const parts = Array.isArray(rawParts)
      ? rawParts.filter((candidate): candidate is Record<string, unknown> => !!candidate && typeof candidate === 'object')
      : [];
    const part = parts.find((candidate) => typeof candidate.key === 'string');
    if (typeof part?.key === 'string' && part.key.startsWith('/')) return part.key;
  }
  return null;
}

export async function getPlexTrackStreamUrl(source: PlexSource, id: string, fetcher: PlexFetch = fetch): Promise<string> {
  const partKey = firstPartKey(await getConfiguredPlexTrack(source, id, fetcher));
  if (!partKey) throw new Error('Plex track has no playable media part');
  return serverUrl(source, partKey);
}

export async function getPlexTrackArtworkUrl(source: PlexSource, id: string, fetcher: PlexFetch = fetch): Promise<string | null> {
  const metadata = await getConfiguredPlexTrack(source, id, fetcher);
  const thumb = typeof metadata.thumb === 'string' && metadata.thumb.startsWith('/') ? metadata.thumb : null;
  return thumb ? serverUrl(source, thumb) : null;
}

/** Read current shared Plex metadata for a configured track. */
export async function getPlexTrack(source: PlexSource, id: string, fetcher: PlexFetch = fetch): Promise<PlexSong> {
  const metadata = await getConfiguredPlexTrack(source, id, fetcher);
  const server = await getPlexServerInfo(source, fetcher);
  const song = mapPlexSong(metadata, server.machineIdentifier);
  if (!song) throw new Error('Plex metadata is not a music track');
  return song;
}

/** Fetch artist-facing Plex material only when a client opens the discovery view. */
export async function getPlexArtistDiscovery(source: PlexSource, trackId: string, fetcher: PlexFetch = fetch) {
  const track = await getConfiguredPlexTrack(source, trackId, fetcher);
  const artistKey = String(track.grandparentRatingKey ?? '');
  if (!artistKey) throw new Error('This track has no Plex artist');
  const albumKey = String(track.parentRatingKey ?? '');
  const within = <T>(promise: Promise<T>, milliseconds: number): Promise<T> => Promise.race([
    promise,
    new Promise<T>((_, reject) => setTimeout(() => reject(new Error('Plex discovery timed out')), milliseconds)),
  ]);
  // The artist record is the useful part. Related-artist lookup is optional
  // and can be disproportionately slow on some Plex libraries.
  const [artistContainer, relatedContainer, albumContainer] = await Promise.all([
    within(plexServerJson(source, `/library/metadata/${artistKey}`, fetcher), 5_000),
    within(
      plexServerJson(source, `/library/metadata/${artistKey}/related?type=8`, fetcher),
      2_000,
    ).catch(() => ({})),
    albumKey
      ? within(plexServerJson(source, `/library/metadata/${albumKey}`, fetcher), 3_000).catch(() => ({}))
      : Promise.resolve({}),
  ]);
  const artist = metadataArray(artistContainer)[0] ?? {};
  const album = metadataArray(albumContainer)[0] ?? {};
  return {
    name: typeof artist.title === 'string' ? artist.title : typeof track.grandparentTitle === 'string' ? track.grandparentTitle : 'Unknown artist',
    bio: typeof artist.summary === 'string' ? artist.summary : '',
    genres: Array.isArray(artist.Genre) ? artist.Genre.map((genre) => typeof genre?.tag === 'string' ? genre.tag : '').filter(Boolean) : [],
    similarArtists: metadataArray(relatedContainer)
      .filter((item) => item.type === 'artist' || item.type === 8)
      .map((item) => typeof item.title === 'string' ? item.title : '')
      .filter(Boolean)
      .slice(0, 12),
    album: {
      name: typeof album.title === 'string' ? album.title : typeof track.parentTitle === 'string' ? track.parentTitle : '',
      year: Number.isFinite(Number(album.year)) ? Number(album.year) : null,
      summary: typeof album.summary === 'string' ? album.summary : '',
    },
  };
}

async function plexServerAction(
  connection: PlexConnection,
  path: string,
  fetcher: PlexFetch = fetch,
): Promise<void> {
  const response = await fetcher(serverUrl(connection, path), {
    method: 'PUT',
    headers: plexHeaders(undefined, connection.token),
  });
  if (!response.ok) throw new Error(`Plex server action failed (${response.status})`);
}

/** Update the selected Plex account's shared 0–10 rating for one track. */
export async function ratePlexTrack(source: PlexSource, id: string, rating: number, fetcher: PlexFetch = fetch): Promise<number> {
  await getConfiguredPlexTrack(source, id, fetcher);
  const ratingKey = parseSourceId(id);
  const nextRating = Math.max(0, Math.min(10, Math.round(rating)));
  const params = new URLSearchParams({
    identifier: 'com.plexapp.plugins.library',
    key: ratingKey,
    rating: String(nextRating),
  });
  await plexServerAction(source, `/:/rate?${params}`, fetcher);
  return nextRating;
}

/** Mark a completed track as played for the shared Plex account. */
export async function scrobblePlexTrack(source: PlexSource, id: string, fetcher: PlexFetch = fetch): Promise<void> {
  await getConfiguredPlexTrack(source, id, fetcher);
  const ratingKey = parseSourceId(id);
  const params = new URLSearchParams({ identifier: 'com.plexapp.plugins.library', key: ratingKey });
  await plexServerAction(source, `/:/scrobble?${params}`, fetcher);
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

/**
 * Plex sharing is the access-control source of truth. A user is admitted only
 * when their own Plex token can enumerate the configured Music library.
 */
export async function canAccessConfiguredPlexLibrary(
  userToken: string,
  fetcher: PlexFetch = fetch,
): Promise<boolean> {
  const source = getActivePlexSource();
  if (!source || !userToken) return false;
  const userConnection: PlexConnection = { baseUrl: source.baseUrl, token: userToken };
  try {
    const libraries = await listPlexMusicLibraries(userConnection, fetcher);
    if (libraries.some((library) => library.key === source.libraryKey)) return true;
  } catch (error) {
    // A direct section lookup below is still authoritative. Some Plex shares
    // can use the library while their /library/sections directory is stale or
    // incomplete, so a listing failure must not by itself reject the guest.
    console.warn('Could not enumerate Plex libraries for access check:', error);
  }

  try {
    // Ask Plex for one item from the configured section. A 200 response is an
    // explicit authorization decision for this exact library and avoids
    // treating directory-list quirks as a sharing denial.
    await plexServerJson(
      userConnection,
      `/library/sections/${encodeURIComponent(source.libraryKey)}/all?X-Plex-Container-Start=0&X-Plex-Container-Size=1`,
      fetcher,
    );
    return true;
  } catch (error) {
    console.warn('Plex access check for configured Music library failed:', error);
    return false;
  }
}

/** List only Plex Media Servers actually owned by this Plex account. */
export async function listOwnedPlexServers(token: string, fetcher: PlexFetch = fetch): Promise<PlexOwnedServer[]> {
  if (!token) throw new Error('Plex access token is required');
  const response = await fetcher(`${PLEX_API_BASE_URL}/api/v2/resources?includeHttps=1&includeRelay=1`, {
    headers: plexHeaders(undefined, token),
  });
  if (!response.ok) throw new Error(`Plex resource request failed (${response.status})`);
  const resources = await response.json();
  if (!Array.isArray(resources)) throw new Error('Plex returned an invalid resource list');
  return resources.flatMap((resource): PlexOwnedServer[] => {
    if (!resource || typeof resource !== 'object') return [];
    const item = resource as Record<string, unknown>;
    const provides = typeof item.provides === 'string' ? item.provides.split(',') : [];
    const machineIdentifier = typeof item.clientIdentifier === 'string' ? item.clientIdentifier : '';
    const name = typeof item.name === 'string' ? item.name : '';
    if (item.owned !== true || !provides.includes('server') || !machineIdentifier || !name) return [];
    const connections = Array.isArray(item.connections) ? item.connections.flatMap((connection) => {
      if (!connection || typeof connection !== 'object') return [];
      const value = connection as Record<string, unknown>;
      const uri = typeof value.uri === 'string' ? value.uri : '';
      if (!uri) return [];
      return [{ uri: plexServerBaseUrl(uri), local: value.local === true, relay: value.relay === true }];
    }) : [];
    return connections.length ? [{ machineIdentifier, name, connections }] : [];
  });
}

/** Resolve a server connection using the owner token, preferring local links. */
export async function connectOwnedPlexServer(
  token: string,
  server: PlexOwnedServer,
  fetcher: PlexFetch = fetch,
): Promise<PlexConnection> {
  const candidates = [...server.connections].sort((a, b) => Number(b.local) - Number(a.local));
  for (const candidate of candidates) {
    const connection = { baseUrl: candidate.uri, token };
    try {
      const identity = await getPlexServerInfo(connection, fetcher);
      if (identity.machineIdentifier === server.machineIdentifier) return connection;
    } catch {
      // Continue through Plex's advertised direct, then relay, connections.
    }
  }
  throw new Error('Could not reach that Plex server');
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

/** Validate a Plex access token and return the minimum identity Harmonicast needs. */
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
