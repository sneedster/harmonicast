# Portable setup ZIP validation — 2026-09-12

Implemented in the Android app; no MusicGrabber or marketing-site changes.

## Checks completed

- `android/build-debug.sh :app:testDebugUnitTest :app:lintDebug`: successful;
  204 tests, zero failures/errors/skips; lint zero errors and 37 warnings.
  The wrapper also assembled `android/app/build/outputs/apk/debug/app-debug.apk`.
- New real-socket tests verify download-only mode returns the bundled HTML and
  exact ZIP bytes with the download filename, MIME type and content length.
  They reject account mutation routes, other Host values, path traversal and
  requests after expiry. Existing MusicGrabber pairing/save tests still pass.
- Setup-service tests accept the production album identity, retain legacy proof
  support, and preserve an unrecognized album. Compose tests cover the download
  action during scan recovery and disabled export during preparation.
- `python3 scripts/build_shared_plex_setup.py --check`: current ZIP matches its
  deterministic sources. Independently checked ZIP CRC, relative entry paths,
  755 directory/644 file modes, exact FLAC tags and two-second audio duration.
- Chrome exercised the bundled HTML served by a temporary localhost static
  server: Download setup ZIP saved a file identical to the bundled archive.
  Stopping that server and retrying displayed actionable inline recovery text.
  Inspected desktop and 390-pixel layouts and keyboard disclosure navigation.
- Inspected the native Compose scan-recovery screenshot at
  `android/app/build/reports/settings-layout/shared-plex-download.png`.
- Strict frontend static audit: no findings; report at
  `build/shared-plex-zip-ui-audit.json`. `git diff --check` passed.

## Practical limits

The browser check used the exact bundled page with a temporary static server;
the real Android HTTP handler was tested separately over sockets in Robolectric.
The debug build was installed on the Pixel 10 Pro on 2026-09-12 at 17:00 local
time using the existing release signing key to preserve app data. Package flags
confirm DEBUGGABLE and the app process is running. The download action is also
available on Library ready so an existing installation can test without deleting
its setup album. The repeat build, tests and lint passed. A second-computer
Chrome download from the Pixel at `http://<phone-lan-address>:<setup-port>/` succeeded:
the web UI reported the download, the saved ZIP exactly matched the bundled
2,826-byte archive, and ZIP integrity and its four entries were verified.
The newly tagged album was subsequently scanned and preparation was confirmed
by the user as Library ready. Actual container mappings, NAS ACLs and Plex filesystem access
remain installation-specific; the instructions and scan-recovery message cover
those checks. The metadata remains an inactive preparation record; live shared-account publication and acquisition still need acceptance.

No reverse proxy, Tailscale Funnel, or public endpoint was configured. The setup
download intentionally uses private LAN access and expires after five minutes.

The user separately supplied `https://musicgrabber.example.ts.net/` as their
new Funnel address. A read-only check returned HTTP 200 with the Music Grabber
page title and successful TLS certificate verification; unauthenticated
`/api/users` returned 401. No app connection was changed or login submitted.

## Live metadata-save correction

The user reached Prepare library for the new album but received a generic
setup failure. Plex Web confirmed Shared Access Setup by Harmonicast, one
two-second track, with no summary. Inspection found that the app sent metadata
fields in a form-encoded PUT body; the PlexAPI implementation sends these as
query parameters to /library/sections/{id}/all. The app now uses encoded query
parameters for this inactive record, keeps authentication in headers, and
preserves owner checks, conflict checks, and readback verification. The fixture
now rejects form-body metadata edits. HTTP errors show a fixed status-based
message rather than suggesting every failure is a connection/sign-in problem.

The full Android build, tests and lint passed; the corrected debug APK was
installed on the Pixel at 17:12 local time. The subsequent retry and parser correction below completed live preparation.

The retry saved the inactive record to Plex, but readback rejected it as other
metadata. Plex Web confirmed the expected current server/library IDs and an
Android-serialized URL containing escaped slashes (`https:\/\/`). The strict
validator had prohibited every backslash, including this valid JSON escape.
It now normalizes only escaped forward slashes before its existing duplicate-key,
remaining-escape, exact-schema and source-binding checks. Regression coverage
accepts escaped-slash records and still rejects Unicode-escaped field names.
Live MusicGrabber credentials remain unpublished; this is preparation only.


## Shared account implementation verification

The dedicated-account publisher and recipient connection are now implemented
locally. The new suite covers strict metadata parsing, verified non-admin session
login, separate encrypted storage, source-change cancellation, restart/opt-out,
password/account/configuration rotation, expired-session login, role promotion,
revocation before POST and room-permission removal during catalog lookup. Owner
publication tests check readback and conflicting metadata preservation. Web socket
tests verify candidate staging, non-admin rejection and absence of secret fields
or a publication route in the response.

The native publication review was rendered and inspected at 390 x 760; destination
confirmation gates review and room permission starts off. Chrome exercised the
actual bundled shared-login page against a local synthetic backend at desktop and
narrow widths: inline code validation, pairing, HTTPS/login entry and completion
text passed. The completion screen contains no password or token and points back
to Android for publication. This browser fixture does not validate the live login.

The user reports a dedicated account named plex with role peon. Its password has
not been entered or published. The next live step is installing the updated debug
build, entering that account in setup, verifying its download destination, and
reviewing publication. Approved/unapproved recipient tests, a real track through
MusicGrabber and Plex indexing, restart and room/nearby-device checks remain open.

Final verification: `android/build-debug.sh :app:testDebugUnitTest :app:lintDebug`
passed with 218 tests, zero failures/errors/skips. Lint has zero errors and
37 existing warnings; strict UI audit has no findings. ZIP freshness and CRC checks
pass. The 3,160-byte ZIP includes the updated publication instructions. The debug
APK was signed with the existing release key, its signature verified, and the
packaged ZIP/login page bytes matched the source assets. This updated APK is ready
for the next Pixel installation; it has not been installed or used to publish live
credentials in this verification pass.

## Publication screen lifecycle correction

A live failure screenshot led to a reproduced Compose lifecycle bug: starting
publication cleared READY, removing the publication subtree and disposing its
tested account before revalidation finished. The regression failed on the old
code and passes with the reviewed state retained during requests and failures.
Source changes and explicit cancellation still discard the account. The path
example now explains that album routing may differ. Shared setup also prefills
only the saved personal connection URL, including after computer pairing; account
credentials remain separate. The browser pairing check confirms the prefilled URL
and empty username/password. The full build, 219 tests and lint pass; strict UI
audit and diff whitespace checks pass. Live publication retry remains pending.

## Settings clarity

Shared status is now prominent and refreshed by a read-only Plex inspection on
page entry. Published, inactive, pending and failed-verification states have
distinct labels. The configured personal account hides its login form until
Edit connection; unconfigured accounts retain immediate entry. Completed setup
downloads are optional. Compose tests cover status/error transitions, connected
form visibility and edit/cancel. Inspected the 390-pixel connected-account render.
The final full suite passes 221 tests; build/lint and the strict UI audit pass.
