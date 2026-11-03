# SoulSync integration validation — November 2, 2026

Initial implementation was prepared on `codex/soulsync-acquisition`.
Stable packaging for v1.1.29 (94) is recorded below; the earlier checks describe
the pre-release implementation checkpoint.

## Implemented behavior

- AcquisitionProvider adapters encapsulate authentication, credential headers,
  connection identity, health checks, input validation, submission, and polling.
- MusicGrabber remains available. Credentials without a provider field retain
  their existing identity and resume existing requests.
- SoulSync supports personal acquisition and guest requests through the owner's
  room. Shared Plex credential publication remains MusicGrabber-only because a
  SoulSync administrator API key is not a dedicated restricted user account.
- Android and paired-browser setup offer service selection. Keys are encrypted
  on Android, masked during entry, staged before Android confirmation, and cleared
  on provider switches and setup completion.
- Uncertain POSTs are never automatically replayed. SoulSync's expiring tracking
  checks Plex before reporting an unmatched request as unconfirmed. Completion
  still requires a matching playable track in the selected Plex library, and
  queue fulfillment retains the durable request receipt.
- The private bad-file replacement action remains MusicGrabber-only.
- SoulSync sources, lossless rules, beets import/naming, and retry jobs were not
  changed by this work.

## Verification

The full debug build, unit suite, and Android lint passed with Java 21 through
`android/build-debug.sh`: **449 repository tests plus one temporary live adapter
probe, 450 total, 66 classes, zero failures/errors/skips**. Lint reported zero
errors and 43 warnings. The temporary probe authenticated and force-checked the
real LAN server using AcquisitionAccount and AcquisitionNetwork, then disconnected
its in-memory connection; it submitted no music.

The acquisition-focused gate passed 69 tests before the full suite. Coverage
includes legacy credentials, shared-account restrictions, Bearer authentication,
key identity changes, structured artist/title input, delayed Plex indexing,
tracking expiry, uncertain submissions, duplicate taps, and queue recovery.
The native settings test verifies service switching, the SoulSync key form, and
clearing an entered key when changing providers; its screenshot was inspected.

The Playwright acquisition script passed using Chromium, including existing
MusicGrabber setup and new narrow-screen SoulSync keyboard selection, missing-key
validation, show/hide, rejected-key recovery, staging, clearing credentials, and
no browser-storage persistence. Existing browser guest and display checks passed.
Both the browser error state and native SoulSync settings screenshot were inspected.
The changed setup flow's scoped strict UI audit has zero findings. The broad
repository audit still reports 23 findings in other existing UI surfaces; this
change does not resolve those unrelated findings.

The NAS-backed `android/.gradle` and `android/app/build` directories are root-owned
and inaccessible. Validation used local generated files under
`/home/mjstrong/.cache/harmonicast-soulsync/`, without changing those directories.
`build-local.gradle` there redirects build output and test working directories,
and converts generated Android test resource paths to absolute paths. This avoids
both inaccessible screenshot output and Robolectric resolving resources from the
wrong working directory.

Full validation command (the live probe source and key remain outside Git):

```bash
HARMONICAST_SOULSYNC_KEY_FILE=/home/mjstrong/Documents/Codex/2026-11-02/yo/work/soulsync-ca/private/api-key \
android/build-debug.sh \
  --project-cache-dir /home/mjstrong/.cache/harmonicast-soulsync/gradle \
  --init-script /home/mjstrong/.cache/harmonicast-soulsync/build-local.gradle \
  --init-script /home/mjstrong/.cache/harmonicast-soulsync/live-test.gradle \
  :app:testDebugUnitTest :app:lintDebug
```

Full test XML is preserved in `~/.cache/harmonicast-soulsync/full-test-results/`.
Build/lint reports are under `~/.cache/harmonicast-soulsync/build/app/reports/`;
browser captures are in `~/.cache/harmonicast-soulsync/browser-reports/`, and native
captures are in `~/.cache/harmonicast-soulsync/test-work/app/build/reports/`.

## Public HTTPS acquisition API

Michael selected public HTTPS access. The configured base URL for Harmonicast is:

**`https://geekbox.bangus-royal.ts.net:8443`**

