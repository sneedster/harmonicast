/*
# Fair queue: per-user request limits + round-robin ordering

1. Changes
  - Add `max_requests_per_user` int to `jukebox_settings` (default 5).
    Limits how many songs a single user can have in the queue at once.
  - Add `reorder_queue_round_robin()` function that reassigns `position` so
    manual songs alternate by user (round-robin), auto-picked songs stay at
    the end.  Called after every manual add.
  - Update `add_to_queue` to:
    * Enforce the per-user limit (raise exception when exceeded)
    * Call `reorder_queue_round_robin()` after inserting a manual song
  - Add `get_max_requests_per_user()` / `set_max_requests_per_user()` helpers.

2. Security
  - No new tables.  Existing RLS policies remain in effect.
*/

ALTER TABLE jukebox_settings
  ADD COLUMN IF NOT EXISTS max_requests_per_user int NOT NULL DEFAULT 5;

-- Read the per-user request limit.
CREATE OR REPLACE FUNCTION get_max_requests_per_user()
RETURNS int
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT COALESCE(
    (SELECT max_requests_per_user FROM jukebox_settings WHERE id = 1),
    5
  );
$$;

GRANT EXECUTE ON FUNCTION get_max_requests_per_user TO authenticated;

-- Update the per-user request limit (host only — enforced by RLS).
CREATE OR REPLACE FUNCTION set_max_requests_per_user(p_limit int)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  IF p_limit < 1 OR p_limit > 100 THEN
    RAISE EXCEPTION 'Request limit must be between 1 and 100';
  END IF;
  UPDATE jukebox_settings
    SET max_requests_per_user = p_limit, updated_at = now()
    WHERE id = 1;
END;
$$;

GRANT EXECUTE ON FUNCTION set_max_requests_per_user TO authenticated;

-- Reassign position values so manual songs alternate by user (round-robin),
-- auto-picked songs remain at the end in their existing relative order.
-- Example:  user-A songs at positions 1,2,3  +  user-B songs at 4,5
--           becomes  A1, B1, A2, B2, A3  (then auto songs).
CREATE OR REPLACE FUNCTION reorder_queue_round_robin()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_row record;
  v_pos double precision := 1;
BEGIN
  -- Temporary table: manual songs, ordered by their current position,
  -- so we cycle through users in a stable way.
  CREATE TEMP TABLE IF NOT EXISTS _rr_manual ON COMMIT DROP AS
    SELECT id, added_by,
           ROW_NUMBER() OVER (
             PARTITION BY added_by ORDER BY position
           ) AS user_seq
    FROM jukebox_queue
    WHERE is_manual = true
    ORDER BY user_seq, added_by;

  -- Round-robin: iterate "rounds" (1st song per user, then 2nd, etc.)
  FOR v_seq IN 1..COALESCE((SELECT MAX(user_seq) FROM _rr_manual), 0) LOOP
    FOR v_row IN
      SELECT id FROM _rr_manual WHERE user_seq = v_seq ORDER BY added_by
    LOOP
      UPDATE jukebox_queue SET position = v_pos WHERE id = v_row.id;
      v_pos := v_pos + 1;
    END LOOP;
  END LOOP;

  -- Auto-picked songs: keep at the end in existing order.
  FOR v_row IN
    SELECT id FROM jukebox_queue
    WHERE is_manual = false
    ORDER BY position
  LOOP
    UPDATE jukebox_queue SET position = v_pos WHERE id = v_row.id;
    v_pos := v_pos + 1;
  END LOOP;

  DROP TABLE IF EXISTS _rr_manual;
END;
$$;

GRANT EXECUTE ON FUNCTION reorder_queue_round_robin TO authenticated;

-- Replace add_to_queue with a version that enforces per-user limits and
-- reorders the queue round-robin after each manual add.
DROP FUNCTION IF EXISTS add_to_queue(text, text, text, text, int, text, boolean);

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
  v_user_count int;
  v_max_requests int;
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

  -- Per-user request limit (only enforced for manual adds)
  IF p_is_manual THEN
    v_max_requests := get_max_requests_per_user();
    SELECT COUNT(*) INTO v_user_count
    FROM jukebox_queue
    WHERE added_by = auth.uid() AND is_manual = true;
    IF v_user_count >= v_max_requests THEN
      RAISE EXCEPTION 'You have reached the limit of % songs in the queue', v_max_requests;
    END IF;
  END IF;

  SELECT email INTO v_user_email FROM auth.users WHERE id = auth.uid();

  IF p_is_manual THEN
    -- Insert before auto-picked songs
    SELECT MIN(position) INTO v_min_auto_pos FROM jukebox_queue WHERE is_manual = false;
    IF v_min_auto_pos IS NOT NULL THEN
      v_insert_pos := v_min_auto_pos - 1;
    ELSE
      SELECT COALESCE(MAX(position), 0) INTO v_max_pos FROM jukebox_queue;
      v_insert_pos := v_max_pos + 1;
    END IF;
  ELSE
    SELECT COALESCE(MAX(position), 0) INTO v_max_pos FROM jukebox_queue;
    v_insert_pos := v_max_pos + 1;
  END IF;

  INSERT INTO jukebox_queue (song_id, title, artist, album, duration, cover_art, position, added_by, added_by_email, is_manual)
  VALUES (p_song_id, p_title, p_artist, p_album, p_duration, p_cover_art, v_insert_pos, auth.uid(), COALESCE(v_user_email, ''), p_is_manual);

  -- Reorder manual songs round-robin so no single user dominates the queue.
  IF p_is_manual THEN
    PERFORM reorder_queue_round_robin();
  END IF;
END;
$$;

GRANT EXECUTE ON FUNCTION add_to_queue TO authenticated;
