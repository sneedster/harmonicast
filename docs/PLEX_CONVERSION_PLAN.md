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
- A clean install has no required Plex environment variables. The first Plex
  server owner signs in, selects an owned server and Music library, and
  Resonance persists that source locally.
- The selected owner Plex token is stored in local app data as plaintext by
  explicit self-hosted product choice. It is never sent to clients, logged, or
  included in backups/documentation by default. Container file permissions
  limit it to the app user and host administrator.
- Ratings will remain in Resonance until native Plex rating capabilities are
  verified against a real target server. No unverified Plex rating write will
  be enabled.

## Architecture

1. **Plex identity (Plex OAuth/PIN flow).** The server creates a short-lived
   Plex PIN, redirects the browser to Plex's authorization UI, and
   polls/consumes the PIN on the server. It then verifies the returned identity
   and server access before issuing the existing Resonance session token.
2. **First-run owner setup.** A clean install sends the first browser through
   Plex sign-in, lists only Plex resources marked as owned, and requires an
   owned server plus one Music library selection. The selected machine
   identifier, reachable connection, library key, and owner token are stored
   in local app data. No `.env` source configuration is required.
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

### 1. Plex sign-in and owner pairing — in progress

- Add a clean-install setup wizard: Plex PIN login, owned-server selection,
  Music-library selection, then first Resonance session.
- Persist source configuration and owner token in local app data; restrict file
  access and never expose/log the token.
- Enforce selected-library access for each guest Plex user and derive admin
  status from Plex resource ownership, not `ADMIN_EMAIL` or an invite list.
- Allow the owner to change server/library later; require confirmation and
  reset or namespace queue/now-playing state because Plex IDs are server-bound.

**Acceptance:** a fresh deployment reaches setup without Plex `.env` values;
only an owned Plex server can be selected; a shared-library user can sign in;
an unshared user cannot; only the Plex server owner can administer settings.

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
- Remove environment-based Plex source credentials, `ADMIN_EMAIL`, Google OAuth,
  Subsonic configuration, routes, dependencies, and client copy after upgrade
  migration and a rollback window.

**Acceptance:** a new deployment requires neither Plex source nor Google/
Subsonic settings; an upgraded deployment has a documented, recoverable
cutover path.

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

- Supporting multiple active Plex servers or libraries at once.
- Passing Plex credentials/tokens through the web or Android client.
- Assuming undocumented Plex endpoints or rating behavior.
- Deleting existing source data before a tested migration/recovery path exists.
