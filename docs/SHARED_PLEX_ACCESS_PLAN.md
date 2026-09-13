# Shared Plex acquisition and room hosting

Status: local implementation includes owner preparation, reviewed non-admin
account publication, recipient discovery/login, revocation checks, and shared-host
room acquisition. Live ZIP installation and inactive library preparation passed on
the Pixel and Plex server. Approved-account reads and same-token revocation passed
the earlier API experiment. Real credential publication, recipient acquisition,
remaining cross-account controls and physical-device acceptance remain outstanding.
No release date assigned.
Decision date: 2026-09-12.

## Agreed outcome

Selected shared Plex users receive a server-provided MusicGrabber connection through
a privately shared Plex media item. The connection uses a dedicated non-admin
MusicGrabber username/password, initially one shared `peon` account. Recipients do
not manually create an account or enter connection credentials. Separate accounts
and configuration items per recipient use the same design when the owner wants
individual revocation and request isolation.

All signed-in users with access to a usable Plex music library may host nearby
rooms, regardless of Plex server ownership. Hosting does not require an acquisition
grant. The room host controls their own playback and queue. Existing restrictions
on Plex rating/history writes remain unchanged.

Use the existing Android app, Plex, and unmodified MusicGrabber. No additional
server application, custom authorization gateway, or MusicGrabber fork is required.
The owner may provide a reachable HTTPS endpoint through their existing reverse
proxy or Tailscale Funnel; private access through Tailscale remains possible.
Acquisition exposure does not expose Harmonicast rooms to the internet.

## Current implementation and changes needed

At planning time, `PersonalPlexSource.canWriteToPlex` controlled several unrelated capabilities:
Plex writes, room hosting, native playback transfer, acquisition configuration,
submission, and recovery. Do not set it to true for shared users.

| Capability | Owner source | Shared source without configuration | Shared source with valid configuration |
|---|---|---|---|
| Browse, play, local queue | Yes | Yes | Yes |
| Host nearby room | Yes | Yes | Yes |
| Offer native playback / host transfer | Yes | Yes, with existing pairing and local-network requirements | Yes, with the same requirements |
| Plex rating/history writes | Existing behavior | No | No |
| Personal acquisition | Existing manually configured connection | No | Yes, through supplied non-admin account |
| Room guest acquisition | Host enables per room | No | Host enables per room |
| Publish server configuration | Plex owner only | No | No |

Introduce separately named capabilities for hosting, offering playback, managing
acquisition configuration, and submitting acquisitions. Reuse a common policy in
the view model, service, coordinator, and receiver; UI visibility alone is not an
authorization check. Active nearby-guest mode cannot start a nested room or use the
device's personal acquisition connection for the joined room. A paired receiver
may retain its saved personal Plex sign-in while participating in a room.

## Phase 1: prove the Plex configuration mechanism

Before implementing credential publication, use a dummy connection record and a
small locally supplied album/track in a dedicated private configuration library.
An album with an owner-edited, locked summary is the first candidate. Plex supports
album sharing and metadata editing, but exact shared-user discovery and field
visibility have not been tested for this purpose.

Verify with owner, approved shared account, and unapproved shared account:

1. The owner can create/edit a bounded configuration record in an exposed metadata
   field and lock it against metadata refresh. Do not assume arbitrary ID3 fields
   or sidecar JSON files are exposed by Plex.
2. A recipient can discover the individually shared item even though its library
   is not shared. Identify the actual API and token type required; existing
   `/playlists` and `/library/sections` enumeration may be insufficient.
3. The approved recipient can read the record, while an unapproved account cannot
   retrieve it even with a known item ID. Test direct metadata access, not only
   whether a card is visible in Plex Web.
4. Removing access prevents fresh reads, including across restart and token reuse.
   Measure propagation and distinguish permission denial from a network failure.
5. Establish trustworthy provenance from the selected Plex server and an
   owner-controlled library item. A title, username, or self-asserted server ID
   inside the JSON is insufficient. Reject user-created playlist imitations and
   ambiguous duplicate configurations.
6. Confirm music-library filtering and restrictions still apply to playback and
   fulfillment. Test supported Plex account types explicitly; do not claim managed
   Home-user support until their sharing and token behavior is verified.

