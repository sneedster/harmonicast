# Resonance container release plan

## Scope

Publish the current Resonance server and web client as a Docker image in
`mjstrong/resonance`, provide pull-only Docker Compose deployment files, and
add an Unraid Community Applications template.

## Boundaries

- The image contains the compiled web client and Node/SQLite server only.
- Runtime credentials remain environment variables; no secrets are committed.
- Compose references published image tags and never includes `build:`.
- The Unraid template mirrors the same image, port, persistent data volume, and
  configuration variables.

## Acceptance criteria

1. The image builds and starts with a writable `/app/data` volume.
2. `mjstrong/resonance:<version>` and `mjstrong/resonance:latest` resolve to
   the published image digest.
3. `docker compose config --quiet` succeeds using `.env.example` values.
4. The README, Compose file, environment example, and Unraid template agree on
   port `3001`, `/app/data`, and required OAuth configuration.
