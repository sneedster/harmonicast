# Nocturne design contract

Owner-approved direction, 2026-09-07. The owner approved implementation after
reviewing mockups, with an explicit requirement to retain Harmonicast's unique
features. This supersedes the earlier mockups-only hold for this direction.

## Visual language and navigation

Near-black indigo, restrained violet ambient light, pearl text and lavender
selected/focused states. Elegant display headings, clean readable body text,
varied real library artwork. Home invites discovery; Library supports deliberate
browsing. Search and Queue remain primary destinations. Settings and Rooms stay
directly accessible. A persistent mini-player opens the full player.

Owner addition: color schemes must be user-selectable. Use semantic Material
color roles and shared palette tokens instead of hard-coded screen colors.
Nocturne is the default; palette changes retain layout, behavior and readable
contrast, apply to phone and TV, and persist locally across restarts.

Phone uses artwork grids and horizontal shelves; TV uses couch-readable shelves
and a compact navigation rail. Album art stays square. Focus is obvious without
layout jumps. Animate navigation and deliberate focus changes, never periodic
playback-state refreshes. Respect system animation settings.

## Delivery and data

Implement in the roadmap's ordered slices. Use real Plex metadata, bounded
requests and pagination rather than scanning the entire library or inventing
recommendations. Keep unavailable discovery features out of the UI until they
have a working data source. Preserve scroll state on back, isolate stale loads
after source/profile changes, and provide loading, retry and empty states.

Initial slice: Home shelves for recently added and recently played albums,
automatic mix and current-track radio entry points; paginated Artists/Albums,
artist-to-album and album-to-track browsing; existing playlist/search/queue and
Settings controls remain accessible while their presentation is modernized.

## Feature parity gate

TV depth refinement (owner feedback, 2026-09-07): use a black canvas with
restrained palette-colored ambient light, richer discovery-card gradients,
raised artwork cards and a clear animated focus lift/rim. Keep accents tied to
the selected scheme. Leave breathing room around scaled cards so focus is not
clipped by shelf/grid bounds.

- Player: weighted mix, adaptive ratings, Track Radio, request-first ordering,
  queue actions and shared read-only Plex access.
- Together: optional BLE rooms, browser guests, fairness, voting and expiry.
- Screens: TV display, native player offering/transfer and host take-back.
- Auto: authoritative Gearhead ownership, protected single-track timeline and
  deliberate browse refresh behavior.
- Validation: compare actual phone/TV screens to the approved direction; test
  D-pad and touch, source changes, navigation restoration and browse-to-play.


## Settings and Rooms — v1.1.8 candidate

Settings has six focused categories with compact rows and semantic palette colors.
Use a hub/detail flow below 840 dp of available content width; otherwise show a
240 dp category pane beside independently scrolling details. Categories activate
on click or D-pad OK, not focus. Preserve category scroll, TV focus, and Back
navigation across dialogs, external Android screens, and configuration changes.
Keep primary navigation and the mini-player accessible.

Rooms is a separate destination, also linked from Settings. Prioritize current
room status and playback controls; show guest/display QR codes in dedicated dialogs.
Navigating away does not close a room. Rating consent consequences remain visible;
longer tuning examples use a disclosure. Use explicit stepped controls for touch
and D-pad input. The three automatic rating controls require opt-in.