If individual-item discovery cannot be made reliable, use a dedicated configuration
library shared only with approved recipients. Confirm that library/item access is
enforced on direct reads. This retains the architecture. Record the tested mechanism
before writing the production reader or owner publisher.

Keep configuration media outside the ordinary music library and exclude it from
Harmonicast's music-source picker, search, automatic mix, and room browse surfaces.
Owner setup must explain that sharing the entire configuration library grants
access to every credential-bearing item in it.

## Phase 2: connection contract and owner setup

Proposed versioned record (placeholder values only):

```json
{
  "type": "harmonicast.acquisition",
  "version": 1,
  "configurationId": "<random stable ID>",
  "revision": 1,
  "plexServerId": "<machine identifier>",
  "musicLibraryId": "<library identity>",
  "allowAcquisition": true,
  "allowRoomAcquisition": true,
  "musicGrabber": {
    "url": "https://music.example.com",
    "username": "harmonicast-guests",
    "password": "<unique account password>"
  }
}
```

The record's permission fields guide Harmonicast. MusicGrabber's account role is the
service-enforced boundary; these fields do not restrict use of copied credentials
outside Harmonicast. Room acquisition additionally requires the host's per-room
toggle, which continues to start off. Publishing room-acquisition access must be
an explicit owner choice; it allows approved shared hosts to submit requests for
their nearby guests using this account.

Owner workflow in Settings > Music acquisition:

1. Create a dedicated MusicGrabber `peon` account alongside the admin account using
   MusicGrabber's existing interface. A `user` account is an explicit alternative;
   do not imply either role is limited to exactly Harmonicast's buttons.
2. Verify the endpoint, account identity and role, destination directory, and Plex
   import/rescan behavior. Owner-provided credentials must be separate from the
   owner's existing personal connection. Never prefill or publish that connection's
   admin password, session, or API key.
3. Offer an owner-only, manually started **Set up shared access** action in
   Settings > Music acquisition. Prefer automatic discovery and reuse of a
   verified existing configuration library, including the current test setup.
   If none exists, provide the small configuration-media asset and ask for its
   placement in a dedicated server-readable folder. Create the library through
   Plex using that folder, scan it, and locate the imported album automatically.
   Plex must scan real media; owner authentication alone must not be treated as
   permission or capability to create arbitrary server-side files. Fully automatic
   file placement needs a separately verified transfer mechanism.
4. Select the configuration item and target music library. Show the exact item,
   server, destination, MusicGrabber account, and room-acquisition choice. Validate
   before publishing. Reject an admin role or unverifiable role for this delegated
   workflow; verify multi-user enforcement, not just a successful login response.
5. Publish/update only the designated metadata field after owner confirmation.
   Recheck ownership and the current record before an update; preserve unrelated
   metadata and lock state deliberately. Report conflicts instead of overwriting
   another device's changes. Do not claim atomic conditional updates unless Plex
   supports them.
6. Use Plex's existing Grant Access interface to choose recipients. For version 1,
   guided Plex sharing is sufficient; a new in-app Plex user-management UI is not
   a dependency. Test that the intended recipient can retrieve the published item.

Owner setup interaction decision (user approved): prioritize ease of use. Present
one next action at a time, show progress for scan/publication/read-back, and avoid
requiring copied tokens, item IDs, JSON, or manual metadata editing. Read back and
validate the published record before reporting completion. Re-running setup must
resume, repair, or update verified resources rather than create duplicates. Names
alone are not sufficient to adopt a library or item; stop on ambiguous matches
and preserve unrelated content. Retain completed setup progress across recoverable
failures. Separate preparation from credential publication so the owner's final
review shows the concrete destination and account before credentials are shared.
The library-preparation portion is now implemented locally in
SharedPlexSetup.kt and SharedPlexSetupSettings.kt. It exports the supplied FLAC,
reuses an existing bound disabled record or creates a library for the chosen
server folder, requests a scan, reviews/writes/locks a disabled record, and checks
the saved result. Preparation reports that acquisition remains off until the separate dedicated
account review and publication flow completes.

