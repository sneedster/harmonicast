# Releases

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
