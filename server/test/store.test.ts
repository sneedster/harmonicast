import assert from 'node:assert/strict';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

process.env.DATA_DIR = mkdtempSync(join(tmpdir(), 'harmonicast-store-test-'));

const { db, initDb } = await import('../db.js');
const {
  addToQueue,
  dequeueNext,
  fetchQueue,
  removeFromQueue,
  updateNowPlaying,
  isActivePlayerSession,
  setActivePlayerSession,
  setCooldownMinutes,
  setMaxRequestsPerUser,
  getRatedTrackShare,
  setRatedTrackShare,
  recordPlayEvent,
  previewVoteOnCurrent,
  voteOnCurrent,
} = await import('../store.js');

initDb();

function resetDatabase() {
  db.exec(`
    DELETE FROM queue;
    DELETE FROM play_history;
    DELETE FROM song_stats;
    DELETE FROM votes;
    DELETE FROM sessions;
    DELETE FROM users;
  `);
  db.prepare(`
    UPDATE settings
    SET cooldown_minutes = 0, max_requests_per_user = 5, rated_track_share = 8,
        active_player_session = NULL, host_user_id = NULL
    WHERE id = 1
  `).run();
}

test('rated-track share is persisted in settings', () => {
  resetDatabase();
  assert.equal(getRatedTrackShare(), 8);
  setRatedTrackShare(3);
  assert.equal(getRatedTrackShare(), 3);
});

function createUser(email: string): number {
  return Number(db.prepare(`
    INSERT INTO users (email, plex_id, name, password_hash)
    VALUES (?, ?, ?, '')
  `).run(email, `plex-${email}`, email).lastInsertRowid);
}

function song(id: string) {
  return { id, title: `Title ${id}`, artist: 'Artist', album: 'Album', duration: 180, coverArt: id };
}

test('manual requests alternate users and stay ahead of auto queue items', () => {
  resetDatabase();
  const alice = createUser('alice@example.test');
  const bob = createUser('bob@example.test');
  setCooldownMinutes(0);
  setMaxRequestsPerUser(5);

  addToQueue({ song: song('alice-1'), userId: alice, userEmail: 'alice@example.test' });
  addToQueue({ song: song('alice-2'), userId: alice, userEmail: 'alice@example.test' });
  addToQueue({ song: song('bob-1'), userId: bob, userEmail: 'bob@example.test' });
  addToQueue({ song: song('auto-1'), userId: null, userEmail: 'Automatic', isManual: false });

  assert.deepEqual(fetchQueue().map((row: { song_id: string }) => row.song_id), [
    'alice-1', 'bob-1', 'alice-2', 'auto-1',
  ]);
  assert.equal(dequeueNext()?.song_id, 'alice-1');
  assert.equal(dequeueNext()?.song_id, 'bob-1');
});

test('ready acquisitions take the next-song lane and alternate their requesters', () => {
  resetDatabase();
  const alice = createUser('alice@example.test');
  const bob = createUser('bob@example.test');
  setCooldownMinutes(0);
  setMaxRequestsPerUser(5);

  addToQueue({ song: song('request-1'), userId: alice, userEmail: 'alice@example.test' });
  addToQueue({ song: song('acquired-alice-1'), userId: alice, userEmail: 'alice@example.test', queueKind: 'acquisition' });
  addToQueue({ song: song('acquired-alice-2'), userId: alice, userEmail: 'alice@example.test', queueKind: 'acquisition' });
  addToQueue({ song: song('acquired-bob-1'), userId: bob, userEmail: 'bob@example.test', queueKind: 'acquisition' });
  addToQueue({ song: song('auto-1'), userId: null, userEmail: 'Automatic', isManual: false });

  assert.deepEqual(fetchQueue().map((row: { song_id: string }) => row.song_id), [
    'acquired-alice-1', 'acquired-bob-1', 'acquired-alice-2', 'request-1', 'auto-1',
  ]);
});

test('active player session has exactly one current token', () => {
  resetDatabase();
  setActivePlayerSession('first-player');
  assert.equal(isActivePlayerSession('first-player'), true);
  assert.equal(isActivePlayerSession('second-player'), false);

  setActivePlayerSession('second-player');
  assert.equal(isActivePlayerSession('first-player'), false);
  assert.equal(isActivePlayerSession('second-player'), true);
});

test('removing a queued song leaves the remaining fair order intact', () => {
  resetDatabase();
  const alice = createUser('alice@example.test');
  const bob = createUser('bob@example.test');
  setCooldownMinutes(0);
  setMaxRequestsPerUser(5);
  addToQueue({ song: song('alice-1'), userId: alice, userEmail: 'alice@example.test' });
  addToQueue({ song: song('bob-1'), userId: bob, userEmail: 'bob@example.test' });
  addToQueue({ song: song('auto-1'), userId: null, userEmail: 'Automatic', isManual: false });

  assert.equal(removeFromQueue('bob-1'), true);
  assert.equal(removeFromQueue('missing'), false);
  assert.deepEqual(fetchQueue().map((row: { song_id: string }) => row.song_id), ['alice-1', 'auto-1']);
});

test('starting a queued track removes it from upcoming entries', () => {
  resetDatabase();
  const alice = createUser('alice@example.test');
  const queued = song('now-playing');
  addToQueue({ song: queued, userId: alice, userEmail: 'alice@example.test' });

  assert.equal(updateNowPlaying(queued, true), true);
  assert.deepEqual(fetchQueue(), []);
  assert.equal(updateNowPlaying(queued, true), false);
});

test('playback outcomes use Plex-compatible whole rating points', () => {
  resetDatabase();
  const played = song('adaptive-rating');

  const completed = recordPlayEvent({
    song_id: played.id, title: played.title, artist: played.artist,
    album: played.album, duration: played.duration, cover_art: played.coverArt,
    event: 'complete', progress: 1,
  });
  assert.equal(completed.rating, 5.1);
  assert.equal(completed.play_count, 1);

  const skipped = recordPlayEvent({
    song_id: played.id, title: played.title, artist: played.artist,
    album: played.album, duration: played.duration, cover_art: played.coverArt,
    event: 'skip', progress: 0,
  });
  assert.equal(skipped.rating, 4.8);
  assert.equal(skipped.skip_count, 1);
});

test('votes apply once and switching sides reverses the previous points', () => {
  resetDatabase();
  const user = createUser('voter@example.test');
  updateNowPlaying(song('vote-rating'), true);

  assert.deepEqual(previewVoteOnCurrent(user, 'up'), {
    songId: 'vote-rating', previousVote: null, deltaPoints: 10, changed: true,
  });
  assert.equal(voteOnCurrent(user, 'up').rating, 6);
  assert.equal(voteOnCurrent(user, 'up').rating, 6);

  assert.deepEqual(previewVoteOnCurrent(user, 'down'), {
    songId: 'vote-rating', previousVote: 'up', deltaPoints: -20, changed: true,
  });
  assert.equal(voteOnCurrent(user, 'down').rating, 4);
});
