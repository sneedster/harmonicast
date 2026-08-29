# Plex conversion plan

## Goal

Convert Resonance from Google identity plus a Navidrome/Subsonic music source
to Plex identity and a Plex Music library, while preserving Resonance's shared
queue, listener requests, player arbitration, and playback history.

## Product boundary

- Plex is the identity provider and music source. Resonance does not expose a
  Plex token to browsers or Android clients.
- A person must authenticate with Plex *and* have access to the configured
  owner Plex server and Music library before Resonance creates a session.
- The configured Plex owner remains the only Resonance administrator. Plex
  sharing does not grant administration.
- Existing Google/ Subsonic users and data remain readable during migration;
  legacy login and source configuration are removed only after a verified Plex
  cutover.
- Ratings will remain in Resonance until native Plex rating capabilities are
  verified against a real target server. No unverified Plex rating write will
  be enabled.

## Architecture

1. **Plex identity (Plex OAuth/PIN flow).** The server creates a short-lived
   Plex PIN, redirects the browser to Plex's authorization UI, and
   polls/consumes the PIN on the server. It then verifies the returned identity
   and server access before issuing the existing Resonance session token.
2. **Owner setup.** An owner pairs their Plex account once and selects one
   server and Music library. The selected machine identifier and library key
   are stored as configuration; the Plex access token is treated as a secret.
3. **Plex source adapter.** A narrow adapter owns Plex headers, key resolution,
   pagination, search, metadata mapping, artwork, and audio stream URLs. API
   handlers use the adapter instead of constructing Plex paths directly.
4. **Stable local references.** Plex metadata IDs, server machine identifier,
   and library key form the source identity for queue rows, playback history,
   and statistics. The adapter maps Plex metadata into the current Song API
   shape while the client conversion is in progress.
5. **Queue follow-up.** After the source cutover, replace the jukebox toggle
   with always-on automatic playback, keeping separate request and automatic
   queues. Similar-track seeding is a later Plex-backed queue feature.

## Delivery phases

### 0. Contract and discovery — in progress

- Record the migration boundary and verify Plex's documented client headers,
  token auth, JSON responses, pagination, and Music metadata type.
- Add isolated server utilities for Plex client headers and PIN state without
  changing the active Google login path.
- Define test seams so Plex HTTP can be mocked.

**Acceptance:** existing Google/Subsonic deployments operate unchanged; Plex
utilities never return an access token to a Resonance client.

### 1. Plex sign-in and owner pairing

- Add a Plex PIN login flow and user identity migration (`plex_id`), preserving
  current accounts by email where safe.
- Add an owner-only pairing flow that discovers accessible servers and Music
  libraries, then persists the chosen source.
- Enforce library access for each Plex user before issuing a Resonance session.
- Add explicit token revocation/re-pairing behavior and never log tokens.

**Acceptance:** an invited, shared Plex user can sign in; an unshared Plex user
cannot; only the pre-existing Resonance owner can administer settings.

### 2. Plex music adapter and API cutover

- Implement browse/search, metadata, artwork, audio streaming with Range
  forwarding, and track mapping through the adapter.
- Convert server API handlers and Android/web API clients behind compatibility
  responses where useful.
- Exercise a real Music library with pagination, missing artwork, and an
  unavailable server.

**Acceptance:** search, queue, artwork, and playback work against the chosen
Plex library from web and Android without Subsonic credentials.

### 3. State migration and removal

- Migrate or intentionally retire source-specific cache/configuration fields.
- Preserve local ratings/play history where Plex identifiers can be matched;
  document unmatched items.
- Remove Google OAuth and Subsonic configuration, routes, dependencies, and
  client copy only after a rollback window.

**Acceptance:** a new deployment requires neither Google nor Subsonic settings;
an upgraded deployment has a documented, recoverable cutover path.

### 4. Queue evolution

- Make automatic queueing always enabled and maintain distinct request and auto
  queue state.
- Add a Now Playing action that takes up to 20 Plex similar tracks before
  returning to normal selection.
- Verify whether Plex exposes usable read/write track ratings before optionally
  syncing them.

**Acceptance:** requests always win, automatic selection restarts only when
both queues are empty, and similar-track seeding cannot overflow the auto
queue.

## Explicit non-goals for the initial cutover

- Supporting multiple owner Plex servers or libraries.
- Passing Plex credentials/tokens through the web or Android client.
- Assuming undocumented Plex endpoints or rating behavior.
- Deleting Google/Subsonic data before a tested Plex deployment is available.
