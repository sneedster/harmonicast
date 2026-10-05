# Listening features — v1.1.21

- **Settings > Automatic mix:** built-in and named mix presets; forgotten favorites
  (rating at least 7, known play at least 90 days ago), underplayed tracks (1–3 Plex
  plays), and never played (no recorded Plex play or retained local play).
  Rediscovery queries inspect at most four 100-track pages; an empty result means
  no eligible candidates in that bounded search, not proof about the entire library.
  Selection presets never enable automatic Plex rating changes.
- **Home > Rediscover your collection:** start a rediscovery mix while preserving
  manual requests. Settings mode changes affect the next automatic batch.
- **Home > Downloads:** explicit album, complete playlist, or current-track audio
  downloads. Wi-Fi-only defaults on; unmetered Ethernet also qualifies. Downloads
  use persistent background jobs, bounded retry, per-track progress and cancellation.
  At most 500 tracks per submitted batch, 256 MB per track, and 5 GB total. Audio
  and selected Plex covers remain private and excluded from backups. Covers use
  bounded JPEG copies, with embedded file artwork as a fallback. Existing audio-only
  downloads gain covers while connected without another audio download, respecting
  their Wi-Fi preference. Missing covers are retried at most once every six hours;
  an artwork failure never removes playable audio. Account
  and library changes hide other scopes; sign-out deletes all saved audio and covers. Account
  token rotation creates a new scope. Play downloads uses a finite local queue.
  Offline play/skip timestamps remain local; Plex scrobbles and rating writes are
  skipped during this queue and are not replayed automatically later.
- **Downloads > Queue cache:** enabled by default with a 12-track window (current
  plus next 11) and mobile data allowed, as requested for driving. Window choices
  are 5, 12, 25 or 50; mobile data can be disabled separately from saved downloads.
  Completed cache files rotate out as the queue advances, with a 512 MB cache cap
  inside the 5 GB total limit. Saving an already cached track promotes it without
  another download. Cache pruning never deletes saved downloads. Normal automatic
  mixes maintain an upcoming tail while connected; manual requests retain their
  order and Track Radio retains its separate continuation. Cached queue entries use
  metadata captured during preloading and still enforce the locally known replay
  window. Local file playback does not wait for rating writes over a slow link.
  Connection recovery restarts queue top-up; interrupted jobs retry via WorkManager.
  A stream that fails after a complete cached file arrives retries from that file
  at the same playback position. Offline coverage lasts only as far as complete
  cached/saved files, and storage or Plex failures can leave a partial window.
- **Settings > Equalizer:** named device-local sound profiles include all ten band
  gains, preamp, and enabled state. Output-device selection is manual.
- **TV Settings > Appearance:** audio-reactive plasma, starfield and rotating
  wireframe scenes, with optional cyan/magenta neon. Off by default. Pause and
  Android reduced motion stop animation. The same PCM processor feeds the visualizer
  in normal and received playback; no microphone permission is needed for visualization.
- **Now Playing > More listening actions > Take me somewhere different:** up to three tracks from a wider
  sonic neighborhood, excluding the nearest 100 and queue/session duplicates.
  Manual requests stay first; ordinary radio then resumes from the original seed.
  No matches reports a recoverable empty result and leaves the queue unchanged.
- **Now Playing > Track Radio:** starts radio from the current song. The redundant
  Home panel was removed. Automatic mix and the three rediscovery modes use
  matching square Home cards.
- **Home artwork:** each mix card samples up to four distinct covers from eligible
  candidates. Artwork previews do not change playback or ratings. The gradient and
  icon remain usable offline or when no eligible cover is found. Rediscovery artwork
  uses the same strict play-count/date bounds as the mixes and a bounded four-page
  search; album or artist thumbnails fill in when a track thumbnail is absent.
  If Forgotten favorites has no eligible covers yet, its artwork samples the
  highest-rated favorites; this visual fallback does not broaden playback rules.
- **Home > Recently played:** individual track titles and artists, ordered by play
  history, with album artwork. Local listening to downloaded tracks is included.

## Validation

Automated coverage exercises preset persistence and consent isolation, rediscovery
history boundaries, offline metadata isolation and playback without Plex, queue
cache rotation/promotion/cancellation/network constraints/budgets/refill, sound
profile restoration, audio-band analysis, detour continuity, and phone/TV Compose
screens. Native screenshots are under android/app/build/listening-features-screenshots.
The complete Android unit/Compose suite passed on 2026-10-03: 354 tests after the
Home artwork/track-history and rolling-cache follow-ups, with no failures or skips. The Settings fixture
explicitly resets the phone/TV feature flag before each screen. Some subsequent
combined Settings runs still hit an intermittent initial-composition idle timeout;
focused navigation/layout checks and the live phone screens passed.
On the Pixel 10 Pro, the signed preview upgraded v1.1.20 without clearing data.
Live checks covered saving/applying/removing mix and sound presets, an underplayed
mix, a three-track sonic detour, a completed 20 MB download from the configured Plex
server, and cold-start playback with Wi-Fi and mobile data disabled. A rolling cache
of 12 completed tracks (374 MB) retained the separate saved track; with Android
reporting no active default network, cold-start playback and Next to another cached
queue track both advanced without a player error. Media3 reported
PLAYING with advancing position and no player error; sound was not independently
audited. Network settings and the original EQ settings were restored, and temporary
test presets were removed.
Long-running background scheduling, Android Auto, and physical TV remote behavior
and frame pacing still need device acceptance.

The design token lint reports no errors and one orphan-token warning. The generic
premium UI audit reports 23 findings in unchanged browser/marketing files; it does
not validate native Compose screens. Native layout verification uses the Android
Compose tests and screenshots instead.

Plex sonic nearest requests follow https://developer.plex.tv/pms/. Background
scheduling follows Android WorkManager; completed files play through existing Media3.
