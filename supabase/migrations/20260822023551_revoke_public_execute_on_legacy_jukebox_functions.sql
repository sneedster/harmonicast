-- Corrective migration.
-- The earlier revoke removed EXECUTE from anon and authenticated, but Postgres
-- grants EXECUTE on every new function to PUBLIC by default, and anon and
-- authenticated inherit PUBLIC. The functions were therefore still callable by
-- any holder of the anon key. These SECURITY DEFINER functions run as their
-- owner and so bypass row level security on the legacy jukebox tables.
-- Revoke the PUBLIC grant so only postgres and service_role can execute them.

REVOKE EXECUTE ON FUNCTION public.add_to_queue(text, text, text, text, integer, text, boolean) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.clear_old_votes(text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.get_cooldown_minutes() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.get_max_requests_per_user() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.get_vote_counts(text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.is_jukebox_configured() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.record_play_event(text, text, text, text, integer, text, text, numeric) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.reorder_queue_round_robin() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.set_cooldown_minutes(integer) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.set_max_requests_per_user(integer) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.vote_now_playing(text) FROM PUBLIC;
