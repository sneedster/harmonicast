# Android validation — 2026-09-07

## Owner acceptance

On 2026-09-07 the owner instructed "consider testing complete" and authorized
v1.1.0 release/server retirement. Testing is accepted. Observations and unverified
scenarios below remain historical evidence, not additional release gates.
No further device tests are requested.

## Pre-cutover validation history

Local debug/release assembly, 70 unit tests and full debug lint pass (zero errors,
46 warnings). `scripts/release-check.sh` also passed server tests, web typecheck,
lint/build, production Docker image build and Compose configuration validation.
Version remains a local 1.0.56 test build; v1.1.0 has not been published.

The immersive Nocturne player was visually checked on Pixel and Shield: large
artwork, responsive portrait/two-column layout, transport/seek/rating controls,
Track Radio and artist discovery. Album/artist browse now includes submitted
search, A–Z/Z–A sorting and available artist biographies. These latest filters
have unit coverage and live TV search/discography/biography checks below.

Android Auto has a separate media-service Library hierarchy backed by the same
Plex collection API: Recently added, Albums, Artists, Recently played, album
tracks and bounded More pages. Four top-level tabs retain For you, Request queue,
Plex playlists and Library. DHU connected to Pixel verified browsing Recently
added → Angel → track and starting playback. The current-track-only timeline
and protected request-queue refresh behavior remain intact. Artwork grid hints
passed DHU validation below. Voice search-query playback resolution is implemented;
spoken voice acceptance remains pending. The voice registration lint error is resolved.

Bundled guest/display pages now use Nocturne colors, selectable persistent
Nocturne/Aurora/Ember schemes and friendly connection-loss status that disables
static room controls. JavaScript parsing and Fire device checks below pass.
TV inspection found and fixed oversized QR layout (now bounded and
fully visible); Ethernet room hosting also needed the existing native LAN
network selector instead of the Wi-Fi-only development fallback.

The first screen-off playback run started at 21:47:43 UTC and stopped at a track
boundary around 21:55:52 while Android was in deep idle. It is a failed gate,
not an extended-playback pass. Added Media3 network wake handling, isolated
completion scrobble failures from next-track advancement, and preserved existing
playable metadata during transport state updates without another Plex request.
A regression test covers offline pause-state persistence. The corrected Pixel
screen-off retest at 22:04:22 UTC advanced but hit a network source error in
deep idle. Android explicitly suspends network access and ignores wake locks
for non-exempt apps in this state. Added retry of network source errors when
leaving idle, explicit play-button recovery, and a phone Settings shortcut to
Android battery optimization settings. Recovery acceptance is pending.

A temporary app-only Doze exemption was enabled for a separate unrestricted
screen-off run at 22:17:28 UTC. Playback crossed multiple track boundaries and
remained playing through 22:27:21 UTC. The temporary exemption was removed
afterward. This is a conditional unrestricted pass, not default Doze acceptance.

Bounded follow-up after scope freeze: first attempt aborted because playback was
paused. Retried with confirmed PLAYING and no app Doze exemption, forced deep idle
for 180 seconds, then restored battery simulation and idle mode and woke the phone.
No network error was reported. Media-session position updates became stale during
the run, so they cannot establish uninterrupted audio. Final pause produced fresh
state at 252.2 seconds versus 21.1 seconds before the test, with 286.4 seconds
buffered. Both attempts restored device state; final state was PAUSED. This is
evidence of playback progress, not verification of error recovery or a complete
extended/background acceptance pass.

Fire HD verification: Nocturne guest connected to the Ethernet-hosted TV room;
Aurora selection survived reload and applied to the separately authorized
display. Display resume advanced position and Next started the next queued
song on TV. Ending the room disabled search/playback controls and displayed
the friendly reconnect/new-invitation message. TV was left paused and its
temporary room ended. Expired invitation text files were removed.

TV library checks: album search for Angel, artist search for Madonna, artist
discography and a scrollable biography displayed real metadata. Returning from
an album retained the submitted filter. Broader focus/scroll and full playlist
action acceptance still remain.

Follow-up: reproduced playlist Play adding eight songs without starting playback.
The TV media Next command worked. Routed album/playlist Play and Shuffle, plus
automatic mix start, through the existing next-track service action instead of
the controller seek command. Debug/release assembly, all 70 unit tests, and lint
passed. Installed on TV: playlist Play started the first track and playback
position advanced from 2.9 to 5.9 seconds. Shuffle started Pump It Up by Elvis
Costello; TV was left paused. Other release gates remain open.

