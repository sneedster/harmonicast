# Releases

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
