/*
# Add multi-user authentication support

Adds a shared Navidrome connection table so multiple users can participate
in the jukebox. One person (the "host") sets up the Navidrome connection;
other users log in with their own accounts to search, queue, and rate songs.

1. New Tables
  - `jukebox_settings` — single-row table (id always 1) storing the shared
    Navidrome connection that all authenticated users share:
    - `id` (int, PK, always 1) — enforces a single row
    - `base_url` (text) — Navidrome server URL
    - `username` (text) — Navidrome username
    - `password` (text) — Navidrome password (shared with all users)
    - `server_name` (text, nullable) — optional display name
    - `direct` (boolean, default true) — direct vs proxy connection mode
    - `host_user_id` (uuid) — the Supabase user who set up the connection
    - `created_at`, `updated_at` (timestamptz)

2. New Functions
  - `is_jukebox_configured()` — SECURITY DEFINER function returning true if
    a connection exists. Callable by anon so unauthenticated visitors can
    determine whether to see the login screen or the setup screen. Returns
    only a boolean — no sensitive data is exposed.

3. Security
  - RLS on `jukebox_settings`:
    * SELECT: any authenticated user (needed to use the jukebox)
    * INSERT/UPDATE/DELETE: only the host (host_user_id = auth.uid())
  - Updated `song_stats` and `play_history` policies from
    `TO anon, authenticated` to `TO authenticated` only, since auth is now
    required to use the app.
*/

CREATE TABLE IF NOT EXISTS jukebox_settings (
  id int PRIMARY KEY DEFAULT 1,
  base_url text NOT NULL,
  username text NOT NULL,
  password text NOT NULL,
  server_name text,
  direct boolean NOT NULL DEFAULT true,
  host_user_id uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT single_row CHECK (id = 1)
);

ALTER TABLE jukebox_settings ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "authenticated_select_settings" ON jukebox_settings;
CREATE POLICY "authenticated_select_settings" ON jukebox_settings FOR SELECT
  TO authenticated USING (true);

DROP POLICY IF EXISTS "host_insert_settings" ON jukebox_settings;
CREATE POLICY "host_insert_settings" ON jukebox_settings FOR INSERT
  TO authenticated WITH CHECK (auth.uid() = host_user_id);

DROP POLICY IF EXISTS "host_update_settings" ON jukebox_settings;
CREATE POLICY "host_update_settings" ON jukebox_settings FOR UPDATE
  TO authenticated USING (auth.uid() = host_user_id) WITH CHECK (auth.uid() = host_user_id);

DROP POLICY IF EXISTS "host_delete_settings" ON jukebox_settings;
CREATE POLICY "host_delete_settings" ON jukebox_settings FOR DELETE
  TO authenticated USING (auth.uid() = host_user_id);

-- Public function: lets unauthenticated visitors check if the jukebox is set up.
CREATE OR REPLACE FUNCTION is_jukebox_configured()
RETURNS boolean
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT EXISTS (SELECT 1 FROM jukebox_settings WHERE id = 1);
$$;

GRANT EXECUTE ON FUNCTION is_jukebox_configured() TO anon, authenticated;

-- Lock down song_stats and play_history to authenticated users only.
DROP POLICY IF EXISTS "anon_select_song_stats" ON song_stats;
CREATE POLICY "authenticated_select_song_stats" ON song_stats FOR SELECT
  TO authenticated USING (true);

DROP POLICY IF EXISTS "anon_insert_song_stats" ON song_stats;
CREATE POLICY "authenticated_insert_song_stats" ON song_stats FOR INSERT
  TO authenticated WITH CHECK (true);

DROP POLICY IF EXISTS "anon_update_song_stats" ON song_stats;
CREATE POLICY "authenticated_update_song_stats" ON song_stats FOR UPDATE
  TO authenticated USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS "anon_delete_song_stats" ON song_stats;
CREATE POLICY "authenticated_delete_song_stats" ON song_stats FOR DELETE
  TO authenticated USING (true);

DROP POLICY IF EXISTS "anon_select_play_history" ON play_history;
CREATE POLICY "authenticated_select_play_history" ON play_history FOR SELECT
  TO authenticated USING (true);

DROP POLICY IF EXISTS "anon_insert_play_history" ON play_history;
CREATE POLICY "authenticated_insert_play_history" ON play_history FOR INSERT
  TO authenticated WITH CHECK (true);

DROP POLICY IF EXISTS "anon_delete_play_history" ON play_history;
CREATE POLICY "authenticated_delete_play_history" ON play_history FOR DELETE
  TO authenticated USING (true);
