/*
# Remove direct connection mode

The app now always proxies Subsonic API calls through the edge function.
The `direct` column on `jukebox_settings` is no longer needed.

1. Changes
  - Drop the `direct` column from `jukebox_settings`.

2. Security
  - No policy changes. Existing RLS policies remain in effect.
*/

ALTER TABLE jukebox_settings DROP COLUMN IF EXISTS direct;
