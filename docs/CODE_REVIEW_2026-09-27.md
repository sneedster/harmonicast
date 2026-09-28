# Harmonicast code review — 2026-09-27

Read-only review of HEAD `92f0669` (on `main`, working tree clean, v1.1.19 / version
code 81). No source file was modified. Every finding below is either verified by me
directly with a file/line citation, or explicitly marked as *reported, not
independently re-verified*.

Scope: 54 Kotlin sources (~18k lines) under
`android/app/src/main/java/io/github/sneedster/harmonicast/`, the 44 unit-test
classes, the bundled browser guest/display assets, the manifest and XML resources,
the build/release scripts, and the roadmap/release documentation claims that the
code is expected to support.

## Verification log

Commands actually run, and what they returned:

| Command | Result |
| --- | --- |
| `./android/build-debug.sh :app:testDebugUnitTest :app:lintDebug` (the `release-check.sh` gate) | **FAILED** — 329 tests, 6 failed, all `TrackSwipePageTest` / `AppNotIdleException` at `TrackSwipePageTest.kt:29` |
| same gate, second run | **FAILED identically** — 329 tests, 6 failed |
| `:app:testDebugUnitTest --tests '*TrackSwipePageTest'` | **BUILD SUCCESSFUL** — the failing class passes in isolation |
| `:app:lintDebug` | **BUILD SUCCESSFUL** — 39 warnings, 0 errors |
| full suite with test-task `maxHeapSize = '4g'` (init script, repo untouched) | **BUILD SUCCESSFUL** — 329 tests, failures=0, errors=0, in 50s instead of ~7 min |
| full suite with test-task `maxHeapSize = '2g'` | **FAILED** — 329 tests, same 6 `TrackSwipePageTest` failures in 6m47s |
| isolated OkHttp 4.12.0 experiment (`/tmp/okredirect`) | redirect does **not** carry the query-string token cross-host (see Corrections) |
| Media3 1.5.1 upstream source (`MediaSessionService`, `MediaSessionImpl`) | media-button entry path confirmed (finding 2) |

## Headline

The security engineering in this project is genuinely strong — the media-session
privacy remediation, the caller-identity policy, the guest-page hardening and the
room access guards are better than most production Android apps. The problems I
found are mostly at the *seams*: one gate that does not actually pass, one entry
point that bypasses the policy, credentials that leave the device by two routes
nobody audited, and a room-code control that has no effect.

Ordered by what I would fix first, not by how alarming the heading sounds.

---

## 1. The documented release gate does not pass at HEAD — root cause found, fix proven

**Severity: HIGH (verification integrity). Verified, reproduced twice.**

`scripts/release-check.sh` runs `:app:testDebugUnitTest :app:lintDebug`. The test
task fails reproducibly on a clean checkout:

```
329 tests completed, 6 failed
TrackSwipePageTest > shortFastFlickSkips FAILED
TrackSwipePageTest > holdingAfterFlickLosesMomentum FAILED
TrackSwipePageTest > dragTracksFingerAndOnlySkipsAfterRelease FAILED
TrackSwipePageTest > shortSlowSwipeAndCancellationReturnWithoutSkipping FAILED
TrackSwipePageTest > disabledPlayerDoesNotSkip FAILED
TrackSwipePageTest > rightSwipeGoesBack FAILED
    androidx.test.espresso.AppNotIdleException at TrackSwipePageTest.kt:29
```

This is **not a defect in `TrackSwipePage`**. The same class passes when run alone,
and the whole 329-test suite passes with failures=0 when the test JVM is given more
heap. The cause is resource starvation in the test worker:

- `android/app/build.gradle.kts` sets `testOptions.unitTests.isIncludeAndroidResources = true`
  and nothing else — no `maxHeapSize`, no `forkEvery`. The test task therefore gets
  Gradle's default **512 MB** per worker.
- `android/gradle.properties` sets `org.gradle.jvmargs=-Xmx8g`, which sizes the
  *daemon*, not test workers.
- The suite is 44 classes of which many are Robolectric `sdk = [35]` + Compose
  (which also render and PNG-dump layouts to `build/reports/`), all in one JVM.

