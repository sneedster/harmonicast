# Releases

## v1.1.11 — shared Plex access and better library browsing

Version code: **73**.

- Owners can prepare a dedicated Plex sharing library and publish a separate non-admin MusicGrabber connection. Approved shared users discover it automatically.
- Download the portable setup ZIP from a computer through the phone's temporary setup page, then extract it into a Plex-accessible folder. No MusicGrabber source changes are needed.
- Music acquisition settings show connection and publication status clearly, with connection editing on demand and the configured server URL prefilled for shared setup.
- Acquisition results mark known library matches and offer Queue existing track. A final Plex check avoids submitting a new download for an existing match; short-lived artist caches limit lookup overhead.
- Android Auto artist and album browsing uses the full letter index. Search supports result pagination and punctuation variants such as Franco Unamerican / Franco Un-American.

Validation: 228 unit tests, debug lint, setup ZIP checks, rendered UI checks, and live Pixel owner/shared-user setup and acquisition checks. Android Auto changes still need a real head-unit check. Further acceptance details and limits are recorded in [the validation notes](docs/acquisition-library-auto-validation.md).

## v1.1.10 — smooth player swipes and stable controls

Version code: **72**.

- Swipe left for next or right for the same previous/restart action as the transport button.
- The player follows your finger, completes on release beyond halfway or with enough
  flick momentum, and springs back for shorter or cancelled drags.
- Two-line titles reserve their space. Portrait playback and discovery controls stay
  above navigation while artwork and metadata can scroll independently.

Pixel verification confirmed right swipes restart playback and return to the previous
track. Layout regression tests cover short portrait screens, the full navigation frame,
landscape, and stable button positions across title lengths. All 161 unit tests and
full debug lint passed.

## v1.1.9 — optional MusicGrabber acquisition

- Username/password connection with remembered login, session recovery, and API key
  under Advanced settings. Credentials are protected with Android Keystore.
- Pair a computer with the temporary setup page to enter credentials, then confirm
  the tested connection on the Android device.
- Restore missing-track acquisition search and artist/release browsing. Tracks enter
  normal request order only after Plex verification.
- Each room starts with acquisition off; the host may enable native, browser, and
  display requests. Accepted work continues after the room closes.
- Shared read-only Plex libraries cannot acquire or host.
- Catalogue results are limited to official albums, EPs, and singles. Selection
  submits only artist/title to MusicGrabber, which handles duplicate checking.

Version code: **71**, superseding acquisition test candidates through code 70.

- Acquisition polling is paced, connection checks are cached, and the visible
  request status updates through Queued. Retry lookup is reserved for search failures.

Validation: 152 unit tests, debug lint, signed APK checks, and live Pixel acquisition
through MusicGrabber, Plex indexing, and queue insertion. Physical TV and live
Tailscale routing were not tested. Details are recorded in
[ANDROID_VALIDATION.md](docs/ANDROID_VALIDATION.md).

## v1.1.8 — organized Settings and music tuning

Version code: **65**. Pixel Settings navigation, replay-window persistence, Rooms/Back navigation, and playback state checks passed. The owner authorized publication after Pixel checks; no physical TV tests were run, as requested.

Validation: 115 tests, full debug lint, and signed release checks passed. Installed
over v1.1.7 on Pixel; unlocked-device checks are recorded in docs/ANDROID_VALIDATION.md.

- Settings is organized into Appearance, Playback, Automatic mix, Automatic ratings,
  Plex account, and About & updates. Larger displays keep categories beside details.
- Rooms opens directly from its shortcut, with consolidated hosting controls,
  playback transfer/take-back, and separate guest/display QR dialogs.
- Tune completion boosts, skip penalties, repeat-play influence, and automatic
  selection preference with simple stepped controls and calculated examples.
- Avoid recent repeats defaults to one week and is configurable in Automatic mix.
  Uses Plex last-played timestamps plus local playback starts, completions, and skips,
  independently of rating consent. Already queued automatic tracks are rechecked;
  manual requests and Track Radio remain available.
- Rating and selection-strength defaults preserve existing behavior. Automatic rating tuning requires explicit
  opt-in; selection tuning works independently. Settings stay local to each device.
- Rating updates serialize with explicit votes, and failed automatic writes do not
  prevent local listening history from being recorded.
- Existing consent, rated/unrated share, Plex setup, palettes, and queued tracks are
  preserved. New selection settings affect the next automatic batch.

