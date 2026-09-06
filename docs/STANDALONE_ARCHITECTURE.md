# Harmonicast standalone architecture

## Product direction

Harmonicast becomes a personal music player first. The normal case is one person
opening the app and listening immediately. Sharing control is an explicit,
temporary mode for the less frequent occasion when other people are present.

This is a replacement architecture, not a small variation of the current
Docker-hosted product. Migration must preserve the useful playback experience
while avoiding a permanent external Harmonicast server requirement.

## Product modes

### Personal

- Default mode.
- The app owns playback, queue, history, ratings, and Plex configuration.
- The app can browse and load playlists configured in the selected Plex Music
  library without importing or duplicating them as Harmonicast playlists.
- No Harmonicast login, server URL, room, or guest setup is required.
- Android Auto controls the same local playback and queue.

### Sharing

- The owner explicitly turns on **Allow guest control**.
- The app creates a temporary nearby room and advertises it over a proximity
  radio only while sharing is enabled. It does not publish an internet-reachable
  room or support remote guests.
- The room ends immediately when the owner disables sharing; it should also
  expire after a conservative idle period.
- Owner-only controls remain local: Plex configuration, app settings, plugin
  credentials, guest removal, queue clearing, and playback ownership.

### Guest

- Any Harmonicast app can temporarily join a nearby host without a Plex or
  Harmonicast guest account. A guest does not need to configure its own Plex
  library first. If it already has a home configuration, that remains intact.
- The guest can search the host's selected library, request tracks, view the
  queue and now-playing state, and vote.
- A guest may browse a host playlist and request individual tracks through the
  normal fairness rules. Loading an entire playlist is owner-only initially so
  one guest cannot replace or overwhelm the shared queue.
- The guest cannot obtain the owner's Plex token, change the source, become the
  playback endpoint, or invoke owner-only controls.

### Room Display

- A tablet or Android TV device can pair with an active room and present the
  existing full-screen kiosk experience.
- Display pairing is accountless, like guest pairing, but uses a distinct
  capability so the host can identify and revoke a shared screen separately.
- The initial display role shows now playing, discovery, playlists, search, and
  queue interactions while the host phone remains the playback endpoint.
- A later **Play on this display** action may promote an explicitly approved
  Shield or tablet to a trusted playback device. Ordinary guests must never be
  able to claim playback.

## Home profile and active room

Every configured installation is a complete, independent Harmonicast host with
its own home profile: Plex source and owner token, local queue, playback state,
history, ratings, preferences, and room policy. Host and guest are temporary
roles, not different account or installation types.

Joining another person creates an active-room overlay:

- the app UI targets the other person's room until the user leaves;
- search and requests use the active host's Plex library, never the guest's
  private library;
- the guest's home profile is neither exposed to the host nor overwritten;
- leaving or losing the room returns the app to its own personal state, or
  the setup/idle screen if the guest has not configured a home library;
- queue, votes, ratings, and history do not merge between the two home profiles;
- either person can host the next room simply by leaving guest mode and enabling
  sharing on their own device.

A device cannot host one room while acting as a guest in another. Joining a room
while sharing is active must first end the device's own room, with an explicit
confirmation if anyone is connected. If local music is already playing, the app
must ask whether to pause it before entering the remote room rather than silently
changing playback.

Android Auto remains attached to the device's home profile. Ordinary guest mode
must not cause a car session to take playback authority from somebody else's
room.

## Authentication and authorization

Conventional guest identity can disappear, but authorization cannot. Proximity
is the bootstrap for a temporary capability rather than a substitute for all
access control.

When sharing begins, the host creates a cryptographically random room secret.
The proximity exchange carries a versioned join payload containing enough
information to find the host plus a short-lived, narrowly scoped capability.
It must never contain the Plex token, plugin credentials, or a reusable owner
session.

The host validates the capability on every guest API and WebSocket connection.
Turning sharing off revokes the room and all derived guest sessions immediately.
Capabilities are scoped to guest operations and expire automatically. Guest
labels can be local, disposable names; stable email/account identity is not
required for fair queueing.

