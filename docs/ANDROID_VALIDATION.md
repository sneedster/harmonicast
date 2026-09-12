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

### MusicGrabber acquisition candidate — 2026-09-11

The local signed test candidate is `android/releases/harmonicast-1.1.9-acquisition.apk`
(version code 66). It has not been published. The next public build must use a
higher version code for devices that install this candidate.

Controlled-response validation covers account login and identity validation,
remembered and token-only sessions, coordinated renewal, rejected credentials,
advanced API-key and anonymous validation, disconnect, staged setup, pairing
expiry/lockout, Host/Origin checks, guest-token rejection, and credential isolation.
The setup gateway is exercised over localhost sockets. Robolectric covers the
phone/TV Settings controls and setup-session survival across Activity recreation.

Acquisition regressions cover failed completed imports, delayed Plex indexing,
existing Plex matches, durable queue receipts and normal manual queue order,
ambiguous submissions without replay, submission survival after picker closure,
guest quota reservations, room permission and requester ownership, and fulfillment
after room closure. Changing to a shared Plex library blocks new acquisition and
pauses accepted tracking until the original account and owner library return.
MusicBrainz fixtures cover pagination, singles, canonical recording selection,
caching, and empty versus failed responses.

The bundled computer setup, browser guest, and display pages passed the Playwright
controlled-response smoke script at `scripts/acquisition-browser-test.cjs`, including
credential clearing, artist browsing, immediate track submission, request feedback,
and live room disablement. Guest and display modal screenshots were visually
inspected. Screenshots are under `android/app/build/reports/acquisition-browser/`.

Builds use the Java 21 wrapper with a writable local Gradle cache:

```bash
GRADLE_USER_HOME="$PWD/.gradle" ./android/build-debug.sh \
  :app:testDebugUnitTest :app:lintDebug \
  -I /tmp/harmonicast-test-init.gradle \
  -Pkotlin.compiler.execution.strategy=in-process
```

The temporary Gradle init file selects the existing cached Robolectric SDK 35 jar
in offline mode to avoid writes to the sandbox's read-only home cache. It is an
environment workaround, not an application change.

No Android device was attached for this run. Physical phone/TV installation,
Bluetooth transport, playback transfer, and a real MusicGrabber/Plex/Tailscale
acquisition remain unverified in this environment. Tests use controlled service
responses and do not demonstrate live download or Plex indexing behavior.
MusicGrabber's single-line import parser cannot safely represent a hyphenated
artist name; those submissions display an error instead of silently changing the
artist. No service installation instructions are included.

Final debug assembly, all **140 tests** (zero failures/errors/skips), and full debug
lint passed. Lint reports **0 errors and 35 warnings**. The signed release build
uses `android/build-release.sh` with `VERSION_NAME=1.1.9-acquisition`,
`VERSION_CODE=66`, and `HARMONICAST_SKIP_RELEASE_CHECKS=1` because the full debug
checks above already passed; release vital lint still runs during assembly.

Signed assembly and release vital lint passed. `apksigner verify --print-certs`
confirms the same signing certificate as v1.1.8, and `aapt dump badging` confirms
package `io.github.sneedster.harmonicast`, version `1.1.9-acquisition`, code 66.
The candidate is eligible to update that signed installation; installation itself
was not exercised. APK SHA-256:
`ce9b05533a79c68e3537c5407d98b140c161e2b79cc070f6f5d3a7621e9fdd70`.

### Connected Pixel follow-up — 2026-09-11

The Pixel 10 Pro was connected after the candidate report above. Installed the
signed APK successfully with `adb install -r`; the installed package reports
`1.1.9-acquisition`, version code 66. Launched the app and verified the Settings
hub and Music acquisition page on the unlocked device. The existing Plex session
and paused playback state remained present.

Computer-assisted setup opened with a private-network address, pairing code, and
QR shortcut. The desktop fetched the bundled page directly from the Pixel over
the LAN with HTTP 200. Closing setup restored the settings form, retained the
unconfigured connection state, and closed the setup listener. No credentials
were entered and no downloads or playback operations were triggered. This
supersedes the earlier no-device limitation for installation, launch, Settings
navigation, and setup gateway open/reachability/cancellation only. Live service
login/acquisition, Bluetooth, playback transfer, and TV checks remain outstanding.

### Connection recovery and official catalogue results — 2026-09-11

