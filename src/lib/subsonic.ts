import type { Connection, Song } from '@/types';
import { request } from '@/lib/api';

interface SubsonicResponseBody {
  'subsonic-response'?: {
    status: 'ok' | 'failed';
    error?: { code: number; message: string };
    [key: string]: unknown;
  };
  error?: string;
}

async function apiCall(
  conn: Connection,
  endpoint: string,
  extra: Record<string, string | number | boolean> = {},
): Promise<Record<string, unknown>> {
  const query = { ...extra };
  const res = await request<SubsonicResponseBody>('/api/subsonic', {
    method: 'POST',
    body: JSON.stringify({ baseUrl: conn.baseUrl, endpoint, query }),
  });

  const sub = res['subsonic-response'];
  if (!sub) throw new Error('The server responded, but it does not look like a Subsonic music server.');
  if (sub.status === 'failed') throw new Error(sub.error?.message || 'The music server rejected the request.');
  return sub;
}

function toSong(raw: Record<string, unknown>): Song {
  return {
    id: String(raw.id ?? ''),
    title: String(raw.title ?? 'Unknown title'),
    artist: String(raw.artist ?? 'Unknown artist'),
    album: String(raw.album ?? ''),
    duration: Number(raw.duration ?? 0),
    coverArt: String(raw.coverArt ?? raw.id ?? ''),
  };
}

export async function ping(conn: Connection): Promise<void> {
  await apiCall(conn, 'ping');
}

export async function getRandomSongs(conn: Connection, size = 100): Promise<Song[]> {
  const res = await apiCall(conn, 'getRandomSongs', { size });
  const container = res.randomSongs as { song?: Record<string, unknown>[] } | undefined;
  return (container?.song ?? []).map(toSong);
}

export async function search(conn: Connection, query: string): Promise<Song[]> {
  const res = await apiCall(conn, 'search3', {
    query,
    songCount: 40,
    albumCount: 0,
    artistCount: 0,
  });
  const container = res.searchResult3 as { song?: Record<string, unknown>[] } | undefined;
  return (container?.song ?? []).map(toSong);
}

export async function scrobble(conn: Connection, id: string, submission: boolean): Promise<void> {
  try {
    await request('/api/scrobble', {
      method: 'POST',
      body: JSON.stringify({ id, submission }),
    });
  } catch {
    // Scrobbling is best-effort.
  }
}

function authQuery(): string {
  const token = localStorage.getItem('resonance_token');
  return token ? `&token=${encodeURIComponent(token)}` : '';
}

export function streamUrl(_conn: Connection, id: string): string {
  const base = import.meta.env.VITE_API_BASE || '';
  return `${base}/api/stream/${encodeURIComponent(id)}?${authQuery().slice(1)}`;
}

export function coverArtUrl(_conn: Connection, coverArt: string, size = 300): string | null {
  if (!coverArt) return null;
  const base = import.meta.env.VITE_API_BASE || '';
  return `${base}/api/cover-art/${encodeURIComponent(coverArt)}?size=${size}${authQuery()}`;
}