The owner's Plex sign-in remains necessary because Plex still protects the
music library. It becomes local app setup and is not repeated when that owner
temporarily joins another Harmonicast room.

## Runtime architecture

```text
                         owner-only, in process
Android UI / Android Auto ───────► Harmonicast Core ───────► Plex Media Server
                                       │
                                       ├── local database
                                       └── Room Gateway (off by default)
                                               ▲           ▲
                                  guest capability     display capability
                                               │           │
                                         guest app    tablet / Shield kiosk
```

For two fully configured phones, the relationship is symmetric:

```text
Phone A home profile ── opens room ──► Phone B joins temporarily
Phone B home profile ── opens room ──► Phone A joins temporarily
```

Only the active host's core and Plex library participate in a room. Reversing
the arrows starts a new room; it does not move or merge either person's data.

### Harmonicast Core

A Kotlin in-process core becomes the authority for:

- Plex owner authentication and selected source;
- search, metadata, artwork, stream resolution, and Plex playlist discovery;
- queue ordering, Track Radio, weighted automatic playback, and cooldowns;
- playback history, ratings, votes, and now-playing state;
- owner/guest policy and capability lifetime.

The owner UI and Media3 service should call this core through typed Kotlin
interfaces. They should not make loopback HTTP requests to their own device.
That keeps personal playback independent of sockets, local-network permissions,
and guest-sharing state.

### Room Gateway

An optional gateway adapts guest-safe and display-safe subsets of the core to a
local request/response and realtime protocol. It exists only while sharing is
on. Keeping this adapter separate prevents room transport concerns from becoming
the app's internal architecture.

The first gateway may reuse the current REST/JSON and WebSocket shapes where
they are already suitable, but the public surface must be allowlisted rather
than exposing every current server route. Streaming should remain on the host;
guest devices control the queue but do not receive the owner's music stream.

### Kiosk packaging

The current React kiosk should be preserved as a portable room-display frontend
rather than rewritten immediately. The host APK can bundle its production web
assets and serve them through the Room Gateway without Node. That creates three
deployment choices from one interface:

- open the room URL full-screen in a tablet browser;
- install a lightweight tablet/Android TV shell that discovers the host and
  renders the bundled kiosk frontend;
- eventually rebuild selected surfaces natively only where Android TV focus,
  screensaver, or media-session behavior requires it.

The Shield shell should provide remote/D-pad navigation, immersive full-screen
behavior, screen-awake handling, reconnection, and a simple pairing screen. It
should not contain Plex credentials or a second copy of the owner's database.
This keeps the visually successful kiosk while avoiding a permanent Docker
deployment.

### Discovery and proximity

Discovery and authorization are separate:

- Direct Bluetooth Low Energy is the production transport. It does not require
  both phones to share a TCP/IP segment, change either phone's Wi-Fi or cellular
  route, or send room traffic through the internet;
- Bluetooth proximity is a required property of joining. Harmonicast does not
  offer a relay, public endpoint, or remote-control mode for physically distant
  guests;
- the temporary LAN listener is a development bridge only and binds to one
  selected Wi-Fi/loopback interface rather than cellular or all phone
  interfaces;
- the proposed **audio QR code** supplies proof of physical proximity and the
  temporary join capability;
- a visual QR code and short code are useful development/bootstrap transports
  for testing the exact same join payload before acoustic transfer is added.

The join payload is versioned and keeps the nearby endpoint separate from the
room capability so the radio implementation can evolve without changing room
security or guest APIs. A raw IP address alone is not a durable identity and
must not be treated as authorization.

## Migration plan

### 0. Create seams without changing behavior

- Introduce typed core interfaces for library, queue, playback state, and
  guest policy.
- Keep the current remote API behind an implementation of those interfaces.
- Add explicit app mode/profile persistence instead of inferring readiness from
  the presence of a server URL and bearer token.
