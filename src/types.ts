export interface Song {
  id: string;
  title: string;
  artist: string;
  album: string;
  duration: number;
  coverArt: string;
}

export interface SongStats {
  song_id: string;
  title: string;
  artist: string;
  album: string;
  duration: number;
  cover_art: string;
  rating: number;
  play_count: number;
  skip_count: number;
  thumbs_up: number;
  thumbs_down: number;
  last_played_at: string | null;
}

export interface PlayHistoryRow {
  id: string;
  song_id: string;
  title: string;
  artist: string;
  event: PlayEvent;
  progress: number;
  created_at: string;
}

export type PlayEvent = 'complete' | 'skip' | 'thumbs_up' | 'thumbs_down';

export interface Connection {
  baseUrl: string;
  username: string;
  password: string;
  serverName?: string;
}
