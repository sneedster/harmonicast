---
version: alpha
name: Harmonicast Android
description: Native Plex music player with nearby rooms and restrained dark settings.
colors:
  primary: '#BFA7F5'
  background: '#090B14'
  surface: '#121421'
  onSurface: '#F1EEF8'
omitted:
  - section: typography
    reason: Android MaterialTheme typography is the runtime authority.
  - section: spacing
    reason: Existing native Compose layouts own dp dimensions.
  - section: rounded
    reason: Android MaterialTheme shapes are the runtime authority.
components:
  settings: {}
  accountIdentity: {}
---

# Harmonicast Android design context

## Scope and ownership

The Android app serves personal Plex listening and nearby shared rooms on phones,
tablets, and TVs. Existing copy is English. This document records the established
native interface; it does not define the separately maintained marketing site.

`android/app/src/main/java/io/github/sneedster/harmonicast/PlayerPalette.kt` owns
runtime color tokens for Nocturne, Aurora, and Ember. The colors above document
Nocturne, not a second generated token source. MaterialTheme owns native typography
and shapes. All additions consume those semantic roles.

## Settings

`SettingsScreen.kt` owns settings navigation and shared SettingsDescription text.
Narrow screens navigate from categories to details; at 840 dp and wider, categories
remain alongside details. Existing scroll containers own scrolling. Native buttons
use tvFocusFeedback and dialogs use FocusRestoringAlertDialog.

Keep settings quiet, readable, and consistent with music acquisition settings.
Use titleMedium for account names and source names, labelMedium for identity labels,
and SettingsDescription for secondary information. Long account text wraps naturally.
Account identity is read-only and belongs only in local Plex account settings.

## Plex identity behavior

Fetch the signed-in account through LocalPlexClient using the saved account token,
not the selected server's resource token. Display username and distinct email;
fall back to title or email when username is absent. Show loading text, then an
inline retry on failure. Leaving the screen cancels the UI request; a failed lookup
does not sign out the user or interrupt playback. No new account data is persisted
or sent to room guests. Existing sign-out confirmation remains authoritative.

## Shared Plex room behavior

RoomsScreen and the joined-room playback card use PlexAccessPolicy for visibility.
Configured shared libraries can host and offer paired playback. A joined guest can
offer a saved personal device but cannot open a nested room or use personal
acquisition. Keep Material buttons, SettingsDescription, tvFocusFeedback, and
FocusRestoringAlertDialog; no new tokens or navigation variants are introduced.
Music acquisition copy distinguishes Plex server ownership from room hosting.

Room startup now validates live Plex access before opening listeners. While the
check is pending, roomSummary displays “Checking Plex access…” in its existing
text slot and Open room keeps its label but is disabled. Success opens the usual
host controls; failure restores the button and uses the existing inline room error.
No additional dialog, screen, token, or navigation variant is introduced.

## Shared library preparation

Settings > Music acquisition owns the manually started Set up shared access
panel. SharedPlexSetupSettings uses the existing SettingsDescription, Material
buttons, RemoteTextField, and tvFocusFeedback inside the parent scroll container.
RemoteTextField now supports an enabled state for asynchronous forms, including
its TV/voice controls. No new palette, typography, navigation, or overlay system.
The interface remains English and follows the existing phone/TV input policy.

The panel offers the next applicable action: download the setup ZIP from a
computer (primary) or save it on this device, provide the Plex-visible folder,
create or rescan the library, review inactive metadata, or
show Library ready. A ready library does not claim enabled shared acquisition.
Real MusicGrabber credentials use a separate dedicated-account test and
publication review after preparation. See docs/SHARED_PLEX_ACCESS_PLAN.md for domain policy.

SharedPlexSetupModel owns progress, duplicate-action suppression, safe inline
errors, cancellation, and stale-result rejection. Source changes/exit invalidate
the panel. The service revalidates live ownership before writes and stores only
non-secret preparation markers to resume after interruption. Prepared library IDs
are omitted from the owner's subsequent music-source selection.

Canonical UI map: forms use RemoteTextField; feedback uses inline text with a
polite live region; progress uses Material LinearProgressIndicator in a fixed
height slot; file export uses Android's document picker; scrolling remains with
SettingsScreen. Service regression tests cover authorization/conflict/recovery;
Compose tests cover input gating, busy state, cancellation, and honest readiness.