Preparation rechecks the selected account's live ownership and advertised server
connection before writes. It limits response bodies, refuses redirects, detects
duplicate/oversized setup libraries and conflicting metadata, rejects overlapping
music folders, and journals uncertain library creation to avoid an automatic
duplicate POST. The record contains only the disabled dummy endpoint/account.
An unresolved create response leads to Check again, never a blind retry. There is
no claim of an atomic Plex compare-and-swap or protection from a simultaneous
write on another device. Valid enabled records have a separate update/disable review. Foreign records are
preserved and rejected. An unlocked live record must be locked in Plex before reuse.

Library creation/scanning request shapes were cross-checked against the maintained
[Python PlexAPI implementation](https://python-plexapi.readthedocs.io/en/latest/_modules/plexapi/library.html).
The Android preparation mutations passed live-server acceptance; the previous
live experiment proved reads/revocation and used Plex Web for metadata writes.

Document the security properties beside publication: the credential is readable
by recipients through Plex; they can use all actions their MusicGrabber role permits
and can change the account password. A shared account also shares request history
and authority over that account's jobs. Store no real credentials in repository
examples, generated documentation, or screenshots.

## Phase 3: recipient discovery and credential lifecycle

Implement a bounded Plex configuration reader in/alongside `LocalPlexClient` and
a server-provided acquisition connection store separate from manual owner setup.

- Discover when selecting a server/library, on explicit Refresh, and when checking
  a delegated acquisition grant. Use paged discovery with cancellation and limits;
  once identified, use direct item reads. Cap a record at 16 KiB and reject unknown
  versions, wrong types, mismatched identities, blank credentials, or ambiguity.
- Verify the configured server/library against the actual selected source. Treat
  metadata as data: no executable instructions, arbitrary headers, file paths,
  or additional callback URLs. Require HTTPS for this remotely supplied endpoint;
  refuse embedded URL credentials, queries/fragments, and redirects.
- Do not send any Plex token to MusicGrabber. Authenticate with only the credentials
  from that configuration; never substitute a saved credential for another URL.
- Reuse username/password login, `/api/auth/me`, bearer sessions, encrypted Android
  storage, sanitized errors, and bounded relogin. Reject admin accounts in this
  delegated mode. Keep the supplied password encrypted for unattended relogin.
- Bind access and work to the Plex account, server, library, configuration identity,
  service URL, and actual MusicGrabber account ID. Never use a display name as identity.
  Password/revision changes with the same service/account can resume existing work
  after validation. A different account or service must not inherit earlier jobs.
- Before each new submission, re-read the known Plex item. Coalesce concurrent
  checks but do not use a durable cached grant to authorize new submissions when
  Plex cannot be reached. Keep status polling separate from this submission check.
- On explicit share denial, grant removal, or source change, stop new submissions,
  disable room acquisition, clear usable delegated credentials, and revoke this
  device's session where possible. Preserve non-secret request records as paused.
  On transient failures retain encrypted state for retry, but block new submissions.
- Revalidate access on app restart and periodically while tracking accepted work
  (initial target: once per minute, bounded and coalesced). Pause delegated tracking
  when verification fails; never resubmit an uncertain or accepted job. MusicGrabber
  jobs already accepted may continue on the server regardless of local permission.
- Sign-out clears delegated secrets and invalidates in-flight callbacks. A change
  of account/source during login, publication, submission, or polling must not save
  a stale result. Keep existing manual owner settings isolated from this lifecycle.

Recipient UI shows connection source, server, account label, and status without
displaying the password. Provide Refresh and local opt-out. Opt-out remains in
effect across rediscovery until the recipient explicitly reconnects. Distinguish
not shared, checking, connected, endpoint unreachable, invalid credentials, and
access removed. Reuse existing Compose settings primitives, palettes, page layout,
TV focus, and focus-restoring dialogs. No new visual system is needed.

## Phase 4: rooms for shared Plex users

This phase can ship independently of delegated acquisition.

- Replace ownership gates in `RoomsScreen`, `MainActivity`, and
  `HarmonicastMediaService.enableGuestControl` with hosting eligibility based on
  personal Plex mode and a configured, usable music source. Shared status alone
  must not block hosting. Enforce eligibility in the service as well as the UI.
- Serve browsing, queue operations, and playback using the host's own shared Plex
  token and selected library. Never borrow an owner token or expand accessible media.
- Preserve nearby Bluetooth and same-Wi-Fi browser/display behavior, room secrets,
  request limits, voting, teardown, and safe guest DTOs. Guests receive neither Plex
  credentials nor the MusicGrabber configuration item, password, or session.
- Decouple `NativePlaybackProtocol.ownerEligible`, receiver startup/request checks,
  and host transfer checks from Plex ownership. Shared-account devices can offer
  playback and shared hosts can transfer/take back through existing explicit pairing.
  Keep local Wi-Fi/Ethernet, lease expiry, host-controlled streaming, and Android Auto
  playback authority. Do not enable credential-free anonymous receiver mode.
- A shared host with a valid delegated acquisition connection can explicitly enable
  guest acquisition if the configuration permits it. Requests use the host's
  MusicGrabber account and existing five-request guest allowance. Room guests do not
  receive independent MusicGrabber access. Ungranted shared hosts can still host
  rooms and request existing library music normally.
- Teardown/stop affected output on sign-out, source/account replacement, or definitive
  loss of library access. Handle transient network failures without upgrading access
  or silently switching libraries. Keep existing recovery and authority semantics.
- Update copy that says shared libraries cannot host. Distinguish "Plex server owner"
  from "room host" throughout transfer, acquisition, and settings text.

## Phase 5: validation and delivery

Use `android/build-debug.sh :app:testDebugUnitTest :app:lintDebug`; the wrapper also
assembles debug and selects the supported Java 21/Android SDK setup. Do not use
Android Studio's Java 25. Run focused suites while developing, then the complete
unit suite, debug build, and lint before preparing a candidate.

Required automated coverage:

- Capability matrix: owner, shared with/without grant, missing source, joined guest,
  receiver, sign-out and source switch. Confirm shared room hosts still cannot issue
  Plex writes or publish delegated configuration.
- Plex discovery/direct reads: approved/unapproved tokens, forged or duplicate items,
  pagination, truncation, oversized or malformed records, stale responses, removal,
  wrong server/library/account, and disabled flags.
- Delegated login: non-admin accepted, admin/unverifiable roles rejected, HTTP and
  redirect rejected, password reset, login limits, expiry, opt-out, and encrypted
  state. Verify no fallback to owner credentials or API-key mode.
- Acquisition: destination verification, shared-source fulfillment, same-account
  password rotation, different-account quarantine, restart, revoked grants, unknown
  submissions, and exactly-once queue admission. Preserve existing owner's behavior.
- Rooms: actual shared-source gateway/service startup, guest browse/request/vote,
  guest quotas, off-by-default acquisition, receiver eligibility, transfer/take-back,
  lease loss, Android Auto precedence, and credential exclusion from all guest paths.
- UI: owner publication and shared connection states, retry, long server/account
  names, narrow/wide Settings, room controls, and keyboard/TV focus behavior using
  existing Compose tests. Use synthetic credentials in captured artifacts.

Live acceptance on the deployed Plex/MusicGrabber versions:

1. Owner publishes a test configuration for a real non-admin account; only the
   intended shared Plex account can read it. Verify role isolation through API calls,
   not just absence of admin screens. Check forced-password-change handling.
2. Shared user acquires a controlled test track; it lands in the intended directory,
   appears in the selected shared Plex library, and enters the queue once.
3. Shared user hosts a nearby room without any acquisition grant. Repeat with a
   grant and explicitly enabled guest acquisition; verify both cases independently.
4. Exercise browser/Bluetooth guest access, shared-account native transfer/take-back,
   and Android Auto where devices are available. Record unavailable hardware checks
   as outstanding; preserve the project's prior instruction against physical TV
   testing unless the user changes it. Automated TV-layout checks remain applicable.
5. Remove the Plex share and verify new app submissions stop. Demonstrate separately
   that resetting the MusicGrabber account password invalidates existing sessions.
   Do not claim Plex removal alone revokes copied MusicGrabber credentials.
6. Test endpoint exposure through the chosen HTTPS route and loss of connectivity.

Update README, architecture, design behavior notes, and validation evidence when
implemented; update release notes only for the actual candidate/release. Prepare
a runnable Android candidate after checks. Publication and a delivery date are not
part of this planning task.

## Residual risks and operational rules

- A single shared account does not isolate recipients or identify the actual person
  behind a request. Separate accounts/items are available when that matters.
- Recipients can retrieve the password and use the account outside Harmonicast.
  The actual server-enforced role, including non-admin queue/file operations, is
  the authority granted. Test `peon` behavior on the deployed version; documented
  hidden Settings screens do not prove every corresponding endpoint is forbidden.
- Removing a Plex share controls future app access; reset the MusicGrabber password
  to revoke already distributed credentials and sessions. Remove the share first,
  reset the password, then publish the replacement to remaining recipients.
- Preserve MusicGrabber multi-user mode. Removing accounts until fewer than two
  remain changes its authentication behavior; password reset is the preferred
  revocation procedure for the shared integration account.
- Recipient and Plex-server compromise can expose the delegated password. Use a
  unique dedicated password, encrypted transport/storage, and redacted diagnostics.
- Owner credential publication is intentional secret distribution, not a secret
  vault. No API keys or admin passwords may enter this delegated publication flow.

## Evidence and implementation touchpoints

Inspected MusicGrabber upstream commit:
`233f19a756cbf041d398a16cc6012c65572cd85d`. Recheck the deployed version during the
proof of concept; this source inspection is not a full security audit.

- [Plex individual-item sharing](https://support.plex.tv/articles/shared-media/)
- [Plex metadata editing and field locking](https://support.plex.tv/articles/201272763-edit-details/)
- [MusicGrabber documented roles and authentication](https://gitlab.com/g33kphr33k/musicgrabber/-/blob/233f19a756cbf041d398a16cc6012c65572cd85d/README.md#security)
- [MusicGrabber authentication middleware](https://gitlab.com/g33kphr33k/musicgrabber/-/blob/233f19a756cbf041d398a16cc6012c65572cd85d/middleware.py)
- [MusicGrabber password/session lifecycle](https://gitlab.com/g33kphr33k/musicgrabber/-/blob/233f19a756cbf041d398a16cc6012c65572cd85d/auth.py)

Android source directory: `android/app/src/main/java/io/github/sneedster/harmonicast/`.
Primary files: `LocalPlexClient.kt`, `AppProfile.kt`, `MainActivity.kt`,
`Acquisition.kt`, `AcquisitionSettings.kt`, `AcquisitionPicker.kt`, `RoomsScreen.kt`,
`SettingsScreen.kt`, `HarmonicastMediaService.kt`, `NativePlaybackProtocol.kt`,
`NativePlaybackReceiver.kt`, and existing guest gateway/nearby protocol tests.
Add dedicated configuration/policy classes rather than growing screen-specific logic.

Existing uncommitted account-identity and other workspace changes must be preserved.
This planning task changes documentation only.

## Implementation progress — 2026-09-12

First slice: independent room and playback capabilities, shared-host room reactions,
service enforcement, receiver source-change checks, and a process-local guest overlay.
Acquisition still requires an owner source; its coordinator now checks a separately
named capability, including joined-guest exclusion. Shared hosts cannot publish or
submit delegated acquisition yet. Phase 1 Plex sharing proof and phases 2–3 remain
outstanding. This is not completion of all phase 4 acceptance criteria: live library
revocation, real shared accounts, nearby devices, and Android Auto need acceptance.

Shared-host room votes are accepted with existing per-guest/per-track deduplication.
Down votes retain automatic-track skip behavior; they never change Plex ratings.
Personal shared-user rating controls remain unavailable. Owner votes retain existing
Plex behavior. Configured-source eligibility is a local prerequisite, not a live
Plex permission attestation; server requests still use the selected user's token.

## Continuation — room access validation and proof kit

Rooms now require a successful live server-identity and selected music-section
check before listeners open. The check uses the selected user's token. A guard
binds the room to that source, rejects stale startup responses and requests after
local source/guest changes, and rechecks Plex approximately once per minute.
Confirmed 401/403 or a valid response showing the selected music section absent
revokes the room and stops affected output. Network errors, 404s, throttling, and
malformed responses are inconclusive: they block initial room opening but do not
revoke an already verified room. This is detection after a fresh check, not an
instantaneous guarantee when connectivity is lost. Native transfer uses the room's
verified guard; standalone receiver eligibility still needs deployed acceptance.

The user requested a kit because private test accounts/library are not ready.
[Sharing proof kit](plex-sharing-test-kit/README.md) supplies tagged silent FLAC,
a CLI-generated disabled dummy record, sanitized direct-read and paged fallback
checks, and an evidence worksheet. No live tokens or MusicGrabber credentials are
included. Individual-item discovery deliberately remains a manual evidence gate;
the probe never turns a successful known-item read into a discovery claim. The
original proof did not implement the production reader or publisher. The local
implementation described below uses dedicated-library discovery; individual-item
discovery remains unproven and is not used.

## Portable owner setup ZIP — 2026-09-12

Owner preparation now offers **Download from a computer**. This explicitly starts
a five-minute local web page in the app's existing setup gateway, serving only
the instructions and a fixed, non-secret ZIP. A computer on the same private
network downloads it directly; no phone-to-server transfer, public hosting,
reverse proxy, Tailscale Funnel, script execution, or MusicGrabber change is needed.
Leaving setup or changing source closes the page. Expiry can be recovered by
opening a fresh download page. **Save setup ZIP on this device** is the fallback.

Extract the archive contents into a dedicated Harmonicast folder outside existing
music roots, on disk or a NAS/SMB share. The pre-tagged album is **Shared Access
Setup** by **Harmonicast**. The app accepts that identity and the original test-kit
identity, while retaining owner, folder-binding, review and conflict checks.
Existing installations should keep their original album; never install both.
The universal ZIP contains no installation IDs or credentials. After scanning,
the app writes and verifies the installation-specific inactive record. This preparation step does
not itself publish credentials; continue with the dedicated account review below.

[Package instructions](shared-plex-setup/README.txt) explain extraction layout,
native and Docker paths, separate mappings, NAS/SMB access and permissions.
Generate the checked-in ZIP with `python3 scripts/build_shared_plex_setup.py`;
verify freshness with `python3 scripts/build_shared_plex_setup.py --check`.
Unix ZIP modes are 755 for directories and 644 for files; share ACLs and the
permissions applied by extraction tools still govern actual access.

Remote acquisition connectivity remains a separate deployment concern: the
chosen MusicGrabber URL must be reachable from intended clients. No proxy,
Funnel, or other public exposure is configured by this preparation flow.


## Dedicated shared account implementation — 2026-09-12

The owner can test a separate HTTPS MusicGrabber login on Android or through the
paired five-minute computer page. The web page stages a candidate only. Android
shows the exact Plex server, music library, configuration album, account/role and
room permission. The owner confirms the download destination was checked and then
reviews publication before writing any live password. Preparation does not copy
the owner's saved personal connection. Account tests reject anonymous/single-user
access, API keys, admin roles, forced password changes and unverifiable identities.

The publisher rechecks live ownership, account identity/role and the reviewed
Plex record before writing. It increments the revision, locks Summary, and reads
back the saved record. Disabling replaces credentials with an inactive record.
Plex metadata updates use query parameters as required by the deployed server;
never log request URLs containing metadata. Plex has no atomic compare-and-swap.

Recipients discover exactly one bounded configuration album in the dedicated
Harmonicast library using their selected Plex token. The strict versioned parser
rejects duplicate keys, malformed records, mismatched server/music library IDs,
non-HTTPS endpoints and unlocked summaries. MusicGrabber receives its own session
credentials only. Delegated credentials are encrypted separately from the owner's
manual connection, bound to the selected Plex account/source and configuration ID.

New submissions recheck Plex before acceptance and immediately before the remote
POST; account identity and non-admin role are checked before service operations.
Loss of access blocks new requests and clears usable credentials when confirmed.
Transient failures pause access; opt-out persists across restart and requires an
explicit reconnect. Source changes invalidate in-flight login results. Password
rotation keeps the same job identity; another account/configuration pauses old
jobs. Accepted remote work can continue. No uncertain submission is retried.

Room acquisition requires both the published permission and the host's per-room
opt-in. A fresh check removing room permission blocks a pending room submission.
Shared access does not permit Plex ratings/history writes, owner setup, or use of
the owner's personal MusicGrabber credentials. Joined guests use the room gateway.

Live acceptance remains: owner enters the dedicated password in the setup UI,
reviews publication, then verifies approved-recipient discovery and one download
through Plex indexing/queue fulfillment. Test a separate unapproved account,
revocation, restart/reconnect and a nearby guest before calling rollout complete.
