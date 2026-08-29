import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import type { PlexFetch, PlexSource } from '../plex.js';

const dataDir = mkdtempSync(join(tmpdir(), 'resonance-plex-test-'));
process.env.DATA_DIR = dataDir;
const { initDb } = await import('../db.js');
const { getPlexRelatedTracks } = await import('../plex.js');
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
