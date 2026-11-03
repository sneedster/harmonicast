# SoulSync acquisition setup

The SoulSync adapter is available starting with Harmonicast v1.1.29.

Connect an existing [SoulSync server](https://github.com/Nezreka/SoulSync) under
**Settings → Music acquisition → Your personal connection**:

1. Choose **SoulSync**.
2. Enter its base URL, such as `http://soulsync:8008`, without `/api/v1`.
3. Enter a SoulSync API key with download access, generated in
   **Settings → Advanced → SoulSync API**. Read-only keys cannot submit requests.
4. Tap **Connect**. The app checks the authenticated status endpoint before saving.

**Set up from another device** offers the same choice. Enter the pairing code,
choose the service, and test the connection. The credentials are staged until
**Save connection** is selected on Android. Keys are stored using Android
Keystore encryption and are never included in guest responses.

SoulSync must already send completed music through its configured import pipeline
into the selected Plex music library. Harmonicast does not change SoulSync's
sources, quality rules, naming, beets configuration, or retry automations.
Playback and queue fulfillment still use Plex.

## Requests and recovery

Harmonicast uses SoulSync's [native REST API](https://github.com/Nezreka/SoulSync/blob/main/Support/API.md),
not its Lidarr compatibility layer. A track is submitted to `POST /api/v1/request`
with separate artist, title, and available duration fields. The returned
`request_id` is polled through `GET /api/v1/request/{id}`. The existing
MusicBrainz catalogue, room quotas, access checks, Plex matching, and durable queue
receipts apply to both acquisition providers.

A server download completion is not a playable queue item. Harmonicast waits for a
matching track in the selected Plex library, including any delay introduced by
beets or Plex scanning. It also checks Plex during SoulSync's queued, searching,
and downloading states because server-side request tracking can lag the import.

SoulSync request status records are temporary: they expire after about an hour
and disappear on a server restart. If tracking disappears, Harmonicast checks
Plex first. If no match is available, the request becomes **unconfirmed**; inspect
SoulSync and dismiss tracking before deciding to request again. An uncertain
submission is never automatically replayed. Changing the service, key, or Plex
library pauses requests bound to the previous connection.

## Access and current limits

The first adapter supports the owner's personal connection and guest requests
through that owner's room, subject to existing room controls. Publishing a
SoulSync key through the shared Plex configuration is not supported: SoulSync's
admin API key is not equivalent to MusicGrabber's dedicated non-admin account.
Existing shared-account setup continues to use MusicGrabber. The private
MusicGrabber-specific bad-file replacement action is hidden on SoulSync.

The configured URL must be reachable from the Android device. A LAN URL works
on the home network; remote access needs a separately configured reachable
endpoint or private network route. The app does not configure networking. For Michael's deployment, a separate
Tailscale Funnel API route was configured during this integration; see
[validation and deployment notes](SOULSYNC_VALIDATION.md).

Disconnecting removes the app's saved key, but does not revoke the key on the
server. Revoke it in SoulSync when it is no longer needed.
