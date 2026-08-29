# Plex-first UI reconciliation plan

## Goal

Make the web and Android clients accurately reflect Resonance as a Plex-first,
single-source shared jukebox, so a new owner can choose Plex music once and
start playback without seeing obsolete Subsonic, Google, environment-token, or
server-disconnect controls.

## Boundaries

- Keep the current shared queue, active playback-device model, voting, local
  history, and Plex adapter contracts.
- Do not expose Plex tokens, connection URLs, or library keys to a client.
- Preserve legacy server routes only as upgrade fallbacks; do not present their
  setup flow in a new-install UI.
- Do not implement server switching in this UI slice. That requires explicit
  source-state reset/namespace semantics and remains a separate follow-up.

## Phase 1 — Web truthfulness and first-play guidance

1. Remove the host header's legacy “Disconnect” action and all references to a
   manually configured music server.
2. Show the selected Plex server and Music library as read-only source context,
   including clear copy that Plex sharing governs guest access and the owner
   token is retained in local Resonance data.
3. Replace empty-player/jukebox language with an immediate path: search for a
   track, add it, and start it on the active playback device.
4. Make the search and queue actions discoverable on compact/mobile layouts;
   controls must not rely on hover alone.

**Acceptance:** a host can identify the selected Plex library, claim playback,
search a track, queue it, and start it without encountering Subsonic, Google,
or deployment-credential language. Guests see how to request music and why
audio plays only on the active device.

## Phase 2 — Android parity

1. Replace the Subsonic connection screen with Plex first-run status and
   owner-only source selection that uses the existing setup endpoints.
2. Add the selected Plex server/library context to Android's player surface.
3. Ensure empty queue/player states explain how a host starts playback and how
   guests request tracks.
4. Keep deep-link Plex sign-in and Media3 playback behavior intact.

**Acceptance:** a clean Android install can complete Plex sign-in, an owner can
select an owned server and Music library, and an active device can play a
Plex-search result without a Subsonic form.

## Phase 3 — Visual and deployment verification

1. Review web at compact and desktop widths, plus all first-run/error states.
2. Build Android with `android/build-debug.sh` and inspect launcher/login/
   playback views on a device or emulator.
3. Re-run the real Plex search and byte-range stream check in a disposable
   container before publishing the Docker image.

**Acceptance:** visual labels match the deployed Plex-first model, builds pass,
and a published container can search and stream a real Plex track.
