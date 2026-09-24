# 10-band equalizer preview

Michael requested a traditional equalizer with at least seven bands after trying
the movable-point editor. The accepted replacement has ten independent sliders
and a smooth visual line connecting their positions. It does not link neighboring
bands or expose frequency/Q editing.

## Behavior and ownership

- Frequencies: 31.5, 63, 125, 250, 500 Hz; 1, 2, 4, 8, 16 kHz.
- ±12 dB in 0.5 dB steps. Default off, all gains zero; Reset to flat centers all
  bands without changing the enable switch. Bypass retains the saved adjustments.
- EqualizerSettings owns two rows of five on phones and one row on wide screens.
  Native Material slider semantics remain; TV up/down changes gain and left/right
  traverses controls, including leaving the bank. The connecting line is a guide
  through the settings, not a waveform or exact measured frequency response.
- EqualizerStore persists only gains and enable state as graphic_v1 within the
  existing device_equalizer preferences. Legacy curve data is retained for
  rollback but never applied to these fixed bands; upgrading starts flat/off.
- Settings are independent of Plex profiles, Android backup/device migration and
  room transfer. Received playback uses the receiving device's settings.
- The existing RBJ peaking-filter engine uses fixed Q 1.4 for these octave bands.
  Validation now allows ten bands throughout. Per-channel/player sample histories,
  conservative gain headroom, 25 ms edit crossfades and PCM bypass are retained.
  Playback still uses 16-bit PCM without passthrough/offload, including while
  bypassed. No new audio-format or battery-efficiency claims are made.

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

## Validation

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
