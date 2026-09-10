# Settings redesign and configurable music tuning

Agreed plan, 2026-09-09. Implementation is complete in the v1.1.8 candidate.
Pixel acceptance and publication remain pending; see ANDROID_VALIDATION.md.
The owner subsequently requested no TV device testing, superseding the Shield
acceptance steps below.

## Summary

Replace the long Settings page with six focused categories, using a category hub
on phones and a two-pane layout on tablets and TV. Give Rooms its own destination,
accessible through the existing Rooms shortcut and a link from Settings.

Integrate configurable rating and selection weights. Preserve existing settings,
Nocturne/Aurora/Ember palettes, and playback behavior by default. Deliver the
combined change through phone and Shield acceptance testing and a public release.

## Settings structure and design

Categories appear in this order:

| Category | Contents | Category summary |
| --- | --- | --- |
| Appearance | Color scheme | Selected palette |
| Playback | Stay awake while charging; background playback guidance and Android settings shortcut | Stay-awake status on phones; device-specific explanation on TV |
| Automatic mix | Existing rated/unrated share; new higher-rating preference | Rated/unrated balance and preference strength |
| Automatic ratings | Explicit opt-in; completion, skip, and repeat-play controls; examples; restore defaults | Off, On with default tuning, or On with custom tuning |
| Plex account | Server/library, read-only status, change source, sign out, existing setup entry when applicable | Current source or setup status |
| About & updates | Installed version, automatic/manual update checks, download/install actions, Share app and QR code | Version and already-known update availability |

- Place a separate Rooms link below the categories, showing hosting/joined status.
- Opening Settings must not trigger update checks, room scans, or network actions
  merely to populate summaries.
- Use compact labeled rows, restrained dividers, and grouped controls instead of
  large cards around every item. Retain semantic palette colors and subtle TV focus.
- Keep essential explanations beside controls. Put longer rating examples behind
  an Examples disclosure, but keep Plex consent consequences visible.
- Put sign-out at the bottom of Plex account with its existing confirmation.
- Preserve capabilities and permission restrictions. On TV, Playback explains
  device-managed background behavior without exposing phone-only controls.
- At available Settings content widths of at least 840 dp, use a 240 dp category
  pane and flexible detail pane. Otherwise use a hub and separate detail pages.
- Wide layout initially selects Appearance and subsequently restores the last
  category. Opening the gear on narrow layouts starts at the hub.
- Activate categories by click/tap or D-pad OK, never just focus movement.
- Preserve primary navigation and the mini-player. Detail pages scroll
  independently and restore their positions when switching categories.

## Navigation and Rooms

Introduce explicit internal category state and a Rooms destination rather than
routing everything to the single Settings route.

- Narrow Back returns detail to hub with originating focus/scroll restored; Back
  from the hub returns to the screen that opened Settings.
- Wide TV Back first moves detail focus to the selected category; Back from the
  category pane exits Settings.
- Dialogs and external Android screens restore launch-control focus. Configuration
  changes retain selected category and page position.
- Primary navigation ends the Settings navigation session. Repeated gear/Rooms
  taps must not accumulate duplicate destinations.
- Give each detail page a distinct focus-restoration key. Preserve Settings access
  in fallback phone/TV shells as well as Nocturne.
- The Rooms shortcut and Settings link open the same Rooms page. Back from a
  Settings-launched Rooms page returns to Settings.
- Without an active room, show Join a room and Host a room, preserving permissions,
  scan results, and read-only restrictions.
- When hosting, prioritize room code, connection status, playback destination,
  transfer/take-back, and End room. Consolidate duplicate TV hosting controls.
- Invite guests and Open room display open separate focused, scrollable dialogs
  containing the corresponding QR code, explanation, and share action.
- Retain joined-room status, leave, and offer/stop-player actions through existing
  state/callbacks. Preserve guest overlays where required by the app shell.
- Navigating away must not end a room, stop playback, or cancel update downloads.

Extract Settings and Rooms composables from MainActivity.kt. Keep service and
view-model behavior authoritative; no room protocol changes are needed.

## Tuning controls

| Control | Steps | Default |
| --- | --- | --- |
| Completion boost | Off, Half, Normal, Strong, Double | Normal |
| Skip penalty | Off, Half, Normal, Strong, Double | Normal |
| Repeat-play influence | Off, Half, Normal, Strong, Double | Normal |
| Prefer higher ratings | Equal chance, Mild, Normal, Strong, Very strong | Normal |

