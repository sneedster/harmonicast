# Published artifacts

This ledger identifies the intended production artifacts. Immutable Docker
`main-<commit>` tags and OCI revision labels are the authoritative link from
an image back to source.

| Channel | Version / tag | Source | Notes |
| --- | --- | --- | --- |
| Docker | `mjstrong/resonance:1.0.8` | Prepared with this release commit | Server and web release; also published as `latest` and an immutable `main-<commit>` tag. |
| Android | GitHub release `v1.0.14` / APK `1.0.14` (code 15) | `d2e9da0` | Current signed Android client; unchanged by the server-only album-search fix. |

## Release rules

- Rebuild and publish Docker for every server or web change.
- Build, version, and publish an Android APK only when Android source changes.
- Never infer that a floating tag identifies source. Use the release tag,
  immutable commit tag, and OCI `org.opencontainers.image.revision` label.