Owner-requested phone setting "Stay awake while charging" is implemented with
a persistent opt-in preference and an activity-window flag. Power broadcasts
update it while open; leaving the activity clears it. Existing TV keep-awake
behavior is retained. Release assembly and debug lint pass. Pixel verification
confirmed the default is off, enabling while unplugged leaves KEEP_SCREEN_ON
unset, simulated USB power sets it, going Home clears it, reopening restores
it, and unplugging clears it. Force-stop/relaunch preserved the enabled switch.
Battery simulation was reset in a finally block; the preference is left enabled.

Native TV host-control pass: Pixel room discovery and TV owner-only offer worked.
Initial transfer was blocked by retained Android Auto controller state after DHU;
stopping Gearhead alone did not clear it. Restarting Harmonicast and opening a
fresh room allowed transfer. This Auto-to-TV transition issue remains open.
In the fresh room, TV reported host control and progressing playback. Pixel
pause held the TV at 2:08; resume continued from that position. Natural completion
advanced Keep on Coming to Hoodie. Host Skip started Why Do You Love Me, and
the TV progressed to 0:14. Take-back stopped the receiver and Pixel resumed at
the current position (observed playing at 0:48). Re-offering and transferring
again worked. Ending the room during TV playback returned Pixel paused at 2:08,
returned TV to its home screen, and removed the NativePlaybackReceiver service.
Both temporary rooms were ended. This verifies reported receiver playback and
transport state; audible output was not independently confirmed in this pass.

Auto connection follow-up: Media3 1.5.1 legacy controllers have a five-minute
inactivity disconnect timeout, so a controller record does not prove projection.
Added AndroidX CarConnection 1.4.0 observation for the actual projection state,
with the previous controller check only until the initial query completes.
Transfer and playback-authority checks now use that state. The transfer message
separately explains an active car/wireless-adapter connection versus an existing
room transfer. Observer cleanup is tied to service destruction. Builds, 70 unit
tests, and lint passed; installed on Pixel and observed disconnected state.
DHU retry reached TLS negotiation but failed its transport before projection;
connected/disconnected transition acceptance remains pending. Test server was
stopped and ADB forwarding removed. Owner reports using a wireless USB Auto
adapter and suspects it remains powered after car shutdown; its actual power
and projection state were not verified. This may explain the original block,
so do not attribute that observation exclusively to stale Media3 state.

Fresh DHU retest passed: the same Harmonicast process (PID 20207) observed actual
projection connected at 16:39:42 local, then disconnected at 16:43:35 after DHU
quit, without restarting Harmonicast. Its authority guard therefore releases on
projection disconnect rather than waiting for legacy controller expiry. Auto
Library → Albums displayed a three-column artwork grid; selecting Blackstar
showed its tracks, and selecting Lazarus started David Bowie playback (PLAYING
at 17.5 seconds). The emulator was closed, playback paused, Gearhead test server
stopped, and forwarding removed. A complete Auto-disconnect-to-TV transfer remains
to be retested; the platform connection transition itself is verified.

Server-only dispositions are resolved: owner approved deferral and server retirement
at v1.1.0. The owner subsequently cancelled acquisition/plugins and alternative
music sources; the planned app remains Plex-only. Remaining Auto/background
acceptance is unresolved. Earlier sections
below are chronological evidence, not a
claim that their remaining-work lists describe the latest build.

## Nocturne first implementation slice

The approved redesign is now in progress (see `NOCTURNE_DESIGN.md`). The first
slice builds both debug and locally signed release APKs and passes **64 unit
tests**, including collection paging, album order, and source/library isolation.
Full debug lint reports the same one pre-existing voice-search registration
error and 46 warnings; no new lint errors remain. These are local 1.0.56 test
updates, not a published release.

Installed on Pixel 10 Pro and Shield Android TV without clearing app data.
Observed actual library artwork on Home and the TV album grid, D-pad navigation
into Library and an album, and album metadata/tracks loading. The TV album
header was changed to a horizontal layout after the first device inspection
showed its controls below the fold. The phone renders the touch layout with
bottom navigation and mini-player. Aurora palette selection persisted across a
force-stop/relaunch on Pixel; Nocturne was restored afterward.

Implemented: source-scoped collection pagination; Recently added and Recently
played shelves; Artists → Albums → Tracks; ordered/shuffled/next/queued album
actions; preserved existing playlist/search/queue/Settings entry points; theme
selection; destination crossfades and artwork focus feedback. Browse data is
cached within the active source so back navigation can restore loaded grids.

