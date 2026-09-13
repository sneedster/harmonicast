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
**Advanced settings**. This manual connection belongs to the Plex owner.
Owners can use **Set up shared access** to publish a
separate non-admin connection for approved Plex recipients. Shared-library room
hosting does not require acquisition access.

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


## Shared Plex access development

The current development build verifies Plex server/library access before opening a
room and periodically while hosting. Confirmed access loss closes the room;
transient network failures do not authorize a new room. Shared sources retain their
existing Plex write restrictions. The development build also includes reviewed
publication of a dedicated non-admin MusicGrabber account and recipient discovery.
Live shared-account download and device acceptance remain pending.

Owner setup provides a [portable ZIP](docs/shared-plex-setup/README.txt) through a
five-minute computer download page. Extract it into a dedicated folder readable
by Plex, prepare the library, then connect and review the dedicated account in
Settings. No MusicGrabber source changes or server script are needed. Remote
recipients need a reachable HTTPS MusicGrabber endpoint; Harmonicast does not
configure the reverse proxy or Funnel.

Recipients select the shared music library and refresh shared access in Settings.
Plex access is rechecked before new requests. Removing a share blocks new app
requests after verification, but recipients can copy the published password;
rotate that dedicated password to revoke copied credentials.

To prepare the required cross-account experiment, use the
[Plex sharing proof kit](docs/plex-sharing-test-kit/README.md). It includes synthetic
silent media, dummy-record generation, a read-only evidence probe, and the manual
acceptance checklist. No real MusicGrabber credentials are needed for that proof.
