# Plex sharing proof kit

This kit prepares the live experiment required before Harmonicast publishes or reads
delegated MusicGrabber credentials. It contains a two-second silent FLAC track and a
read-only Python probe. **No live sharing proof has been performed yet.**

The probe accepts only its disabled dummy connection record. It never calls
MusicGrabber, edits Plex metadata, grants access, removes shares, or saves Plex
tokens. Requests are GETs to Plex; redirects are refused, JSON responses are capped
at 1 MiB, summaries at 16 KiB, and fallback library discovery at ten pages of fifty
albums. Python 3.10 or newer is sufficient; no pip packages are required.

## 1. Prepare the private library

Use three separate ordinary Plex accounts: the server owner, an approved recipient,
and an unapproved recipient. Use separate browser profiles to avoid confusing their
sessions. Give both recipient accounts access to the ordinary music library so
that it provides a control for server access; leave the configuration library
unshared. Managed Plex Home accounts are a later, separate acceptance test.

Copy `media/01-sharing-proof.flac` from this kit into a new server-side folder with
this organization:

```text
Harmonicast Configuration Test/
  Harmonicast Test/
    Harmonicast Sharing Proof/
      01-sharing-proof.flac
```

Create a dedicated Music library pointing only to that folder. Keep it private.
Do not put this asset in the normal music library or select the test library as
Harmonicast's playback source. Automatic exclusion of configuration libraries is
not implemented yet. The track is synthetic silence with artist/album/title tags;
no external media download or real MusicGrabber account is needed.

Wait for Plex to scan the album. Record these values locally:

- The server origin, preferably its working HTTPS address (no `/web` suffix).
- The server machine identifier.
- The ordinary music-library section ID accessible to your approved recipient.
- The new private configuration-library section ID.
- The configuration **album's** numeric rating key, not the track's key.

These values can be obtained from Plex item information/XML and the existing
server/library setup. A section's numeric key is different from its UUID. For the
probe, use numeric section keys. The selected server must match its machine ID.

## 2. Generate and publish the dummy summary manually

From the Harmonicast repository root, replace the example origin and IDs below:

```bash
python3 scripts/plex_sharing_probe.py configure \
  --base-url https://YOUR-PLEX-HOST:32400 \
  --server-id YOUR-MACHINE-IDENTIFIER \
  --music-library-id 7 \
  --configuration-library-id 9 \
  --item-id 42 \
  --output-dir /tmp/harmonicast-plex-proof
```

The output directory must be new. The command creates:

- `setup.json`: non-secret connection/identity inputs and the expected dummy record.
- `dummy-record.json`: the exact JSON to paste into the album's Summary field.

The record points at `https://harmonicast-sharing-proof.invalid`, contains explicitly
dummy credentials, and disables both acquisition flags. Do not substitute a working
URL or real password; the probe deliberately rejects those changes.

