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
- Optional MusicGrabber integration to acquire missing tracks and queue them once available in Plex.
- Android Auto library browsing, artwork grids, and playback.
- Temporary nearby rooms, requests/votes, and bundled browser guest/display pages.
- Owner-controlled native room playback transfer and take-back.
- Optional **Stay awake while charging** while Harmonicast is open.

Rooms are for nearby guests. Native transfer needs a reachable local Wi-Fi/Ethernet
network. Internet guest control is outside scope. Active Android Auto projection
retains playback authority until disconnected. Track Radio depends on Plex support.

## Settings and music tuning

Settings has seven categories: **Appearance**, **Playback**, **Automatic mix**,
**Automatic ratings**, **Music acquisition**, **Plex account**, and **About & updates**. Phones open a page
per category; larger displays keep the category list beside the selected page.
The mini-player remains available.

### Rooms

Use **Rooms** to join or host a room, share guest/display QR codes, or transfer and
take back playback. The Settings hub also links to Rooms.

### Automatic mix

**Automatic mix** sets the rated/unrated balance and preference for higher ratings.
Selection-strength changes apply to new automatic batches.

**Avoid recent repeats** defaults to **1 week**. Available options are Off, 1 day,
3 days, 1 week, 2 weeks, and 30 days.

- **Play and skip history:** Automatic tracks are excluded using the newer of Plex
  last-played time and local play/skip history, including tracks already queued
  when they come up. Skips count even with automatic ratings off.
- **Explicit selections:** Explicit requests, Track Radio, and Previous remain available.
- **No eligible tracks:** The mix stops instead of relaxing the repeat-avoidance window.
- **Other devices and apps:** Skips are only known when Plex records them.

### Automatic ratings

**Automatic ratings** requires explicit opt-in before its completion, skip, and
repeat-play controls can be adjusted.

- Changes are saved to Plex and can replace existing ratings.
- Turning the feature off does not undo past changes.
- Defaults preserve the earlier tuning.

### Device preferences

Preferences stay local to each device. During native transfer, the controlling
host's preferences apply.

Music tuning and the Settings layout were introduced in v1.1.8; Music acquisition
was added in v1.1.9.

## Optional MusicGrabber integration

[MusicGrabber](https://gitlab.com/g33kphr33k/musicgrabber) is a separate, self-hosted
service that Harmonicast can use to acquire music missing from your library.
It is optional; Plex remains the playback source. Requested tracks enter normal
queue order only after Harmonicast verifies them in the selected Plex Music library.

To install a server, follow MusicGrabber's
[Quick Start](https://gitlab.com/g33kphr33k/musicgrabber/-/blob/main/README.md#quick-start).
Configure it to save music into your Plex library and follow its
[Plex auto-rescan instructions](https://gitlab.com/g33kphr33k/musicgrabber/-/blob/main/README.md#plex-auto-rescan)
so new tracks become available. For server installation, configuration, and
troubleshooting, use the [MusicGrabber documentation](https://gitlab.com/g33kphr33k/musicgrabber/-/blob/main/README.md)
and project support resources. Harmonicast support covers the app's integration.

Once your server is running, open **Settings → Music acquisition**, enter its
reachable URL and your MusicGrabber username/password, then tap **Connect**.
You can also use **Set up from another device** to enter credentials on a computer
and confirm the connection on Android. API-key connections are available under
**Advanced settings**. Acquisition requires owner access to the selected Plex
library; shared read-only libraries support listening only.

Acquisition starts disabled in each room. The host can enable **Allow music acquisition**
for guest requests using the host device's MusicGrabber connection. Accepted requests
continue even after the room closes.

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
