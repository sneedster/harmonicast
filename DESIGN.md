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

The panel offers the next applicable action: download the setup ZIP from a
computer (primary) or save it on this device, provide the Plex-visible folder,
create or rescan the library, review inactive metadata, or
show Library ready. A ready library does not claim enabled shared acquisition.
Real MusicGrabber credentials use a separate dedicated-account test and
publication review after preparation. See docs/SHARED_PLEX_ACCESS_PLAN.md for domain policy.

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

The computer download uses AcquisitionSetupGateway's download-only variant:
an explicitly opened, five-minute private-LAN page with no pairing, account,
or mutation routes. SharedPlexSetupModel owns its lifecycle and closes it on
exit/source change. It remains available during folder and scan recovery, and on
Library ready for instructions or another installation without deleting the
working album.
The page owns a semantic download link, native details disclosures, inline polite
status, bounded fetch/retry, document scrolling and visible keyboard focus.
Its standalone Nocturne CSS follows display/open.html (Georgia heading, system
body, semantic palette); it is bundled with the app, not the marketing site.
Instructions distinguish extraction destination from Plex's container/share path
and explain permissions without suggesting recursive media changes. The same
credential-free ZIP is served on the web and exported by Android's document picker.

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


## Shared acquisition publication and recipients

SharedPublicationModel owns transient login input and tested candidates. Source
changes, cancellation and expiry close the paired computer page and discard the
candidate. Password fields use the existing RemoteTextField with explicit Show/
Hide controls; no password or session token appears in review or status text.
Starting a setup operation preserves the last reviewed preparation and mounted
publication controls, disabled while busy. Otherwise disposal would discard the
tested account during publication. Service checks still revalidate every write.
SharedAccountReview names the server, music library, configuration album, URL,
username and verified role. Destination verification is an unchecked owner
attestation because MusicGrabber paths cannot establish Plex's host/container
mapping. Room permission defaults off. Review publication opens the existing
FocusRestoringAlertDialog with the concrete destination and credential disclosure.

The shared form initially copies only the saved personal connection URL, leaving
the dedicated username and password empty. An edited URL is preserved. The paired
computer page receives that URL after pairing.

The computer form uses the existing Nocturne layout, inline validation and polite
status. Shared mode hides API-key and forget-password alternatives and explicitly
says publication happens on Android. It clears inputs after validation. Owner
manual login retains its original account storage and mode choices.

SharedRecipientSettings owns refresh/reconnect and device opt-out, with an inline
connection state and fixed-height progress slot. Shared users see the service
address, username and room permission after verification; no credential editors
or Plex owner actions. Existing acquisition pickers and room switches use the
verified shared capability without granting Plex write access. Pending requests
stay visible with a paused reason when the original connection is unavailable.


Music acquisition settings show shared-access status before the personal account.
Opening the page performs a read-only Plex inspection; the status distinguishes
published access, prepared-but-off, checking, and an unverified/error state.
A failed refresh does not present the prior publication as current success.
Shared setup details stay behind Manage/Set up shared access; completed download
instructions are optional. Connected personal accounts show identity and actions,
with login fields only after Edit connection. Unconfigured accounts show the form
automatically. Cancel clears temporary secrets and restores the connection summary.


## Acquisition library matches and in-car navigation

AcquisitionCatalogRow owns catalog item actions. A confirmed Plex match is labeled
In your library and offers Queue existing track; unconfirmed items retain Acquire
track and are checked before downloading. The host applies the same lookup and
guard for personal and nearby requests, including web guests/displays. Artist
snapshots are account/source-bound, short-lived and bounded; an absent badge is
not proof of absence. Failed final Plex verification asks the user to retry.

Android Auto Artists/Albums open Plex's complete first-character index; selecting
a letter jumps directly to its server offset and More stays within its range.
Recent views and artist-to-album navigation retain their existing behavior.
Auto search respects requested pagination and server totals across title/artist/
album filters. A zero-result multiword search can retry using an anchor word and
compare punctuation-normalized candidates; this is not general spelling correction.
