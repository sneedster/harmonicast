# Android connected-music-source search plan

## Goal

Bring the useful parts of the kiosk's connected-music-source experience to
the native Android client: when the configured Plex library cannot satisfy a
search, a guest can choose an authoritative MusicBrainz result and ask an
installed, healthy music-source plugin to acquire it. The Android app remains
a client of Harmonicast's existing authenticated API; it does not contact
MusicBrainz, MusicGrabber, Plex, or a plugin service directly.

This work is isolated on `codex/android-acquisition-search`. `main` remains
unchanged while the current Android source submission is awaiting F-Droid
review.

## Progress

- **Complete:** Native source discovery, fallback and local-artist entry
  points, compact recording results, artist → album → track browsing, and
  direct single-track acquisition are implemented on this branch.
- **Complete:** Acquisition now polls the owned server request briefly and
  reports fulfilled, failed, or still-processing truthfully; it never calls a
  track queued until Harmonicast has verified it in Plex.
- **Complete:** Debug and locally signed release builds compile cleanly; the
  signed test build was installed and smoke-tested on a Pixel 10 Pro.
- **Before a future release:** repeat the physical test matrix below against
  the release candidate, then decide when it is appropriate to merge after
  the pending F-Droid review.

## Boundaries

- Reuse the existing server routes and plugin request ownership checks. This
  feature must not add provider-specific code, URLs, credentials, or API keys
  to the Android app.
- Keep local Plex search as the primary result set. A connected source is a
  fallback for an empty track search, plus an optional artist-browse path when
  Harmonicast recognizes a local artist.
- Support single-track acquisition only. Album acquisition, provider candidate
  picking, and a second confirmation are intentionally out of scope.
- Use native Compose screens/dialogs. Do not open a plugin kiosk page in a
  browser or WebView.
- Treat the kiosk as a workflow reference, not a layout reference. Android's
  phone UI must optimize for one-handed use and a small viewport: progressive
  drill-down instead of wide grids, compact rows with the decision-critical
  metadata, and one focused task at a time.
- Preserve the existing queue behavior: the server fulfills a verified Plex
  track and applies the acquisition lane's next-song and per-requester
  round-robin rules. Android never inserts a speculative queue entry.
- No new Android permissions, analytics, background work, or non-F-Droid
  dependencies.

## Existing API contract to consume

All routes require the app's normal bearer token:

1. `GET /api/extensions/music-sources` reports the first healthy music-source
   extension (`id`, display name, availability) or `null`.
2. `POST /api/extensions/music-sources/:id/launch` creates an owned durable
   request with `{ query, mode: "search" | "artist" }` and returns its id.
3. The plugin's authenticated routes return MusicBrainz recordings, artist
   albums, and release-group tracks, and accept the selected recording for
   acquisition.
4. `GET /api/extensions/music-sources/requests/:id` exposes the caller's safe
   lifecycle status. WebSocket/normal refreshes continue to update the shared
   queue once fulfillment completes.

The Android client needs small typed models and API wrappers for these
responses, matching the already-shipped web client. Request ids stay in
view-model memory only; no provider data or request state is persisted on the
device.

## Delivery slices

### 1. Native fallback entry point and API models

- Add typed music-source status, recording, and album models plus authenticated
  `Api` wrappers.
- Have a completed local search determine whether a healthy source is
  available. Show **Search connected music sources** only when local track
  results are empty.
- When a local artist browse match exists, show **Find songs by this artist**
  as a separate, clearly labelled action. This allows an artist already in the
  library to be explored externally without replacing normal local results.
- Make every request cancellable by changing search terms or leaving the tab;
  stale results must not overwrite a newer search.

Acceptance: without a plugin (or with an unhealthy one), Android behaves
exactly as it does today and shows no connected-source affordance.

### 2. MusicBrainz chooser and artist browse

- Launch the selected extension from the view model and display results in a
  native, full-height sheet or dedicated Compose route over the Search tab.
  It must preserve the underlying query so Close/Back returns to exactly where
  the guest started.
- For song search, render concise recording rows: title, artist, album/year,
  duration, and disambiguation where provided. Do not use kiosk-style large
  artwork grids; artwork is optional and must never push the title/artist off
  screen. Explicitly distinguish a true no-result response from a lookup
  failure.
- For artist mode, start in a paginated album browse view, then drill into an
  album's tracks. Each level has a clear Back affordance and a single scrolling
  list; offer **Load more albums** rather than rendering an unbounded artist
  catalogue.
- Provide Back, Close, retry, loading, empty, and error states. Closing always
  returns to the same Android Search tab with its original query and results.
  Keep the acquire action reachable without requiring precision taps, and do
  not block dismissal or local search while a network request is slow.

Acceptance: selecting an artist does not dump a huge flat track list; it
follows artist → album → track, and a failed first lookup can be retried
without restarting the app.

### 3. Request submission and completion feedback

- Selecting a recording immediately submits the existing acquire call: the
  chosen MusicBrainz identity is the single user choice and there is no
  provider-result picker or second confirmation.
- Replace the chooser with a compact request-status state, poll its owned
  request briefly while acquisition is in progress, and then dismiss back to
  Search with a clear success/failure notice. Avoid a permanently spinning
  button or modal.
- Refresh queue/search state after fulfillment. The request is reported as
  queued only after the server has verified the new Plex track and fulfilled
  it.

Acceptance: a successful request gives timely, truthful feedback; unsuccessful
or delayed acquisition remains actionable and does not block local search or
playback controls.

### 4. Verification and release readiness

- Add focused JVM/unit coverage where the current Android test setup permits,
  particularly JSON parsing and stale-response/result-state handling.
- Run `./android/build-debug.sh`, Android lint/tests available through the
  project wrapper, and the server test suite to confirm the existing API
  contract still passes.
- Perform a physical-device smoke test against: no extension, unavailable
  extension, no local result with a song request, local artist browse followed
  by external album/track browse, request failure/retry, and successful
  fulfillment into the shared queue.
- Keep any resulting Android release/version bump and F-Droid metadata changes
  off `main` until the current F-Droid review is resolved and the user approves
  a new submission.

## Decisions to retain

- The server owns provider credentials and acquisition. The APK sees only
  Harmonicast's existing bearer-authenticated API.
- Plugin availability is a capability check, not a guaranteed result: normal
  local search must always remain usable.
- The app exposes only the provider-neutral label supplied by the plugin, so
  future Navidrome, Jellyfin, or other source plugins can use the same Android
  UI.
- Phone interaction wins over visual parity with the kiosk: fewer simultaneous
  choices, no essential hover/gesture-only controls, accessible tap targets,
  and ordinary Android Back behavior at every drill-down level.