Enter that base without `/api/v1`. Tailscale Funnel on Geekbox forwards only the
`/api/v1` path to `http://127.0.0.1:8008/api/v1`. Root and UI paths are not served;
opening the bare base URL in a browser returns 404. The public status endpoint
returns 401 without a key and 200 with the existing securely stored API key.
The same temporary Android adapter probe also passed against the public HTTPS
base after propagation, exercising real validation, save, forced health check,
and local disconnect without submitting a download.
No key was printed, embedded in the APK, or committed.

Geekbox's existing Tailscale installation is version 1.104.4. Its serve
configuration was empty before this work. The Unraid Tailscale plugin defaults
`ALLOW_FUNNEL` to false and actively clears Funnel even after the CLI reports
success. The plugin also caches that setting in its watcher. The authorized
publication therefore created `/boot/config/plugins/tailscale/tailscale.cfg` with
`ALLOW_FUNNEL="1"` and restarted the host Tailscale service to load it. The existing
container Tailscale daemons were preserved. Funnel was then configured with:

```bash
tailscale funnel --bg --https=8443 --set-path=/api/v1 http://127.0.0.1:8008/api/v1
```

The route is background/persistent in the existing Tailscale state directory.
Public TLS started working after propagation. Cloudflare DNS and Zoraxy routes
were not changed. Tailscale's upstream documentation notes that public DNS can
[take up to ten minutes](https://tailscale.com/docs/features/tailscale-funnel#dns-propagation).
A real host reboot was not tested.

Rollback: disable this route with
`tailscale funnel --https=8443 --set-path=/api/v1 off`. The original plugin
configuration file was absent, recorded by
`/boot/config/plugins/tailscale/harmonicast-soulsync-before.absent`.
To restore the prior plugin policy, remove only this task's ALLOW_FUNNEL setting
while preserving any later configuration, then apply/restart Tailscale through
its normal settings workflow. No other service or tailnet policy needs changing.

## Remaining acceptance

No new music was downloaded during this work. A real Android request through the
public API, lossless acquisition, beets import, Plex indexing, and playback on the
phone remain the final acceptance test. No Android device was connected by ADB.
Shared Plex publication of SoulSync credentials and SoulSync support for the
private replacement action remain outside this first adapter's scope.

## Stable v1.1.29 packaging — November 2, 2026

Michael requested building and publishing the updated APK. The release uses
version 1.1.29 / code 94; both checked-in version defaults were advanced.

The final release gate passed through the Java 21 wrapper, using the existing
local build redirection because the NAS build directories remain inaccessible:

```bash
android/build-debug.sh \
  --project-cache-dir /home/mjstrong/.cache/harmonicast-soulsync/gradle \
  --init-script /home/mjstrong/.cache/harmonicast-soulsync/build-local.gradle \
  :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

All **449 repository tests in 65 isolated classes** passed with zero failures,
errors, or skips. Debug lint reported zero errors and 43 existing warnings.
Debug assembly, signed release assembly, and release vital lint passed.
The MusicGrabber/SoulSync acquisition browser suite passed again, covering
pairing, keyboard service choice, key recovery/clearing, guest/display search,
requests, and room-disable behavior. The temporary live adapter probe was not
included or rerun for this packaging gate.

The signed output was copied to `android/releases/harmonicast-1.1.29.apk`.
APK metadata confirms package `io.github.sneedster.harmonicast`, version 1.1.29,
code 94, minimum API 26, target API 35. Signature verification passed and the
certificate matches the downloaded published v1.1.28 APK:
`dfc39bdbf60bed1f1fb1135b9a19a007fd194b001fd2461e8ff046415b7f13f8`.

APK SHA-256: `7074ed112768f7b11d0f91da587fea7d22875a58a3935397bd47d4bb69668308`.
A companion `harmonicast-1.1.29.apk.sha256` file is prepared for the release.
Build log: `~/.cache/harmonicast-soulsync/release-1.1.29.log`;
browser log/captures: `~/.cache/harmonicast-soulsync/release-browser-1.1.29*`.

No device installation, music download, phone playback, TV, or Android Auto test
was performed during packaging. The real acquisition-to-Plex acceptance above
remains pending.
