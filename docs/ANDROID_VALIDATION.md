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
