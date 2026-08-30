# Connected music-source plugins: v1 plan

## Purpose

Harmonicast supports optional, private server plugins that can acquire a song
after a completed Plex search has returned zero tracks. The public project
ships the loader and plugin contract only; provider-specific code, credentials,
and source repositories remain private. The first private plugin will use an
operator's existing MusicGrabber service.

## Trust and installation boundary

- A plugin is trusted server-side code. Installing one is equivalent to giving
  its publisher access to Harmonicast's process and local server data.
- Only the Harmonicast host can install, update, enable, or remove a plugin.
- Settings accepts a source URL and immutable Git revision or release tag. It
  never accepts a floating branch as an installed revision.
- Harmonicast installs plugin source under persistent app data and records the
  source, resolved revision, package metadata, and checksum.
- Repository credentials are deployment-only, read-only container secrets;
  they never enter Settings, browser requests, SQLite, or logs.
- Plugins are loaded only at server startup. Install/update reports that a
  restart is required, avoiding hot-loading privileged code into a live app.

## Plugin contract

- A plugin contains a manifest: stable id, display name, API version, server
  entry point, and declared configuration schema. The schema can define normal
  fields (URLs, switches, labels, and limits) plus explicitly marked secret
  fields such as API keys.
- Harmonicast imports enabled plugins from its local extension directory at
  startup. Invalid manifests, duplicate ids, or incompatible API versions
  disable only that plugin and never prevent the base server from starting.
- A music-source plugin receives a narrow host API: its data directory,
  lifecycle updates for its own request, primary-library lookup, and verified
  fulfillment into the acquisition queue. It never receives raw SQLite access,
  the Plex owner token, or a browser session token.
- Plugin kiosk pages are served under Harmonicast's own origin and use the
  caller's normal authenticated session. A plugin verifies request ownership
  through the host API before showing or changing a request.

## Plugin settings

- Host Settings renders each installed plugin's declared settings schema. This
  gives every plugin a normal configuration card without Harmonicast knowing
  anything about a provider's URLs or field names.
- Secret fields use password inputs, are never returned to browsers after they
  are saved, and are omitted from status, diagnostics, logs, exports, and the
  plugin manifest.
- Harmonicast encrypts saved secret values at rest using a deployment-provided
  master key. A server without that key can still use plugins with ordinary
  settings but cannot save secret fields through the UI; it is directed to use
  a mounted deployment secret instead.
- The host can edit, clear, and validate a plugin's settings. Validation is
  performed by the plugin and returns only a safe success/failure message.

## User experience

1. A completed kiosk Plex search has no tracks.
2. If an enabled plugin is healthy, the kiosk offers **Search connected music
   sources**. Otherwise it presents the normal no-results state.
3. Harmonicast creates a durable request and opens the plugin's in-origin
   kiosk page.
4. The plugin resolves the guest's MusicBrainz choice and performs acquisition.
   The guest makes that single choice; there is no second confirmation or
   provider candidate picker.
5. The plugin reports lifecycle states and fulfills only when the song exists
   in Harmonicast's configured Plex library.
6. A fulfilled track enters the priority acquisition lane immediately after
   the current track. Multiple ready acquisitions alternate by requester.

## Installation flow

1. The host enters a repository/archive URL and pinned release tag or commit
   in Settings, then confirms the trust warning.
2. Harmonicast downloads the exact revision using its deployment-only source
   credential when needed, validates the manifest, installs production
   dependencies, and atomically moves the prepared directory into its plugin
   store.
3. Settings displays installed source, revision, checksum, and restart
   requirement. A failed installation leaves the active plugin unchanged.
4. On restart, Harmonicast validates and loads enabled plugins. Health and
   non-sensitive status are host-visible; guests see nothing unless a healthy
   plugin applies to an empty search.

## Acceptance criteria

- An unconfigured or unhealthy plugin causes no external activity and shows no
  kiosk action.
- Plugin source is pinned and a failed install cannot replace a known-good
  plugin.
- Repository credentials never enter Settings, browser requests, SQLite, logs,
  or manifests. Plugin secret settings are accepted only through host-only
  password fields, encrypted at rest, and never returned after save.
- A plugin cannot inspect another plugin's requests or fulfill an arbitrary
  Plex track.
- Restarting Harmonicast or a plugin preserves active acquisition progress and
  the existing priority and round-robin queue guarantees.
- Public docs contain no MusicGrabber-specific guidance or credentials.