The project already knows this: `RELEASES.md` records validation for v1.1.17,
v1.1.18 and v1.1.19 as "all N Android tests passed **with per-class isolation**".
The gate script does not implement per-class isolation or any heap sizing, so it
cannot reproduce the validation it is supposed to certify. A gate that fails
identically every run is worse than no gate: real regressions arrive looking like
the familiar six.

Fix (verified effect — the init-script form is what I ran, with the heap value swept):

```kotlin
// android/app/build.gradle.kts
testOptions {
    unitTests.isIncludeAndroidResources = true
    unitTests.all { it.maxHeapSize = "4g" }   // 4g: 329 tests, 0 failures, 50s
}
```

I measured the threshold rather than guessing: **`2g` still fails** with the identical
6 failures (6m47s), while **`4g` passes** with 0 failures in 50s. The 8–14× wall-clock
difference is itself the evidence that the original failures were GC thrash, not a
logic bug. If 4g is too heavy for a target machine, `forkEvery = 1` (per-class
isolation, which is what `RELEASES.md` says the release validations actually used) is
the alternative, at the cost of a much longer run.

Then re-run `./scripts/release-check.sh` and correct the "all 328 tests" line in
`RELEASES.md` (the suite is 329 tests at HEAD since `948fe54` added the
blank-track-id guard test).

## 2. The media-button entry point bypasses the controller policy

**Severity: HIGH for the security model, MEDIUM-HIGH in impact. Verified against Media3 1.5.1 source and the app's own code.**

`docs/security/media-session-p0-2026-09-20.md:33` states: "No `isTrusted` exception
grants commands in our policy." That is true for `onConnect`, but there is a second
entry into the same session that the policy never sees.

`HarmonicastMediaService` is exported (`AndroidManifest.xml:75`),
`onGetSession` deliberately admits the anonymous legacy placeholder
(`HarmonicastMediaService.kt:1401-1408`), and `onStartCommand` calls
`super.onStartCommand` (`HarmonicastMediaService.kt:692`). In Media3 1.5.1,
`MediaSessionService.onStartCommand` handles `ACTION_MEDIA_BUTTON` by calling
`onGetSession(createLegacyControllerInfo())` and then dispatching the key event:

```java
MediaSessionImpl sessionImpl = session.getImpl();
sessionImpl.getApplicationHandler().post(() -> {
    ControllerInfo callerInfo = sessionImpl.getMediaNotificationControllerInfo();
    ...
    if (!sessionImpl.onMediaButtonEvent(callerInfo, intent)) { ... }
});
```

Two things follow. First, `callerInfo` is *the app's own trusted notification
controller*, so `MediaControllerAccess.allowed` is satisfied by construction.
Second, the only validation inside `MediaSessionImpl.onMediaButtonEvent` is:

```java
ComponentName intentComponent = intent.getComponent();
if (... || (intentComponent != null
        && !Objects.equals(intentComponent.getPackageName(), context.getPackageName()))
    || keyEvent == null || keyEvent.getAction() != KeyEvent.ACTION_DOWN) return false;
```

The attacker sets the component on their own intent, so that check compares the
attacker's chosen value against the app's package name and passes. `Haronicast`
does not override `MediaSession.Callback.onMediaButtonEvent` (grep finds no
reference), so the default handling applies KEYCODE_MEDIA_NEXT/PREVIOUS/PLAY_PAUSE/
STOP/FF/REW to the player.

Impact is larger than "pause my music": `seekToNext → advance()`
(`HarmonicastMediaService.kt:1272-1333`) dequeues the shared queue, scrobbles, and —
when the owner has opted into automatic ratings — writes ratings to Plex
(`LocalHarmonicastCore.kt:259-269`). In a room, that desyncs every paired device.

Precondition: an installed app that can `startService` this intent (a foreground app
can; and once Harmonicast is running its media FGS the service is already alive).

Caveat worth respecting: legitimately delivered media buttons (Bluetooth headset,
Android Auto, SystemUI) travel the *same* path with the *same* callerInfo, so this
cannot be fixed by rejecting that caller. Options: override
`onMediaButtonEvent` and treat externally-started media buttons as transport-only
(never dequeue/rate), or document the exposure explicitly in the P0 note as an
accepted platform property. Either way the P0 doc's claim should be narrowed to
`onConnect`/`onBind`.

