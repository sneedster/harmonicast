# Android validation

## Accepted baseline

The owner accepted release testing on 2026-09-07. Phone and Shield observations
covered Nocturne browsing and playback, playlist actions, saved palettes and native
TV transfer/take-back. Android Auto DHU checks covered library grids, browsing,
playback, and projection connect/disconnect. Browser guest checks covered joining,
searching, requesting, queue synchronization and fullscreen display.

These observations do not establish uninterrupted audible playback under every
battery policy, network-error recovery, spoken voice acceptance, or the complete
Auto-disconnect-to-TV sequence. Those limits remain explicit.

## v1.1.1

Artist browsing includes singles and EPs. The owner confirmed the fix on Pixel.
The signed version 1.1.1 (58) installed successfully on Shield; no additional
Shield play-through was performed for that patch. Android unit tests, debug lint,
release build, APK signature and version checks passed.

## Repeatable checks

Run `scripts/release-check.sh` for debug assembly, unit tests and lint. Run
`android/build-release.sh` for release checks and the signed APK. Keep device
installation, launch and end-to-end playback verification distinct in reports.

## In-app updater

2026-09-07: implemented Settings update checks, optional daily launch checks,
release notes, user-requested APK downloads and Android installer handoff.
Checks reject a wrong package, signer, version, download URL or missing checksum.
All 66 unit tests, debug lint and signed release assembly passed. Installed on
Pixel and verified App updates controls and a live GitHub check returning
"You're up to date" for 1.1.1. Automatic checks remain off. The owner subsequently confirmed successful in-app
upgrades to v1.1.2 on Pixel and Shield, with no Google Play Protect block.


## TV focus and voice input (working build)

2026-09-07: added theme-colored focus outlines, per-page/dialog/window focus
restoration, explicit OK-to-edit text fields, and separate microphone buttons
using the installed Android speech recognizer. The Shield exposes that recognizer.
The owner rejected the first thick focus ring and reported that its nested
microphone could not be activated; the revised design uses a thin outline and a
sibling microphone button so the text editor cannot consume its D-pad events.

Device acceptance for the revised build remains pending. Check with the remote:

- Move through a text field without opening the keyboard; press OK to edit,
  dismiss the keyboard, and move down again.
- Move right to the microphone, press OK, speak a query, and cancel/retry; ensure
  focus returns to the microphone and recognized text reaches the field.
- Open and dismiss a dialog, return from an external screen, and return from
  album/playlist details; focus should return to the previous control.
- Check focus visibility on cards, buttons, sliders and text fields across palettes.


### Shield recognition follow-up

The owner reported the system voice dialog opened but heard no speech, and that
Google Assistant also failed. Removed the abandoned `family.strong.rhondavoice`
diagnostic app from Shield. Google remained the selected assistant/recognizer;
its microphone permission was granted and system microphone mute flags were off.
Logs showed Google authentication failures, but their relationship to recognition
is unproven. Prior Rhonda device notes recorded successful direct SpeechRecognizer
capture and a system recognition activity that failed to return text.

TV input now uses SpeechRecognizer directly, with a runtime microphone permission,
15-second limit, cancellation/cleanup, and visible recognition errors. Phones retain
the system recognition activity. Spoken acceptance on the Shield remains pending.


### Live search follow-up

The owner confirmed direct TV voice input transcribed successfully. Search and
library filtering had still required explicit submission. Both now automatically
search after a 300ms pause in typed or recognized text; clearing the query resets
results, and Search cancels older requests and ignores stale responses. Playlist
filtering already updates immediately. Device acceptance of live search is pending.


### Album search navigation and keyboard submission

Music search now presents matching albums as artwork/title/artist/year rows that
open the same album page as Library browsing. Album matches no longer expand into
tracks for this screen; track-oriented playback and guest search retain their
existing expansion behavior. Direct song matches remain under Songs. The album
metadata/no-expansion regression is covered in LocalPlexClientTest.

The keyboard Search action now submits and hides the keyboard, including on TV.
Both changes await the owner's Shield acceptance.


Album-page return controls now say Back regardless of whether the page came from
Search or Library. On TV, opening an album places initial focus on Back; loading
tracks does not repeat that focus request. Shield acceptance remains pending.


## v1.1.3 release acceptance

