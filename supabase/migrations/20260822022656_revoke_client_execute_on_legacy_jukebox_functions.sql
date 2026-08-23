-- # Revoke client EXECUTE on legacy jukebox functions
--
-- The application no longer uses this Supabase schema; it runs against a
-- self-hosted Node/SQLite backend and ships no Supabase client library. These
-- SECURITY DEFINER functions were nonetheless still executable by the `anon`
-- role, meaning any unauthenticated caller holding the (public) anon key could
-- invoke them as RPC endpoints and write to the jukebox tables, bypassing RLS
-- entirely because a DEFINER function runs as its owner.
--
-- ## Changes
-- 1. Revoke EXECUTE from `anon` and `authenticated` on all ten legacy public
--    functions. This closes the unauthenticated write path (add_to_queue,
--    vote_now_playing, clear_old_votes, reorder_queue_round_robin) and the
--    unauthenticated configuration path (set_cooldown_minutes,
--    set_max_requests_per_user), plus the read helpers.
-- 2. Pin search_path on `record_play_event`, the one function that had a
--    mutable path, so unqualified names cannot be shadowed.
--
-- ## Notes
-- - No application code calls any of these, so no feature depends on the grants.
-- - The functions are left in place (not dropped) so no data or definition is
--   lost; they simply become unreachable from the Data API.

REVOKE EXECUTE ON FUNCTION public.add_to_queue(text, text, text, text, integer, text, boolean) FROM anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.vote_now_playing(text) FROM anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.clear_old_votes(text) FROM anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.reorder_queue_round_robin() FROM anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.set_cooldown_minutes(integer) FROM anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.set_max_requests_per_user(integer) FROM anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.get_cooldown_minutes() FROM anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.get_max_requests_per_user() FROM anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.get_vote_counts(text) FROM anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.is_jukebox_configured() FROM anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.record_play_event(text, text, text, text, integer, text, text, numeric) FROM anon, authenticated;

ALTER FUNCTION public.record_play_event(text, text, text, text, integer, text, text, numeric) SET search_path = public, pg_temp;
