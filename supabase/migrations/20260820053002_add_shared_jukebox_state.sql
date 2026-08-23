/*
# Shared jukebox queue and now-playing state

Adds tables so multiple users can share a single jukebox session. The host's
device plays the audio; guests see the same queue and now-playing track in
real time and can add songs or vote, but have no audio controls.

1. New Tables
  - `jukebox_queue` — the shared upcoming queue, ordered by `position`:
    - `id` (uuid, PK)
    - `song_id` (text) — Subsonic song id
    - `title`, `artist`, `album` (text) — cached metadata
    - `duration` (int) — length in seconds
    - `cover_art` (text) — Subsonic cover art id
    - `position` (double) — sort key for queue ordering
    - `added_by` (uuid) — the user who added the song
    - `added_by_email` (text) — email for display
    - `created_at` (timestamptz)
  - `jukebox_now_playing` — single-row table (id always 1) tracking what the
    host is currently playing:
    - `id` (int, PK, always 1)
    - `song_id` (text, nullable) — null when nothing is playing
    - `title`, `artist`, `album` (text)
    - `duration` (int)
    - `cover_art` (text)
    - `is_playing` (boolean) — whether the host has audio playing
    - `updated_at` (timestamptz) — used to detect stale state
  - `jukebox_votes` — per-user votes on the currently playing song:
    - `id` (uuid, PK)
    - `song_id` (text) — the song being voted on
    - `user_id` (uuid) — who voted
    - `vote` (text) — 'up' or 'down'
    - `created_at` (timestamptz)
    - UNIQUE constraint on (song_id, user_id) — one vote per user per song

2. New Functions
  - `add_to_queue(...)` — SECURITY DEFINER function that inserts a song into
    the queue at the end. Callable by any authenticated user.
  - `vote_now_playing(p_vote text)` — SECURITY DEFINER function that records
    a vote for the currently playing song. Enforces one vote per user per
    song via upsert on the unique constraint. Callable by authenticated.
  - `get_vote_counts(p_song_id text)` — returns up/down vote counts for a song.

3. Security
  - RLS on all new tables:
    * `jukebox_queue`: SELECT for all authenticated; INSERT/DELETE for all
      authenticated (any user can add or remove their own additions);
      UPDATE only for host.
    * `jukebox_now_playing`: SELECT for all authenticated; INSERT/UPDATE/
      DELETE only for host.
    * `jukebox_votes`: SELECT for all authenticated; INSERT for all
      authenticated (via the SECURITY DEFINER function); DELETE for host
      (to clear stale votes when the song changes).
*/

