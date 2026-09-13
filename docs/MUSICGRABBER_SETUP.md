# MusicGrabber integration setup

This guide is for users connecting a separate, self-hosted
[MusicGrabber](https://gitlab.com/g33kphr33k/musicgrabber) server to Harmonicast.
MusicGrabber must be installed and reachable before connecting.
The integration is optional; Plex remains the playback source. Requested tracks enter normal
queue order only after Harmonicast verifies them in the selected Plex Music library.

To install a server, follow MusicGrabber's
[Quick Start](https://gitlab.com/g33kphr33k/musicgrabber/-/blob/main/README.md#quick-start).
Configure it to save music into your Plex library and follow its
[Plex auto-rescan instructions](https://gitlab.com/g33kphr33k/musicgrabber/-/blob/main/README.md#plex-auto-rescan)
so new tracks become available. For server installation, configuration, and
troubleshooting, use the [MusicGrabber documentation](https://gitlab.com/g33kphr33k/musicgrabber/-/blob/main/README.md)
and project support resources. Harmonicast support covers the app's integration.

Once your server is running, open **Settings → Music acquisition**, enter its
reachable URL and your MusicGrabber username/password, then tap **Connect**.
You can also use **Set up from another device** to enter credentials on a computer
and confirm the connection on Android. API-key connections are available under
**Advanced settings**. This manual connection belongs to the Plex owner.
Owners can use **Set up shared access** to publish a
separate non-admin connection for approved Plex recipients. Shared-library room
hosting does not require acquisition access.

Acquisition starts disabled in each room. The host can enable **Allow music acquisition**
for guest requests using the host device's MusicGrabber connection. Accepted requests
continue even after the room closes.

## Shared Plex access

Owners can publish a dedicated non-admin connection for approved Plex recipients.
Shared sources retain their existing Plex write restrictions. Room hosting does
not require this connection.

Owner setup provides a [portable ZIP](shared-plex-setup/README.txt) through a
five-minute computer download page. Extract it into a dedicated folder readable
by Plex, prepare the library, then connect and review the dedicated account in
Settings. No MusicGrabber source changes or server script are needed. Remote
recipients need a reachable HTTPS MusicGrabber endpoint; Harmonicast does not
configure the reverse proxy or Funnel.

Recipients select the shared music library and refresh shared access in Settings.
Plex access is rechecked before new requests. Removing a share blocks new app
requests after verification, but recipients can copy the published password;
rotate that dedicated password to revoke copied credentials.

For implementation and acceptance evidence, see the
[shared-access plan](SHARED_PLEX_ACCESS_PLAN.md),
[Plex sharing proof kit](plex-sharing-test-kit/README.md), and
[validation notes](acquisition-library-auto-validation.md).

[Back to the app guide](../README.md)
