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
- Local signed Android release workflow, with a verified 1.0.1 release APK.
- Touch-friendly kiosk mode with curated discovery, live search, host-aware
  entry, and idle/attract behavior.

## Release hardening

- Completed: required `./scripts/release-check.sh` gate for server tests, web
  type/lint/build checks, Docker image build, and Compose validation. Signed
  Android releases invoke it automatically.
- Completed: documented clean-install and upgrade verification pass for Docker,
  web, Android, and Android Auto.

## Future enhancements

- Optional on-demand Plex metadata views such as lyrics, artist biography, and
  album details. Fetch them only when requested; do not bulk-index them.
- Better queue controls where Android Auto exposes supported controls.
- Playback-device visibility in Plex, if it can be implemented without
  weakening the current proxy/player model.
- Refine weighted selection from real shared Plex play history and ratings.
