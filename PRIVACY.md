# Privacy

Harmonicast is a standalone Android Plex music player. The project operates
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

Update checks connect to GitHub for public release metadata. Optional automatic
checks are off by default and run at most once a day when the app launches. APKs
are downloaded only on request. GitHub receives normal connection information
such as IP address and the app version in the update-check user agent; no Plex
credentials or listening data are sent. Downloads stay in the app cache.

Voice input starts only when you select a microphone button. On TV, Harmonicast
requests microphone permission and uses Android’s installed speech-recognition
provider. That provider may send audio to its own service under its privacy policy.
Harmonicast does not store voice recordings; recognized text becomes a search or
filter query sent to the selected Plex server. Listening stops on completion,
cancellation, leaving the app, or after a short timeout.

Questions: [GitHub issues](https://github.com/sneedster/harmonicast/issues).
Harmonicast is not affiliated with or endorsed by Plex.
