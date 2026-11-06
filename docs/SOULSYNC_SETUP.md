# SoulSync acquisition setup

Use Harmonicast v1.1.30 or later for SoulSync’s full native acquisition workflow.
Version 1.1.29 used its transfer-only endpoint.

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

SoulSync must be configured to publish into the selected Plex music library.
Harmonicast leaves its sources, quality, naming, extras and automations under
SoulSync’s control. Playback and queue fulfillment use Plex.

## Requests and recovery

Harmonicast submits structured artist, title and available duration data to
`POST /api/v1/wishlist`, saves the returned track identity, then calls
`POST /api/v1/wishlist/process`. This starts SoulSync’s normal verification,
tagging and library-import workflow, including its configured Plex scan.
A busy processor does not discard the accepted wishlist item or replay the
submission; SoulSync’s normal processor can pick it up later.

The app checks the paginated wishlist and the selected Plex library. A wishlist
entry disappearing does not prove a successful import. Only a matching playable
Plex track fulfills the request and adds it to the queue. Existing catalogue,
room quotas, access checks and durable queue receipts still apply.

Older transfer-only request receipts become **unconfirmed** after a Plex check;
the app does not replay them. Inspect SoulSync before dismissing and requesting
again. A submission whose response was lost is also never automatically replayed.
Changing the service, key or Plex library pauses requests bound to the previous
connection.

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
