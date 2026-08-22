/*
# Weighted Jukebox schema

Stores per-song popularity data and an auto-rating for a Subsonic-backed music
jukebox. This is a single-tenant app (no sign-in), so all data is shared and the
anon key is allowed full access.

1. New Tables
  - `song_stats` — one row per song (keyed by the music server's song id):
    - `song_id` (text, primary key) — the Subsonic song id
    - `title`, `artist`, `album` (text) — cached metadata for display
    - `duration` (int) — length in seconds
    - `cover_art` (text) — Subsonic cover art id
    - `rating` (numeric, default 5) — auto/manual rating on a 0-10 scale
    - `play_count` (int, default 0) — number of full plays
    - `skip_count` (int, default 0) — number of skips before finishing
    - `thumbs_up` / `thumbs_down` (int, default 0) — explicit votes
    - `last_played_at` (timestamptz) — used to cool down recently played songs
    - `created_at` / `updated_at` (timestamptz)
  - `play_history` — an append-only log of playback events:
    - `id` (uuid, primary key)
    - `song_id`, `title`, `artist` (text)
    - `event` (text) — one of complete, skip, thumbs_up, thumbs_down
    - `progress` (numeric) — fraction 0..1 of the song played when the event fired
    - `created_at` (timestamptz)

2. Functions
  - `record_play_event(...)` — upserts a song's stats and appends a history row,
    applying the auto-rating rules atomically:
      * complete: rating rises slightly, by an amount that grows with play count
      * skip: rating drops slightly, more when skipped early
      * thumbs_up / thumbs_down: larger explicit adjustments
    Rating is always clamped to the 0-10 range.

3. Security
  - RLS enabled on both tables.
  - Because the app has no sign-in, anon + authenticated may read/write. The data
    is intentionally shared across everyone using this jukebox.
*/

CREATE TABLE IF NOT EXISTS song_stats (
  song_id text PRIMARY KEY,
  title text NOT NULL DEFAULT '',
  artist text NOT NULL DEFAULT '',
  album text NOT NULL DEFAULT '',
  duration int NOT NULL DEFAULT 0,
  cover_art text NOT NULL DEFAULT '',
  rating numeric NOT NULL DEFAULT 5,
  play_count int NOT NULL DEFAULT 0,
  skip_count int NOT NULL DEFAULT 0,
  thumbs_up int NOT NULL DEFAULT 0,
  thumbs_down int NOT NULL DEFAULT 0,
  last_played_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS play_history (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  song_id text NOT NULL,
  title text NOT NULL DEFAULT '',
  artist text NOT NULL DEFAULT '',
  event text NOT NULL,
  progress numeric NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS song_stats_rating_idx ON song_stats (rating DESC);
CREATE INDEX IF NOT EXISTS song_stats_play_count_idx ON song_stats (play_count DESC);
CREATE INDEX IF NOT EXISTS song_stats_last_played_idx ON song_stats (last_played_at DESC NULLS LAST);
CREATE INDEX IF NOT EXISTS play_history_created_idx ON play_history (created_at DESC);

ALTER TABLE song_stats ENABLE ROW LEVEL SECURITY;
ALTER TABLE play_history ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "anon_select_song_stats" ON song_stats;
CREATE POLICY "anon_select_song_stats" ON song_stats FOR SELECT
  TO anon, authenticated USING (true);

DROP POLICY IF EXISTS "anon_insert_song_stats" ON song_stats;
CREATE POLICY "anon_insert_song_stats" ON song_stats FOR INSERT
  TO anon, authenticated WITH CHECK (true);

DROP POLICY IF EXISTS "anon_update_song_stats" ON song_stats;
CREATE POLICY "anon_update_song_stats" ON song_stats FOR UPDATE
  TO anon, authenticated USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS "anon_delete_song_stats" ON song_stats;
CREATE POLICY "anon_delete_song_stats" ON song_stats FOR DELETE
  TO anon, authenticated USING (true);

DROP POLICY IF EXISTS "anon_select_play_history" ON play_history;
CREATE POLICY "anon_select_play_history" ON play_history FOR SELECT
  TO anon, authenticated USING (true);

DROP POLICY IF EXISTS "anon_insert_play_history" ON play_history;
CREATE POLICY "anon_insert_play_history" ON play_history FOR INSERT
  TO anon, authenticated WITH CHECK (true);

DROP POLICY IF EXISTS "anon_delete_play_history" ON play_history;
CREATE POLICY "anon_delete_play_history" ON play_history FOR DELETE
  TO anon, authenticated USING (true);

CREATE OR REPLACE FUNCTION record_play_event(
  p_song_id text,
  p_title text,
  p_artist text,
  p_album text,
  p_duration int,
  p_cover_art text,
  p_event text,
  p_progress numeric
)
RETURNS song_stats
LANGUAGE plpgsql
AS $$
DECLARE
  v_row song_stats;
  v_delta numeric := 0;
BEGIN
  INSERT INTO song_stats (song_id, title, artist, album, duration, cover_art)
  VALUES (p_song_id, coalesce(p_title, ''), coalesce(p_artist, ''), coalesce(p_album, ''),
          coalesce(p_duration, 0), coalesce(p_cover_art, ''))
  ON CONFLICT (song_id) DO UPDATE
    SET title = coalesce(NULLIF(p_title, ''), song_stats.title),
        artist = coalesce(NULLIF(p_artist, ''), song_stats.artist),
        album = coalesce(NULLIF(p_album, ''), song_stats.album),
        duration = GREATEST(song_stats.duration, coalesce(p_duration, 0)),
        cover_art = coalesce(NULLIF(p_cover_art, ''), song_stats.cover_art)
  RETURNING * INTO v_row;

  IF p_event = 'complete' THEN
    -- Slight increase that grows with how many times the song has been played.
    v_delta := 0.05 * (1 + ln(v_row.play_count + 1));
    UPDATE song_stats
      SET rating = LEAST(10, rating + v_delta),
          play_count = play_count + 1,
          last_played_at = now(),
          updated_at = now()
      WHERE song_id = p_song_id
      RETURNING * INTO v_row;

  ELSIF p_event = 'skip' THEN
    -- Slight decrease, larger when the song was skipped early.
    v_delta := 0.3 * (1 - LEAST(1, GREATEST(0, coalesce(p_progress, 0))));
    UPDATE song_stats
      SET rating = GREATEST(0, rating - v_delta),
          skip_count = skip_count + 1,
          last_played_at = now(),
          updated_at = now()
      WHERE song_id = p_song_id
      RETURNING * INTO v_row;

  ELSIF p_event = 'thumbs_up' THEN
    UPDATE song_stats
      SET rating = LEAST(10, rating + 1.0),
          thumbs_up = thumbs_up + 1,
          updated_at = now()
      WHERE song_id = p_song_id
      RETURNING * INTO v_row;

  ELSIF p_event = 'thumbs_down' THEN
    UPDATE song_stats
      SET rating = GREATEST(0, rating - 1.0),
          thumbs_down = thumbs_down + 1,
          updated_at = now()
      WHERE song_id = p_song_id
      RETURNING * INTO v_row;
  END IF;

  INSERT INTO play_history (song_id, title, artist, event, progress)
  VALUES (p_song_id, v_row.title, v_row.artist, p_event,
          LEAST(1, GREATEST(0, coalesce(p_progress, 0))));

  RETURN v_row;
END;
$$;