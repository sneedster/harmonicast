# Roadmap

## Completed foundation

- Plex PIN/forwarding authentication and first-run owner server/library setup.
- Shared Plex-backed search, streaming, cover art, ratings, and scrobbling.
- Request-first automatic queueing with weighted random refill.
- Plex Sonic Analysis **Track Radio** queueing.
- Web, Android, and Android Auto playback, queue, voting, and Track Radio.
- Docker Hub image, Compose deployment, and Unraid template.
- Isolated Node 20 regression tests for first-run Plex source selection,
  Track Radio, queue fairness, active-player ownership, and the Plex metadata
  Android Auto consumes.
- Local signed Android release workflow, with versioned signed APK releases.
- Touch-friendly kiosk mode with curated discovery, live search, host-aware
  entry, and idle/attract behavior.
- Shared Plex-library sign-in via Plex's server-scoped guest access token; the
  token is verified transiently and is never persisted.

## Release hardening

- Completed: required `./scripts/release-check.sh` gate for server tests, web
  type/lint/build checks, Docker image build, and Compose validation. Signed
  Android releases invoke it automatically.
- Completed: documented clean-install and upgrade verification pass for Docker,
  web, Android, and Android Auto.

## Status

- Active redesign: make Harmonicast a personal, standalone Android music player
  first, with temporary proximity-based guest control as an optional mode. The
  architecture and parity constraints are tracked in
  [`docs/STANDALONE_ARCHITECTURE.md`](docs/STANDALONE_ARCHITECTURE.md).
- Device accepted on Pixel and DHU: Android personal mode now performs
  Plex PIN sign-in and owner server/library selection on-device, streams from
  Plex directly, and persists its queue, playback position, and bounded history.
  Search includes track, artist, and album matches; Track Radio and the existing
  rated/unrated automatic mix run locally. Thumbs and playback outcomes continue
  to update Plex ratings. The existing remote-server profile remains available
  during migration.
- Device accepted on Pixel and DHU: browse and load playlists
  configured in the selected Plex Music library, including ordered playback,
  shuffle, Play next, and Add to queue across Android, Android Auto, and the
  retained tablet/TV kiosk experience.
- Implemented and device accepted: browse Plex audio playlists
  and load them ordered, shuffled, next, or at the queue end. Android Auto can
  browse playlist tracks and start an ordered or shuffled playlist while keeping
  the protected current-track-only player timeline.
- Completed: on-demand Plex artist discovery (biography, genres, and similar
  artists) in Android and the web player. Metadata is fetched only when opened.
- Completed: on-demand Plex album context (year and summary) in the shared
  discovery views. Metadata is fetched only when opened.
- Completed: Android Auto queue controls supported by the platform: Track
  Radio, active Radio-queue feedback, and Clear upcoming queue.
- Deferred: playback-device visibility in Plex. Harmonicast remains a shared
  proxy player; do not simulate per-guest Plex clients until Plex offers a
  reliable supported path for proxy/headless player sessions.
- Completed: weighted auto-queue selection using Plex ratings, play history,
  skips, and recency cooldowns.
- Guest gateway slice 1 completed and device validated: personal-mode Settings
  can explicitly enable an ephemeral, accountless LAN room. A random capability
  gates an allowlisted API for status, search, requests, queue viewing, and
  voting; responses omit Plex tokens and stream URLs. Disabling sharing closes
  the listener and revokes the capability immediately. Realtime push updates and
  QR/proximity onboarding remain in later slices.
- Guest transport decision: production rooms are proximity-only through direct
  Bluetooth Low Energy, without requiring a shared TCP/IP segment or changing
  either phone's internet route. Remote relays and internet control are excluded.
  The current LAN listener remains a same-Wi-Fi browser fallback. The intended
  room is a shared car or living room.
- Guest browser slice 1 completed: the host APK bundles and serves a responsive
  no-install controller for now playing, queue, search, individual requests and
  voting when guest and host already share Wi-Fi. Its capability stays in the
  URL fragment, is removed from the visible address after loading, and is sent
  only in same-origin authorization headers. Responses disable caching,
  referrers and external content.
- Rejected after device testing: forcing every guest onto a local-only hotspot.
  It removed internet access from Wi-Fi-only guests. Cross-network guest control
  will use the Harmonicast app over Bluetooth while each phone retains its
  existing Wi-Fi or cellular route; the browser remains the same-Wi-Fi fallback.
- Bluetooth guest slice 1 completed and device validated: a clean Samsung found
  the Pixel's advertised room, connected directly over BLE, and read the room
  code and now-playing status without Plex credentials. Both phones remained on
  their existing `scshub` Wi-Fi connection. The guest actively refreshes room
  status and now clears its connected state when the host ends sharing, validated
  on the same Pixel/Samsung pair. That completed the discovery and room-lifecycle
  slice.
- Bluetooth guest control is implemented and device accepted on the Pixel and
  clean Samsung through the same allowlisted room router as the browser
  controller. The native guest room shows now playing and paged queue/search
  results, submits individual requests and votes, preserves request-first queue
  order, and keeps both phones on their existing internet connection. Host room
  shutdown and explicit guest Leave both return the Samsung to its signed-out
  home screen.