The computer download uses AcquisitionSetupGateway's download-only variant:
an explicitly opened, five-minute private-LAN page with no pairing, account,
or mutation routes. SharedPlexSetupModel owns its lifecycle and closes it on
exit/source change. It remains available during folder and scan recovery, and on
Library ready for instructions or another installation without deleting the
working album.
The page owns a semantic download link, native details disclosures, inline polite
status, bounded fetch/retry, document scrolling and visible keyboard focus.
Its standalone Nocturne CSS follows display/open.html (Georgia heading, system
body, semantic palette); it is bundled with the app, not the marketing site.
Instructions distinguish extraction destination from Plex's container/share path
and explain permissions without suggesting recursive media changes. The same
credential-free ZIP is served on the web and exported by Android's document picker.

## Manual display entry

RoomsScreen's existing display dialog puts the typed local /open address and
four-digit display code before the optional QR code. Use MaterialTheme titleMedium
for the address, headlineMedium for the four-digit code, and SettingsDescription for
same-Wi-Fi instructions. The shared dialog continues to own scrolling and focus.

The standalone display/open.html entry form uses the display's Nocturne colors,
Georgia heading and system body text. It owns inline validation, pending status,
timeout recovery and keyboard focus. Display codes are separate secrets from the
advertised room name; RoomCapability owns expiry and the shared attempt limit.
Successful entry uses the existing display capability bootstrap and permission
allowlist. No new public service or persistent credentials are introduced.


## Shared acquisition publication and recipients

SharedPublicationModel owns transient login input and tested candidates. Source
changes, cancellation and expiry close the paired computer page and discard the
candidate. Password fields use the existing RemoteTextField with explicit Show/
Hide controls; no password or session token appears in review or status text.
Starting a setup operation preserves the last reviewed preparation and mounted
publication controls, disabled while busy. Otherwise disposal would discard the
tested account during publication. Service checks still revalidate every write.
SharedAccountReview names the server, music library, configuration album, URL,
username and verified role. Destination verification is an unchecked owner
attestation because MusicGrabber paths cannot establish Plex's host/container
mapping. Room permission defaults off. Review publication opens the existing
FocusRestoringAlertDialog with the concrete destination and credential disclosure.

The shared form initially copies only the saved personal connection URL, leaving
the dedicated username and password empty. An edited URL is preserved. The paired
computer page receives that URL after pairing.

The computer form uses the existing Nocturne layout, inline validation and polite
status. Shared mode hides API-key and forget-password alternatives and explicitly
says publication happens on Android. It clears inputs after validation. Owner
manual login retains its original account storage and mode choices.

SharedRecipientSettings owns refresh/reconnect and device opt-out, with an inline
connection state and fixed-height progress slot. Shared users see the service
address, username and room permission after verification; no credential editors
or Plex owner actions. Existing acquisition pickers and room switches use the
verified shared capability without granting Plex write access. Pending requests
stay visible with a paused reason when the original connection is unavailable.


Music acquisition settings show shared-access status before the personal account.
Opening the page performs a read-only Plex inspection; the status distinguishes
published access, prepared-but-off, checking, and an unverified/error state.
A failed refresh does not present the prior publication as current success.
Shared setup details stay behind Manage/Set up shared access; completed download
instructions are optional. Connected personal accounts show identity and actions,
with login fields only after Edit connection. Unconfigured accounts show the form
automatically. Cancel clears temporary secrets and restores the connection summary.


## Acquisition library matches and in-car navigation

AcquisitionCatalogRow owns catalog item actions. A confirmed Plex match is labeled
In your library and offers Queue existing track; unconfirmed items retain Acquire
track and are checked before downloading. The host applies the same lookup and
guard for personal and nearby requests, including web guests/displays. Artist
snapshots are account/source-bound, short-lived and bounded; an absent badge is
not proof of absence. Failed final Plex verification asks the user to retry.

Android Auto Artists/Albums open Plex's complete first-character index; selecting
a letter jumps directly to its server offset and More stays within its range.
Recent views and artist-to-album navigation retain their existing behavior.
Auto search respects requested pagination and server totals across title/artist/
album filters. A zero-result multiword search can retry using an anchor word and
compare punctuation-normalized candidates; this is not general spelling correction.

## App update prompt

AppUpdates.kt owns startup checks and the shared update state for Settings and
UpdatePrompt. Check once per fresh launch by default, preserving an explicit
opt-out. A newer stable release opens FocusRestoringAlertDialog with Update now
and Later; no update or a failed background check opens no dialog. Later defers
for this session, while Settings retains the available release and retry action.
Update now downloads and verifies the APK before opening Android's installer;
Android still owns install consent. Long release notes scroll within the dialog
while actions remain in its footer. Busy downloads disable the primary action
and offer cancellation. Dismissing the dialog clears automatic installer launch.
Existing MaterialTheme roles and tvFocusFeedback remain the visual owners.

## MusicGrabber communication

