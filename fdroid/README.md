# F-Droid submission material

`io.github.sneedster.harmonicast.yml` is the proposed metadata for an initial
F-Droid submission. It is intentionally kept in this source repository so the
metadata, store description, privacy policy, licensing, and release process can
be reviewed together.

Before submitting it to the F-Droid `fdroiddata` repository:

1. Build and test the first release using application ID
   `io.github.sneedster.harmonicast`.
2. Commit and push the release source, then create an immutable release tag.
3. Use the tag's full commit SHA. F-Droid requires an immutable source revision,
   not a branch name.
4. Copy this file to `fdroiddata/metadata/io.github.sneedster.harmonicast.yml`
   and submit it for review.

`NonFreeNet` is declared because a working installation uses Plex, a
proprietary network service. This declaration is informational; it does not
change Harmonicast's AGPL-3.0-or-later license.
