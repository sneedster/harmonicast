# Acquisition library matches and Android Auto checks

Implemented locally and verified with 228 passing Android tests, build and lint.
The strict UI audit and guest/display JavaScript syntax checks pass. Inspected the
390-pixel native catalog row render, including the existing-track action and busy
state. Regression tests cover source-bound artist-cache reuse, badge serialization
for guests, queuing without a remote submission, and blocking downloads if the
final library check fails.

Library badges reuse up to eight artist snapshots for two minutes, at most 300
tracks per artist and four artists per page, with a three-second total lookup
budget. No full-library scan or per-row network request is used for badges. An
unmarked row remains unconfirmed; choosing it performs the ordinary exact-match
check before downloading. Existing matches use the same request queue and room
quota machinery.

Auto tests cover letter-index counts beyond the first 40 items, direct navigation
to offset 400, within-letter pagination, server search offsets and totals, and
Franco Unamerican matching Franco Un-American after a zero-result exact search.
Search pages query Plex, not loaded player/browse items. The punctuation fallback
uses a word anchor, checks at most 1,000 matching candidates and rejects a broader
ambiguous search; it does not scan the complete library or correct spelling.

API shapes were checked against [Plex's API documentation](https://developer.plex.tv/pms/)
and the maintained [PlexAPI library documentation](https://python-plexapi.readthedocs.io/en/latest/modules/library.html).
The letter-index endpoint uses the established singular form, with the documented
plural form as a 404 fallback. Index counts and title sorting determine offsets.
Library changes while a letter view is open have ordinary offset-pagination limits;
reopen the letter index to refresh counts.

Physical head-unit acceptance remains pending. An unauthenticated read-only probe
of the deployed Plex server returned 401; no live query success is claimed from
that probe. On the updated Pixel, verify an existing ABBA track is marked/queued
without downloading, browse a late artist letter in Auto, and repeat the Franco
Unamerican search. The native and web matching indicators share the host result.
