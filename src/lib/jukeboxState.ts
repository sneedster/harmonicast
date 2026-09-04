import type { Song } from '@/types';
import { request } from '@/lib/api';

export interface QueueRow {
  id: string;
  title: string;
  artist: string;
  album: string;
  duration: number;
  coverArt: string;
  addedByEmail: string;
  isManual: boolean;
}

export interface NowPlayingInfo {
  song: Song | null;
  isPlaying: boolean;
  isAutoQueue: boolean;
  playbackPosition: number;
}

export interface VoteCounts { up: number; down: number; }

export async function fetchQueue(): Promise<QueueRow[]> {
  return request<QueueRow[]>('/api/queue');
}

export async function fetchQueueSongs(): Promise<Song[]> {
  const rows = await fetchQueue();
  return rows.map((r) => ({
    id: r.id, title: r.title, artist: r.artist, album: r.album,
    duration: r.duration, coverArt: r.coverArt,
  }));
}

export async function fetchNowPlaying(): Promise<NowPlayingInfo> {
  return request<NowPlayingInfo>('/api/now-playing');
}

export async function fetchVoteCounts(songId: string): Promise<VoteCounts> {
  return request<VoteCounts>(`/api/votes/${encodeURIComponent(songId)}`);
}

export async function fetchCooldownMinutes(): Promise<number> {
  const s = await request<{ cooldownMinutes: number }>('/api/settings');
  return s.cooldownMinutes;
}

export async function setCooldownMinutes(minutes: number): Promise<void> {
  await request('/api/settings', { method: 'PUT', body: JSON.stringify({ cooldownMinutes: minutes }) });
}

export async function fetchMaxRequestsPerUser(): Promise<number> {
  const s = await request<{ maxRequestsPerUser: number }>('/api/settings');
  return s.maxRequestsPerUser;
}

export async function fetchRatedTrackShare(): Promise<number> {
  const s = await request<{ ratedTrackShare: number }>('/api/settings');
  return s.ratedTrackShare;
}

export async function fetchJukeboxMode(): Promise<boolean> {
  const s = await request<{ jukeboxMode: boolean }>('/api/settings');
  return s.jukeboxMode;
}

export async function setMaxRequestsPerUser(limit: number): Promise<void> {
  await request('/api/settings', { method: 'PUT', body: JSON.stringify({ maxRequestsPerUser: limit }) });
}

export async function addToQueue(song: Song, isManual = true): Promise<void> {
  await request('/api/queue', {
    method: 'POST',
    body: JSON.stringify({ song, isManual }),
  });
}

export async function addToQueueAuto(song: Song): Promise<void> {
  await request('/api/queue/auto', {
    method: 'POST',
    body: JSON.stringify({ song }),
  });
}

export async function updateNowPlaying(song: Song | null, isPlaying: boolean, isAutoQueue = false): Promise<void> {
  await request('/api/now-playing', {
    method: 'POST',
    body: JSON.stringify({ song, isPlaying, isAutoQueue }),
  });
}

export async function clearQueue(): Promise<void> {
  await request('/api/queue', { method: 'DELETE' });
}

export async function clearAutoQueue(): Promise<void> {
  await request('/api/queue/auto', { method: 'DELETE' });
}

export async function removeFromQueue(songId: string): Promise<void> {
  await request(`/api/queue/${encodeURIComponent(songId)}`, { method: 'DELETE' });
}

export async function dequeueNext(): Promise<{ song: Song | null; isManual: boolean }> {
  return request<{ song: Song | null; isManual: boolean }>('/api/queue/dequeue', { method: 'POST' });
}

export async function queueSimilarTracks(): Promise<number> {
  const result = await request<{ added: number }>('/api/queue/similar', { method: 'POST' });
  return result.added;
}

// Plex is the shared rating authority, so every successful tap adjusts its
// owner-account rating and deliberately has no per-user attribution.
export async function voteOnCurrent(vote: 'up' | 'down'): Promise<number | null> {
  const result = await request<{ rating?: number }>('/api/vote', {
    method: 'POST',
    body: JSON.stringify({ vote }),
  });
  return typeof result.rating === 'number' ? result.rating : null;
}

export async function clearOldVotes(songId: string): Promise<void> {
  await request(`/api/votes/clear/${encodeURIComponent(songId)}`, { method: 'POST' });
}
