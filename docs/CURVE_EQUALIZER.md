# Curve equalizer preview (superseded)

The movable-point interface below was replaced by the [10-band equalizer](EQUALIZER.md)
after Michael found it confusing. This page records the original trial only.

Implemented at Michael's request on 2026-09-23. The feature belongs to the Android
player on each device. Room transfer selects the receiver's own EQ; it never
copies the source device's curve.

## Controls

Settings → Equalizer starts off with four flat points at 80, 350, 1500 and 6500 Hz.
Enable to hear changes; bypass retains the curve. Drag a point horizontally for
frequency and vertically for gain, or tap empty plot space to add a point. Up to
eight points are supported from 20 Hz–20 kHz, ±12 dB each. Select a point for
frequency/gain/width sliders and minus/plus buttons. Lower Q gives a broader
change (0.25–8 Q). Remove point and Reset to flat are immediate local edits.
The same controls are available without touch, including on TV.

The plotted line is the summed response of peaking biquads, not an arbitrary
interpolated spline. Handles represent each individual band's frequency/gain;
overlapping bands can make the combined response differ from a handle position.
The vertical range expands to accommodate combined boosts/cuts. The plot uses
the latest locally configured sample rate (48 kHz before playback). Frequencies
above 45% of the playing sample rate are clamped by the filter design.

## Audio and persistence

- `CurveEqualizer.kt`: double-precision RBJ peaking biquads, shared response math,
  immutable validated settings, and separate channel histories.
- `EqualizerAudioProcessor.kt`: Media3 PCM processor, independent per player.
  The processor stays active when bypassed so live toggles need no player restart.
  A 25 ms crossfade between filter banks smooths live edits; edits received during
  a fade are coalesced to the latest state at the next available input buffer.
  Flush clears history for seeks/format changes. Bypassed 16-bit samples pass
  through unchanged after transitions finish.
- Sum of positive band gains is reserved as conservative preamp headroom. This
  includes overlapping boosts and can noticeably lower volume with several
  boosted bands. Final PCM conversion saturates instead of integer wrapping;
  there is no claim that headroom eliminates every possible transient clip.
- Both HarmonicastMediaService and NativePlaybackReceiver use the same renderer
  factory. PcmEqualizerSink retains device route tracking, rejects encoded
  passthrough, and advertises no hardware offload. Float output is disabled so
  Media3 converts decoded audio to the processor's 16-bit PCM format. This also
  applies while EQ is bypassed: the trial favors immediate toggles over hardware
  offload/high-resolution output, with a possible battery/format tradeoff.
- EqualizerStore owns `device_equalizer` preferences, independent of source/profile
  resets. Values are bounded; corrupt data falls back to off. Android backup and
  device transfer exclude this preference file. No account or room API is added.

## Verification

All 297 tests in 42 classes passed with no failures, errors or skips using
`android/build-debug.sh --init-script /tmp/harmonicast-eq-tests.gradle
:app:testDebugUnitTest :app:lintDebug` (per-class JVM isolation, one fork at a time).
The initial lint stage flagged the receiver's new Media3 API usage; an explicit
AndroidX UnstableApi opt-in was added. The final
`android/build-debug.sh :app:lintDebug` rerun passed (zero errors, 39 warnings).

New checks cover filter peak/cut accuracy, processed-sine versus plotted response,
independent channels, bypass identity, extreme filters at 8–192 kHz, live updates,
flush/format changes, end-of-stream, offload/passthrough rejection, persistence and
corruption recovery. Compose checks cover touch add/drag, keyboard adjustment,
bypass/reset, width, empty/eight-point states, and actual phone/TV category access.
Existing room, playback and Android Auto tests also passed in the full suite.

Phone, wide Ember and TV screenshots were inspected under the ignored
`android/app/build/reports/equalizer/` and `settings-layout/` folders. The strict UI
static audit passed with zero findings. DESIGN.md lint has zero errors and one
pre-existing orphaned-token warning. These static checks do not replace the
Compose interaction tests or physical-device verification.

Physical listening, Bluetooth/USB output behavior, paired transfer and Android
Auto remain separate acceptance checks; none is implied by unit/UI tests.

## Signed trial package

`HARMONICAST_SKIP_RELEASE_CHECKS=1 VERSION_NAME=1.1.18-eq-preview VERSION_CODE=80
android/build-release.sh` passed after the checks above, including release vital
lint. The preview retains code 80 so a subsequent official code 81+ can update it.
This is a local trial, not a GitHub release or website publication.

APK: `android/releases/harmonicast-1.1.18-eq-preview.apk` (ignored build artifact).
SHA-256: `777a9d08f257c5cc895f2b26a4cd751ac040b46d4ef4c190ab4471f1b05436ae`.
Apksigner verified it, and its signing-certificate SHA-256 matches the existing
1.1.18 release. Installation and physical launch results are recorded below.

ADB `install -r` succeeded on the Pixel 10 Pro without clearing app data.
PackageManager confirmed `1.1.18-eq-preview` (80), and explicit activity launch
returned `Status: ok`. The device was locked: UIAutomator showed the lock screen,
so physical in-app interaction and listening were not verified. The trial is
ready at Settings → Equalizer after unlocking. No Shield installation or
physical playback transfer was performed. EQ defaults off with the flat curve.