Candidate `1.1.9-acquisition.2`, code 67, adds one bounded retry for transient
MusicGrabber GET transport failures. POST transport remains single-attempt;
uncertain submissions are never replayed by the HTTP client. MusicBrainz retains
its own paced retry loop. Connection errors distinguish timeout, address lookup,
TLS, and connection failures. A Plex duplicate-check failure identifies Plex and
explicitly states that no download was submitted.

MusicBrainz search and release-group browsing restrict eligibility to official
albums, EPs, and singles. Track browsing verifies the release metadata, and every
submission revalidates the canonical recording's official release membership,
including selections previously cached by search. MusicGrabber still receives
only one artist/title line, with playlist creation and placement disabled.
The implementation follows the MusicBrainz recording/release-group search fields
and lookup filters documented at https://musicbrainz.org/doc/MusicBrainz_API/Search
and https://musicbrainz.org/doc/MusicBrainz_API.

All 147 tests passed (zero failures/errors/skips), debug assembly passed, and lint
reports zero errors and 35 warnings. New real-socket tests exercise dropped GET
responses, bounded recovery, non-replayed POSTs, and 401 handling. Additional tests
cover Plex preflight isolation and official-release filtering/revalidation. A live
MusicBrainz lookup confirmed the original Andy Allder/DJ Choci recording has an
Official Album release. That metadata check does not validate acquisition.

On the Pixel, the saved MusicGrabber URL/account remained present. A read-only
connection test succeeded, then a later idle read reproduced the old generic
failure; the original exception was not logged, so a stale connection remains a
hypothesis rather than a confirmed diagnosis. Wireless ADB connected using the
existing authorization. No new debugging pairing was needed.

Signed release assembly and signature verification passed. Installed code 67 over
code 66 on the Pixel via the existing Wi-Fi ADB connection. The saved account
survived the update, and initial connection validation and a subsequent explicit
Test connection both showed Connected without re-entering credentials.
Candidate SHA-256:
`d18586ad72bde7791fed0acfb30d10972d9e880c86c0155dcd65ef780f6cfa4c`.

### Direct submission correction — 2026-09-11

The code 67 live retry reproduced the underlying acquisition failure: Plex timed
out during the pre-submission duplicate check. MusicGrabber read-only validation
succeeded and no download was submitted. The owner then explicitly removed the
requirement for a Plex duplicate check, delegating duplicate handling to
MusicGrabber.

Code 68 (`1.1.9-acquisition.3`) supersedes that preflight behavior. Eligible
recording selection submits artist/title directly to MusicGrabber without querying
Plex. Plex is used only after completion to locate the playable track. Verification
uses track-only search, stops at the first match, and falls back to recent tracks.
A verification outage retains the accepted import ID and waiting-for-Plex state;
it does not resubmit the download. Tests cover submission with unavailable Plex,
the exact artist/title payload, durable normal queue insertion, and Plex recovery.

Final direct-submission regression run: 148 tests passed, no failures/errors/skips;
debug assembly and full debug lint passed. No pre-submission Plex lookup remains.

Code 68 signed assembly and signature verification passed, and the APK installed
successfully over Wi-Fi ADB. Retried the original Andy Allder/DJ Choci “Dead Can
Dance” recording. MusicGrabber logged HTTP 200 for `/api/bulk-import-async` and
subsequent status polling of import `9895399c`; the Pixel displayed “Acquiring
track…”. This confirms live submission now proceeds without the Plex preflight.
APK SHA-256:
`eb7ef80c29aca1f1d678ce6c7724b7cde7885b8c2761854608f9c3e3fe48a368`.

MusicGrabber's live queue showed the exact submitted artist/title and its own
“Downloading · Checking for duplicates” stage. The Pixel continued status tracking
after leaving the picker and showed Connected in settings, with the new request
listed as Acquiring. Earlier failed attempts remain visible as history. Download
completion, Plex verification, and final queue insertion for this live request
were still pending at handoff. No release was published.

## MusicGrabber polling reduction — 2026-09-11

Candidate 1.1.9-acquisition.4 (69) reduces the acquisition polling cycle from five
to thirty seconds, spaces account GET calls by five seconds, and caches automatic
health checks for five minutes on success or one minute on failure. Concurrent
explicit health checks share one request. Room controls no longer force a check
on every opening. Submission POSTs remain unreplayed.

