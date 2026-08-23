-- # Revoke client access on legacy jukebox tables
--
-- These six tables belong to an earlier iteration of the app. The application
-- now runs entirely against a self-hosted Node/SQLite backend and ships no
-- Supabase client library, so nothing reads or writes them.
--
-- They were left with SELECT/INSERT/UPDATE/DELETE granted to both `anon` and
-- `authenticated` on all columns, and their policies did not restrict rows:
-- authenticated_delete_queue, host_delete_now_playing, host_update_queue,
-- authenticated_delete_play_history and authenticated_update_song_stats were
-- all `USING (true)`, so any signed-in user could delete or rewrite every row
-- including other people's queue entries and every song rating. Policies named
-- "host_*" did not in fact check the host.
--
-- ## Changes
-- 1. Drop every permissive policy on the six tables.
-- 2. Revoke all table privileges from `anon` and `authenticated`.
-- 3. Leave RLS enabled, so the tables are deny-by-default and unreachable from
--    the Data API while the rows themselves are preserved untouched.
--
-- ## Notes
-- - No data is dropped or altered; only access is removed.
-- - No application query breaks, because no application query targets these.

-- 1. Drop permissive policies.
DROP POLICY IF EXISTS "authenticated_select_now_playing" ON public.jukebox_now_playing;
DROP POLICY IF EXISTS "host_delete_now_playing" ON public.jukebox_now_playing;
DROP POLICY IF EXISTS "host_insert_now_playing" ON public.jukebox_now_playing;
DROP POLICY IF EXISTS "host_update_now_playing" ON public.jukebox_now_playing;

DROP POLICY IF EXISTS "authenticated_delete_queue" ON public.jukebox_queue;
DROP POLICY IF EXISTS "authenticated_insert_queue" ON public.jukebox_queue;
DROP POLICY IF EXISTS "authenticated_select_queue" ON public.jukebox_queue;
DROP POLICY IF EXISTS "host_update_queue" ON public.jukebox_queue;

DROP POLICY IF EXISTS "authenticated_select_settings" ON public.jukebox_settings;
DROP POLICY IF EXISTS "host_delete_settings" ON public.jukebox_settings;
DROP POLICY IF EXISTS "host_insert_settings" ON public.jukebox_settings;
DROP POLICY IF EXISTS "host_update_settings" ON public.jukebox_settings;

DROP POLICY IF EXISTS "authenticated_insert_votes" ON public.jukebox_votes;
DROP POLICY IF EXISTS "authenticated_select_votes" ON public.jukebox_votes;
DROP POLICY IF EXISTS "host_delete_votes" ON public.jukebox_votes;

DROP POLICY IF EXISTS "authenticated_delete_play_history" ON public.play_history;
DROP POLICY IF EXISTS "authenticated_insert_play_history" ON public.play_history;
DROP POLICY IF EXISTS "authenticated_select_play_history" ON public.play_history;

DROP POLICY IF EXISTS "authenticated_delete_song_stats" ON public.song_stats;
DROP POLICY IF EXISTS "authenticated_insert_song_stats" ON public.song_stats;
DROP POLICY IF EXISTS "authenticated_select_song_stats" ON public.song_stats;
DROP POLICY IF EXISTS "authenticated_update_song_stats" ON public.song_stats;

-- 2. Revoke all client table privileges.
REVOKE ALL ON public.jukebox_now_playing FROM anon, authenticated;
REVOKE ALL ON public.jukebox_queue FROM anon, authenticated;
REVOKE ALL ON public.jukebox_settings FROM anon, authenticated;
REVOKE ALL ON public.jukebox_votes FROM anon, authenticated;
REVOKE ALL ON public.play_history FROM anon, authenticated;
REVOKE ALL ON public.song_stats FROM anon, authenticated;

-- 3. Keep RLS on so the tables stay deny-by-default.
ALTER TABLE public.jukebox_now_playing ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.jukebox_queue ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.jukebox_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.jukebox_votes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.play_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.song_stats ENABLE ROW LEVEL SECURITY;
