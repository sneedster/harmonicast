# Privacy Policy for Harmonicast

Effective date: 2026-08-29

Harmonicast is a self-hosted, shared music player for a Plex Music library. This
policy describes the software's data handling. The person or organisation that
operates a Harmonicast server is responsible for its deployment, access controls,
backups, and any additional legal notices required for its users.

## Where data goes

The Android app and web client communicate only with the Harmonicast server URL
that the user chooses. Harmonicast has no central service operated by this
project, and it does not include advertising, analytics, telemetry, or a
third-party crash-reporting SDK.

To sign in and use a library, the configured Harmonicast server communicates with
Plex. Plex's own privacy policy and terms govern Plex's handling of data.
Harmonicast uses the Plex PIN/forwarding sign-in flow and sends Plex requests only
as needed to authenticate users, discover the selected server and Music
library, search and retrieve music metadata, stream music, and record actions
such as ratings and completed plays.

## Data stored by a Harmonicast server

The server stores its data in its local SQLite database (normally the
`harmonicast-data` Docker volume). Depending on how it is used, that database can
contain:

- Plex account identifiers, email addresses, and optional display names for
  signed-in users;
- session tokens and device names, used to authenticate and manage active
  players;
- the configured Plex server and Music-library selection; and
- shared queue entries, votes, playback state, playback history, and the user
  associated with queue and vote actions.

During first-run setup, the server retains the signing-in owner's Plex access
token. After a source is selected, it retains that owner's token locally to
access the configured library. When a shared user signs in, the server may obtain
that user's Plex server-scoped token from the owner's Plex sharing metadata to
verify access to the selected library. It uses that token only in memory for the
sign-in check and does not store it. Plex access tokens are never sent from the
Harmonicast server to web or Android clients. Server operators should protect the
database volume, use HTTPS outside a trusted local network, and limit access to
people who are authorised to use the Plex library.

## Data stored on a device

The Android app stores the selected Harmonicast server URL and its Harmonicast
session information on the device so the user can remain signed in and control
playback. Android's media session may also display current track metadata and
artwork to system media controls and Android Auto.

## Sharing and retention

Harmonicast does not sell or share personal data with advertisers or analytics
providers. Data is shared with Plex only as described above and is visible to
the operator of the self-hosted Harmonicast server. Data remains in the local
server database until the server operator removes it, deletes the relevant
account/session data, or deletes the database volume.

## Your choices

Do not sign in or configure a server you do not trust. To remove local Android
app data, sign out and clear the app's storage or uninstall it. For data on a
Harmonicast server, contact that server's operator. The operator can remove the
associated database records or reset the installation by deleting its data
volume.

## Contact

For questions about the upstream software, open an issue at
https://github.com/sneedster/harmonicast/issues. For questions about a particular
deployment, contact that deployment's server operator.

## Relationship to Plex

Harmonicast is not affiliated with, endorsed by, or sponsored by Plex. Plex is a
trademark of its respective owner. This policy does not replace Plex's own
privacy policy or terms.
