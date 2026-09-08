# Releases

## v1.1.1 — artist release browsing

Version code: **58**.

- Artist library browsing now includes singles and EPs alongside albums.
- Preserves sorting, pagination, and library identity checks.
- Owner verified the fix on Pixel; regression tests cover singles-only artists and pagination.


## v1.1.0 — standalone Android cutover

Version code: **57**. Signed GitHub APK from main is the supported distribution.

- Standalone Plex playback on phone, tablet, TV, and Android Auto.
- Nocturne redesign, selectable palettes, paged browsing and Auto artwork grids.
- Nearby rooms, bundled guest/display pages, native TV transfer and take-back.
- Playlist Play/Shuffle fixes, actual Auto connection detection, charging screen setting.
- Background wake handling, metadata preservation, and recovery improvements.
- Retired Node/React, Docker/Compose/Unraid releases, F-Droid distribution, and legacy
  Harmonicast-server login. Historical server source is retained, unsupported.

The owner accepted testing as complete on 2026-09-07. The [validation notes](docs/ANDROID_VALIDATION.md)
preserve unverified scenarios, including uninterrupted deep-idle audio, error
recovery, spoken voice, and the full Auto-disconnect-to-TV sequence. These are
not additional release gates.

Install over the existing same-key APK. Personal Plex settings/local data remain.
Legacy server users sign in directly with Plex. No server history import; old
server volumes are not removed by this release.

## Historical channels

Previous server production: server-v1.0.48. Last Android migration prerelease:
dev-android-v1.0.56.1. Existing artifacts remain historical and unsupported.
No new server image accompanies v1.1.0.

## Release rules

Build with android/build-release.sh, verify signature/version, and publish the
non-prerelease vX.Y.Z tag from main with signed APK and SHA-256. Keep signing keys
and credentials out of Git.