In Plex Web as the owner, edit the album, paste the full contents of
`dummy-record.json` into Summary, make sure the field is locked, and save. This is
an intentional manual metadata edit to the test album only. Do not refresh or edit
unrelated media. Plex documents metadata editing and locking in
[Edit Details](https://support.plex.tv/articles/201272763-edit-details/).

## 3. Collect baseline and single-item sharing evidence

Obtain a **Plex account token** for each test account locally. Plex's
[token instructions](https://support.plex.tv/articles/204059436-finding-an-authentication-token-x-plex-token/)
describe temporary tokens. A token copied from a server item's XML can be a
server/resource token: this probe first checks it against the plex.tv account API
and stops if that check fails. Obtain the account token from that account's own
Plex Web session in that case. Never paste tokens into chat, report files, screenshots,
command arguments, shell history, or the JSON setup.

Run the probe in an interactive terminal; piped token input is rejected. The probe
prompts without echo. Account information is used only to validate the
session and produce a hashed account ID for comparing reports. Server resource
tokens are resolved only for the selected machine identifier. If no unique resource
token is available, it explicitly reports an account-token fallback; do not silently
treat these token modes as equivalent. Plex describes resource information in its
[connection troubleshooting guide](https://support.plex.tv/articles/206721658-using-plex-tv-resources-information-to-troubleshoot-app-connections/).

Run once per account before granting access, using a new report filename each time:

```bash
python3 scripts/plex_sharing_probe.py run \
  --setup /tmp/harmonicast-plex-proof/setup.json \
  --role owner --phase before-share --mechanism item \
  --output /tmp/harmonicast-plex-proof/owner-before.json
```

Repeat with `--role approved` and `--role unapproved`, entering each account's own
token and changing the output filename. Role labels are your experiment labels;
the account fingerprints and owner's resource `owned` flag must corroborate them.
The owner should read the exact dummy record. Both recipients should initially be
unable to read it. A network failure, invalid session, 404, or malformed response is
**inconclusive**, not proof of isolation.

Now use Plex's **Grant Access** on the test album for the approved account only.
Leave the configuration library itself unshared. Plex documents individual-item
sharing in [Single Item Access](https://support.plex.tv/articles/shared-media/).
If Plex presents a notification or acceptance step, complete it as the approved
recipient and record that step in the evidence worksheet.

Repeat all three probes with `--phase shared`, using new output names. Repeat the
approved and unapproved probes with `--token-mode account` to distinguish direct
account-token behavior from resource-token behavior. The expected observations are:

| Check | Owner | Approved | Unapproved |
|---|---|---|---|
| Account validation | Valid | Valid, distinct ID | Valid, distinct ID |
| Direct album summary read | Exact dummy | Exact dummy | Explicit 401/403 denial |
| Server identity on successful reads | Matches | Matches | May be denied |
| Item type and configuration-library binding | Album, matches | Album, matches | Not exposed |
| Normal music-library access | As configured | Remains available | As configured |

The reports contain observations, not a synthesized “secure” verdict. Compare
`sourceFingerprint` across runs to ensure they tested the same configuration.

**Individual-item discovery is deliberately an open gate.** A known rating key is
supplied to test direct access; the probe does not pretend to discover an item that
is not in a shared library. In the approved account's Plex Web session, open its
shared items view. Record which network request enumerates the album, the token
kind it uses, pagination behavior, and the album's server/library identity. Do not
export or share a raw HAR: it can contain tokens and private media. Write only the
sanitized path/query parameter names and findings in `EVIDENCE_TEMPLATE.md`.
A visible card alone is insufficient to select a production discovery API.

## 4. Verify metadata locking, provenance, and revocation

As owner, refresh metadata **only for the test album**. Rerun owner and approved
checks with `--phase metadata-refresh`. The exact dummy summary must survive. The
probe records whether Plex reports a Summary lock in `Field`; absence of that field
is unverified lock state, not proof the field is unlocked. Record the UI lock and
refresh result separately.

Record that the item belongs to the owner-controlled private library. A matching
JSON server ID or title does not establish that authority. In a separate controlled
negative test, create a recipient-owned playlist with the same title/dummy summary
if Plex permits it; record whether discovery includes it. Production selection must
reject playlist imitations and ambiguous duplicates. The probe rejects a direct
item that is not the expected album/library, and fallback discovery reports the
number of acquisition-record candidates. Do not create duplicates containing real
credentials.

Start a removal observation as the approved recipient:

```bash
python3 scripts/plex_sharing_probe.py run \
  --setup /tmp/harmonicast-plex-proof/setup.json \
  --role approved --phase removed --mechanism item \
  --watch-seconds 120 --interval-seconds 15 \
  --output /tmp/harmonicast-plex-proof/approved-removal-watch.json
```

While it runs, remove the album share in the owner browser and record the exact UTC
time manually. The probe keeps the same server token for the whole watch and
revalidates the account session on each sample. Timestamps bracket requests; network
latency affects the interval, so report measured bounds, not an exact enforcement
latency. The phase label describes the experiment, not the instant removal occurred.
A watch is limited to ten minutes and ends when the command exits; it creates no
scheduled task.

Then start a new probe process with `--phase restart`, using the same valid account
token and new output name. Repeat with a fresh sign-in token and record that fact.
Rerun the owner control to show the item still exists. Fresh reads must be denied
while the account remains valid; 404 alone is ambiguous. Repeat the unapproved
control. If denial does not propagate, record the observed failure rather than
changing the result to a pass.

## 5. Dedicated-library fallback

If reliable individual-item discovery cannot be established, first remove the
item-only share. Share the dedicated configuration library with the approved
account only. **Sharing this whole library grants access to every item in it.**
Keep it restricted to the synthetic test media during this experiment.

Repeat the baseline/shared/removal/restart matrix with `--mechanism library`.
That mode adds paged album discovery through the configuration section. Require
`complete: true`, `targetDiscovered: true`, and exactly one configuration candidate
for the approved account. A truncated, repeated, malformed, or capped page sequence
is inconclusive. It never searches another library or expands account permissions.

The fallback's discovery can be exercised automatically, but direct-read isolation,
provenance, restrictions, and removal still require the cross-account matrix.
Record which mechanism passed before implementing a production reader/publisher.

## Local HTTP and evidence handling

Use HTTPS with a valid certificate. If the test server is reachable only over your
trusted LAN, `--allow-local-http` must be supplied to **both** configure and run;
it allows only a private literal IP or localhost. There is no TLS-verification bypass.
HTTP exposes the Plex token on that LAN, so use HTTPS when available.

Reports exclude tokens, passwords, URLs, account names/emails, raw metadata, and
HTTP error bodies. The setup file contains your local server address and IDs; keep
it local. Reports use exclusive creation and mode 0600 to preserve earlier evidence.
Review reports before sharing. Copy `EVIDENCE_TEMPLATE.md` into your local proof
folder, fill in the manual observations, and keep missing checks explicitly pending.

## Local verification

```bash
python3 -m unittest discover -s scripts/tests -p 'test_plex_sharing_probe.py' -v
```

These tests use fake responses and a loopback HTTP server. They validate the probe,
not Plex's deployed sharing behavior. The packaged FLAC was generated locally with
FFmpeg from two seconds of mono silence; no copyrighted source audio is included.

## Browser sign-in (no copied token)

Add `--browser-login --expected-user YOUR_TEST_USERNAME` to a `run` command.
Open https://plex.tv/link in the browser signed in to that test account, enter the
four-character code printed by the probe, and complete linking. This code was
created by the local Harmonicast Sharing Proof process. The user observed that
Plex's link page did not identify the app being linked; do not rely on that page
to display the app name. Only use the code from the probe you just started.
The probe waits up to five minutes and verifies the expected username before any
server request. A wrong account stops the run. Tokens remain in process memory;
reports contain no username or token. Plex may retain the linked app under
Authorized Devices after this process exits; it can be revoked there when testing
is complete. The default hidden-token mode remains available.

This follows Plex's documented PIN authentication flow:
https://developer.plex.tv/pms/
