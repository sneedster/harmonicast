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
- Albums, singles, EPs, artists, biographies, search, and paged Plex playlists.
- Request-first queues, configurable automatic mix, adaptive ratings, and Track Radio.
- Android Auto library browsing, artwork grids, and playback.
- Temporary nearby rooms, requests/votes, and bundled browser guest/display pages.
- Owner-controlled native room playback transfer and take-back.
- Optional **Stay awake while charging** while Harmonicast is open.

Rooms are for nearby guests. Native transfer needs a reachable local Wi-Fi/Ethernet
network. Internet guest control is outside scope. Active Android Auto projection
retains playback authority until disconnected. Track Radio depends on Plex support.

## Background playback

If music stops with the screen off, use **Settings → Background playback settings**
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
