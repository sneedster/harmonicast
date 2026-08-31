import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import type { PlexFetch, PlexSource } from '../plex.js';

const dataDir = mkdtempSync(join(tmpdir(), 'harmonicast-plex-test-'));
process.env.DATA_DIR = dataDir;
const { initDb } = await import('../db.js');
const {
  beginPlexSetup,
  canAccessConfiguredPlexLibrary,
  clearPlexSetup,
  getActivePlexSource,
  getPersistedPlexSource,
  getPlexRelatedTracks,
  getPlexArtistDiscovery,
  searchPlexTracks,
  getPlexSetup,
  getPlexTrack,
  savePersistedPlexSource,
} = await import('../plex.js');
initDb();

test.after(() => rmSync(dataDir, { recursive: true, force: true }));

const source: PlexSource = {
  baseUrl: 'http://plex.example.test',
  token: 'test-token',
  machineIdentifier: 'server-1',
  serverName: 'Test Plex',
  libraryKey: '5',
  libraryName: 'Music',
};

function response(body: unknown): Response {
  return new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } });
}

test('first-run Plex setup persists the selected server and clears its temporary token', () => {
  clearPlexSetup();
  beginPlexSetup(42, 'temporary-owner-token');
  assert.deepEqual(getPlexSetup(), { userId: 42, token: 'temporary-owner-token' });

  savePersistedPlexSource({
    ...source,
    baseUrl: 'http://plex.example.test/web',
  });
  assert.deepEqual(getPersistedPlexSource(), source);
  assert.deepEqual(getActivePlexSource(), source);

  clearPlexSetup();
  assert.equal(getPlexSetup(), null);
});

test('shared Plex users resolve the configured server through their own Plex resources', async () => {
  const requestedUrls: string[] = [];
  const fetcher: PlexFetch = async (input) => {
    const url = String(input);
    requestedUrls.push(url);
    const parsed = new URL(url);
    if (parsed.hostname === 'plex.example.test') return new Response('unauthorized', { status: 401 });
    if (parsed.hostname === 'plex.tv' && parsed.pathname === '/api/v2/resources') {
      return response([{
        owned: false,
        provides: 'server',
        clientIdentifier: 'server-1',
        name: 'Shared Plex',
        connections: [{ uri: 'https://shared-plex.example.test', local: false, relay: false }],
      }]);
    }
    if (parsed.hostname === 'shared-plex.example.test' && parsed.pathname === '/library/sections') {
      return response({ MediaContainer: { Directory: [{ key: '5', title: 'Music', type: 'artist' }] } });
    }
    return new Response('missing', { status: 404 });
  };

  assert.equal(await canAccessConfiguredPlexLibrary('shared-user-token', fetcher), true);
  assert.ok(requestedUrls.some((url) => new URL(url).hostname === 'shared-plex.example.test'));
});

test('Track Radio uses Plex nearest with sonic-analysis distance and excludes its seed', async () => {
  const requestedUrls: string[] = [];
  const fetcher: PlexFetch = async (input) => {
    const url = String(input);
    requestedUrls.push(url);
    if (new URL(url).pathname === '/') {
      return response({ MediaContainer: { machineIdentifier: 'server-1', friendlyName: 'Test Plex' } });
    }
    return response({
      MediaContainer: {
        Metadata: [
          { type: 'track', ratingKey: '101', title: 'Seed', grandparentTitle: 'Seed Artist' },
          { type: 'track', ratingKey: '102', title: 'Near One', grandparentTitle: 'Artist One', parentTitle: 'Album One', duration: 180_000 },
          { type: 'track', ratingKey: '103', title: 'Near Two', grandparentTitle: 'Artist Two', parentTitle: 'Album Two', duration: 210_000 },
        ],
      },
    });
  };

  const songs = await getPlexRelatedTracks(source, 'plex:server-1:101', 20, fetcher);

  const nearest = new URL(requestedUrls.find((url) => url.includes('/nearest?')) ?? 'http://missing.test');
  assert.equal(nearest.pathname, '/library/metadata/101/nearest');
  assert.equal(nearest.searchParams.get('limit'), '20');
  assert.equal(nearest.searchParams.get('maxDistance'), '0.25');
  assert.deepEqual(songs.map((song) => song.id), ['plex:server-1:102', 'plex:server-1:103']);
  assert.deepEqual(songs.map((song) => song.artist), ['Artist One', 'Artist Two']);
});