The owner accepted the final Shield Back-button behavior and authorized publication.
The preceding working-build notes preserve intermediate checks and their limits;
spoken recognition was owner-confirmed. Final release checks cover all 67 tests,
lint, signed APK metadata and signature.

## Artist search album follow-up — 2026-09-07

Owner reported searching Tesla on the phone still showed expanded song rows.
The Pixel was already on v1.1.4/code61; the gap was artist expansion, not a
missing app update. Main Search now combines album-title matches with albums
from matching artists, follows artist album pagination, deduplicates collection
IDs, and retains direct song-title matches without expanding artist tracks.
Track-oriented guest/playback searches retain their existing behavior.
Removed the unused artist-track lookup from the Search view model.

Regression coverage checks Tesla artist albums across pages, duplicate album
matches, direct songs and absence of artist track expansion. All 68 unit tests
passed; debug and signed release builds and release vital lint succeeded.
Installed the signed local fix over v1.1.4 on Pixel 10 Pro with app data preserved.
Owner confirmation of Tesla results and album navigation remains pending.
No public release or Shield installation was performed for this follow-up.

The owner subsequently confirmed the Tesla fix on Pixel and authorized v1.1.5
publication (version code 62).
Release checks for v1.1.5 passed: 68 tests, full debug lint, signed release
build, APK version metadata and signing-certificate continuity with v1.1.4.

## Automatic Plex rating consent — 2026-09-08

Added a persistent, device-local Settings opt-in, disabled for fresh installs and
existing installs without an explicit choice. Completion and skip rating writes
check this preference, including a recheck after fetching Plex metadata. Settings
explains rating effects, Plex persistence, existing-rating replacement, opt-out
limits, and the separate explicit thumbs-up/down behavior.

Validation: debug APK build, all 71 unit tests, and debug lint passed. Regression
tests cover default-off upgrades, opt-in writes, persistence, service opt-out,
opt-out during metadata fetch, read-only sources, and explicit votes while off.
No device installation or visual/device acceptance was performed; not published.
Future configurable weights are recorded in ROADMAP.md. Project Memory tools
were unavailable; shared-memory sync of this milestone and decision remains pending.

The owner authorized publication as v1.1.6 (code 63). Release checks passed:
71 tests, full debug lint, signed release build and release vital lint. APK
version metadata and signing-certificate continuity with v1.1.5 were verified.
No device installation was performed.

## Phone landscape and live ratings — 2026-09-08

Separated song metadata from progress/transport in NocturnePlayer. Landscape
uses a compact navigation rail/header, fits artwork to available height, and
places Track Radio/Discover below artwork on short displays. Metadata scrolls
independently if needed; portrait keeps the stacked layout.

Confirmed Plex vote writes now update the current playback snapshot and publish
a change event. Playback callbacks preserve that rating. Failed votes leave the
display unchanged; delayed votes cannot overwrite or skip a different current
track. Both native star displays show half stars plus the numeric Plex rating.

Validation: 78 tests, debug assembly, full debug lint, signed local release
assembly and release vital lint passed. Four native-rendered Compose tests cover
portrait, landscape, short landscape, full landscape navigation, and visible
rating changes. Core tests cover successful/failed/delayed vote responses.

Installed signed 1.1.6-player-test (code 63) on Pixel 10 Pro over wireless ADB.
Observed full metadata, ratings, progress and all transport buttons in landscape
and portrait. Screenshots showed the same paused song changing from 4.7 to 5.7;
the agent did not issue a live vote. Restored automatic rotation to its original
enabled state. Wireless ADB was re-enabled at the owner's request and verified.
No Shield installation and no public release for these changes. Device owner
acceptance remains pending. Project Memory sync of the v1.1.6 opt-in decision
and release is now complete.

The owner accepted the Pixel layout and rating fixes and authorized publication
as v1.1.7 (version code 64) on 2026-09-08.
Release checks for v1.1.7 passed: 78 tests, full debug lint, signed release
build and release vital lint. Verified APK version metadata and signing
certificate continuity with v1.1.6.


## Settings redesign and tuning — v1.1.8 candidate, 2026-09-09

Implemented six Settings categories, adaptive hub/detail and two-pane layouts,
a separate Rooms destination, compact room sharing dialogs, and four device-local
music tuning controls. The existing Plex rating opt-in gates rating tuning;
selection tuning is independent. Defaults reproduce the previous formulas.

