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

Your personal connection also serves guest requests in rooms you host, subject to
the room toggle. To let shared Plex users acquire music and allow guest requests
in rooms they host, use the [SoulSync request relay](#shared-plex-access). The
private MusicGrabber bad-file replacement action remains specific to MusicGrabber.

The configured URL must be reachable from the Android device. A LAN URL works
on the home network; remote access needs a separately configured reachable
endpoint or private network route. The app does not configure networking. For Michael's deployment, a separate
Tailscale Funnel API route was configured during this integration; see
[validation and deployment notes](SOULSYNC_VALIDATION.md).

Disconnecting removes the app's saved key, but does not revoke the key on the
server. Revoke it in SoulSync when it is no longer needed.

## Shared Plex access

Keep the dedicated **Harmonicast** Plex library and its existing setup album.
The album's locked summary is how approved Plex clients find the connection.
The owner can update the same record in Settings without creating a new library,
album, or Plex share. The updated app reads both the older MusicGrabber format
and the new SoulSync relay format. Recipients need an updated app before they can
use a SoulSync record.

1. Run `services/soulsync-relay/relay.py` with Python 3 or build the Dockerfile in
   that directory. Set `SOULSYNC_URL` to the trusted SoulSync origin. Set
   `SOULSYNC_KEY_FILE` and `REQUEST_KEY_FILE` to separate private files readable
   by the process. The former contains a SoulSync admin key. Generate the latter
   with `python3 -c 'import secrets; print("hcr_" + secrets.token_hex(32))'` and
   write it directly into the private file. The container runs as UID/GID 65534.
2. Use your HTTPS reverse proxy to publish only the relay path. The service
   listens on `127.0.0.1:8097` by default. Use host networking for loopback
   access, or set `BIND_HOST=0.0.0.0` in an isolated container network and bind
   the host port to loopback. Strip the public path prefix so the service
   receives `/v1/status` and `/v1/requests`. Never send unencrypted client
   traffic to the relay across a network.
3. In **Settings → Music acquisition → Manage shared access**, choose **SoulSync**.
   Enter the relay HTTPS URL, including its path prefix, and the `hcr_` request
   key. Test the connection, verify that SoulSync publishes to the selected Plex
   library, and choose whether to permit acquisition in rooms hosted by shared
   users. Review and publish. Harmonicast updates the locked album summary in place.
4. Verify that an approved Plex recipient can request a missing track from a
   room they host and that it joins the queue when Plex has a playable match.

The relay accepts only single-track requests and a connection check. It holds
the SoulSync admin key server-side, submits to the native wishlist, and starts
normal wishlist processing. It never returns SoulSync data beyond the request
receipt. The app checks Plex for completion and preserves room quotas and opt-outs.
The relay allows up to 10 submissions per minute and 100 per rolling day across
all holders of the key. Restarting it resets those limits.

Removing a recipient's Plex access stops fresh app requests. A copied relay key
can still submit outside the app. To revoke copies, rotate the request key on the
relay, restart it, and publish the new key to your intended recipients. Disabling
shared access in the app replaces the active summary with an inactive record.
