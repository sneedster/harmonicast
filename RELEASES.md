# Published artifacts

This ledger identifies the intended production artifacts. The Docker image's
OCI revision label and the matching GitHub release tag are the authoritative
link from an image back to source.

| Channel | Version / tag | Source | Notes |
| --- | --- | --- | --- |
| Docker | `mjstrong/harmonicast:1.0.40` | GitHub release `v1.0.40` / commit `8a859d6` | Harmonicast server and web release; also published as `latest`. |
| Android | GitHub release `v1.0.21` / APK `1.0.21` (code 22) | Release commit | Signed Android client with Android Auto playback and browsing fixes. |

## Release rules

- Rebuild and publish Docker for every server or web change.
- Build, version, and publish an Android APK only when Android source changes.
- Never infer that a floating tag identifies source. Use the release tag and
  OCI `org.opencontainers.image.revision` label.