## v1.1.7 — landscape player and live rating feedback

Version code: **64**.

- Phone landscape separates song details from progress and playback controls, with spacing that adapts to available height.
- Compact landscape navigation leaves more room for the player. Track Radio and Discover sit beneath artwork on short screens.
- Thumbs-up/down updates the visible rating after Plex confirms the change. Half-stars and a numeric rating make small changes visible.
- Rating feedback preserves playback position and survives later playback updates. Failed or delayed votes cannot incorrectly update another song's display.
- Automatic Plex rating changes remain opt-in and disabled by default.

The owner accepted the fixes on Pixel. Validation includes 78 tests, rendered portrait/landscape checks, full debug lint, and the signed release build.

## v1.1.6 — automatic Plex ratings require opt-in

Version code: **63**.

- Automatic Plex rating changes are now **off by default**, including for existing users upgrading.
- Enable them in **Settings → Automatic Plex ratings** if you want listening completions and skips to adjust your Plex song ratings.
- The setting explains how ratings change, that changes are saved to Plex and can replace your own ratings, and that disabling does not undo earlier changes.
- Explicit thumbs-up/down votes still change ratings. Automatic mixes, play counts and listening history continue to work.
- Configurable rating and selection weights are planned for a future update.

Validation: all 71 unit tests, debug lint, and signed release build. This update has not been device-tested.

## v1.1.5 — artist albums in search

Version code: **62**.

- Searching an artist, such as Tesla, shows clickable album entries.
- Includes subsequent pages of artist releases and removes duplicate album matches.
- Direct song-title matches remain available alongside albums.

The owner confirmed the fix on Pixel. All 68 unit tests passed.

## v1.1.4 — share Harmonicast

Version code: **61**.

- Settings → Share app displays a QR code pointing to harmonicast.app.
- Phones can share the website link through Android’s share sheet.
- Browser guests get a Get the Android app link and installation guidance.
- Installing remains optional for browser guests; the website links to the latest APK.

## v1.1.3 — TV navigation and live voice search

Version code: **60**.

- Subtle TV focus outlines and focus restoration across pages, dialogs and external screens.
- TV text fields open the keyboard on OK, and keyboard Search submits and dismisses it.
- Microphone buttons support spoken text; TV uses direct Android speech recognition.
- Search and library filters update automatically after a brief typing or speech pause.
- Album search matches open the existing album page instead of expanding into all tracks.
- Album pages label the return control Back and initially focus it on TV.

The owner accepted the Shield changes and confirmed spoken recognition.
Voice input uses the installed speech provider and may require microphone permission.

## v1.1.2 — in-app updates

Version code: **59**.

- Check GitHub for updates from Settings, with optional daily checks on launch.
- Read release notes, download a verified APK, and open Android’s installer.
- Verify checksum, package, signing certificate, Android compatibility and newer version code.
- Remove unused external-server and acquisition code.

Automatic checks are off by default. Installation requires user confirmation.
The owner confirmed successful in-app upgrades on both Pixel and Shield.

## v1.1.1 — artist release browsing

Version code: **58**.

- Artist library browsing now includes singles and EPs alongside albums.
- Preserves sorting, pagination, and library identity checks.
- Owner verified the fix on Pixel; regression tests cover singles-only artists and pagination.


## v1.1.0 — first public Android release

Version code: **57**. Signed GitHub APK from main is the supported distribution.

- Standalone Plex playback on phone, tablet, TV, and Android Auto.
- Nocturne redesign, selectable palettes, paged browsing and Auto artwork grids.
- Nearby rooms, bundled guest/display pages, native TV transfer and take-back.
- Playlist Play/Shuffle fixes, actual Auto connection detection, charging screen setting.
- Background wake handling, metadata preservation, and recovery improvements.

Install over the existing same-key APK to preserve Plex settings and local data.

## Release rules

Build with android/build-release.sh, verify signature/version, and publish the
non-prerelease vX.Y.Z tag from main with signed APK and SHA-256. Keep signing keys
and credentials out of Git.

## In-app update compatibility

Publish stable releases as `vX.Y.Z` with one signed `harmonicast-X.Y.Z.apk` asset.
Keep the application ID and signing key, and increase Android versionCode on every
release. The updater reads GitHub's SHA-256 asset digest and verifies the downloaded
package identity, signing certificate, Android compatibility and newer version code.
Drafts and prereleases are excluded. A signing-key change requires a separate migration.