## 3. The Plex account token crosses the LAN in cleartext, inside a URL

**Severity: MEDIUM-HIGH. Verified.**

- `LocalPlexClient.kt:622-625` builds stream, artwork, scrobble and rate URLs with
  the credential **in the query string**: `...$path${separator}X-Plex-Token=...`.
- `AndroidManifest.xml:42` sets `android:usesCleartextTraffic="true"` app-wide, with
  no `networkSecurityConfig` to narrow it, and `normalizeServerUrl`
  (`LocalPlexClient.kt:650-655`) plus `PlexAccessPolicy.kt:17` accept plain `http://`
  Plex servers — which is the normal `http://192.168.x.x:32400` setup.

So on any shared Wi-Fi, a passive sniffer sees the owner's long-lived Plex account
token in the request line. This is the *same* concern as the guest-bearer-over-HTTP
issue, but the credential is far more valuable. Note the API calls on
`plex.tv` already use the `X-Plex-Token` **header** (`LocalPlexClient.kt:76-83`); the
query form exists because Media3/Coil need a self-authenticating URL for streaming
and artwork. Reasonable design, but it deserves an explicit decision:
prefer an HTTPS Plex connection when the server offers one, and consider a
short-lived per-stream token instead of the account token.

Also: the tokenised `streamUri` is persisted inside every saved song
(`SongSerialization.kt:21-28` written at `LocalHarmonicastCore.kt:124,149,234,283,352`),
which is how it reaches `local.queue`, `local.radioSeed` and the play history — and
hence finding 4.

## 4. The Plex account token is included in cloud backup and device transfer

**Severity: MEDIUM. Verified.**

- `AndroidManifest.xml:34` `android:allowBackup="true"`.
- `res/xml/backup_rules.xml` excludes exactly one file — `device_equalizer.xml`.
- `res/xml/data_extraction_rules.xml` repeats that single exclusion for both
  `cloud-backup` and `device-transfer`.
- The credential store is the `harmonicast` prefs file, plaintext
  (`Model.kt:39-48`, keys at `AppProfile.kt:54,60`: `home.plex.token`,
  `home.plex.accountToken`).

The owner's account token is therefore uploaded to Google Drive backup and travels
with device transfer. The contrast is instructive: MusicGrabber secrets are already
sealed with an AndroidKeyStore key (`Acquisition.kt:88-106`) — the Plex token is the
outlier, so the fix is to bring it up to the same standard or exclude the prefs file:

```xml
<exclude domain="sharedpref" path="harmonicast.xml" />
```

## 5. The room code gate has no effect: the server hands the code to any LAN GET

**Severity: MEDIUM. Verified.**

`GuestRoomGateway.kt:620-634` serves the entry pages with the live room code
rendered into the HTML, with **no authentication**:

```kotlin
if (first[0] == "GET" && uri.path in setOf("", "/", "/join", "/display", "/open", "/enter")) {
    ...
    GuestWebPage.render(template, capability?.roomCode.orEmpty()),
```

`POST /v1/guest/open` (`GuestRoomGateway.kt:636-646`) then exchanges that code for a
real 256-bit guest bearer. So any device on the same network needs two requests —
`GET /`, then `POST /v1/guest/open` — to obtain full guest rights: library-wide
search and browse, queue requests, votes, and acquisition submission when the owner
has enabled room acquisition. The advertised "four-letter room code" and its 5/min
exchange limiter are bypassed entirely.

Everything downstream of the bearer is well built (the router authenticates before
every handler and re-checks access; `MessageDigest.isEqual` constant-time compare at
`GuestRoomGateway.kt:198-205`; display/guest operations separated). The problem is
only that the bootstrap secret is published. Either stop rendering the live code on
unauthenticated `GET /`, or state plainly that "on the same Wi-Fi" *is* the trust
boundary and drop the pretense of a code.

## 6. BLE proximity is the only gate, and `offer-player` can redirect playback

**Mechanism verified by me; the `offer-player` consequence is reported, not re-verified.**

`GuestRoomGateway.kt:553-557` injects the room bearer into BLE-originated requests
("BLE is itself the proximity bootstrap"), and the command characteristic is created
with `PROPERTY_WRITE` / `PERMISSION_WRITE` only (`NearbyRoomBluetooth.kt:428-429`) —
no encrypted/MITM requirement, so any BLE central in range can issue guest commands
with no code and no owner confirmation. In addition, each new BLE address yields a
fresh participant id, which defeats the per-participant request limit.

