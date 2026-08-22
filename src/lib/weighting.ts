import type { Song, SongStats } from '@/types';

export const DEFAULT_RATING = 5;
const COOLDOWN_HOURS = 6;

export interface WeightedCandidate {
  song: Song;
  stats: SongStats | null;
  weight: number;
  ratingComponent: number;
  recencyComponent: number;
  popularityComponent: number;
}

function hoursSince(iso: string | null): number {
  if (!iso) return Infinity;
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return Infinity;
  return (Date.now() - then) / (1000 * 60 * 60);
}

/*
 * Weight = rating^1.6  x  recency  x  popularity
 *  - rating: higher-rated songs are exponentially more likely.
 *  - recency: songs played within the cooldown window are damped so the jukebox
 *    does not repeat itself; a song played long ago (or never) gets full weight.
 *  - popularity: a gentle boost from total play count.
 */
export function computeWeight(song: Song, stats: SongStats | null): WeightedCandidate {
  const rating = stats ? stats.rating : DEFAULT_RATING;
  const ratingComponent = Math.pow(Math.max(0.1, rating), 1.6);

  const hrs = hoursSince(stats?.last_played_at ?? null);
  const recencyComponent = Math.min(1, hrs / COOLDOWN_HOURS);

  const playCount = stats ? stats.play_count : 0;
  const popularityComponent = 1 + Math.log(playCount + 1) * 0.15;

  const weight = ratingComponent * recencyComponent * popularityComponent;

  return { song, stats, weight, ratingComponent, recencyComponent, popularityComponent };
}

export function buildCandidates(
  songs: Song[],
  statsMap: Map<string, SongStats>,
): WeightedCandidate[] {
  const seen = new Set<string>();
  const candidates: WeightedCandidate[] = [];
  for (const song of songs) {
    if (!song.id || seen.has(song.id)) continue;
    seen.add(song.id);
    candidates.push(computeWeight(song, statsMap.get(song.id) ?? null));
  }
  return candidates;
}

export function pickWeighted(
  candidates: WeightedCandidate[],
  excludeIds: Set<string>,
): WeightedCandidate | null {
  const pool = candidates.filter((c) => c.weight > 0 && !excludeIds.has(c.song.id));
  if (pool.length === 0) return null;

  const total = pool.reduce((sum, c) => sum + c.weight, 0);
  let target = Math.random() * total;
  for (const candidate of pool) {
    target -= candidate.weight;
    if (target <= 0) return candidate;
  }
  return pool[pool.length - 1];
}
