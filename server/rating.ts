export const MIN_RATING_POINTS = 0;
export const MAX_RATING_POINTS = 100;
export const DEFAULT_RATING_POINTS = 50;
export const THUMBS_RATING_POINTS = 10;

export type PlaybackRatingEvent = 'complete' | 'skip';

function clamp(value: number, minimum: number, maximum: number): number {
  return Math.max(minimum, Math.min(maximum, value));
}

/** Convert Plex's 0.0-10.0 rating into Harmonicast's integer 0-100 scale. */
export function plexRatingToPoints(rating: number | null | undefined): number {
  if (rating === null || rating === undefined) return DEFAULT_RATING_POINTS;
  const numeric = Number(rating);
  return Number.isFinite(numeric)
    ? clamp(Math.round(numeric * 10), MIN_RATING_POINTS, MAX_RATING_POINTS)
    : DEFAULT_RATING_POINTS;
}

/** Convert integer rating points to the one-decimal precision Plex preserves. */
export function ratingPointsToPlex(points: number): number {
  return clamp(Math.round(Number(points) || 0), MIN_RATING_POINTS, MAX_RATING_POINTS) / 10;
}

export function quantizePlexRating(rating: number): number {
  return ratingPointsToPlex(plexRatingToPoints(rating));
}

/**
 * Legacy completion reward, translated from the 0-10 formula to whole points.
 * The old 0.05 * (1 + ln(playCount + 1)) becomes
 * 0.5 * (1 + ln(playCount + 1)) points, rounded to Plex's 0.1 step.
 */
export function completionRewardPoints(playCount: number): number {
  const plays = Math.max(0, Math.floor(Number(playCount) || 0));
  return Math.round(0.5 * (1 + Math.log(plays + 1)));
}

/**
 * Legacy skip penalty on the 100-point scale. An immediate skip costs three
 * points (0.3 in Plex); the penalty tapers to zero as the track approaches its
 * end.
 */
export function skipPenaltyPoints(progress: number): number {
  const normalizedProgress = clamp(Number(progress) || 0, 0, 1);
  return Math.round(3 * (1 - normalizedProgress));
}

export function adjustRatingByPoints(rating: number | null | undefined, deltaPoints: number): number {
  return ratingPointsToPlex(plexRatingToPoints(rating) + Math.round(deltaPoints));
}

export function applyPlaybackRating(
  rating: number | null | undefined,
  event: PlaybackRatingEvent,
  progress: number,
  playCount: number,
): { rating: number; ratingPoints: number; deltaPoints: number } {
  const deltaPoints = event === 'complete'
    ? completionRewardPoints(playCount)
    : -skipPenaltyPoints(progress);
  const ratingPoints = clamp(
    plexRatingToPoints(rating) + deltaPoints,
    MIN_RATING_POINTS,
    MAX_RATING_POINTS,
  );
  return { rating: ratingPointsToPlex(ratingPoints), ratingPoints, deltaPoints };
}