- Model the selected remote room separately from the permanent home profile so
  entering guest mode never overwrites local Plex or playback configuration.
- Protect the known Android Auto current-track timeline and server-queue
  behavior while the authority moves.

### 1. Personal mode on-device

- Move owner Plex sign-in and source selection into the Android app.
- Implement local Plex search, artwork, stream resolution, and direct playback.
- Browse Plex Music playlists and load them in their configured order, shuffled,
  next, or at the end of the queue. Plex remains the source of truth; inaccessible
  tracks are skipped with a visible count rather than failing the whole playlist.
- Expose playlist browsing and playback through Android Auto without expanding
  the protected current-track-only Media3 timeline.
- Persist the personal queue, now-playing state, and listening history locally.
- Make the app launch directly into the personal player after first-run setup.

Acceptance: after initial Plex setup, one phone can search, play, resume, queue,
load or shuffle a Plex playlist, use Track Radio, and use Android Auto with no
Harmonicast server running. A loaded playlist survives an app restart as part of
the local queue.

### 2. Guest policy and gateway

- Add **Allow guest control**, off by default.
- Generate/revoke ephemeral rooms and capability-scoped guest sessions.
- Expose only search, request, queue, now playing, and voting operations.
- Preserve per-guest request fairness using a disposable room participant ID.

Acceptance: given two independently configured phones, either one can open a
room and the other can join, request, and vote. After leaving, the guest returns
to its unchanged home profile and can reverse the relationship by opening its
own room. Disabling sharing disconnects the guest immediately; no owner
credential or owner-only command is available.

### 3. Proximity onboarding

- Prove the join payload and lifecycle first with visual QR/deep link.
- Advertise the temporary room over BLE and connect through a guest-safe GATT
  service. The first device-validated slice discovers the room and reads its
  code and now-playing status; the command protocol for search, requests, queue,
  and votes follows.
- Handle network changes, host disappearance, expiry, replay, and two nearby
  hosts without requiring account sign-in.

Acceptance: a nearby guest joins intentionally and quickly, while a device that
did not receive the current capability cannot control the host.

### 4. Room display

- Bundle the existing kiosk frontend as versioned Android assets.
- Serve it from the Room Gateway and add display-scoped pairing.
- Preserve Plex playlist discovery and ordered, shuffled, next, and queued
  loading in the touch and D-pad interfaces; whole-playlist loading remains an
  owner-authorized action.
- Validate full-screen tablet behavior first, then add a Shield/Android TV shell
  with D-pad focus and reliable reconnect behavior.
- Make the kiosk the default UI on Android TV and Shield-class devices while
  retaining an explicit room-display choice on tablets.
- Keep playback on the host until trusted display promotion is separately
  implemented and approved.

Acceptance: a tablet or Shield can pair with a room and retain the recognizable
kiosk experience without an external Harmonicast server.

### 5. Migration and retirement

- Offer import of compatible queue/history/preferences from the existing
  Harmonicast installation where practical.
- Keep a remote-server profile during development so current users are not cut
  off before personal mode reaches parity.
- Retire Docker/server deployment only after the on-device path meets the
  personal and guest acceptance checks.

## Features that require an explicit decision

The current Node server is also the home of the React web client, SQLite state,
and loadable plugins. Those do not automatically move into an Android APK.
Before retiring the server, decide explicitly whether to port, replace, or
discontinue each of:

- no-install browser guest control;
- whether the preserved kiosk remains bundled web UI or is eventually native;
- loadable server plugins and connected-source acquisition;
- Subsonic compatibility;
- migration of historical stats and ratings;
- editing Plex playlists or synchronizing playlist changes back to Plex;
- non-Android host platforms.

No item in this list is implicitly removed by adopting the standalone design.

Remote and out-of-home guest control is a resolved product boundary rather than
an undecided feature: it is excluded. Rooms are for guests physically present
in the same car or living room, and Harmonicast will not provide an internet
relay or public guest-control endpoint.

## First implementation milestone

