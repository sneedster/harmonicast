# Harmonicast architecture

## Personal playback

The Android app owns its queue, playback, preferences and local listening state.
It connects directly to Plex for authentication, library browsing, streaming,
artwork and supported rating/history updates. Shared libraries permit listening;
Plex writes and room hosting require owner access. Android Auto controls this
same local playback through Media3.

## Nearby rooms

Sharing is explicit and temporary. The host advertises a room only while enabled.
Native guests use nearby discovery and room-scoped capabilities. Browser guests
and displays use the HTTP gateway and bundled pages hosted by the Android device.
Keep these room endpoints separate from Plex credentials and owner-only controls.
Ending sharing revokes room access and disconnects guests.

Requests take priority over the automatic mix, with fairness between participants.
Guests can request and vote; Plex configuration and playback ownership stay with
the owner. Internet guest control and a public relay are outside scope.

## Native TV transfer

The owner can offer playback to a nearby receiver and take it back. Audio flows
through the host without revealing its Plex token. Transfer needs reachable local
Wi-Fi or Ethernet. Active Android Auto projection retains playback authority
until it disconnects.

## Browse and persistence

Plex collection queries use bounded pages, selected-library identity checks and
artist filters that include albums, singles and EPs. Profile and playback state
live in Android storage; a temporary room connection does not replace the home
Plex configuration. Signing out clears token-bearing local playback state.

GitHub signed APKs are the supported distribution. Keep release signing keys and
local SDK configuration outside Git.