Reported and consistent with the code above: an unauthenticated `offer-player`
command lets a nearby peer register an arbitrary LAN address as a playback receiver
under an attacker-chosen display name, so if the owner taps the resulting
"Play on &lt;name&gt;" button (`RoomsScreen.kt:89`), the phone streams its own audio
to the attacker's endpoint (`NativePlaybackHost.kt:78-80,159-173`). Worth confirming
on a device, then requiring explicit owner confirmation before a BLE-offered player
becomes selectable.

## 7. Unbounded request line in the room gateway

**Severity: MEDIUM-LOW. Verified.**

`GuestRoomGateway.kt:595` reads the request line with no length cap:

```kotlin
val first = reader.readLine()?.split(' ') ?: return
```

The 8 KB guard at `:602` applies only to *subsequent* header lines, and the
`soTimeout = 10_000` at `:593` is per-read — a client that keeps sending bytes is
never timed out while the line buffer grows. The worker pool is bounded (4 threads),
so a handful of connections is enough to exhaust the app process while a room is
open. Cap the request line the same way the headers are capped.

Related, same handler: `socket.accept()` is wrapped as
`runCatching { socket.accept() }.getOrNull() ?: break` (`:526`), so any non-close
`IOException` permanently exits the accept loop while `running` stays true — the UI
can report an active room with a dead listener.

## 8. Blocking disk writes and whole-queue JSON parsing on the UI thread

**Severity: MEDIUM (jank/ANR risk). Verified.**

`Model.kt:46` uses a synchronous commit:

```kotlin
check(editor.commit()) { "Could not save home profile" }
```

That path is reached from the UI thread: `MainActivity.kt:801-806` defines
`coreAction` as a plain `viewModelScope.launch` (which dispatches on
`Dispatchers.Main.immediate`), and queue mutations go through it. Every add/remove
therefore blocks the main thread on a disk write. Reported alongside it: a 5-second
`refresh()` poll re-reads and re-parses the entire queue JSON on the main thread
(`MainActivity.kt:374,876-881` → `LocalHarmonicastCore.kt:337-349`), so cost grows
with queue length. `apply()` (or an IO-dispatched write) would remove the blocking
write; the poll wants to move off the main thread or be diffed.

## 9. `advance()` publishes "playing" before the item can fail

**Severity: MEDIUM. Verified by reading the order.**

```
1310  core.playback.publish(song, isPlaying = true, isAutoQueue = isAuto)
1311  core.playback.scrobble(song.id, submission = false)
1317  player.setMediaItem(createMediaItem(song), 0)
...
1327  } catch (e: Exception) { Log.e("HarmonicastMedia", "advance failed ...", e) }
```

`createMediaItem` (`:1397-1399`) evaluates `core.library.streamUrl(song)`, which
throws when that song has no fresh `streamUri` (`LocalHarmonicastCore.kt:49-50`). The
throw lands in the catch at `:1327`, which only logs: the queue head is consumed and
the shared now-playing state already says the track is playing, while the local
player never received it. Resolve the media item *before* publishing state.

## 10. `onGetItem` cannot resolve non-current media IDs

**Severity: MEDIUM. Reported, not independently re-verified.**

Reported at `HarmonicastMediaService.kt:452-461`: for any ID other than the current
track it fabricates `Song(mediaId, mediaId, "", "", 0, "")` with no stream URL and
returns the same throwing path as finding 9, surfacing as
`LibraryResult.ofError(ERROR_UNKNOWN)`. The P0 evidence covers the DHU
"select from queue" path (`onAddMediaItems`), not `onGetItem`, so this may be an
untested hole. Worth a DHU `GET_ITEM` check.

## 11. Playback failures are invisible to the user

**Severity: LOW-MEDIUM (hardening). Partially verified.**

The error paths log and nothing else — e.g. `:1328` above, and the reported
`PlaybackException` listener at `:139-146`. There is no classification of
401/403/404/5xx, no token refresh, no automatic skip after repeated failure, and no
message in the UI. A user whose token expired sees a track that never starts. A
"next" pressed while `advance()` is awaiting a slow Plex dequeue is silently dropped
by the `changingTrack` guard (`:1274`).

