# P0: media-session credential disclosure remediation

Verified on September 20, 2026 against a physical Pixel 10 Pro, Android 17, and the real Android Auto 17.6.663464 release app using Desktop Head Unit 2.0. The installed, production-signed device-test APK is `1.1.14-p0-media-privacy` (version code 76), SHA-256:

`52d373ccf954429868fd7f8eef8f8c5fdf86760059cabad5fd7acf3af2883c50`

The installed APK hash matched the built APK. This is a local device-test build, not a published release. Plex authorization was not rotated or revoked. No other review punch-list work is included.

## Changes

- `MediaControllerAccess` validates the package against the Binder-derived UID before granting access. Both `onGetSession` and `onConnect` enforce the policy. Android Auto playback-authority handling uses the same validation.
- `SessionMediaItems` builds track items for playback, queue, search, item lookup, playlists and resumption. It removes `RequestMetadata.mediaUri` entirely. Authenticated stream URLs exist only in the local playback configuration.
- Track and album artwork use `content://io.github.sneedster.harmonicast.media-artwork/<opaque SHA-256 handle>`. `MediaArtworkProvider` privately resolves registered handles, loads bounded-size artwork through Coil, and re-encodes JPEG pixels into a pipe. The public URI never contains the upstream address, query, or token. It does not redirect clients to Plex or return original file metadata, upstream errors, or response headers.
- The artwork provider only supports reads of registered, exact handles; queries return no data and mutations are unsupported. Its source registry is process-local, bounded to 512 entries, and never enumerable over the provider. Image requests have a ten-second timeout and request 256-pixel artwork. Old handles can expire on process death or registry eviction; fresh browse/current-item results register them again. Artwork remains asynchronous and does not delay playback or browsing.
- Public text/cover-art extras omit values containing the credential marker; invalid IDs fail closed. Playlist actions are rebuilt instead of returning caller-supplied metadata/extras unchanged.

## Identity assumptions and special handling

| Caller | Required identity |
| --- | --- |
| HarmoniCast UI and its Media3 notification controller | Exact application package and own process UID; package ownership checked through PackageManager |
| Android Auto | Exact `com.google.android.projection.gearhead` package, matching calling UID, and a Google production signing certificate from the allowlist |
| Framework, System UI, Bluetooth media controls | Exact `android`, `com.android.systemui`, or `com.android.bluetooth` package, matching UID, system-image application flag, and platform signature or privileged `MEDIA_CONTENT_CONTROL` permission |
| Other apps, including notification listeners | No privileged session/library access |