Still pending: broader back/focus restoration testing, browse-to-play and
multi-page real-library acceptance, artist biographies/related content, Tracks
category and richer filters, redesigned playlist/search/queue/player/room
screens, complete motion polish and feature-parity acceptance. Existing guest
and Android Auto unit coverage passed, but device parity was not re-exercised
in this UI pass. Revisit host control of TV playback as the owner's separate
follow-up before final room-display acceptance.

## Second Nocturne presentation slice — 2026-09-07

Follow-on pagination: replaced the fixed preview with 100-entry pages and
load-more/retry controls. Offset advancement uses raw entries so unavailable
tracks cannot shift page boundaries; repeated playlist tracks remain intact.
Debug/release builds and 66 tests passed. Installed on Pixel and Shield.
On Pixel, opened the 40,558-track All Music playlist, observed 100 loaded,
scrolled to Load more, fetched the second page without returning to the top,
then verified 200 loaded in the header. Playback was not triggered. TV paging
and full playlist actions still need device acceptance.

Owner accepted the TV depth refinement and asked to move on. Added artist
artwork headers, filterable alphabetical playlist cards, a playlist detail
destination with existing play/shuffle/next/queue actions, and semantic-color
search/queue/track-row styling. Playlist preview requests are bounded to 100
entries after device inspection found a 40,558-track playlist. Full playlist
actions retain their existing endpoint behavior; preview limits are explicit
in the UI. A regression test verifies preview bounds do not alter full actions.

Debug/release builds and 65 unit tests passed. Installed on Pixel and Shield
without clearing data. Visually observed the live Pixel playlist grid and an
eight-track playlist detail, plus Shield queue rendering and remote navigation
into Queue. Full playlist actions were not triggered, and large-preview device
acceptance, artist/search device checks and broader feature parity remain.

## Fire HD browser check — 2026-09-07

TV depth refinement later the same day: debug/release assembly succeeded and
the release test APK was installed on Shield with app data retained. Visually
checked black/ambient backdrop, gradient discovery cards, card shadows and
D-pad focus lift/rim on Home and its artwork shelf. Moving down into artwork
scrolled the shelf into view. Accents still use the selected semantic palette;
alternate palettes were not re-tested in this refinement. No playback behavior
was changed or revalidated.

Tested the existing bundled web UI in Chrome on the Android 7 Fire HD 10,
connected to a temporary Pixel-hosted LAN room. Guest invitation loaded the
correct now-playing track and queue. Searching Madonna returned real results;
requesting "4 Minutes" showed confirmation and appeared second in the separate
room display queue. The display invitation and fullscreen layout worked.

Ended the temporary room on Pixel afterward. Display changed to "Room
unavailable"; both views reported "Failed to fetch". Stale content and
apparently actionable controls remain visible after shutdown: improve this
state during the web redesign. This check does not validate playback controls,
voting, reconnect, native TV control, or Nocturne web styling. The test request
was added to the personal queue; playback remained paused.

## Earlier hardening pass

Base: `4944dad`, with uncommitted TV setup/room controls and transferred-playback
resume fix. No device acceptance is implied by the checks below.

## Local checks

Ran `./android/build-debug.sh :app:testDebugUnitTest :app:lintDebug` using the
project Java 21 wrapper. Debug APK assembly succeeded; all 60 unit tests passed.
The combined command exits unsuccessfully because lint still reports one error
and 46 warnings.

Resolved 17 lint errors: SessionError constants, scoped Media3 unstable API
opt-ins, legacy AppCompat image tint attributes, and coarse location permission
alongside fine location (both limited to Android 11 and older).

Remaining error: `MissingIntentFilterForMediaSearch`. The service implements
browse search but does not resolve `MediaItem.requestMetadata.searchQuery` in
its playback item callback. Implement and validate voice playback before merely
advertising the missing action or treating Android Auto acceptance as complete.

## Next device checks

The owner selected Google TV testing. ADB currently sees only the Fire HD 10;
Google TV connection is pending. No APK was installed during this pass.

- TV: complete Plex server/library selection with D-pad only; verify initial
  focus, navigation, back behavior, and opening/ending a room from Settings.
- Transfer: offer TV as a player, select it from the host, verify audio on TV,
  pause/resume without jumping to the old host position, skip, natural track
  advancement, and explicit take-back.
- Disconnect: end the room and interrupt the control connection; verify receiver
  audio stops and the host returns paused without duplicate playback.
- Reconnect: rejoin and offer the TV again without stale room state.
- Long duration: exercise screen-off playback beyond the previous 15-second
  phone test, followed by a controlled Doze test on the phone host.

Google TV and long-duration playback acceptance remain pending. The v1.1.0
retirement/release gate has not been satisfied by this pass.
