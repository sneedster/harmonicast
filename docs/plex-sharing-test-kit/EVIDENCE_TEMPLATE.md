# Plex configuration-sharing evidence

Status: NOT RUN. Do not change this to verified until every applicable gate has evidence.

## Environment (no secrets)

- Test date/time zone:
- Plex Media Server version:
- Plex Web version:
- Account types: owner / approved / unapproved (ordinary accounts initially):
- Account fingerprints from reports (must identify three distinct accounts):
- Mechanism tested: individual album / dedicated configuration library:
- Source fingerprint from reports:
- TLS route verified:
- Baseline music-library restrictions on each recipient:

## Report matrix

| Experiment | Owner report | Approved report | Unapproved report | Result / missing evidence |
|---|---|---|---|---|
| Before share | Pending | Pending | Pending | Pending |
| Shared, resource-token mode | Pending | Pending | Pending | Pending |
| Shared, account-token mode | Pending | Pending | Pending | Pending |
| Test album metadata refresh | Pending | Pending | Pending | Pending |
| Access removal | Pending | Pending | Pending | Pending |
| New process, reused token | Pending | Pending | Pending | Pending |
| Fresh sign-in token | Pending | Pending | Pending | Pending |

## Discovery and provenance gate

- Owner-controlled album/library verified independently of JSON/title:
- Owner resource ownership corroborated:
- Approved recipient can discover the item without a supplied rating key:
- Sanitized discovery endpoint path (no URL host, token, or raw HAR):
- Query parameter names, token type, pagination and bounds:
- Required recipient acceptance step:
- Does the private library stay absent in item-only mode?
- Exact album type, server identity, and section binding visible:
- Summary field lock observed in UI/API:
- Exact summary retained after album-only metadata refresh:
- Recipient playlist imitation rejected:
- Duplicate configurations detected and rejected:
- Restrictions on real playback library still enforced:
- Managed Home-user support: NOT TESTED unless separately demonstrated:

## Removal measurements

- Share-removal time (UTC):
- Last successful fresh read (UTC):
- First explicit denial with valid account control (UTC):
- Token kind retained by the watcher:
- Same token reused in new process (manual attestation):
- Fresh account-token result:
- Owner control confirms album still exists:
- Unapproved account remains denied with valid account control:
- Network failures / 404s / timeouts recorded as inconclusive:
- Observed propagation bound, including request timing uncertainty:

## Decision

- Selected mechanism and evidence supporting it:
- Unmet checks:
- Production reader/publisher authorized by completed evidence: NO / YES:
- Next implementation step:

No real MusicGrabber credentials have been published in this experiment. Plex share
removal has not been claimed to revoke copied MusicGrabber credentials or sessions.