Validation completed:

- 98 unit/Compose tests passed, including 20 new tuning/integration/UI tests.
- Full debug lint, debug assembly, signed release assembly, and release vital lint passed.
- Native-rendered Compose checks cover phone hub, consent-gated ratings, automatic
  mix, Rooms, short landscape examples, tablet/Ember, and TV two-pane navigation.
- Tests cover saved-state restoration, read-only restrictions, rating-write failures,
  concurrent automatic/explicit votes, storage errors, and deterministic selection.
- Verified APK package, version 1.1.8/code 65, and signing-certificate continuity
  with v1.1.7. Candidate SHA-256:
  a490cdb4a8520606a143554dffadd8ff02b886b0082bd59ff88b8ed84eea98c6.
- Installed the signed candidate on Pixel 10 Pro over v1.1.7 with `adb install -r`;
  package metadata confirms version 1.1.8/code 65.

The Pixel was locked, so interactive Settings/playback verification and owner
acceptance remain pending. The owner explicitly requested no TV device testing;
no Shield installation or physical TV tests were performed. Automated TV layout
checks ran locally, not on the TV. No live Plex rating changes were issued.
No new physical Android Auto or room-transfer acceptance is claimed. Public release
is pending Pixel review; v1.1.7 remains the published release.


### Replay-window follow-up — 2026-09-09

The owner requested excluding songs played within the last week from automatic
mixes, including skips that do not receive a Plex completion scrobble. Added
Settings → Automatic mix → Avoid recent repeats, defaulting to 1 week with Off,
1 day, 3 days, 1 week, 2 weeks, and 30 days options.

Automatic candidates use the newer of Plex `lastViewedAt` and durable local
play/start/skip timestamps. Existing available local history is imported on upgrade;
the recent-track index is retained by age rather than the 500-event display-history
limit. It remains independent of automatic-rating consent. Source changes retain
server-scoped timestamps; sign-out clears local history. Skips on another host or in
another app require Plex to record them to become visible here.

All candidate pools respect the cutoff. Bounded oldest-first pages top up random
samples dominated by recent tracks. Automatic queue entries are checked again
against current local history and fresh Plex metadata when dequeued. Explicit
requests, Track Radio, and Previous retain their behavior. Starting a new random
mix discards the forward playback-history lane; exhaustion displays a message and
never relaxes the exclusion window.

Validation: 115 tests, full debug lint, signed release assembly, and release vital
lint passed. New regressions cover boundary dates, persistence, history migration,
more than 500 recent tracks, opt-out/read-only playback, queue freshness, requests
arriving during metadata fetch, fallback filtering, bounded top-up, library changes,
sign-out, forward-history reset, and the Settings control. The phone layout was
rendered and visually inspected. No physical TV tests were run, per owner request.

The updated signed v1.1.8/code 65 candidate replaced the earlier candidate on Pixel
with `adb install -r`. Signature continuity and APK metadata were verified. SHA-256:
43fc79eb76048d04fddb1f0a7c9ec52a1a50a885d03e8bd7db019d9a416b282b.
At installation the Pixel was locked; the interactive checks below supersede that status.
The earlier candidate hash above is superseded. No live Plex votes or completion
scrobbles were issued as part of these checks.

### Unlocked Pixel checks — 2026-09-09

Verified the signed candidate on the unlocked Pixel 10 Pro. The six-category
Settings hub renders with the existing Ember palette. Automatic mix shows the
default 1-week replay window; changing it to 2 weeks and back to 1 week works,
and the value persists after leaving and reopening the page. Rooms opens as a
separate destination, and Android Back restores the Automatic mix detail page.
The library shelves loaded successfully on returning to Home.

Temporarily disabled automatic rating changes, resumed the existing paused song,
observed the playing-state Pause control, and paused it again. Restored automatic
rating changes to On and verified the paused-state Play control. The existing
queued track was preserved. This checks playback state transitions, not audible
output or live random-queue exclusion; exclusion is covered by the automated
regressions above. No physical TV checks were performed. The owner subsequently authorized committing, pushing, and publishing v1.1.8.
The release uses the same signed APK verified on the Pixel.
