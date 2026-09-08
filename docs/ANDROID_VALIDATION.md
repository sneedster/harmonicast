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
"You're up to date" for 1.1.1. Automatic checks remain off. A full download-to-upgrade
cycle and Shield permission/installer screens are not yet device-verified.
