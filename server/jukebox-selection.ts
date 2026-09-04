import type { PlexSong } from './plex.js';

export const JUKEBOX_MIX_LENGTH = 10;
export const DEFAULT_RATED_TRACK_SHARE = 8;
// A rating at or below 1 means the track has exhausted its automatic chances.
export const MIN_JUKEBOX_RATING_EXCLUSIVE = 1;

function plexJukeboxWeight(song: PlexSong, now: number): number {
  const rating = song.userRating ?? 5;
  const ratingWeight = Math.pow(Math.max(0.1, rating), 1.6);
  const hoursSincePlayed = song.lastViewedAt
    ? Math.max(0, (now - new Date(song.lastViewedAt).getTime()) / 3_600_000)
    : Infinity;
  const recencyWeight = Math.min(1, hoursSincePlayed / 6);
  const playWeight = 1 + Math.log(song.viewCount + 1) * 0.15;
  const skipWeight = 1 / (1 + song.skipCount * 0.1);
  return ratingWeight * recencyWeight * playWeight * skipWeight;
}

function pickWeighted(
  songs: PlexSong[],
  excludedIds: Set<string>,
  random: () => number,
  now: number,
): PlexSong | null {
  const pool = songs.filter((song) => !excludedIds.has(song.id));
  if (!pool.length) return null;
  const weighted = pool.map((song) => ({ song, weight: plexJukeboxWeight(song, now) }));
  const total = weighted.reduce((sum, candidate) => sum + candidate.weight, 0);
  if (total <= 0) return null;
  let target = random() * total;
  for (const candidate of weighted) {
    target -= candidate.weight;
    if (target <= 0) return candidate.song;
  }
  return weighted[weighted.length - 1].song;
}

export interface PlexJukeboxSelection {
  songs: PlexSong[];
  nextMixIndex: number;
}

/**
 * Select a configurable share of eligible rated tracks in each ten-track
 * cycle. Unrated slots are spread evenly through the cycle; for example, the
 * default share of eight produces rated/rated/rated/rated/unrated twice.
 * The cursor is retained between refills, so the mix still holds when the
 * queue is normally replenished one song at a time.
 */
export function choosePlexJukeboxTracks(
  ratedSongs: PlexSong[],
  unratedSongs: PlexSong[],
  fallbackSongs: PlexSong[],
  count: number,
  ratedTrackShare = DEFAULT_RATED_TRACK_SHARE,
  mixIndex = 0,
  random: () => number = Math.random,
  now = Date.now(),
): PlexJukeboxSelection {
  const selected: PlexSong[] = [];
  const selectedIds = new Set<string>();
  let nextMixIndex = Math.max(0, Math.floor(mixIndex));
  const ratedSlots = Math.max(0, Math.min(JUKEBOX_MIX_LENGTH, Math.round(ratedTrackShare)));
  const unratedSlots = JUKEBOX_MIX_LENGTH - ratedSlots;

  while (selected.length < count) {
    const slot = nextMixIndex % JUKEBOX_MIX_LENGTH;
    const exploration = Math.floor(((slot + 1) * unratedSlots) / JUKEBOX_MIX_LENGTH)
      > Math.floor((slot * unratedSlots) / JUKEBOX_MIX_LENGTH);
    const primary = exploration ? unratedSongs : ratedSongs;
    const strictEndpoint = ratedSlots === 0 || ratedSlots === JUKEBOX_MIX_LENGTH;
    const secondary = strictEndpoint ? [] : exploration ? ratedSongs : unratedSongs;
    const fallback = strictEndpoint
      ? fallbackSongs.filter((song) => ratedSlots === 0
        ? song.userRating === null
        : (song.userRating ?? 0) > MIN_JUKEBOX_RATING_EXCLUSIVE)
      : fallbackSongs;
    const song = pickWeighted(primary, selectedIds, random, now)
      ?? pickWeighted(secondary, selectedIds, random, now)
      ?? pickWeighted(fallback, selectedIds, random, now);
    if (!song) break;
    selected.push(song);
    selectedIds.add(song.id);
    nextMixIndex++;
  }

  return { songs: selected, nextMixIndex };
}
