# Resonance

Resonance is a self-hosted, shared music player for a Plex Music library. One
browser or Android device is the active player; anyone granted access to the
selected Plex library can sign in, search, queue music, and vote. The active
player streams through Resonance, so the queue and playback state are shared
between the web client, Android app, and Android Auto.

## What it does

- Plex PIN/forwarding sign-in; no Plex client secret is required.
- First-run setup lets the Plex server owner select an owned server and Music
  library. Plex library sharing controls guest access.
- Shared request queue with per-user limits, cooldowns, and fair ordering.
- Automatic weighted playback when the request queue runs out.
- Plex-backed 1–10 ratings, completed-play scrobbles, and playback history.
- **Track Radio** uses Plex Sonic Analysis to queue up to 20 nearby tracks.
- Web player, native Android player, and Android Auto search, queue, voting,
  Next, and Track Radio controls.

Resonance is experimental software. Keep it on a trusted network or put it
behind HTTPS and appropriate access controls before exposing it publicly.

## Architecture

```
Browser / Android / Android Auto ── HTTPS + WebSocket ── Resonance ── Plex Media Server
                                                         └── SQLite volume
```

The server stores application state, Plex source selection, and the selected
owner's Plex token in its local SQLite volume. It never sends that owner token
to web or Android clients.

## Deploy with Docker

1. Copy `.env.example` to `.env`.
2. Set `PUBLIC_URL` to the exact URL users will open, without a trailing slash.
   For example: `https://resonance.example.com`.
3. Optionally pin `RESONANCE_IMAGE_TAG` to a tested image tag.
4. Start it:

   ```bash
   docker compose pull
   docker compose up -d
   ```

5. Open Resonance and sign in with Plex. On a new installation, choose an
   owned Plex Media Server and one Music library.

The named `resonance-data` volume holds the SQLite database. Do not remove it
unless you intentionally want to reset the installation and repeat Plex setup.

### Unraid

Import [`unraid/resonance.xml`](unraid/resonance.xml), or create a container
with these settings:

- Image: `mjstrong/resonance:latest` (or a pinned `main-<commit>` tag)
- Port: container `3001` mapped to your chosen host port
- Volume: `/app/data` mapped to an Unraid appdata directory
- Variable: `PUBLIC_URL` set to the exact browser-facing URL

After updating, pull the image and recreate the container. Persistent app data
remains in `/app/data`.

## Plex setup and access

The first Plex account to finish setup is the Resonance owner. It must own the
Plex server being selected. Other people sign in with their own Plex accounts;
they can join only if Plex itself has shared the selected Music library with
them. Resonance treats identity as authentication and access control—the queue,
ratings, and playback history are shared.

`PUBLIC_URL` is required because Plex must return the browser to Resonance after
sign-in. HTTPS is strongly recommended outside a trusted LAN.

## Android and Android Auto

The Android client is in [`android/`](android/). Enter your Resonance URL, sign
in through Plex, and select **Play here** to make that phone the active player.

Android Auto supports search, browsing the shared Request queue, Up next,
play/pause, Next, and **Queue Track Radio**. Its Up next list is synchronized
from the shared Resonance queue. The checked-in build helper
uses the compatible local Java/SDK setup:

```bash
./android/build-debug.sh
```

The project deliberately permits cleartext Android traffic for trusted LAN
servers; use HTTPS for any server reachable outside that network.

### Signed Android release APK

Create the local signing identity once. The keystore and passwords are ignored
by Git and must be backed up securely—future updates need the same key.

```bash
./android/create-release-keystore.sh
```

Then build a signed, versioned APK. The result is written to the ignored
`android/releases/` directory.

```bash
VERSION_NAME=1.0.2 VERSION_CODE=3 ./android/build-release.sh
```

This runs the required server, web, Docker image, and Compose checks before
signing. To upgrade an installed copy, increase both the point version and
`VERSION_CODE`, then keep the same keystore. Do not distribute an APK if you
have lost the keystore. `RESONANCE_SKIP_RELEASE_CHECKS=1` is reserved for
local troubleshooting; do not use it for a published APK.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `RESONANCE_PORT` | `3001` | Docker host port |
| `RESONANCE_IMAGE_TAG` | `latest` | Docker image tag; pin after testing |
| `PUBLIC_URL` | none | Exact URL used for Plex sign-in return |
| `PLEX_CLIENT_IDENTIFIER` | generated | Optional stable Plex client ID; generated and persisted when omitted |

No Plex URL, Plex token, Music library key, or administrator email belongs in
the environment for a normal first-time setup. The optional `MUSIC_SERVER_*`
variables are legacy Subsonic migration support only and should be left unset
for Plex deployments.

## Development

Install dependencies for both parts of the app:

```bash
npm install
(cd server && npm install)
```

Run the API server and the Vite client in separate terminals:

```bash
(cd server && npx tsx index.ts)
npm run dev
```

The Vite development server proxies `/api` and `/ws` to port 3001.

Useful local checks:

```bash
./scripts/release-check.sh
./android/build-debug.sh
```

`npm test` runs the server suite in the same Node 20 Alpine runtime as the
production image. It needs Docker but does not modify the checkout or require
Plex credentials.

### Release verification

Run `./scripts/release-check.sh` for every Docker or web release. It is also
run automatically by `./android/build-release.sh` before an APK is signed.
The gate covers the Node 20 server tests, web type checking, linting,
production build, production Docker image build, and Compose configuration.

Before publishing, perform these short manual checks against the candidate
image or APK:

1. **Clean Docker install:** use a new Compose project name and empty data
   volume, open the app, complete Plex sign-in, select an owned server and
   Music library, then verify search and first playback.
2. **Docker upgrade:** start from a copy of a real installation's data volume,
   recreate it with the candidate image, confirm the existing Plex source and
   queue remain available, and play a track.
3. **Web:** sign in, claim playback, play/pause/skip, search by artist and
   title, add Track Radio, vote, and confirm the shared queue updates.
4. **Android and Android Auto:** install the new APK over the prior version,
   reconnect it, repeat playback and voting, then verify the same controls and
   shared queue in Android Auto or DHU.

Do not publish a Docker image or GitHub APK release until the automated gate
and the relevant manual checks have passed.

## API notes

All REST routes are under `/api` and require a Resonance Bearer token unless
they start the Plex sign-in flow. The app clients are the supported API
consumers. Key integration routes are:

- `GET /api/auth/plex` and callback: Plex sign-in.
- `GET /api/search`, `GET /api/stream/:id`, and `GET /api/cover-art/:id`:
  selected Plex library search and playback.
- `GET|POST|DELETE /api/queue` and `POST /api/queue/similar`: shared queue and
  Track Radio.
- `GET|POST /api/now-playing`, `POST /api/player/claim`, and
  `GET /api/player/status`: active-player coordination.
- `POST /api/vote` and `POST /api/scrobble`: shared Plex ratings and completed
  play reporting.

The WebSocket endpoint is `/ws`; it broadcasts queue, now-playing, active
player, vote, and playback-position changes.

## License

Private project.
