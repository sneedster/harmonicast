import assert from 'node:assert/strict';
import test from 'node:test';
import {
  adjustRatingByPoints,
  applyPlaybackRating,
  completionRewardPoints,
  plexRatingToPoints,
  quantizePlexRating,
  ratingPointsToPlex,
  skipPenaltyPoints,
} from '../rating.js';

test('rating conversion uses an integer 0-100 scale and Plex tenths', () => {
  assert.equal(plexRatingToPoints(null), 50);
  assert.equal(plexRatingToPoints(5.05), 51);
  assert.equal(ratingPointsToPlex(51), 5.1);
  assert.equal(quantizePlexRating(5.049), 5);
  assert.equal(quantizePlexRating(5.05), 5.1);
  assert.equal(ratingPointsToPlex(-10), 0);
  assert.equal(ratingPointsToPlex(110), 10);
});

test('legacy completion reward is rounded to whole rating points', () => {
  assert.equal(completionRewardPoints(0), 1);
  assert.equal(completionRewardPoints(1), 1);
  assert.equal(completionRewardPoints(10), 2);
  assert.deepEqual(applyPlaybackRating(5, 'complete', 1, 0), {
    rating: 5.1,
    ratingPoints: 51,
    deltaPoints: 1,
  });
  assert.equal(applyPlaybackRating(null, 'complete', 1, 0).rating, 5.1);
});

test('legacy skip penalty tapers from three points to zero', () => {
  assert.equal(skipPenaltyPoints(0), 3);
  assert.equal(skipPenaltyPoints(0.5), 2);
  assert.equal(skipPenaltyPoints(0.9), 0);
  assert.deepEqual(applyPlaybackRating(5, 'skip', 0, 0), {
    rating: 4.7,
    ratingPoints: 47,
    deltaPoints: -3,
  });
});

test('thumb adjustments stay at ten points and clamp at the scale bounds', () => {
  assert.equal(adjustRatingByPoints(5, 10), 6);
  assert.equal(adjustRatingByPoints(5, -10), 4);
  assert.equal(adjustRatingByPoints(9.8, 10), 10);
  assert.equal(adjustRatingByPoints(0.2, -10), 0);
});
