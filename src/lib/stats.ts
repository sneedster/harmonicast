import type { PlayEvent, PlayHistoryRow, Song, SongStats } from '@/types';
import { request } from '@/lib/api';

export async function recordPlayEvent(
  song: Song,
  event: PlayEvent,
  progress: number,
): Promise<SongStats | null> {
  const result = await request<SongStats | null>('/api/stats/play-event', {
    method: 'POST',
    body: JSON.stringify({
      song_id: song.id,
      title: song.title,
      artist: song.artist,
      album: song.album,
      duration: Math.round(song.duration) || 0,
      cover_art: song.coverArt,
      event,
      progress,
    }),
  });
  return result;
}

export async function fetchStatsFor(songIds: string[]): Promise<Map<string, SongStats>> {
  const map = new Map<string, SongStats>();
  if (songIds.length === 0) return map;
  const rows = await request<SongStats[]>('/api/stats/batch', {
    method: 'POST',
    body: JSON.stringify({ songIds }),
  });
  for (const row of rows) {
    map.set(row.song_id, row);
  }
  return map;
}

export async function fetchTopRated(limit = 50, minRating = 5.05): Promise<SongStats[]> {
  return request<SongStats[]>(`/api/stats/top-rated?limit=${limit}&minRating=${minRating}`);
}

export async function fetchMostPlayed(limit = 50): Promise<SongStats[]> {
  return request<SongStats[]>(`/api/stats/most-played?limit=${limit}`);
}

export async function fetchRecentlyPlayed(limit = 30): Promise<PlayHistoryRow[]> {
  return request<PlayHistoryRow[]>(`/api/stats/recent?limit=${limit}`);
}

export function statsToSong(stats: SongStats): Song {
  return {
    id: stats.song_id,
    title: stats.title,
    artist: stats.artist,
    album: stats.album,
    duration: stats.duration,
    coverArt: stats.cover_art,
  };
}
