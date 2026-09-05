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
- The app creates a temporary nearby room and advertises only while sharing is
  enabled.
- The room ends immediately when the owner disables sharing; it should also
  expire after a conservative idle period.
- Owner-only controls remain local: Plex configuration, app settings, plugin
  credentials, guest removal, queue clearing, and playback ownership.

### Guest

- Any configured Harmonicast app can temporarily join a nearby host without a
  Plex or Harmonicast guest account. Its own Plex configuration remains intact.
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
- leaving or losing the room returns the app to its own personal state;
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

- local service discovery can locate a host on the current network;
- the proposed **audio QR code** supplies proof of physical proximity and the
  temporary join capability;
- a visual QR code and short code are useful development/bootstrap transports
  for testing the exact same join payload before acoustic transfer is added.

The join payload must be transport-neutral so the acoustic mechanism can evolve
without changing room security or guest APIs. A raw IP address alone is not a
durable identity and must not be treated as authorization.

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
- Add local discovery and the audio QR transport for the same payload.
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
- remote/out-of-home control;
- migration of historical stats and ratings;
- editing Plex playlists or synchronizing playlist changes back to Plex;
- non-Android host platforms.

No item in this list is implicitly removed by adopting the standalone design.

## First implementation milestone

Start with migration step 0, then deliver the personal-mode acceptance slice in
step 1 before building guest discovery. The standalone design is only proven
when the owner can complete a real listening session without the Node server;
an isolated embedded HTTP-server demo is not sufficient.
