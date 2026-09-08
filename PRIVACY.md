# Privacy

Harmonicast 1.1.0 is a standalone Android Plex music player. The project operates
no central account service and includes no advertising, analytics, telemetry,
or third-party crash-reporting SDK.

The app connects to Plex for sign-in, server/library discovery, metadata, artwork,
audio, and supported rating/play-history updates. Plex's privacy policy and terms
apply. The selected Plex server may be local or remotely reachable.

The device stores Plex credentials, server/library selection, preferences, queue,
playback state, and listening information. Android media controls and Android Auto
receive track metadata and artwork. Sign out to remove the personal source;
clear storage or uninstall to remove all local app data. Plex-side data is managed
separately.

Opening a room temporarily enables nearby discovery and a local HTTP guest/display
interface. Guests see shared metadata, requests, votes, and playback state.
Invitation links/QR codes grant temporary access; treat them as access credentials.
Ending a room revokes access. Native transfer sends current audio through the host
to the chosen receiver. Guests/receivers do not receive the host's Plex token.
Network visibility depends on local network security. No public room relay is
provided, and the app does not sell personal data.

The separate server is retired. Android upgrades do not erase its database,
volumes, or backups; their owner manages retention. Legacy Android server
credentials are cleared at cutover while personal Plex configuration is preserved.

Questions: [GitHub issues](https://github.com/sneedster/harmonicast/issues).
Harmonicast is not affiliated with or endorsed by Plex.
