# Connected music-source extensions: v1 plan

## Purpose

Harmonicast can offer an external music-source extension only after a completed
Plex search returns zero tracks. Extensions are independently deployed services
that resolve a requested recording into a track in Harmonicast's configured
primary Plex library.

The first private implementation will talk to an operator's existing
MusicGrabber service. MusicGrabber-specific code, credentials, sources, and
deployment instructions are deliberately out of scope for this public
repository.

## Boundaries

- Harmonicast distributes a generic, documented extension protocol and no
  extension implementation.
- An extension is a separate network service. It is not dynamically linked,
  loaded into Harmonicast's server process, or bundled in the Harmonicast image.
- Harmonicast never sends its Plex owner token, SQLite access, or a browser
  session token to an extension.
- Extension secrets are deployment environment values. Harmonicast Settings
  displays only non-sensitive availability and health information.
- v1 extensions fulfill requests only by returning a verified track ID from
  the configured primary Plex library. Direct playback from another media
  server is not part of v1.

## User experience

1. A completed kiosk Plex search has no tracks.
2. If a configured extension reports healthy, the kiosk offers **Search
   connected music sources**. Otherwise it shows the normal no-results state.
3. Harmonicast creates a short-lived, single-use launch token bound to the
   requester and normalized query, then opens the extension's kiosk flow.
4. The extension resolves ambiguous recordings and performs its own fulfillment
   work. Harmonicast does not expose its provider-specific UI or credentials.
5. The extension reports lifecycle states and, only when ready, a verified Plex
   track ID.
6. A fulfilled track enters the priority acquisition lane immediately after the
   current track. Multiple ready acquired tracks use round-robin fairness by
   requesting user. A request reserves no position while it is unfinished.

## Protocol milestones

### Public Harmonicast

- Define a versioned extension manifest and strict allow-list configuration.
- Add authenticated endpoints to list configured extension availability and
  create launch sessions.
- Persist extension request state, requester identity, timestamps, lifecycle
  state, and final Plex track ID. Expire audit data on a bounded retention
  schedule.
- Add an idempotent, scoped callback endpoint for extension status updates and
  fulfillment. Validate that a returned track belongs to the configured Plex
  library before it can enter the queue.
- Add the priority acquisition lane to queue ordering without disturbing normal
  manual/auto queue behavior.
- Add the kiosk no-result affordance, launch state, and progress/error states.
- Document the protocol, security requirements, and a mock extension for
  third-party developers.

### Private adapter

- Live in its own private repository and image.
- Resolve a guest query through MusicBrainz; the guest makes the sole choice
  when multiple recordings are plausible.
- Submit the selected canonical artist/title as a single-track automatic
  acquisition request to the operator's MusicGrabber service.
- Poll its job/import lifecycle, wait for Plex indexing, match the imported
  track, and send only the Plex track ID and allowed status fields back to
  Harmonicast.

## Acceptance criteria

- With no configured or unhealthy extension, a zero-result kiosk search makes
  no external request and shows no extension action.
- A healthy extension receives only a single-use launch token and the minimum
  requester/query context; browser and Plex credentials remain private.
- A restart of either service does not lose a submitted request or queue an
  unverified track.
- Duplicate and slow external requests are safe: each request waits for its own
  fulfillment and joins the priority lane only after it is ready.
- Callback retries are idempotent; an extension cannot fulfill another
  request, a different user's request, or an arbitrary Plex track.
- Public docs contain no MusicGrabber-specific implementation, endpoint,
  credential, or source guidance.
- A mock extension proves the protocol and remains the public example for
  extension authors.