Controlled-response validation: Java 21 debug assembly, 150 unit tests (zero
failures/errors/skips), and lint passed (zero errors, 35 warnings). New tests cover
health cache expiry, explicit retry after failure, and concurrent check coalescing.
No additional live download was submitted for this change.

Read-only MusicGrabber diagnosis: current Settings shows Skip duplicates enabled.
The saved database and WAL were copied locally for read-only inspection; no saved
skip_dupes override was found. This does not establish the historical UI value.
Current container code defaults it to true, but the user recalls enabling it after
reset. The local tag fallback scans the whole library into a memory-only cache,
permits concurrent rebuilds, and timestamps the cache before the scan completes.
This is a plausible upstream slowdown, not a measured proof of the active bottleneck.
No MusicGrabber configuration or database was changed.

The signed candidate installed successfully over wireless ADB on Pixel
10.11.12.41:5555; package manager confirms version 1.1.9-acquisition.4 / code 69,
and the activity accepted launch. APK signature verification passed. SHA-256:
`c37f6bb30ed7e367cb1df5dd02d33bf5e83b01eac253e65c97ed1ad3103420ee`.
Live download completion and subsequent Plex queue insertion remain unverified.

## Acquisition status display — candidate 1.1.9-acquisition.5

The native phone/TV acquisition picker now tracks the submitted request by ID and
updates its visible header from the existing status poll. Search failures and
submission messages are separate; only search failures offer Retry lookup.
This also applies to the shared native nearby-room picker. No service polling
interval or submission/retry policy changed.

Controlled-response validation: Java 21 via android/build-debug.sh, debug and
signed release assembly, all 152 unit tests passed (zero failures/errors/skips),
lint zero errors and 37 warnings. Two new Compose regression tests exercise the
Acquiring → Waiting for Plex → Queued transition without resubmission and ensure
Retry lookup only appears for a lookup failure. A stale Kotlin incremental-cache
failure was resolved by rebuilding with -Pkotlin.incremental=false.

Installed over the existing signed app on the wireless Pixel; package manager
confirms version 1.1.9-acquisition.5 / code 70. APK:
android/releases/harmonicast-1.1.9-acquisition.5.apk
SHA-256: 3a80ba7b46e268ef9b147ca3424cd1e88bb03aa4b048baaf70b9b67bdc5d55d5.

Live Pixel/MusicGrabber validation: browsed Bowling for Soup → the official 1985
single → 1985 and submitted once. The same pinned header visibly changed from
1985 — Acquiring track… to 1985 — Queued without reopening the dialog. Retry lookup
was absent. MusicGrabber completed at 2026-09-12 03:32:17.900 UTC with Already
exists, using the previously verified FLAC; no additional download was needed.
The previous fresh-download test and this status-display test are distinct.

## Public release v1.1.9

Version name 1.1.9 / code 71 supersedes the acquisition candidates. Final Java 21
build through android/build-debug.sh completed debug and signed release assembly,
152 unit tests (zero failures/errors/skips), and lint (zero errors, 37 warnings).
The signed APK's package/version and existing Harmonicast certificate were checked.
Installed successfully over the test candidate on the wireless Pixel.

APK: android/releases/harmonicast-1.1.9.apk
SHA-256: f5e57368033ea676a11cdf99871035d7473c5189a8c7a75484acbb7760746dfa.
Stable GitHub release: https://github.com/sneedster/harmonicast/releases/tag/v1.1.9.


## Shared Plex capability slice — 2026-09-12

Command: `android/build-debug.sh :app:testDebugUnitTest :app:lintDebug`.
Final steady-source run: BUILD SUCCESSFUL in 51 seconds. 172 tests passed,
zero failures/errors/skips. Lint completed with zero errors; existing warning-level
findings remain in the generated report. The initial sandbox run could not write
the Gradle cache; validation used authorized access to the existing local cache.
An intermediate run overlapped a source edit and produced stale-position lint
errors; the final unchanged-source run above passed.

Coverage added: owner/shared/missing/invalid-source capability matrix; joined-guest
exclusion and overlapping client lifecycle; acquisition coordinator rejects joined
owners without posting; shared LocalHarmonicastCore with GuestRoomRouter supports
browse/request/vote and duplicate rejection; shared votes suppress Plex requests
and skip only automatic tracks; native eligibility accepts shared profiles and
rejects cleared profiles; Compose shared Rooms controls are enabled.