- Use discrete controls with visible values and accessible decrease/increase
  buttons supporting touch and D-pad operation without requiring dragging.
- Map rating controls to multipliers 0, 0.5, 1, 1.5, 2. Map selection preference
  to exponents 0, 0.8, 1.6, 2.4, 3.2.
- Disable all three rating controls until explicitly opted in, and for read-only
  sources. Preserve saved values when disabled. Disable repeat influence when
  completion boost is Off. Selection tuning is independent of rating consent.
- Keep tuning device-local. The controlling host's settings apply during transfer.
- Restore rating defaults and Restore selection preference act on their respective
  pages; neither changes consent or rated/unrated share. Rating reset requires opt-in.

### Storage and behavior

Add an immutable internal tuning model and ProfileStorage store, saving all four
step indices together. Missing/invalid fields use individual defaults. Save
committed changes immediately; on failure show an error and restore persisted values.

Parameterize existing pure helpers in LocalHarmonicastCore.kt, retaining default
arguments, current rounding, unrated starting value 5.0, and final bounds 0–10:

```text
completion delta in 0–100 points =
  round(0.5 × completionMultiplier
        × (1 + repeatMultiplier × ln(max(PlexPlayCount, 0) + 1)))

skip delta in 0–100 points =
  -round(3 × skipMultiplier × (1 - clampedProgress))

selection weight = max(ratingOrDefault, 0.1) ^ selectedExponent
```

- Zero adjustments must neither assign an unrated song a rating nor issue a write.
- Only completion and skip events trigger automatic adjustments.
- Read tuning for each rating event and automatic batch without restarting the
  service. After metadata fetch, use current tuning and recheck consent before writing.
- Serialize automatic adjustments with explicit votes using the existing rating
  mutex. Preserve confirmed-write display updates and delayed-response protections.
- Keep history recording and playback advancement working when rating requests fail.
- Apply selection changes to newly generated batches. Preserve queued tracks,
  pool/fallback behavior, rated/unrated scheduling, deduplication, and request order.
- Leave explicit votes, Track Radio, and manual playlist/request ordering unchanged.
- Generate examples using production helpers: completions at zero/ten Plex plays,
  skips at 25%/75%, and relative selection weights for ratings 8 versus 4. Explain
  rounding and that weights do not guarantee library-wide selection probabilities.
- Preserve existing consent on upgrade, including default-off when no opt-in exists.

No public API, guest protocol, account synchronization, or historical rating
migration is required.

## Validation and release

### Navigation and visual acceptance

- Verify every existing action is reachable in its assigned category or Rooms.
- Check phone portrait, short landscape, tablet/two-pane, TV, all palettes, and
  enlarged text. Verify Back, focus/scroll restoration, rotation, dialogs, and
  return from Android settings.
- Verify touch/D-pad controls, disabled explanations, summaries, accurate examples,
  immediate palette changes, and downloads surviving category navigation.

### Behavioral regression coverage

- Test default formula equivalence, all tuning steps, rounding, unrated songs,
  play-count/progress boundaries, bounds, and zero adjustments.
- Test persistence/upgrades, malformed values, save failures, mid-request consent
  changes, read-only sources, overlapping votes, and failed/delayed Plex responses.
- Use deterministic randomness for selection-strength tests; retain pool, share,
  fallback, and deduplication coverage.
- Exercise room join/host, permissions, QR dialogs, sharing, expiry, transfer,
  take-back, and room continuity during navigation.

### Delivery sequence

1. Implement Settings/Rooms structure and verify feature parity, then integrate tuning.
2. Run scripts/release-check.sh through the required Android debug wrapper and
   compatible Java runtime. Build the signed candidate with android/build-release.sh.
3. Verify upgrading from v1.1.7 on Pixel and Shield preserves settings/consent.
   Complete acceptance for Settings, Rooms, playback, transfer, and Android Auto.
   Use mocked Plex responses for exhaustive rating tests and a designated test
   track for live device verification.
4. Target v1.1.8 / code 65; advance if another release intervenes. Verify package,
   signer continuity, version, and SHA-256.
5. Update roadmap, design contract, release notes, and validation evidence. After
   device acceptance, publish the stable release from main with signed APK and
   checksum, then verify in-app update discovery.

Settings search, cloud synchronization, additional themes, and historical rating
repair are outside this release.