- Bluetooth guest UI is visually aligned with the host player and device
  accepted on the Samsung: a prominent now-playing card shows album, playback
  progress and host state; voting has persistent selected feedback; search and
  queue use compact music cards; and Leave is immediately available at the top.
  The expanded BLE metadata remains bounded to GATT-sized pages and excludes
  Plex credentials, stream URLs and artwork URLs.
- Bluetooth guest fairness and basic abuse limits are implemented: the host
  assigns disposable room-scoped participant labels, interleaves each person's
  manual requests ahead of the automatic tail, limits each participant to five
  waiting requests, and accepts one vote per participant and track. The Samsung
  guest also receives a small now-playing cover image in bounded BLE chunks;
  the Pixel resolves and resizes the art without sharing its Plex URL or token.
- Mixed guest transports are device validated concurrently: an Android 7 Fire
  HD 10 used the same-Wi-Fi browser controller while the Samsung remained joined
  over BLE, and a browser request succeeded without dropping either guest. The
  bundled page avoids unsupported newer JavaScript syntax, and an idle or
  half-open browser socket can no longer crash the host process.
- Nearby discovery now waits briefly for all visible Harmonicast advertisements.
  A single room still joins automatically; if multiple hosts are nearby, the
  signed-out guest sees their four-letter room codes and explicitly chooses one.
  Arrival ordering, duplicate advertisements, selection, and malformed codes are
  covered by unit tests. The chooser and selected-room connection are device
  accepted with simultaneous Pixel and Samsung hosts and an Android 8 Galaxy S7
  guest. The S7 was a one-time borrowed test device and is not part of the
  ongoing device pool. Joining also dismisses any keyboard left open by the
  migration URL field.
- Guest room lifetime is now enforced across both transports. Browser requests
  and connected BLE status refreshes extend the thirty-minute idle window, while
  the four-hour hard limit cannot be extended. Expiry revokes the capability,
  stops LAN and BLE listeners, disconnects nearby guests, and explains the stop
  in host Settings.
- Standalone playback now seeds the weighted automatic mix when personal mode
  starts without a current track and refills it whenever the upcoming queue is
  exhausted. A deliberately paused current track remains paused.
- Host Next always skips the current track, including a guest request. Rating a
  requested/manual track down changes its Plex rating without also skipping it;
  automatic tracks retain the down-rating-and-skip behavior.
- Personal-mode Settings now shows the selected Plex source, can reopen the
  server/library picker without another sign-in, and can explicitly sign out.
  Signing out clears Plex credentials and persisted playback records that may
  contain authenticated stream URLs.
- Personal setup accepts both owned and shared Plex servers. Shared resources
  use their server-scoped access token and run as a read-only local player with
  search, playlists, queues, automatic playback, artwork, and Android Auto.
  Rating controls, adaptive rating writes, scrobbling, and guest-room hosting
  are disabled; local queue, resume, and history state still persist normally.
  Playlist results belong to the signed-in Plex user; playlists created by the
  server owner are not inherited by a shared account. The sole local player can
  skip every current track, including one it manually queued.
- Room display slice 1 adds a separate same-Wi-Fi display link and scoped kiosk
  capability. A full-screen tablet/browser view shows live now-playing progress
  and the upcoming queue, searches and queues music, and controls play/pause and
  Next while audio remains on the host. It cannot vote or access Plex data,
  credentials, source selection, or app settings. A following slice will let
  the host phone explicitly move audio to a trusted native app on Google TV or
  another phone and take it back; browser clients are never audio destinations.
- Native room playback is implemented and device accepted on an owner-signed-in
  Pixel host and Samsung receiver. An owner app joins the existing Bluetooth
  room, explicitly offers itself as a player, and appears by device name in the
  host's room controls. The host retains queue selection, pause, skip, automatic
  advancement, and take-back. Leaving or ending the room revokes the receiver
  and stops its audio. Both devices must be signed in as Plex owners, while the
  audio stream stays on the local Wi-Fi/Ethernet network. Google TV hardware and
  long-duration Doze validation remain in the next slice.
- Clean-device validation on a Samsung browser confirmed accountless room load,
  now-playing and queue updates, search, request submission, and immediate
  disconnect when the Pixel host ended sharing. The run exposed and fixed local
  request ordering: manual owner/guest requests now remain in arrival order ahead
  of automatic upcoming tracks.

New work is driven by real-use feedback. The standalone redesign is the active
product direction; existing deployment remains supported until the on-device
personal-player acceptance slice reaches parity.

## v1.1.0 standalone cutover

Version 1.1.0 is the planned standalone milestone. Once the on-device personal,
guest, Android Auto, and room-display acceptance checks are complete:

- withdraw the existing F-Droid submission and remove repository automation and
  metadata that continue publishing Harmonicast through F-Droid;
- merge the validated `dev` branch into `main` and publish v1.1.0 from `main`;
- retire the Node/Docker server component and its deployment path after resolving
  every feature disposition listed in the standalone architecture document;
- update installation, release, and migration documentation around the Android
  standalone application as the supported product.

Do not perform these retirement steps during the 1.0.x migration series. The
server remains supported until the v1.1.0 acceptance gate is satisfied.
