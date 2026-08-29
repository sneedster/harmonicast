# Roadmap

## Plex migration

- Replace Google OAuth with Plex OAuth. Plex server access grants Resonance access; the owner remains the only admin.
- Replace the Navidrome/Subsonic integration with Plex library search and streaming. Expect a substantial API rewrite.
- Verify Plex API support for reading and writing track ratings; use native Plex ratings if available.

## Always-on queueing

- Remove the Jukebox mode toggle; automatic playback is always enabled.
- Maintain two internal queues:
  - **Request queue:** listener-added tracks; always played first.
  - **Auto queue:** automatically selected tracks; refills when both queues are empty.

## Similar-track playback

- Add a Now Playing action that seeds the auto queue from Plex's similar tracks.
- Apply the existing picker rules to that source.
- Add up to 20 similar tracks, then return to normal random selection.
