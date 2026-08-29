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

## Release hardening

- Completed: required `./scripts/release-check.sh` gate for server tests, web
  type/lint/build checks, Docker image build, and Compose validation. Signed
  Android releases invoke it automatically.
- Completed: documented clean-install and upgrade verification pass for Docker,
  web, Android, and Android Auto.

## Status

- Completed: on-demand Plex artist discovery (biography, genres, and similar
  artists) in Android and the web player. Metadata is fetched only when opened.
- Completed: on-demand Plex album context (year and summary) in the shared
  discovery views. Metadata is fetched only when opened.
- Completed: Android Auto queue controls supported by the platform: Track
  Radio, active Radio-queue feedback, and Clear upcoming queue.
- Deferred: playback-device visibility in Plex. Resonance remains a shared
  proxy player; do not simulate per-guest Plex clients until Plex offers a
  reliable supported path for proxy/headless player sessions.
- Completed: weighted auto-queue selection using Plex ratings, play history,
  skips, and recency cooldowns.

There are no active planned enhancements. New work is driven by real-use
feedback and should be added here before implementation.