## 12. The roadmap and Project Memory overstate acquisition verification

**Severity: LOW-MEDIUM (documentation precision + robustness). Verified.**

`ROADMAP.md:13-14` says "Acquired tracks are queued only after verification in the
selected Plex library", and the same wording is stored as a curated claim in
Project Memory. What the code enforces is a heuristic match
(`Acquisition.kt:426-431,488-495`):

```kotlin
return a.isNotBlank() && b.isNotBlank() && normalize(song.title) == normalize(recording.title) &&
    (a == b || a.contains(b) || b.contains(a)) &&
    (song.duration == 0 || recording.durationMs == 0 || abs(...) <= 30_000)
```

Title must match exactly after normalisation (good), but the artist test is
substring matching, and the duration check is **skipped entirely** whenever either
side reports 0 — which happens when Plex omits `length`. There is no content hash,
format or size check. So a match can be satisfied by a pre-existing different copy.
"Verified" should be softened to "matched against the Plex library by
artist/title/duration", and the guard should require duration when both sides know it.

## 13. Repository and process hygiene

**Severity: LOW. Verified.**

- **Orphaned build trees.** A 142 MB root `node_modules/` with no `package.json`, a
  59 MB `server/node_modules/` with no server sources, and empty `src/`, plus a
  `dist/`. These are untracked and gitignored, so there is no repo risk — but they
  are leftovers from a different project (a Vite/Express app named "Resonance") and
  they make "is this the Harmonicast tree?" ambiguous.
- **A foreign secret in the project root.** The root `.env` configures *Resonance*,
  not Harmonicast, and contains a live-looking `PLEX_SERVER_TOKEN`, `PLEX_SERVER_URL`
  and `ADMIN_EMAIL`. It is gitignored, so it was never committed — but a plaintext
  Plex token sitting in this directory is worth a deliberate decision: delete it, or
  rotate it if Resonance is retired.
- **No "Pick up here" section.** The project workflow (persona spec and roadmap
  instructions) depends on a "Pick up here" section of `README.md`; no such section
  exists anywhere in the repo (`grep -rn 'Pick up here'` returns nothing). Either
  add it or drop the instruction, because as written the handoff step cannot be
  followed.
- **Hardcoded host paths in a tracked script.** `android/build-debug.sh` defaults
  `JAVA_HOME` and `ANDROID_HOME` to absolute `/home/mjstrong/...` paths. They are
  overridable, so this is a nit, but a fallback to `android/local.properties`
  (`sdk.dir`) would be cleaner for another workstation.

---

## Verified good — do not re-audit

- **`MediaControllerAccess`** (`MediaControllerAccess.kt:22-48`): UID-to-package
  ownership via `getPackagesForUid`, own-package UID equality, Android Auto pinned to
  two production certificate fingerprints, system controllers limited to
  `android`/`systemui`/`bluetooth` with `FLAG_SYSTEM` plus signature or
  `MEDIA_CONTENT_CONTROL`, and `getOrDefault(false)` so any exception fails closed. I
  read this file in full; it is the strongest part of the codebase.
- **Session metadata redaction:** the P0 fix is present and matches its document
  (`SessionMediaItems.kt`, `MediaSessionPrivacyTest`). The artwork handle design is
  sound — opaque SHA-256 keys, exact-match lookup, one path segment, `query()` null,
  mutations throwing (`MediaArtworkProvider.kt:24,37-42,62-65`); no traversal, no
  arbitrary fetch, no upstream URL exposed.
- **Guest asset security:** I re-ran the sink grep myself — zero
  `innerHTML`/`document.write`/`eval`/`new Function`/`insertAdjacentHTML` anywhere in
  `android/app/src/main/assets/`. Every response carries
  `Cache-Control: no-store`, `Referrer-Policy: no-referrer`,
  `X-Content-Type-Options: nosniff`, a CSP and `Connection: close`
  (`GuestRoomGateway.kt:707`). One note: that CSP includes `script-src 'self'
  'unsafe-inline'`, which removes CSP's XSS backstop — acceptable only as long as the
  templates stay static and untrusted data continues to reach the DOM via
  `textContent`.
