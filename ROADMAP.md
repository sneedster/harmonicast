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
- Planned for the first standalone acceptance slice: browse and load playlists
  configured in the selected Plex Music library, including ordered playback,
  shuffle, Play next, and Add to queue across Android, Android Auto, and the
  retained tablet/TV kiosk experience.
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

New work is driven by real-use feedback. The standalone redesign is the active
product direction; existing deployment remains supported until the on-device
personal-player acceptance slice reaches parity.
