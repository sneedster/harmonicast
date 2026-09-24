# Harmonicast

Standalone Plex music playback for Android phones, tablets, TV, and Android Auto.
Plex and access to a Plex Music library are required. Android 8.0 (API 26) or later.

## Install

Download the signed APK from the [latest release](https://github.com/sneedster/harmonicast/releases/latest).
Install over the existing signed app to retain personal-mode settings and data.
Choose **Sign in with Plex**, then your server and Music library. TV setup shows
a QR code for Plex sign-in on your phone. Shared libraries support listening.
The current development build also supports shared-library room hosting and paired
native playback; Plex rating/history writes still require server owner access.

## Features

- Nocturne browsing/player UI with persistent Nocturne, Aurora, and Ember palettes.
- Albums, singles, EPs, artists, biographies, live search, and paged Plex playlists.
- Voice input through Android speech recognition and remote-friendly TV focus.
- Request-first queues, configurable automatic mix, adaptive ratings, and Track Radio.
- Android Auto library browsing, artwork grids, playback, thumbs up/down, and track ratings (controls may appear under More).
- Temporary nearby rooms, requests/votes, and bundled browser guest/display pages.
- Owner-controlled native room playback transfer and take-back.
- Optional **Stay awake while charging** while Harmonicast is open.

Rooms are for nearby guests. Native transfer needs a reachable local Wi-Fi/Ethernet
network. Internet guest control is outside scope. Active Android Auto projection
retains playback authority until disconnected. Track Radio depends on Plex support.

## Settings and music tuning

Settings has nine categories: **Appearance**, **Playback**, **Equalizer**, **Automatic mix**,
**Track Radio**, **Automatic ratings**, **Music acquisition**, **Plex account**, and **About & updates**. Phones open a page
per category; larger displays keep the category list beside the selected page.
The mini-player remains available.

### 10-band equalizer (preview)

Open **Settings → Equalizer** for ten fixed frequency bands from 31.5 Hz to
16 kHz. Move a slider up to boost that range or down to cut it, up to ±12 dB.
A smooth line connects the slider positions. Each slider adjusts independently.
**Reset to flat** centers all ten; switching EQ off keeps the saved settings.
Phone controls use two rows of five, while wide screens show one row of ten.
TV remote left/right selects a control and up/down adjusts the selected band.

Settings save on this device and apply to local or received playback. The earlier
curve-editor preview starts flat/off on upgrade. See [implementation and validation](docs/EQUALIZER.md).

### Rooms

Use **Rooms** to join or host a room, share guest/display QR codes, or transfer and
take back playback. The Settings hub also links to Rooms.

For browser guests, choose **Invite guests**. Type the short local address shown
on the host (for example, `http://192.168.1.20:8788`) and enter the room's four-letter
code. Both devices need the same Wi-Fi. No long invitation URL is required; QR
scanning and shared links remain optional. An old browser tab asks for the current
room code when its previous access expires.

For a screen without a camera, choose **Open room display** on the host. Connect
the other device to the same Wi-Fi, type the short local address shown in its
browser, and enter the four-digit display code. QR scanning remains optional.

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

Music tuning and the Settings layout were introduced in v1.1.8.

For the optional connection in **Settings → Music acquisition**, see the
[MusicGrabber integration setup guide](docs/MUSICGRABBER_SETUP.md).

## Share the app

Use **Settings → About & updates → Share app** for a QR code linking to [harmonicast.app](https://harmonicast.app).
Phones also offer a share-link action. Browser guest pages include **Get the Android app**;
guests can continue using the browser without installing anything.

## App updates

Use **Settings → About & updates → Check for updates** to check GitHub Releases.
Automatic checks run on each fresh launch by default; an existing opt-out is
preserved. Available updates offer **Update now** or **Later**, and downloading
is always your choice. You can turn automatic checks off in Settings. Harmonicast verifies the APK checksum, package, newer version code
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