Visually inspected `android/app/build/reports/settings-layout/phone-shared-rooms.png`:
390 x 760 phone layout, readable copy and reachable Open room control, matching
existing Material/Settings primitives. Full existing Compose tests also passed.
The frontend strict static audit returned no findings (artifact in
`/tmp/harmonicast-shared-ui-audit.json`); it is not runtime accessibility evidence.

Runnable debug candidate: `android/app/build/outputs/apk/debug/app-debug.apk`.
No release published. Live owner/approved/unapproved Plex sharing proof, delegated
MusicGrabber access, definitive library-revocation teardown, actual service/network
startup on a shared account, cross-device Bluetooth/native transfer, and Android
Auto acceptance remain outstanding. No physical TV testing performed.


## Shared Plex continuation — live room guard and proof kit, 2026-09-12

Final command: `android/build-debug.sh :app:testDebugUnitTest :app:lintDebug`.
BUILD SUCCESSFUL in 32 seconds; 185 tests, zero failures/errors/skips. Lint completed
with zero errors and 37 warnings. APK: `android/app/build/outputs/apk/debug/app-debug.apk`.
No release or version bump was performed.

Added coverage verifies selected-user token use, server identity and exact music
section checks, wrong server/type/missing section, malformed-response distinction,
401/403 versus transient failures, initial failure versus an established room's
outage tolerance, cancellation/timeouts, stale source responses and teardown, guest
mode transitions, old guest/display capability rejection, and pending UI state.
A Robolectric test opens a real loopback GuestRoomGateway socket with a synthetic
shared source, verifies both LAN and nearby-router paths before/after denial, then
closes the listener. Another real HTTP test confirms Plex 403 status survives as a
typed error without exposing response-body details. These are not physical-device
or deployed Plex acceptance results.

Inspected `phone-room-access-check.png` in `android/app/build/reports/settings-layout/`:
existing 390 x 760 phone layout, readable pending summary, unchanged Open room label,
and disabled opening action. The complete Compose suite passes. The frontend strict
static audit reports zero findings in `/tmp/harmonicast-shared-phase2-ui-audit.json`.

Proof kit verification: `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s
scripts/tests -p 'test_plex_sharing_probe.py' -v`: 12 tests pass. They cover transport
redirect refusal/response bounds, hidden-token CLI output, disabled-only dummy
contract, URL/path validation, explicit account control, exact item binding, malformed
and oversized records, duplicate JSON fields, bounded/paged discovery, repeated and
truncated pages, resource-token selection, and exclusive sanitized report writing.
A complete CLI run is exercised with synthetic responses. FFprobe verifies the
packaged synthetic FLAC is exactly two seconds and has the expected album tags.

The user confirmed that the test library/accounts are not yet prepared. The kit and
manual evidence worksheet are ready; live single-item discovery, direct-read
isolation, provenance, share removal with valid reused tokens, library fallback,
managed Home accounts, physical Bluetooth/native transfer, and Android Auto remain
outstanding. Production delegated MusicGrabber discovery/publication/login is not
implemented. Config-library exclusion from Android source selection remains pending;
the kit directs users to keep test media out of the playback library.
## Shared Plex owner preparation — 2026-09-12

`android/build-debug.sh :app:testDebugUnitTest :app:lintDebug` completed successfully:
198 tests, zero failures/errors/skips; debug APK assembled. Lint has zero errors
and 37 warnings. The setup tests cover live-owner gating, stale source/guest
rejection, empty-record review, reuse without another write, uncertain creation
recovery, conflicting metadata, failed read-back, duplicate libraries, overlapping
music folders, strict disabled records, bounded responses, and blocked redirects.
Compose tests cover folder validation, disabled fields/actions while busy,
cancellation, sanitized retry errors, and the distinction between Library ready
and enabled acquisition. The isolated staged snapshot was verified separately from the uncommitted
display-entry work.

Inspected the rendered phone readiness state at
`android/app/build/reports/settings-layout/shared-plex-ready.png`. The existing
Settings suite also passed with the new action in the acquisition category.
The premium static UI audit reported no findings; it does not establish physical
device accessibility or live-server behavior. Bundled FLAC bytes match the tested
media asset. No live Plex library creation or Android metadata write was performed
during this implementation; these mutations need acceptance against the server.
Real MusicGrabber credential publication and recipient integration remain pending.
