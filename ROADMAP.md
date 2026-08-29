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

- Completed: on-demand Plex artist discovery (biography, genres, and similar
  artists) in Android and the web player. Metadata is fetched only when opened.
- Optional on-demand Plex metadata views such as lyrics and album details.
  Fetch them only when requested; do not bulk-index them.
- Better queue controls where Android Auto exposes supported controls.
- Playback-device visibility in Plex, if it can be implemented without
  weakening the current proxy/player model.
- Completed: weighted auto-queue selection using Plex ratings, play history,
  skips, and recency cooldowns.
