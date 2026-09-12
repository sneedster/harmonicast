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

## Optional music acquisition

The process-wide acquisition coordinator owns MusicBrainz catalogue lookups,
MusicGrabber authentication, durable requests, import polling, and Plex
verification. It starts with the app/media service and resumes persisted work
when the process returns; it does not schedule a separate Android background job.
Playback remains Plex-only. Shared read-only libraries cannot acquire or host.

Account login uses MusicGrabber's session API and fetches `/api/auth/me` to bind
requests to the actual account ID. Keystore-encrypted credentials are separate
from ordinary preferences. Remembered login permits one serialized re-login after
an authentication rejection; invalid credentials require user intervention.
Advanced API-key mode is explicit and never an automatic fallback.

Accepted requests survive dismissal of the picker, room teardown, and process
restart. Uncertain submissions retain a separate state and are never replayed;
the owner can dismiss their tracking after checking MusicGrabber. Account, service,
and Plex-library identities prevent recovery against a different destination.
Import completion examines track outcomes as well as the overall job state. The
selected Plex library must contain a matching track before queue admission.

Queue writes serialize across local core instances. Acquisition fulfillment
commits the queue and a durable request-ID receipt together, then updates the
request record; recovery cannot enqueue the same fulfillment twice. Acquisitions
use the ordinary manual-request fairness rules. Pending guest acquisitions reserve
slots in the existing five-request allowance, including ordinary track requests.

A room-scoped permission is off by default and enforced by the gateway for browser,
display, and Bluetooth operations. Only safe catalogue/request DTOs cross that
boundary. Bluetooth uses bounded pages and full recording IDs for submission;
display labels may be shortened to fit transport limits.

Computer setup uses a separate temporary listener and capability, with one paired
browser, a five-minute lifetime, failed-pairing limits, and Host/Origin validation.
Login candidates stay staged until confirmation on Android. Cancelled or replaced
candidates have their sessions revoked where possible. The setup listener uses
the same private-network HTTP trust boundary as local rooms and never accepts room
capabilities. Its lifecycle is retained in a ViewModel across activity recreation.

MusicGrabber's bulk single input currently splits on the first hyphen-like
separator and limits each line to 200 characters. Harmonicast rejects ambiguous
artist names or multiline/oversized selections rather than silently acquiring a
different track. MusicGrabber service installation and account management remain
outside Harmonicast.

MusicBrainz acquisition results require an official Album, EP, or Single release.
Selection revalidates eligibility and submits only artist/title. MusicGrabber owns
duplicate checking; Harmonicast performs no Plex lookup before submission. Plex
track-only lookup happens after import completion to locate the playable item,
stopping at the first match and using recent tracks only as a fallback.

MusicGrabber background tracking uses a 30-second polling cycle and spaces account
GET calls at least five seconds apart. Automatic connection checks cache success
for five minutes and failure for one minute; concurrent checks share one result.
Opening room controls uses that cache. Explicit Test connection can refresh it.
The polling loop backs off to at most five minutes when a cycle throws.

## Shared Plex capability foundation (development)

`PlexAccessPolicy` separates hosting, native playback offering, Plex writes, manual
acquisition management, and submission. Shared source ownership is never upgraded.
The UI, media service, native receiver, and acquisition coordinator reuse this
policy. `NearbyGuestParticipation` tracks live client participation in process and
prevents nested hosting and personal acquisition while joined; it is not persisted.
Receiver pairing remains explicit and retains the existing local-network and lease
checks. Receiver source replacement stops output; profile reload tears down hosted
rooms, and the room monitor checks source/participation changes every ten seconds.

`GuestControl.roomVote` preserves owner voting and allows shared-host guest reactions
without Plex writes. Shared down votes can skip automatic tracks, while manual
requests remain queued. The room router retains deduplication and sanitized DTOs.
Delegated configuration discovery, credentials, and live revocation enforcement are
still pending the proof described in `SHARED_PLEX_ACCESS_PLAN.md`.

### Verified room lifetime

`LocalPlexClient.canAccessMusicLibrary` checks the actual server machine identifier
and selected music section with the saved source token. `PlexRequestFailure`
retains HTTP status without response bodies or URLs. `PlexRoomAccessGuard` binds
verification to a source and serializes checks with a timeout. A pending startup
cannot be reused after teardown, source replacement, or joining a nearby room.

The media service verifies before opening its gateway, reports pending access in
the existing room summary, and rechecks roughly every minute. A confirmed denial
or missing selected section revokes access and stops playback/output; transient
failures preserve an existing room but cannot create a newly authorized room.
Source/guest changes are also checked locally at ten-second lifecycle intervals
and on each routed request. GuestRoomRouter guards both LAN and nearby operations,
rechecks before queue admission and after suspended responses, and native host
transfer uses the same room guard. This does not bypass server-side restrictions
or upgrade shared sources to owner authority. Real-device propagation remains an
acceptance check; automated tests use synthetic responses and real loopback sockets.

The developer-only `scripts/plex_sharing_probe.py` collects phase 1 evidence. It is
not part of Android's production delegated-acquisition path and publishes nothing.
