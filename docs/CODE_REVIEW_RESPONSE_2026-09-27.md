# Response to the 2026-09-27 code review

The review was checked against the working tree. This response records what changed and what still needs design or device validation.

| Finding | Response |
| --- | --- |
| 1. Release gate | Set the Android unit-test worker heap to 4 GiB and isolate each test class. The heap alone still hit the swipe-test idle timeout during the Unreal installation. The full release gate now passes with 329 tests and debug lint. The v1.1.19 release record remains at 328 tests because that is a historical validation claim. |
| 2. Media-button entry | Confirmed as a separate Media3 entry path. This needs a device test for headset and Android Auto behavior before altering playback controls. The existing P0 document describes the `onConnect` policy, not a complete guarantee for externally started media-button intents. |
| 3. Plex token on HTTP URLs | Local HTTP is part of the documented private-network compatibility choice. Stream/artwork URLs use the selected server token when Plex supplies one, with the account token as a possible fallback (`MainActivity.kt:427`, `LocalPlexClient.kt:624`). Header authentication cannot simply replace the URL token for Media3/Coil media fetches. Prefer an HTTPS Plex connection where available; do not force it until LAN-only Plex setups and certificate behavior are tested. Token exposure on an untrusted Wi-Fi network remains a real consequence to document. |
| 4. Backup | Excluded `harmonicast.xml` from cloud backup, legacy backup, and device transfer. This also covers saved queue entries with tokenized stream URLs. The owner accepts signing in to Plex again after a device transfer; that convenience cost is small. |
| 5. Room code | Removed the live room code from unauthenticated guest/display HTML. The owner's screen still shows the short address and four-letter code; QR invitations still carry the temporary capability. This keeps the documented convenient join methods. |
| 6. BLE player offers | Accountless nearby joining is an intentional convenience choice: proximity bootstraps a room-scoped capability, with guest commands guarded by the room router. BLE is not paired or encrypted. The reported `offer-player` consequence needs a physical BLE test before changing receiver selection. |
| 7. HTTP request line | Added a bounded line reader for request and header lines so oversized lines stop before unbounded allocation. The accept loop retries an IO failure while the room is still running. |
| 8. UI-thread storage/poll | Confirmed as a performance concern. Replacing `commit()` with `apply()` changes persistence guarantees, so this should be addressed with IO dispatch and measured with StrictMode. |
| 9. Playback publication order | Construct the media item before publishing and scrobbling the next track. A dequeue can still consume a track whose URL has expired, so recovery needs a separate design. |
| 10. `onGetItem` | Removed the fabricated fallback. Current and queued song IDs resolve to real items; unknown IDs return an error. Other Plex library IDs still need a lookup path and a DHU check. |
| 11. Playback errors | User-visible error classification and retry policy remain open. |
| 12. Acquisition wording | Changed the roadmap claim to a title, artist, and available duration match. This is a heuristic; it does not establish file identity. |
| 13. Hygiene | The ignored foreign `.env` and build trees were left untouched to avoid deleting unrelated local state. The host-specific build wrapper remains per this workspace's Android instructions. |

The reported UI items and device-only observations in the review remain unverified here.

## Validation status

After the Unreal installation finished, `./scripts/release-check.sh` passed in 2m 56s with per-class isolation: 329 unit tests across 46 test result classes, zero failures, zero errors, and debug lint completed. The debug APK assembled. `git diff --check` passed. No physical-device or DHU check was performed for these changes.
