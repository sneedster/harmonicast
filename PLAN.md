# Resonance release contract

## Deployment

- Docker images are published to `mjstrong/resonance` with a numbered release
  tag, `latest`, and an immutable `main-<commit>` tag.
- `docker-compose.yaml` is pull-only: it never contains `build:`.
- `/app/data` is the sole persistent application-data path. It contains the
  SQLite database, selected Plex source, and owner Plex token.
- First-run Plex setup happens in the browser. No Plex server URL, token, or
  library setting is required in `.env`.

## Release gate

Run `./scripts/release-check.sh` before publishing a Docker/web release. It
executes server tests, web type/lint/build checks, a production Docker build,
and Compose validation. Signed Android releases invoke the same gate unless
explicitly skipped for local troubleshooting.

## Publishing checks

1. The versioned Docker tag, `latest`, and immutable commit tag resolve to the
   same published digest.
2. A signed APK increments both version name and version code.
3. README, `.env.example`, Compose, and the Unraid template agree on port
   `3001`, persistent path `/app/data`, and `PUBLIC_URL`.
