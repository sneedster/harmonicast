# Harmonicast roadmap

Harmonicast is a Plex music player for Android phones, tablets, TV and Android Auto.
The public release line starts at v1.1.0; v1.1.1 adds singles and EPs to artist browsing.

## Product scope

Preserve weighted automatic mixes, adaptive ratings, Track Radio, request-first
queues, guest fairness and voting, nearby rooms, browser guest controls and displays,
native playback transfer and take-back, shared read-only Plex libraries, and Android Auto.
Plex is the supported playback source. Optional MusicGrabber acquisition is implemented in v1.1.9
for owner libraries: username/password sign-in, advanced API-key mode, temporary
computer-assisted setup, and explicit per-room guest permission. Acquired tracks
are queued only after verification in the selected Plex library. Alternative
playback sources and a general plugin installer remain outside scope.

## Rating consent and future controls

- Automatic Plex rating changes require explicit opt-in in Settings and default to disabled, including upgrades (owner decision, 2026-09-08).
- Implemented in v1.1.8: stepped completion, skip, repeat-play and selection controls with current-behavior defaults, per-device persistence, and explicit rating consent. Pixel checks passed and publication was authorized.
- Automatic mix replay protection defaults to one week, with configurable windows and durable local skip/play timestamps alongside Plex last-played dates.
- Settings now includes a Music acquisition category alongside the existing adaptive categories; Rooms is a separate destination. See [implementation plan](docs/SETTINGS_REDESIGN_PLAN.md).
- The opt-in decision and v1.1.6 release were synchronized to Project Memory on 2026-09-08.

## Shared-user access — implementation started

- Allow shared Plex users with a usable music library to host nearby rooms and use
  native playback transfer, independently of Plex write permissions.
- Let owners distribute a dedicated non-admin MusicGrabber username/password and
  endpoint through a privately shared Plex configuration item. Approved users can
  acquire music without manual MusicGrabber setup; room acquisition remains an
  explicit per-room choice within the owner's delegated configuration.
- Use existing Plex and MusicGrabber installations; no separate server app or
  MusicGrabber modification. Dedicated-library API discovery, approved reads,
  and same-token revocation have passed the live proof; further acceptance remains.
  See [implementation plan](docs/SHARED_PLEX_ACCESS_PLAN.md).
- Owner-only **Set up shared access** preparation is implemented locally: reuse or
  create the dedicated library, scan its media, publish and lock an inactive
  placeholder, and verify the result. Ask
  for initial server-side file placement only when needed; resume existing setup
  without duplicates. No copied tokens, JSON, or manual metadata editing in the
  normal setup flow. Live creation/write acceptance and real credential publication
  remain outstanding; the screen does not enable shared acquisition yet.
- Local implementation covers room/playback capabilities, shared-host reactions,
  live room-access guards, and a prepared Plex sharing proof kit. Delegated
  acquisition and deployed acceptance remain outstanding; no target release or
  delivery date assigned.

## Ongoing quality

- Keep phone and TV browsing responsive, with bounded queries, pagination and retained scroll position.
- Maintain the Nocturne design and persistent Nocturne, Aurora and Ember palettes.
- Check playback, queue ordering, room teardown, transfer and Android Auto when their code changes.
- Track platform-specific background playback limits without overstating device verification.

See [architecture](docs/STANDALONE_ARCHITECTURE.md), [design](docs/NOCTURNE_DESIGN.md)
and [validation](docs/ANDROID_VALIDATION.md).
