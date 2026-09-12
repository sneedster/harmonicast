# Test-kit validation

Status: tool and assets verified locally; live Plex Web sharing cycle completed
with user-reported recipient results on 2026-09-12. Approved-account direct API
read, discovery, and same-token revocation behavior verified; separate
cross-account controls and restart behavior remain pending.

- 16 Python tests passed with fake Plex responses and a real loopback HTTP server.
- End-to-end CLI invocation with a mocked hidden prompt writes sanitized evidence.
- Response/record bounds, strict JSON, duplicate/truncated/repeated page rejection,
  resource-token binding, no redirects, no credential-bearing URLs, no raw response
  output, and exclusive evidence creation are covered.
- Mistaken token command arguments are not echoed in parser errors.
- Synthetic configure invocation created the disabled dummy record and setup files.
- FFprobe verified a 2.000000-second FLAC with the expected title, artist, album,
  and track tags. FFmpeg generated it from mono silence; no source recording used.

Run from the extracted kit or repository root:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/tests -p 'test_plex_sharing_probe.py' -v
```

## Live Plex Web check — 2026-09-12

- Owner preparation independently observed: the disabled dummy record was saved
  and locked in the test album Review field, and persisted after a page reload.
- The intended recipient's test-library grant was independently observed.
- User reported reading the test record as the recipient, losing both the album
  and library after removing the grant and reloading, and seeing them return after
  restoring the grant and refreshing.
- After instructions to run Refresh Metadata on the test album and reload it,
  the user confirmed that the harmonicast.acquisition text was still present.
  This is user-reported persistence evidence; exact record equality was not checked.

## Approved-account API check — 2026-09-12

Browser authorization verified the expected recipient before server access. The
probe selected one matching, non-owned Plex resource and its resource token.
The server identity matched. Direct album metadata returned the exact dummy JSON,
correct item/library binding, and a reported summary lock. Both ordinary music
and configuration libraries were visible. Bounded discovery completed in one page,
found the target, and reported exactly one configuration candidate. The account
control was valid. Sanitized local evidence is in
`build/plex-sharing-live/approved-shared-api.json`.

This proves the approved-account read/discovery path for the dedicated-library
mechanism. Separate owner/unapproved controls and restart behavior remain pending.
No real MusicGrabber credentials were used.

## Same-token API revocation watch — 2026-09-12

Evidence: `build/plex-sharing-live/approved-revocation-watch-api.json`.
The process selected the non-owned resource token once, then reused it for all
59 samples over 600.39 seconds. Nine initial samples returned the exact dummy
record. At 22:26:40.873581 UTC (sample completed at elapsed 94.91 seconds), reads
transitioned to direct album HTTP 404 and configuration-library HTTP 403. All
50 subsequent samples retained that state through the end of the run.

Every sample had a valid account control, matching server identity, and visible
ordinary Music library. Configuration-library visibility changed from true to
false. The owner UI independently showed that only the test-library grant had
been removed. This supports resource-scoped revocation, rather than account
expiration or a server/network outage. A direct 404 alone would be inconclusive;
the companion library 403 and unchanged controls provide the relevant context.
The exact sharing-save time was not recorded, so 94.91 seconds is a watch offset,
not measured permission-propagation latency. Restoration after this API watch
remains pending; earlier restoration was checked only through Plex Web.

Browser-link tests cover successful identity binding, wrong-account rejection,
malformed PIN rejection, timeout, and absence of the token from printed output.
Live browser authorization and approved direct reads also passed as recorded above.

The probe now prints fixed, sanitized progress messages per sample. A regression
test confirms unexpected response values cannot be printed as progress. This
change applies to new runs; the already-running revocation watch retains its
original end-of-run output behavior.