- **Room router authorization:** every routed request authenticates a bearer or
  display capability before any handler, re-checks access, and separates guest from
  display operations (`GuestRoomGateway.kt:260-275`), with constant-time comparison.
- **Transport hardening elsewhere:** no custom `TrustManager`, hostname verifier or
  `ConnectionSpec` anywhere; MusicGrabber/plex.tv traffic pins
  `followRedirects(false)` + timeouts + a 1 MiB response cap; MusicGrabber secrets are
  AndroidKeyStore-sealed and models override `toString()` to redact; pagination loops
  are bounded and MBIDs/rating keys are regex-validated before path interpolation.
- **Build and release metadata is consistent:** `versionCode 81` /
  `versionName 1.1.19` matches `RELEASES.md`, and `android/releases/` holds the
  matching APK and SHA-256. Lint is clean (39 warnings, 0 errors) and stable with the
  count recorded in the P0 note.
- **Missing `POST_NOTIFICATIONS` is not a bug.** Media-session notifications are
  explicitly exempt from the Android 13+ notification permission ([Android docs,
  Exemptions → Media sessions](https://developer.android.com/about/versions/13/changes/notification-permission)),
  so the absence is correct at `targetSdk 35`.

## Corrections to claims I could not confirm

Two claims raised during this review did not survive checking, and are recorded so
they are not re-investigated later:

1. **"A Plex redirect leaks the query-string token to the redirect target."** I
   built an isolated harness with the project's exact dependency
   (OkHttp 4.12.0, default `OkHttpClient()`, a 302 from a fake Plex server to a
   second local server). Result: the redirect target received `/steal` with **no**
   query string and no token, and an absolute cross-host `Location` is resolved as
   written. Redirects *are* followed for non-`DELETE` methods
   (`LocalPlexClient.kt:35,50-51`), so the token-in-URL exposure in finding 3 is real
   — but a hostile server cannot extract the token simply by redirecting.
2. **"Missing `POST_NOTIFICATIONS` breaks the media notification."** Incorrect; see
   above.

## Open items that need a device

These cannot be settled by reading code, and I have no device access:

1. Finding 2 end-to-end: an APK that calls `startService(ACTION_MEDIA_BUTTON)` with a
   `KeyEvent` on the exported service from a foreground third-party app, confirming
   both that it works and that a callback-level fix does not break Bluetooth headset
   buttons or Android Auto.
2. Finding 6: real-device BLE friction (bonding prompt?) and the `offer-player`
   sequence against a fake receiver.
3. Finding 4: whether Auto Backup actually uploads the `harmonicast` prefs on this
   build (the rules read as inclusive, but a `bmgr`/restore run would prove it).
4. Finding 8: quantify the main-thread poll with StrictMode rather than by estimate.
5. Reported UI items not re-verified by me: a stale debounced search writing results
   while the Plex detail page is open (`MainActivity.kt:629-641`);
   `BigScreenHome` (`MainActivity.kt:1422-1460`) not wrapped in `TvFocusPage`, so TV
   focus restoration after backgrounding is unpinned; `EqualizerSettings.kt:69,72`
   indexing `points[selected]` where sibling paths use `getOrElse`;
   `NocturnePlaylists.kt:99,122` appending pages with no dedupe; and
   `NocturnePlayer.kt:146` labelling only the first rating star.

## Suggested next actions

1. Fix the gate (finding 1) — smallest change, and everything else depends on
   trusting the gate again.
2. Decide the Plex credential posture (findings 3 and 4): exclude the prefs file from
   backup and prefer HTTPS for the Plex connection.
3. Fix the media-button entry (finding 2) or narrow the P0 document's claim, with a
   device check for headset/Auto regressions.
4. Correct the acquisition-verification wording in `ROADMAP.md` and Project Memory
   (finding 12), and tighten `acquisitionMatches`.
5. Decide the room trust boundary explicitly (findings 5 and 6) — publish the code or
   publish the intent, but not both.
6. Cheap hardening: cap the request line (finding 7), reorder `advance()`
   (finding 9), move the writes off the UI thread (finding 8).

No finding in this review was marked as accepted on Michael's behalf; items above are
proposals, and the device-dependent ones are explicitly unverified.