The Android Auto certificate pins are the two production SHA-256 fingerprints published in [Android's UAMP sample](https://github.com/android/uamp/blob/main/common/src/main/res/xml/allowed_media_browser_callers.xml). Development/simulator keys and other Google apps are deliberately excluded. Android's verified signing-certificate history permits legitimate signing-key rotation. The installed Auto APK's current certificate matched the rotated production pin `1ca8dcc0bed3cbd872d2cb791200c0292ca9975768a82d676b8b424fb65b5295`.

This trusts Android's PackageManager, Binder caller UID, verified signer lineage, and system-image/privileged-permission enforcement. A compromised OS or compromised allowed signer is outside this boundary. Package names, service discoverability, connection hints, `isTrusted`, and Media3's Auto-detection helpers alone do not authorize access. Unknown/negative controller UIDs fail closed.

Media3 1.5.1 has two important compatibility details, verified against its cached source before implementation:

1. Legacy `MediaBrowserService` binding calls `onGetSession` with an anonymous legacy placeholder (negative UID and legacy controller version). That placeholder is allowed **only to select the binder**. The real caller must then pass `onConnect`; the foreign legacy-browser test confirms it cannot get a root. This is required for Android Auto.
2. A trusted controller connecting directly with a session token can survive `onConnect` rejection in Media3 with **empty player/session commands**. The service-token route is rejected earlier by `onGetSession`. No `isTrusted` exception grants commands in our policy. Metadata sanitization is independent, including for platform media metadata observed by OS-authorized listeners.

Live accepted identities were HarmoniCast (`uid=10444`, including its notification controller) and Android Auto (`uid=10131`). No additional third-party package was added. The anonymous legacy lookup was the only special connection handling needed for Auto. The named OS controls remain intentionally supported; individual OEM/older-Android Bluetooth variants were not separately exercised.

## Why playback URLs can remain local

In the exact Media3 1.5.1 source, `MediaItem.toBundle()` excludes `localConfiguration`. Library results and timeline/current-item session transport use that representation. `LegacyConversions` uses `requestMetadata.mediaUri`, not `localConfiguration.uri`, for the external media URI. Keeping the authenticated local URI therefore preserves ExoPlayer and native playback-transfer input without publishing it. The new regression test checks this serialization contract explicitly. Do not switch any outbound path to `toBundleIncludeLocalConfiguration` during a future Media3 upgrade.

## Verification

Only **one new regression test** was added: `MediaSessionPrivacyTest.foreignIdentityCannotAuthorizeAndSerializedItemsContainNoPlexCredentials`. It covers caller/UID mismatch rejection, own-app acceptance, credential-free artwork/request metadata, nested serialized values/extras, poisoned text fields, and exclusion of the authenticated local stream URL. It passed alongside four existing `AutoTrackRatingTest` cases: **5 tests, 0 failures**.

Build/analysis commands:

```sh
./android/build-debug.sh :app:assembleRelease :app:testDebugUnitTest \
  --tests '*MediaSessionPrivacyTest' --tests '*AutoTrackRatingTest' \
  -PversionName=1.1.14-p0-media-privacy
./android/build-debug.sh :app:lintDebug -PversionName=1.1.14-p0-media-privacy
```

Debug assembly, signed release assembly, release vital lint, and debug lint succeeded. Debug lint reports 39 warnings, including the deliberately exported read-only artwork provider and the exported, callback-authenticated media service; warnings were reviewed, not described as zero-warning lint.

### Foreign-client result

The original standalone Media3 1.5.1 probe (`org.example.foreignprobe`, separate UID 10410, independently signed, no requested permissions) was reinstalled against the fix. Android reported signature mismatch (-3). Both controller and browser connections failed. An extended run of the same isolated probe also checked the platform legacy browser and package-name spoofing:

```text
SPOOF io.github.sneedster.harmonicast REJECTED=true
SPOOF com.google.android.projection.gearhead REJECTED=true
SPOOF com.android.systemui REJECTED=true
CONTROLLER FAILED=java.util.concurrent.ExecutionException
BROWSER FAILED=java.util.concurrent.ExecutionException
LEGACY REJECTED=true
```

Service logs independently showed UID 10410 rejected for every claimed package. The probe received no privileged session, root, queue, current item, or timeline.

### Authorized metadata and playback inspection

A separate, temporary instrumentation harness ran under the legitimate application identity, without changing production policy. It inspected the exact serialized Media3 item representation and actual platform legacy-browser descriptions, including IDs, URI fields, text, extras, current metadata, timeline, request queue, and album browse results. It also read and decoded three content-provider images and scanned the image bytes.

```text
AUTHORIZED CONNECTED
LEGACY AUTHORIZED CONNECTED
LEGACY items=23
ARTWORK decoded=true bytes=19818
ARTWORK decoded=true bytes=16053
ARTWORK decoded=true bytes=23406
PLAYBACK state=3 playing=true positionAdvance=3005 error=none
SUMMARY items=67 decodedImages=3 credentialMarkers=0
```

An initial harness build bundled its own Media3 resources and caused a notification resource-ID collision. The harness was corrected to compile against, but not package, Media3; the successful run above used the installed app's classes/resources. No production workaround was introduced.

### Real Android Auto / phone checks

- Android Auto connected twice with its verified production identity and projection became active.
- DHU displayed current-track art, request-queue art, and the recently-added album grid through the new provider.
- DHU pause/resume changed the platform session to PAUSED/PLAYING as expected.
- Selecting “Best Thing in Town” from the request queue started that track and rendered its cover; this exercises ID-based resolution without requestMetadata.mediaUri.
- Phone UI continued to show library and current-track artwork after projection ended.
- Auto initially had a stale head-unit connection and then a settings-window focus ANR. Restarting the Auto head-unit session and returning the phone to Home before projection resolved it. Subsequent browse/transport/artwork checks completed; no HarmoniCast production change was needed for that harness/environment issue.

Evidence: [Auto queue](media-session-p0-2026-09-20/auto-queue.png), [selected track](media-session-p0-2026-09-20/auto-player.png), [album grid](media-session-p0-2026-09-20/auto-albums.png), [phone artwork](media-session-p0-2026-09-20/phone-artwork.png). This was digital/device/DHU verification, not a physical-car or human listening test.

The verification clients remain isolated outside the repository at `/tmp/harmonicast-media-probe` and `/tmp/harmonicast-session-audit`, with logs and source there. Both verification APKs were uninstalled afterward. The fixed signed HarmoniCast build remains installed. Authorization credentials were neither displayed nor rotated/revoked.