MusicGrabber is an optional Settings integration. Public marketing focuses on Plex
playback, library browsing, music tuning, and listening rooms. Do not promote
acquisition in feature lists, banners, search metadata, or screenshot galleries.
Keep connection instructions in docs/MUSICGRABBER_SETUP.md, with a quiet link in
the app guide. Preserve accurate privacy disclosures and technical documentation.


## Browser display recovery

RoomsScreen shows the selectable local /open URL directly under the hosted room.
The existing display dialog owns the private entry code and QR invitation.
The browser returns missing or rejected display capabilities to /open with inline
re-entry guidance; transient network and source errors retain retry behavior.
RoomCapability remains the authority for session validity; the advertised room
name alone never authorizes a display.

The host shows the short local guest address and four-letter room code on Rooms
and in Invite guests. Root browser navigation and expired guest sessions lead to
the shared entry form in guest mode. Guest code exchange grants only the existing
guest capability; display entry keeps its independent private code and attempt
limit. QR codes and shared full invitations remain optional shortcuts. The shared
entry template owns validation, focus, loading, timeout, and error behavior for
both modes; room name is visible text for guest entry, display code is masked.

## Restored house jukebox and guest artwork

The room display restores `src/components/KioskView.tsx` from the parent of
`157fdcf`, at the user's request: amber #fbbf24 on ink #0a0a0b, charcoal
#111113 surfaces, bold system sans typography, large square album art, four
bottom destinations, swipe navigation and a 60-second idle attract screen.
`display/index.html` owns this intentional kiosk variant; Android palettes remain
unchanged. Tonight's picks restores Crowd favorites, Underplayed gems, Recently added,
and Wild cards from the retired server. Each card queues its named track.

The guest browser on phone and desktop keeps the app's Nocturne/Aurora/Ember
palette and Georgia display headings, with prominent artwork, progress, and
artwork-bearing search/queue rows. Native nearby guests already receive current
art via NearbyRoomBluetooth; their existing app interface remains the owner.

Canonical web owners: each bundled page's root CSS owns tokens and global
scrollbars; semantic forms own explicit search with stale-result rejection and
Clear; the existing polite message region owns action feedback; native dialog
owns connected-source focus/modal behavior. Guest appearance uses the existing
native select with platform-owned popup. Panels own kiosk scrolling; guest
content retains document scrolling. Missing artwork uses a reserved square music
placeholder. Attract mode restores focus on wake and never covers an open dialog
or an in-progress search.

GuestRoomRouter authorizes read-only `/v1/artwork` and `/v1/picks` for room
capabilities. Artwork resolves an existing library track on the host, accepts only
bounded JPEG/PNG/WebP bytes, and returns inline image data without Plex URLs or
tokens. Existing guest requests/votes and display playback permissions remain
separate. Browser caches are bounded and transient; no account identity is shared.


### Album chronology, artwork and picks density

Library search results group tracks by album, oldest year first, with unknown
years last and stable track order within an album. Native album search uses the
same chronology. Connected-source artist discographies are fetched in bounded
batches, sorted before 25-album display pagination, and cached in memory so later
pages continue the same chronology. Unknown dates sort last. Release tracks keep
their original order.

Connected-source album/recording entries carry a Cover Art Archive release
identity. The web loads thumbnails through the host's room-authorized artwork
route; no source credentials or arbitrary remote URLs reach the browser. Native
catalog rows use the public Cover Art Archive thumbnail. Missing covers retain a
reserved placeholder. See https://musicbrainz.org/doc/Cover_Art_Archive/API.

Tonight's picks uses four equal quadrants of the available content area. The
layout evaluates complete square-cover grids against each quadrant's width and
height, preferring high space usage and fewer, larger covers. Artwork grows with
the screen instead of staying at thumbnail size; a 420 CSS pixel ceiling and at
most eight choices per quadrant keep it a curated display. Counts are an outcome
of the fit, not a design target. Captions grow for room-distance reading. Narrow
screens retain the four sections and fewer covers. Small libraries may have fewer
choices. Sample ranking follows the original rating/play-count formulas; recent
additions are host library tracks, and wild cards shuffle the sample. Artwork
loads with bounded concurrency. Resize invalidates stale layouts.

Colors are intentionally unchanged: the guest browser saves its own selected
Nocturne/Aurora/Ember theme; it does not inherit the host phone's setting. The
kiosk retains the original amber identity.

### Library artist navigation and live Plex discovery

The guest and kiosk pages share `room/library.js`, served as `/room-library.js`.
An exact or unique library artist match opens year-ordered album cards. Selecting
an album fetches its tracks; Back to albums restores the album list and focus.
Changing/clearing search invalidates pending album and artwork requests. Only
track rows offer Queue/Request. The host validates album identities against the
selected Plex library and keeps authenticated artwork URLs private.

