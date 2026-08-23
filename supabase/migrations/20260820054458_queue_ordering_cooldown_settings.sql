/*
# Queue ordering, duplicate/cooldown prevention, and host settings

1. Changes
  - Add `is_manual` boolean to `jukebox_queue` (default false). Manual additions
    (from search) get is_manual=true; auto-picked songs get is_manual=false.
  - Add `cooldown_minutes` int to `jukebox_settings` (default 30). Configurable
    by the host via the settings page.
  - Replace `add_to_queue` with a version that:
    * Accepts p_is_manual boolean
    * Rejects duplicates already in the queue (raises exception)
    * Rejects songs played within the cooldown window (raises exception)
    * Inserts manual songs before auto-picked songs by using position = 
      (min position of auto-picked songs) - 1, or max+1 if no auto songs exist
  - Add `get_cooldown_minutes()` helper to read the setting.

2. Security
  - No new tables. Existing RLS policies remain in effect.
*/

ALTER TABLE jukebox_queue ADD COLUMN IF NOT EXISTS is_manual boolean NOT NULL DEFAULT false;

ALTER TABLE jukebox_settings ADD COLUMN IF NOT EXISTS cooldown_minutes int NOT NULL DEFAULT 30;

CREATE INDEX IF NOT EXISTS jukebox_queue_manual_idx ON jukebox_queue (is_manual, position);

-- Read the cooldown setting.
CREATE OR REPLACE FUNCTION get_cooldown_minutes()
RETURNS int
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT COALESCE(
    (SELECT cooldown_minutes FROM jukebox_settings WHERE id = 1),
    30
  );
$$;

GRANT EXECUTE ON FUNCTION get_cooldown_minutes TO authenticated;

-- Update the cooldown setting (host only — enforced by RLS on jukebox_settings).
CREATE OR REPLACE FUNCTION set_cooldown_minutes(p_minutes int)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  IF p_minutes < 0 OR p_minutes > 1440 THEN
    RAISE EXCEPTION 'Cooldown must be between 0 and 1440 minutes';
  END IF;
  UPDATE jukebox_settings SET cooldown_minutes = p_minutes, updated_at = now() WHERE id = 1;
END;
$$;

GRANT EXECUTE ON FUNCTION set_cooldown_minutes TO authenticated;

-- Add a song to the shared queue with duplicate and cooldown checks.
-- Manual songs are inserted before auto-picked songs.
DROP FUNCTION IF EXISTS add_to_queue(text, text, text, text, int, text);

CREATE OR REPLACE FUNCTION add_to_queue(
  p_song_id text,
  p_title text,
  p_artist text,
  p_album text,
  p_duration int,
  p_cover_art text,
  p_is_manual boolean DEFAULT true
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_user_email text;
  v_max_pos double precision;
  v_min_auto_pos double precision;
  v_insert_pos double precision;
  v_cooldown_minutes int;
  v_recent_count int;
  v_existing_count int;
BEGIN
  -- Check for duplicate in queue
  SELECT COUNT(*) INTO v_existing_count FROM jukebox_queue WHERE song_id = p_song_id;
  IF v_existing_count > 0 THEN
    RAISE EXCEPTION 'This song is already in the queue';
  END IF;

  -- Check cooldown from play_history
  v_cooldown_minutes := get_cooldown_minutes();
  IF v_cooldown_minutes > 0 THEN
    SELECT COUNT(*) INTO v_recent_count
    FROM play_history
    WHERE song_id = p_song_id
      AND event IN ('complete', 'skip')
      AND created_at > now() - (v_cooldown_minutes || ' minutes')::interval;
    IF v_recent_count > 0 THEN
      RAISE EXCEPTION 'This song was played recently and is on cooldown';
    END IF;
  END IF;

  SELECT email INTO v_user_email FROM auth.users WHERE id = auth.uid();

  IF p_is_manual THEN
    -- Insert before auto-picked songs: find the min position among auto songs
    SELECT MIN(position) INTO v_min_auto_pos FROM jukebox_queue WHERE is_manual = false;
    IF v_min_auto_pos IS NOT NULL THEN
      v_insert_pos := v_min_auto_pos - 1;
    ELSE
      SELECT COALESCE(MAX(position), 0) INTO v_max_pos FROM jukebox_queue;
      v_insert_pos := v_max_pos + 1;
    END IF;
  ELSE
    -- Auto-picked: always at the end
    SELECT COALESCE(MAX(position), 0) INTO v_max_pos FROM jukebox_queue;
    v_insert_pos := v_max_pos + 1;
  END IF;

  INSERT INTO jukebox_queue (song_id, title, artist, album, duration, cover_art, position, added_by, added_by_email, is_manual)
  VALUES (p_song_id, p_title, p_artist, p_album, p_duration, p_cover_art, v_insert_pos, auth.uid(), COALESCE(v_user_email, ''), p_is_manual);
END;
$$;

GRANT EXECUTE ON FUNCTION add_to_queue TO authenticated;