test('Plex track metadata provides Android Auto-safe title, artist, album, and artwork fields', async () => {
  const requestedUrls: string[] = [];
  const fetcher: PlexFetch = async (input) => {
    const url = String(input);
    requestedUrls.push(url);
    if (new URL(url).pathname === '/') {
      return response({ MediaContainer: { machineIdentifier: 'server-1', friendlyName: 'Test Plex' } });
    }
    return response({
      MediaContainer: {
        Metadata: [{
          type: 10,
          ratingKey: '101',
          title: 'Metadata Track',
          grandparentTitle: 'Metadata Artist',
          parentTitle: 'Metadata Album',
          duration: 201_600,
          userRating: 8,
          viewCount: 12,
          skipCount: 3,
          lastViewedAt: 1_700_000_000,
          librarySectionID: '5',
        }],
      },
    });
  };

  const song = await getPlexTrack(source, 'plex:server-1:101', fetcher);

  assert.ok(requestedUrls.some((url) => new URL(url).pathname === '/library/metadata/101'));
  assert.deepEqual(song, {
    id: 'plex:server-1:101',
    title: 'Metadata Track',
    artist: 'Metadata Artist',
    album: 'Metadata Album',
    duration: 202,
    coverArt: 'plex:server-1:101',
    userRating: 8,
    viewCount: 12,
    skipCount: 3,
    lastViewedAt: '2023-11-14T22:13:20.000Z',
  });
});

test('Plex search expands matching album results into playable tracks', async () => {
  const requestedUrls: string[] = [];
  const fetcher: PlexFetch = async (input) => {
    const url = String(input);
    requestedUrls.push(url);
    const pathname = new URL(url).pathname;
    if (pathname === '/') return response({ MediaContainer: { machineIdentifier: 'server-1', friendlyName: 'Test Plex' } });
    if (pathname === '/library/sections/5/search') {
      const type = new URL(url).searchParams.get('type');
      return response({ MediaContainer: { Metadata: type === '9' ? [{ type: 'album', ratingKey: '301', title: 'Album Match' }] : [] } });
    }
    if (pathname === '/library/metadata/301/children') return response({ MediaContainer: { Metadata: [{
      type: 'track', ratingKey: '101', title: 'Album Track', grandparentTitle: 'Artist', parentTitle: 'Album Match', duration: 180_000,
    }] } });
    return new Response('missing', { status: 404 });
  };

  const songs = await searchPlexTracks(source, 'Album Match', fetcher);
  assert.deepEqual(songs.map((song) => song.title), ['Album Track']);
  const albumSearch = requestedUrls.map((url) => new URL(url)).find((url) => url.pathname === '/library/sections/5/search' && url.searchParams.get('type') === '9');
  assert.ok(albumSearch);
  assert.ok(requestedUrls.some((url) => new URL(url).pathname === '/library/metadata/301/children'));
});

test('artist discovery includes album context without failing when related artists are unavailable', async () => {
  const fetcher: PlexFetch = async (input) => {
    const path = new URL(String(input)).pathname;
    if (path === '/') return response({ MediaContainer: { machineIdentifier: 'server-1', friendlyName: 'Test Plex' } });
    if (path === '/library/metadata/101') return response({ MediaContainer: { Metadata: [{
      type: 'track', ratingKey: '101', title: 'Track', grandparentRatingKey: '201',
      grandparentTitle: 'Artist', parentRatingKey: '301', parentTitle: 'Album', librarySectionID: '5',
    }] } });
    if (path === '/library/metadata/201') return response({ MediaContainer: { Metadata: [{
      type: 'artist', ratingKey: '201', title: 'Artist', summary: 'Artist biography.', Genre: [{ tag: 'Rock' }],
    }] } });
    if (path === '/library/metadata/301') return response({ MediaContainer: { Metadata: [{
      type: 'album', ratingKey: '301', title: 'Album', year: 1994, summary: 'Album context.',
    }] } });
    return new Response('missing', { status: 404 });
  };

  const discovery = await getPlexArtistDiscovery(source, 'plex:server-1:101', fetcher);
  assert.deepEqual(discovery, {
    name: 'Artist', bio: 'Artist biography.', genres: ['Rock'], similarArtists: [],
    album: { name: 'Album', year: 1994, summary: 'Album context.' },
  });
});
