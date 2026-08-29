# Published artifacts

This ledger identifies the intended production artifacts. Immutable Docker
`main-<commit>` tags and OCI revision labels are the authoritative link from
an image back to source.

| Channel | Version / tag | Source | Notes |
| --- | --- | --- | --- |
| Docker | `mjstrong/harmonicast:1.0.17` | Release commit | Harmonicast server and web release; also published as `latest` and an immutable `main-<commit>` tag. |
| Android | GitHub release `v1.0.15` / APK `1.0.15` (code 16) | Release commit | Signed Android client with a persistent owner-only Take control action. |

## Release rules

- Rebuild and publish Docker for every server or web change.
- Build, version, and publish an Android APK only when Android source changes.
- Never infer that a floating tag identifies source. Use the release tag,
  immutable commit tag, and OCI `org.opencontainers.image.revision` label.