Room discovery samples bounded pages in Plex's title index instead of issuing a
full-library random sort. Recently added uses the album date index and one track
per album, with four concurrent reads at most; the full-track added-date query
was observed timing out on the live host. Categories report failures separately,
so one unavailable source does not discard successful categories.

Acquisition submissions allow three minutes for source validation and show in-progress feedback; uncertain responses direct listeners to request history. Acquisition recent-library checks use the Plex album date index and include all fetched tracks, avoiding the expensive global track-date sort.

## Continuous sonic Track Radio

SettingsScreen owns a Track Radio category using existing MaterialTheme text,
SettingsDescription, Material Slider, and tvFocusFeedback controls. The starting
sonic distance is a device-local host preference, 0.05–0.30 in 0.01 increments,
default 0.25. Minus/plus buttons provide precise TV and non-drag operation; the
slider saves on completion. Changes affect the next batch, not queued songs.
No palette, typography, or global navigation conventions change.

TrackRadioSettings owns persistence/validation; radioBatch owns candidate
selection and bounded widening. Start at the saved distance for each batch,
request 100 candidates, filter seed/queue/recent artist-title duplicates, and
queue up to 20 tracks. If fewer than ten remain, widen by 0.05 at most twice.
The queue authority persists radio mode and the last 100 played tracks; depletion
continues from the last played song. Empty sonic results stay empty and report
through the existing automaticMixStatus text. Clear queue/source reset ends the
radio session. The preference remains device-local across source resets.

## Phone now-playing layout — v1.1.14

NocturnePlayer places Plex rating stars at the right of the playback-status row.
Title, artist, and album share a left edge and consistent inter-row spacing;
each is a single line using Compose basicMarquee for overflow. Portrait artwork
fills available width when vertical space permits, reserving space for metadata
and transport on shorter screens. Metadata remains scrollable for large text.
TrackSwipePage now wraps artwork only: its horizontal translation and hit area
leave metadata, progress, controls, and navigation stationary. The existing
vertical artwork gesture still opens discovery. PlayerPalette, MaterialTheme,
and existing transport components remain the visual owners.

## Android Auto track ratings

HarmonicastMediaService owns the Media3 custom actions. Thumbs up/down precede
Track Radio and Clear queue; Android Auto owns placement and may use More.
Reuse core.guests.vote so each press changes Plex by one point and thumbs down
retains the existing automatic-track skip rule. Disable rating actions without a
current track or usable personal Plex source; the core rechecks rating capability.
AutoTrackRating preserves title/artist/album identity, adds the rating or Unrated
to the display subtitle, and supplies a five-star Media3 user rating from Plex's
ten-point value. Core changes refresh current-item metadata without reloading the
stream; track/source checks prevent applying a late result to another item.
No custom car layout, palette, font, or separate rating storage is introduced.

## Shared-user personal ratings

Server ownership does not gate personal ratings. PlexAccessPolicy.canRateTracks
owns availability for configured personal sources, including shared libraries;
joined guests retain room-reaction behavior. Phone, TV, Auto, and rating settings
reuse existing controls and feedback. Automatic changes remain opt-in and use
the selected user token; owner-only setup/acquisition and room-vote rules remain
unchanged. This follows Michael's September 20 confirmation of per-user Plex ratings.


## Track artist across Now Playing

LocalPlexClient owns artist interpretation: nonblank Plex originalTitle is the
track artist, falling back to grandparentTitle and then Unknown artist. Song.artist
is the shared playback label for phone/tablet/TV players, mini-player, Media3
(Android Auto, notification and lock screen), paired receivers, nearby room
summaries, browser guests, and display/attract mode. Song.albumArtist separately
retains grandparentTitle; album/artist browse continues using Plex collection
metadata and IDs. SessionMediaItems publishes both fields without mixing them.
Saved legacy metadata refreshes when selected/resumed; a Plex outage preserves
the playable saved track until a later refresh. Existing layout and tokens apply.

### Artist discovery reading layout

ArtistDiscoveryPage owns a fixed artist photo/name header and Material Artist /
Album tabs. Only the body beneath the tabs scrolls, with independent positions
per tab, reset for a different song. Artist contains the biography and related
artist metadata; Album contains the album title, year, and review. Plex artist
background art is preferred, with the artist thumbnail as fallback. The wide
banner uses the existing authenticated artwork URL helper and Coil; unavailable
images retain a reserved person placeholder. Banner height adapts to the available
height for landscape. Loading, errors, and separate missing-text messages remain
in the body. Down and system Back close the page; downward swipe dismissal is
limited to the header so scrolling the text cannot close the page. MaterialTheme,
playerColors, and tvFocusFeedback remain the visual and focus owners.