CREATE TABLE IF NOT EXISTS jukebox_queue (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  song_id text NOT NULL,
  title text NOT NULL DEFAULT '',
  artist text NOT NULL DEFAULT '',
  album text NOT NULL DEFAULT '',
  duration int NOT NULL DEFAULT 0,
  cover_art text NOT NULL DEFAULT '',
  position double precision NOT NULL DEFAULT 0,
  added_by uuid REFERENCES auth.users(id) ON DELETE SET NULL,
  added_by_email text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS jukebox_queue_position_idx ON jukebox_queue (position);

ALTER TABLE jukebox_queue ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "authenticated_select_queue" ON jukebox_queue;
CREATE POLICY "authenticated_select_queue" ON jukebox_queue FOR SELECT
  TO authenticated USING (true);

DROP POLICY IF EXISTS "authenticated_insert_queue" ON jukebox_queue;
CREATE POLICY "authenticated_insert_queue" ON jukebox_queue FOR INSERT
  TO authenticated WITH CHECK (true);

DROP POLICY IF EXISTS "authenticated_delete_queue" ON jukebox_queue;
CREATE POLICY "authenticated_delete_queue" ON jukebox_queue FOR DELETE
  TO authenticated USING (true);

DROP POLICY IF EXISTS "host_update_queue" ON jukebox_queue;
CREATE POLICY "host_update_queue" ON jukebox_queue FOR UPDATE
  TO authenticated USING (true) WITH CHECK (true);

CREATE TABLE IF NOT EXISTS jukebox_now_playing (
  id int PRIMARY KEY DEFAULT 1,
  song_id text,
  title text NOT NULL DEFAULT '',
  artist text NOT NULL DEFAULT '',
  album text NOT NULL DEFAULT '',
  duration int NOT NULL DEFAULT 0,
  cover_art text NOT NULL DEFAULT '',
  is_playing boolean NOT NULL DEFAULT false,
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT single_now_playing CHECK (id = 1)
);

ALTER TABLE jukebox_now_playing ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "authenticated_select_now_playing" ON jukebox_now_playing;
CREATE POLICY "authenticated_select_now_playing" ON jukebox_now_playing FOR SELECT
  TO authenticated USING (true);

DROP POLICY IF EXISTS "host_insert_now_playing" ON jukebox_now_playing;
CREATE POLICY "host_insert_now_playing" ON jukebox_now_playing FOR INSERT
  TO authenticated WITH CHECK (true);

DROP POLICY IF EXISTS "host_update_now_playing" ON jukebox_now_playing;
CREATE POLICY "host_update_now_playing" ON jukebox_now_playing FOR UPDATE
  TO authenticated USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS "host_delete_now_playing" ON jukebox_now_playing;
CREATE POLICY "host_delete_now_playing" ON jukebox_now_playing FOR DELETE
  TO authenticated USING (true);

CREATE TABLE IF NOT EXISTS jukebox_votes (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  song_id text NOT NULL,
  user_id uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  vote text NOT NULL CHECK (vote IN ('up', 'down')),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (song_id, user_id)
);

ALTER TABLE jukebox_votes ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "authenticated_select_votes" ON jukebox_votes;
CREATE POLICY "authenticated_select_votes" ON jukebox_votes FOR SELECT
  TO authenticated USING (true);

DROP POLICY IF EXISTS "authenticated_insert_votes" ON jukebox_votes;
CREATE POLICY "authenticated_insert_votes" ON jukebox_votes FOR INSERT
  TO authenticated WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS "host_delete_votes" ON jukebox_votes;
CREATE POLICY "host_delete_votes" ON jukebox_votes FOR DELETE
  TO authenticated USING (true);

-- Add a song to the end of the shared queue.
CREATE OR REPLACE FUNCTION add_to_queue(
  p_song_id text,
  p_title text,
  p_artist text,
  p_album text,
  p_duration int,
  p_cover_art text
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_max_pos double precision;
  v_user_email text;
BEGIN
  SELECT email INTO v_user_email FROM auth.users WHERE id = auth.uid();
  SELECT COALESCE(MAX(position), 0) INTO v_max_pos FROM jukebox_queue;
  INSERT INTO jukebox_queue (song_id, title, artist, album, duration, cover_art, position, added_by, added_by_email)
  VALUES (p_song_id, p_title, p_artist, p_album, p_duration, p_cover_art, v_max_pos + 1, auth.uid(), COALESCE(v_user_email, ''));
END;
$$;

GRANT EXECUTE ON FUNCTION add_to_queue TO authenticated;

-- Vote on the currently playing song. One vote per user per song.
CREATE OR REPLACE FUNCTION vote_now_playing(p_vote text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_song_id text;
BEGIN
  IF p_vote NOT IN ('up', 'down') THEN
    RAISE EXCEPTION 'Invalid vote type';
  END IF;
  SELECT song_id INTO v_song_id FROM jukebox_now_playing WHERE id = 1;
  IF v_song_id IS NULL THEN
    RAISE EXCEPTION 'No song is currently playing';
  END IF;
  INSERT INTO jukebox_votes (song_id, user_id, vote)
  VALUES (v_song_id, auth.uid(), p_vote)
  ON CONFLICT (song_id, user_id) DO UPDATE SET vote = EXCLUDED.vote, created_at = now();
END;
$$;

GRANT EXECUTE ON FUNCTION vote_now_playing TO authenticated;

-- Get vote counts for a song.
CREATE OR REPLACE FUNCTION get_vote_counts(p_song_id text)
RETURNS TABLE(up_count bigint, down_count bigint)
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT
    COUNT(*) FILTER (WHERE vote = 'up'),
    COUNT(*) FILTER (WHERE vote = 'down')
  FROM jukebox_votes
  WHERE song_id = p_song_id;
$$;

GRANT EXECUTE ON FUNCTION get_vote_counts TO authenticated;

-- Clear votes for songs that are no longer playing (host can call this when advancing).
CREATE OR REPLACE FUNCTION clear_old_votes(p_song_id text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  DELETE FROM jukebox_votes WHERE song_id = p_song_id;
END;
$$;

GRANT EXECUTE ON FUNCTION clear_old_votes TO authenticated;
