# Harmonicast

Standalone Plex music playback for Android phones, tablets, TV, and Android Auto.
Plex and access to a Plex Music library are required. Android 8.0 (API 26) or later.

## Install

Download the signed APK from the [latest release](https://github.com/sneedster/harmonicast/releases/latest).
Install over the existing signed app to retain personal-mode settings and data.
Choose **Sign in with Plex**, then your server and Music library. TV setup shows
a QR code for Plex sign-in on your phone. Shared libraries support listening;
owner-only Plex writes and room hosting require owner access.

## Features

- Nocturne browsing/player UI with persistent Nocturne, Aurora, and Ember palettes.
- Albums, singles, EPs, artists, biographies, live search, and paged Plex playlists.
- Voice input through Android speech recognition and remote-friendly TV focus.
- Request-first queues, configurable automatic mix, adaptive ratings, and Track Radio.
- Android Auto library browsing, artwork grids, and playback.
- Temporary nearby rooms, requests/votes, and bundled browser guest/display pages.
- Owner-controlled native room playback transfer and take-back.
- Optional **Stay awake while charging** while Harmonicast is open.

Rooms are for nearby guests. Native transfer needs a reachable local Wi-Fi/Ethernet
network. Internet guest control is outside scope. Active Android Auto projection
retains playback authority until disconnected. Track Radio depends on Plex support.

## Settings and music tuning

Settings has six categories: Appearance, Playback, Automatic mix, Automatic ratings,
Plex account, and About & updates. Phones open a page per category; larger displays
keep the category list beside the selected page. The mini-player remains available.

Use **Rooms** to join or host a room, share guest/display QR codes, or transfer and
take back playback. The Settings hub also links to Rooms.

**Automatic mix** sets the rated/unrated balance and preference for higher ratings.
Selection-strength changes apply to new automatic batches. **Avoid recent repeats**
defaults to **1 week**, with Off, 1 day, 3 days, 1 week, 2 weeks, and 30 days options.
Automatic tracks are excluded using the newer of Plex last-played time and local
play/skip history, including tracks already queued when they come up. Skips count
even with automatic ratings off. Explicit requests, Track Radio, and Previous remain
available. If no eligible tracks are found, the mix stops instead of relaxing the
window. Skips on other devices or in other apps are only known when Plex records them.
**Automatic ratings** requires explicit opt-in before its completion, skip, and
repeat-play controls can be adjusted. Changes are saved to Plex and can replace
existing ratings. Turning the feature off does not undo past changes. Defaults
preserve the earlier tuning. Preferences stay local to each device; during native
transfer, the controlling host's preferences apply.

These Settings changes are included in v1.1.8.

## Share the app

Use **Settings → About & updates → Share app** for a QR code linking to [harmonicast.app](https://harmonicast.app).
Phones also offer a share-link action. Browser guest pages include **Get the Android app**;
guests can continue using the browser without installing anything.

## App updates

Use **Settings → About & updates → Check for updates** to check GitHub Releases.
Optional automatic checks run on launch at most once a day; downloading is always
your choice. Harmonicast verifies the APK checksum, package, newer version code
and matching signing certificate before opening Android’s installer. If prompted,
allow Harmonicast to install apps, then return and tap **Install update**. Installing
restarts the app; same-key updates preserve settings and local data.

## Background playback

If music stops with the screen off, use **Settings → Playback → Background playback settings**
to review Android battery optimization. Uninterrupted playback under every OEM
battery policy is not guaranteed. See [validation notes](docs/ANDROID_VALIDATION.md).

## Build and release

Use Java 21 and Android SDK 35 through the checked-in wrapper helper:

```sh
./android/build-debug.sh
./scripts/release-check.sh
./android/build-release.sh
```

Set JAVA_HOME and ANDROID_HOME for another workstation. Do not use Java 25 with
this Kotlin toolchain. Signed builds require your own ignored signing.properties
and keystore; android/create-release-keystore.sh helps create them. Keep the same
signing key for upgrades. Release APKs are written to android/releases/.
Releases use vX.Y.Z tags from main.

[Privacy](PRIVACY.md) · [Releases](RELEASES.md) · [Roadmap](ROADMAP.md)

Harmonicast is not affiliated with Plex. Licensed under AGPL-3.0-or-later.
