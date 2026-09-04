import assert from 'node:assert/strict';
import test from 'node:test';
import type { PlexSong } from '../plex.js';
import { choosePlexJukeboxTracks } from '../jukebox-selection.js';

function song(id: string, rating: number | null): PlexSong {
  return {
    id, title: id, artist: 'Artist', album: 'Album', year: null, duration: 180,
    coverArt: id, userRating: rating, viewCount: 0, skipCount: 0, lastViewedAt: null,
  };
}

test('Jukebox selects four rated tracks followed by one unrated exploration track', () => {
  const rated = Array.from({ length: 8 }, (_, index) => song(`rated-${index}`, 8));
  const unrated = Array.from({ length: 4 }, (_, index) => song(`unrated-${index}`, null));
  const selection = choosePlexJukeboxTracks(rated, unrated, [], 5, 8, 0, () => 0);

  assert.deepEqual(selection.songs.map((candidate) => candidate.userRating), [8, 8, 8, 8, null]);
  assert.equal(selection.nextMixIndex, 5);
});

test('mix cursor preserves the four-to-one cadence across one-song refills', () => {
  const rated = Array.from({ length: 8 }, (_, index) => song(`rated-${index}`, 8));
  const unrated = Array.from({ length: 4 }, (_, index) => song(`unrated-${index}`, null));
  const ratings: Array<number | null> = [];
  let cursor = 0;

  for (let index = 0; index < 10; index++) {
    const selection = choosePlexJukeboxTracks(rated, unrated, [], 1, 8, cursor, () => index / 10);
    ratings.push(selection.songs[0].userRating);
    cursor = selection.nextMixIndex;
  }

  assert.deepEqual(ratings, [8, 8, 8, 8, null, 8, 8, 8, 8, null]);
});

test('Jukebox falls back without duplicating a track when a preferred pool is empty', () => {
  const unrated = [song('unrated-1', null), song('unrated-2', null)];
  const fallback = [song('unrated-1', null), song('fallback-1', 4)];
  const selection = choosePlexJukeboxTracks([], unrated, fallback, 3, 8, 0, () => 0);

  assert.deepEqual(selection.songs.map((candidate) => candidate.id), ['unrated-1', 'unrated-2', 'fallback-1']);
});

test('share zero selects only unrated tracks when that pool is available', () => {
  const rated = Array.from({ length: 10 }, (_, index) => song(`rated-${index}`, 8));
  const unrated = Array.from({ length: 10 }, (_, index) => song(`unrated-${index}`, null));
  const selection = choosePlexJukeboxTracks(rated, unrated, [], 10, 0, 0, () => 0);

  assert.equal(selection.songs.every((candidate) => candidate.userRating === null), true);
});

test('share ten selects only rated tracks when that pool is available', () => {
  const rated = Array.from({ length: 10 }, (_, index) => song(`rated-${index}`, 8));
  const unrated = Array.from({ length: 10 }, (_, index) => song(`unrated-${index}`, null));
  const selection = choosePlexJukeboxTracks(rated, unrated, [], 10, 10, 0, () => 0);

  assert.equal(selection.songs.every((candidate) => candidate.userRating !== null), true);
});

test('zero and ten remain strict when the requested pool is empty', () => {
  const rated = [song('rated', 8)];
  const unrated = [song('unrated', null)];

  assert.deepEqual(choosePlexJukeboxTracks(rated, [], rated, 1, 0).songs, []);
  assert.deepEqual(choosePlexJukeboxTracks([], unrated, unrated, 1, 10).songs, []);
});

test('all-rated mode still gives low-rated tracks above one another chance', () => {
  const fallback = [song('excluded-at-one', 1), song('eligible-above-one', 1.1)];
  const selection = choosePlexJukeboxTracks([], [], fallback, 1, 10, 0, () => 0);

  assert.deepEqual(selection.songs.map((candidate) => candidate.id), ['eligible-above-one']);
});
