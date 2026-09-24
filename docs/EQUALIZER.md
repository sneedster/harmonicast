# 10-band equalizer preview

The current trial follows Michael's Pixel screenshot of Plexamp's EQ: all ten
fixed-frequency points share one graph, with gains above and frequencies below.
The earlier two-bank fader layout was rejected. Harmonicast uses its own active
palette, selected-band guide/halo, restrained fill and native settings controls.
The reference screenshot is local at `/tmp/plexamp-eq-reference.png`; no Plexamp
assets or preset data are bundled.

## Behavior and ownership

- Frequencies: 31.5, 63, 125, 250, 500 Hz; 1, 2, 4, 8, 16 kHz.
- ±12 dB in 0.5 dB steps. All ten points stay together at phone, narrow and TV
  widths. Drag vertically; tap a band's lane and use minus/plus for precise edits.
  Keyboard/TV left/right traverses bands and up/down changes gain. Each lane
  exposes accessible range, current value and SetProgress actions.
- The smooth line and fill are a settings guide, not the waveform or exact filter
  response. Bands remain independent. SettingsScreen owns outer scrolling;
  EqualizerSettings owns graph gestures and native Material preset/menu controls.
- Original built-in presets: Flat, Bass lift, Warm, Vocal, Bright and Lively.
  They change band gains, preserving enable state and preamp. Manual edits show
  Custom unless the gains match a preset. No headphone calibration/database claim.
- Reset to flat zeros all bands and preamp, preserving the enable switch.
  Bypass preserves all edits and bypasses both filters and preamp. Default off.
- Preamp is explicit, ±12 dB, default 0 dB. The old sum-of-positive-gains
  attenuation has been removed: it reduced the whole signal by every band's
  positive gain, so boosts could mostly make music quieter. Band edits now change
  only their filter response, without an automatic global level change. Lowering
  preamp provides manual headroom when needed; PCM conversion saturates at its
  limits, so excessive boosts can distort if preamp is not reduced.
- EqualizerStore keeps graphic_v1 gains/enable state and adds optional preampDb.
  Older ten-band settings load with 0 dB preamp. Legacy curve data remains unused
  for rollback. Corrupt records fall back off/flat; nonfinite/out-of-range preamp
  is validated. Preferences remain independent of Plex profiles, excluded from
  Android backup/device migration, and absent from room transfer messages.
- The RBJ peaking-filter engine uses fixed Q 1.4. Each player/channel has its own
  sample history. Live edits retain 25 ms crossfades. Playback uses 16-bit PCM
  without passthrough/offload even while bypassed; no new battery claims.

## Pixel pause repair

During this trial Michael reported that the Pixel's in-app Pause button did not
stop local playback. A tap left Android's media session PLAYING; Android's media
Pause key changed it to PAUSED, confirming that the audio engine could pause.
The app's Play button also did not resume it.

Inspection found that initialization waited for remote Plex endpoint discovery
before connecting its local MediaController. A failed discovery skipped that
connection entirely, while nullable transport calls silently did nothing. A
non-null disconnected controller also prevented reconnection.

Local playback now connects before discovery. PlaybackConnection shares one
in-flight connection, queues requested commands, replaces disconnected instances,
and releases obsolete/late connections after close. Toggle, previous, seek and
queued-track playback use this connection; failures show a retry notice. Local
play/pause consults connected Media3 playWhenReady, while transferred playback
retains its remote state. Existing authenticated MediaController access stays
in force; no exported transport action was added.

## Previous trial validation (eq2)

- Full debug unit suite: 303 tests across 43 classes, zero failures/errors/skips.
  Android debug lint and signed release assembly passed. Tests include independent
  touch/keyboard faders, narrow/wide layouts, storage upgrade/roundtrip, tenth-band
  DSP processing, and missing/stale/failed/late playback connections.
- Design lint: zero errors (one existing orphaned-token warning); strict UI audit:
  zero findings. Phone, narrow and wide Robolectric screenshots inspected.
- Signed `1.1.18-eq2-preview` (code 80) installed over the prior Pixel 10 Pro
  preview with data preserved. APK SHA-256:
  `6c3bb5ad25ccd020fa597bc258237c653b685dadc64d3e7c71c92fcb80d0efa2`.
- On the physical Pixel, both mini-player and full-player button taps changed the
  Android media session from PAUSED to PLAYING and back to PAUSED. The final
  paused position was 182757 ms. No system transport key was used for these
  post-install checks.
- Physical EQ check: dragging 125 Hz upward changed only that band to +6.5 dB
  and drew the smooth connecting line. Reset restored all ten gains to zero;
  scrolling exposed the complete second bank. EQ was left off/flat and playback
  paused. Screenshots remain local under `/tmp/harmonicast-eq2-*.png`.
- Physical Bluetooth, Android Auto, room transfer and listening comparisons were
  not repeated in this trial. This is a local preview, not a public release.

## Continuous graph and gain repair validation (eq3)

- Focused DSP, PCM processor, storage and Compose suites passed. A +6 dB 1 kHz
  boost was checked against actual PCM output at 44.1/48/96 kHz; 63 Hz and 8 kHz
  remain essentially unchanged. Regression checks cover preamp, bypass, all ten
  bands, channel separation, live transitions and bounded PCM conversion.
- UI checks cover vertical boost/cut, all ten bands sharing bounds on a 320 dp
  phone, keyboard traversal, presets, manual Custom state, precise minus/plus,
  reset and preamp. Phone/narrow/wide screenshots inspected.
- Full suite: 306 tests across 43 classes, zero failures/errors/skips. Signed
  release assembly and Android debug lint passed. Design lint has zero errors (one existing token
  warning); strict static UI audit has zero findings.
- Signed `1.1.18-eq3-preview` (code 80) installed on the Pixel 10 Pro with data
  preserved. APK SHA-256:
  `d028cf8ee58b402a4d10a9089f8642308f5b3214d924d764ef9c0a67966f7055`.
- Live Pixel: opened preset menu, selected Lively, observed all ten expected
  gains/points together, dragged 125 Hz from +1 to +6 dB and observed Custom,
  then reset to the original flat curve. Scrolled to verify enabled EQ and
  neutral preamp. Left playback paused and enable state unchanged (on).
  Screenshots are local under `/tmp/harmonicast-eq3-*.png`.
- Audio gain is verified digitally through real processor output, not a claimed
  listening judgment. Physical Bluetooth, Auto and room-transfer audio checks
  were not repeated. This remains an unpublished local preview.
