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

## Release hardening

- Make lint and the automated test suite required release checks.
- Generate and securely back up the local Android signing identity, then
  produce a versioned/signed release APK rather than distributing only debug
  builds.
- Run a documented clean-install and upgrade verification pass against Docker,
  web, Android, and Android Auto.

## Future enhancements

- Optional kiosk mode for a tablet or web display: a touch-friendly,
  TouchTunes-inspired browsing interface with prominent artwork, discovery,
  search, and queue actions while preserving the shared Resonance queue and
  active-player model.
- Optional on-demand Plex metadata views such as lyrics, artist biography, and
  album details. Fetch them only when requested; do not bulk-index them.
- Better queue controls, including explicit removal/reordering where Android
  Auto exposes supported controls.
- Playback-device visibility in Plex, if it can be implemented without
  weakening the current proxy/player model.
- Refine weighted selection from real shared Plex play history and ratings.
