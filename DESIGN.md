---
version: alpha
name: Harmonicast Android
description: Native Plex music player with nearby rooms and restrained dark settings.
colors:
  primary: '#BFA7F5'
  background: '#090B14'
  surface: '#121421'
  onSurface: '#F1EEF8'
omitted:
  - section: typography
    reason: Android MaterialTheme typography is the runtime authority.
  - section: spacing
    reason: Existing native Compose layouts own dp dimensions.
  - section: rounded
    reason: Android MaterialTheme shapes are the runtime authority.
components:
  settings: {}
  accountIdentity: {}
---

# Harmonicast Android design context

## Scope and ownership

The Android app serves personal Plex listening and nearby shared rooms on phones,
tablets, and TVs. Existing copy is English. This document records the established
native interface; it does not define the separately maintained marketing site.

`android/app/src/main/java/io/github/sneedster/harmonicast/PlayerPalette.kt` owns
runtime color tokens for Nocturne, Aurora, and Ember. The colors above document
Nocturne, not a second generated token source. MaterialTheme owns native typography
and shapes. All additions consume those semantic roles.

## Settings

`SettingsScreen.kt` owns settings navigation and shared SettingsDescription text.
Narrow screens navigate from categories to details; at 840 dp and wider, categories
remain alongside details. Existing scroll containers own scrolling. Native buttons
use tvFocusFeedback and dialogs use FocusRestoringAlertDialog.

Keep settings quiet, readable, and consistent with music acquisition settings.
Use titleMedium for account names and source names, labelMedium for identity labels,
and SettingsDescription for secondary information. Long account text wraps naturally.
Account identity is read-only and belongs only in local Plex account settings.

## Plex identity behavior

Fetch the signed-in account through LocalPlexClient using the saved account token,
not the selected server's resource token. Display username and distinct email;
fall back to title or email when username is absent. Show loading text, then an
inline retry on failure. Leaving the screen cancels the UI request; a failed lookup
does not sign out the user or interrupt playback. No new account data is persisted
or sent to room guests. Existing sign-out confirmation remains authoritative.

## Shared Plex room behavior

RoomsScreen and the joined-room playback card use PlexAccessPolicy for visibility.
Configured shared libraries can host and offer paired playback. A joined guest can
offer a saved personal device but cannot open a nested room or use personal
acquisition. Keep Material buttons, SettingsDescription, tvFocusFeedback, and
FocusRestoringAlertDialog; no new tokens or navigation variants are introduced.
Music acquisition copy distinguishes Plex server ownership from room hosting.

Room startup now validates live Plex access before opening listeners. While the
check is pending, roomSummary displays “Checking Plex access…” in its existing
text slot and Open room keeps its label but is disabled. Success opens the usual
host controls; failure restores the button and uses the existing inline room error.
No additional dialog, screen, token, or navigation variant is introduced.

## Shared library preparation

Settings > Music acquisition owns the manually started Set up shared access
panel. SharedPlexSetupSettings uses the existing SettingsDescription, Material
buttons, RemoteTextField, and tvFocusFeedback inside the parent scroll container.
RemoteTextField now supports an enabled state for asynchronous forms, including
its TV/voice controls. No new palette, typography, navigation, or overlay system.
The interface remains English and follows the existing phone/TV input policy.

The panel offers the next applicable action: provide the server folder and save
the bundled media, create or rescan the library, review inactive metadata, or
show Library ready. A ready library does not claim enabled shared acquisition.
Publication of real MusicGrabber credentials is not exposed in this preparation
flow. See docs/SHARED_PLEX_ACCESS_PLAN.md for domain policy.

SharedPlexSetupModel owns progress, duplicate-action suppression, safe inline
errors, cancellation, and stale-result rejection. Source changes/exit invalidate
the panel. The service revalidates live ownership before writes and stores only
non-secret preparation markers to resume after interruption. Prepared library IDs
are omitted from the owner's subsequent music-source selection.

Canonical UI map: forms use RemoteTextField; feedback uses inline text with a
polite live region; progress uses Material LinearProgressIndicator in a fixed
height slot; file export uses Android's document picker; scrolling remains with
SettingsScreen. Service regression tests cover authorization/conflict/recovery;
Compose tests cover input gating, busy state, cancellation, and honest readiness.

## Manual display entry

RoomsScreen's existing display dialog puts the typed local /open address and
four-digit display code before the optional QR code. Use MaterialTheme titleMedium
for the address, headlineMedium for the four-digit code, and SettingsDescription for
same-Wi-Fi instructions. The shared dialog continues to own scrolling and focus.

The standalone display/open.html entry form uses the display's Nocturne colors,
Georgia heading and system body text. It owns inline validation, pending status,
timeout recovery and keyboard focus. Display codes are separate secrets from the
advertised room name; RoomCapability owns expiry and the shared attempt limit.
Successful entry uses the existing display capability bootstrap and permission
allowlist. No new public service or persistent credentials are introduced.