Start with migration step 0, then deliver the personal-mode acceptance slice in
step 1 before building guest discovery. The standalone design is only proven
when the owner can complete a real listening session without the Node server;
an isolated embedded HTTP-server demo is not sufficient.

## Implementation checkpoint: first personal core

The first migration foundation is implemented in Android:

- `HarmonicastCore` provides typed library, queue, playback-state, guest-policy,
  and change-notification contracts. `RemoteHarmonicastCore` adapts the existing
  server endpoints; phone playback operations and the Media3 service use it.
- Existing credentials migrate once into an explicit remote home profile.
  Changing the remote address clears the previous server's bearer token.
- `AppProfile` models a temporary room separately from home storage. Room
  credentials are memory-only and expire or disappear on process restart;
  no guest network connection or pairing UI is implemented yet.
- Media3 retains its current-track-only timeline, Auto playback authority, and
  deliberate-action-only browse refresh behavior.
- Personal setup now uses Plex's PIN flow directly in the APK, validates an
  owned server connection, filters its Music libraries, and stores the selected
  source as the home profile. It does not require a Harmonicast server.
- The local core searches Plex by track, artist, and album, resolves direct
  streams and artwork, persists the personal queue/resume position/history,
  runs Track Radio and the rated/unrated automatic mix, and lists Plex audio
  playlists. Phone controls can play or shuffle a playlist, put it next, or add
  it to the queue. Android Auto can browse playlist tracks and start ordered or
  shuffled playback without expanding the Media3 timeline.

This implementation passes the local Android build and contract tests. The
personal slice was accepted on a Pixel with direct Plex playback and in DHU with
the player, request queue, and Plex playlist surfaces. Thumbs and
completed/skipped playback update Plex ratings
with the same point rules used by the existing server. Connected-source
extensions still require the existing remote profile and are intentionally
absent from personal mode.

## Implementation checkpoint: ephemeral host gateway

The first guest-policy slice is implemented and validated over the LAN:

- **Allow guest control** is explicit, off by default, and available only for a
  complete personal Plex profile.
- Enabling it creates a memory-only four-letter room plus a 256-bit random
  capability and starts a bounded local HTTP gateway. Disabling it closes the
  listener and revokes the capability immediately.
- Every guest operation requires the room capability. The allowlist contains
  now playing, queue viewing, search, individual track requests, and voting.
  Queue clearing, playback claim, settings, source selection, and bulk playlist
  loading are absent from the router.
- Guest-safe song JSON omits stream and artwork URLs so a Plex token embedded in
  either URL cannot cross the room boundary. Backend errors are redacted.

The host join link is currently displayed for development. A guest client,
disposable participant identities and fairness, realtime updates, QR/deep-link
onboarding, and network-change handling are still required for step 2 and 3
acceptance.

The invitation now uses a versioned join payload with a list of connection
candidates. Only nearby and temporary LAN-development transports are accepted;
there is no relay or internet transport. The LAN development gateway binds to a
single selected Wi-Fi interface, falling back to loopback, instead of listening
on cellular or every phone interface.

The APK now bundles the first no-install guest controller for guests already on
the host's Wi-Fi. It uses only
same-origin HTML, CSS and JavaScript, and covers now playing, queue viewing,
search, individual requests and voting. The room capability arrives in the URL
fragment, which browsers do not send in the initial HTTP request, and the page
removes it from the visible address after retaining it for that tab. The host
serves the page with no-store, no-referrer and restrictive content-security
headers. A forced local-only hotspot was rejected after device testing because
Wi-Fi-only guests lost their internet route. Cross-network rooms will instead
use an app-to-app Bluetooth transport so both phones retain their existing
Wi-Fi or cellular connection. The browser controller remains a same-Wi-Fi
convenience path.

A clean Samsung with no Harmonicast installation completed the browser flow
against a Pixel host: room load, live state, search, an individual request, and
host shutdown. Manual requests are inserted after earlier manual requests but
before automatic queue entries, so a guest request is next unless another person
already has an earlier request in the fair request lane.
