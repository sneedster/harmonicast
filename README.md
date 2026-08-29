# Resonance — Weighted Jukebox

A self-hosted, collaborative music jukebox for Subsonic-compatible music servers (Navidrome, Airsonic, Gonic, etc.). The host device plays audio through its speakers while passengers search, queue, and vote on songs from their phones — all in real time.

Designed for road trips: run the server on your Unraid box, open the web app on your Android Auto head unit as the host player, and let passengers control the music from their phones.

## Features

- **Weighted random play** — the jukebox auto-fills the queue using a rating system that learns from your listening habits (plays, skips, and thumbs up/down).
- **Multi-user queue** — anyone can search the library and add songs to the shared queue.
- **Fair queue ordering** — when multiple people add songs, the queue automatically alternates between users so nobody can dominate it.
- **Per-user request limits** — configurable limit on how many songs each person can have in the queue at once.
- **Voting** — passengers can thumbs-up or thumbs-down the currently playing song.
- **Song cooldown** — prevent recently played songs from being re-queued.
- **Active player session** — only one device plays audio at a time. If a second host device connects, it can take over playback or watch as a guest.
- **Listening stats** — top rated, most played, and recent activity.
- **Self-hosted** — no cloud dependencies. All data stays on your server.

## Architecture

```
┌─────────────┐     HTTP/WS      ┌──────────────────┐     Subsonic API     ┌──────────────┐
│  Browser     │ ──────────────► │  Resonance Server │ ──────────────────► │  Navidrome   │
│  (host or    │ ◄────────────── │  (Node.js +       │ ◄────────────────── │  / Airsonic  │
│   guest)     │                 │   SQLite + WS)    │                     │  / Gonic     │
└─────────────┘                  └──────────────────┘                     └──────────────┘
```

- **Server** (`server/`): Express.js REST API + WebSocket server, SQLite database, Subsonic proxy. Serves the built frontend.
- **Client** (`src/`): React + Vite SPA. Talks to the server via REST for actions and WebSocket for real-time updates.
- **Database**: SQLite file stored in `data/resonance.db`. No external database needed.

## Quick Start

### Using Docker (recommended)

1. Clone this repo.
2. Copy `.env.example` to `.env` and fill in the environment variables (see [Configuration](#configuration)).
3. Pull and run:
   ```bash
   docker compose pull
   docker compose up -d
   ```
4. Open `http://localhost:3001` in your browser (or the host port you map in Compose).
5. Sign in with Plex using the email you set as `ADMIN_EMAIL` — this user becomes the host.
6. Configure your music server connection (or pre-configure it via env vars), then start playing.

Pin `RESONANCE_IMAGE_TAG` in `.env` to an immutable release tag after testing.

### Plex sign-in

Resonance uses Plex's OAuth-style PIN/forwarding flow for sign-in. It does not
need a Plex client secret or Google Cloud configuration.

1. Set `PUBLIC_URL` to the exact browser-facing URL of Resonance, for example
   `https://resonance.example.com`.
2. Optionally set `PLEX_CLIENT_IDENTIFIER` to a stable opaque identifier. If
   omitted, Resonance generates one and persists it in its SQLite data volume.
3. Set `ADMIN_EMAIL` to the email on the Plex account that should become the
   owner. After signing in, the owner can invite more people from Settings.

Plex library integration is in progress. This initial conversion slice changes
identity only; the configured music source remains Subsonic-compatible until
the Plex library adapter is complete.

### Using Docker on Unraid

1. Import [`unraid/resonance.xml`](unraid/resonance.xml) as a custom template, or create a container with:
   - Image: `mjstrong/resonance:latest`
   - Port: `3001` mapped to your preferred host port
   - Volume: `/app/data` mapped to your appdata folder (for persistent SQLite storage)
3. Set the environment variables (see [Configuration](#configuration)).
4. Open the web app on any device on your network.

### Development

1. Install frontend dependencies:
   ```bash
   npm install
   ```
2. Install server dependencies:
   ```bash
   cd server && npm install && cd ..
   ```
3. Start the server (in one terminal):
   ```bash
   cd server && npx tsx index.ts
   ```
4. Start the Vite dev server (in another terminal):
   ```bash
   npm run dev
   ```
5. Open the Vite URL (usually `http://localhost:5173`). Vite proxies `/api` and `/ws` to the server on port 3001.

### Android app

The native Android client lives in [`android/`](android/). It supports Plex sign-in, real-time queue/search/voting, and host playback using the same API as the web client.

1. Ensure the server has `PUBLIC_URL` and a Subsonic connection configured as described above.
2. Open the `android` directory in Android Studio (JDK 17 and Android SDK 35), then run the `app` configuration on a device.
3. Enter the public/LAN URL of your Resonance server and sign in. The app returns from Plex using the `resonance://auth` deep link.

For LAN HTTP servers, cleartext traffic is deliberately enabled for this app. Use HTTPS for any server reachable outside your trusted network.

## How Host / Guest Works

- **Admin-controlled access**: set `ADMIN_EMAIL` to designate the host. Only the admin can create the first account. After signing in, the host invites others by adding their Plex account email in the Settings page.
- The host's browser plays audio through its speakers. Only one device is the active player at a time — if a second host device connects, it can take over playback or watch as a guest.
- Other users sign in with Plex and join as **guests** — they can search, queue, and vote, but audio plays on the host device only.
- For road trips: open the web app on your Android Auto head unit as the host, and passengers connect from their phones.

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `3001` | Port the server listens on |
| `DATA_DIR` | `../data` | Directory for the SQLite database file |
| `PUBLIC_URL` | _(none)_ | The exact public URL where users access Resonance (no trailing slash). Required for Plex's sign-in return URL. Example: `http://localhost:3001` |
| `PLEX_CLIENT_IDENTIFIER` | generated | Optional stable Plex client identifier. If omitted, generated once and persisted in SQLite. |
| `ADMIN_EMAIL` | _(none)_ | The email address of the admin/owner. This user is always the host and is the only one who can create the first account. After the admin has signed in, they can invite more people from the Settings page. |
| `MUSIC_SERVER_URL` | _(none)_ | Pre-configure your Navidrome/Subsonic server address so you don't have to type it every time. Example: `http://192.168.1.50:4533` |
| `MUSIC_SERVER_USER` | _(none)_ | Username for the music server |
| `MUSIC_SERVER_PASSWORD` | _(none)_ | Password for the music server |
| `MUSIC_SERVER_NAME` | _(none)_ | Optional display name for the music server |

When `MUSIC_SERVER_URL`, `MUSIC_SERVER_USER`, and `MUSIC_SERVER_PASSWORD` are all set, the server auto-configures the connection on startup. When `ADMIN_EMAIL` is set, that user automatically becomes the host on first sign-in — no setup screen needed.

### Host Settings (in-app)

- **Song Cooldown** — minutes before a song can be re-queued (0 = disabled).
- **Max Requests Per User** — how many songs one person can have in the queue at once.
- **Manage Invites** — add or remove Plex account email addresses that are allowed to sign in.

## API Reference

All API endpoints are under `/api`. Authentication uses a Bearer token issued by Resonance after Plex sign-in.

### Authentication

#### `GET /api/auth/config`
Check which authentication methods are configured.
```json
// Response 200
{ "plexOAuth": true }
```

#### `GET /api/auth/plex`
Starts the Plex OAuth/PIN forwarding flow. Plex returns the browser to
`/api/auth/plex/callback`; the Plex access token stays server-side.

The Android app uses `?mobile_redirect=resonance://auth`; this is the only accepted mobile return URL and the resulting token is placed in the URL fragment.

#### `GET /api/auth/plex/callback`
Plex sign-in callback. It validates the Plex account, checks the invite list,
creates a Resonance session, and redirects with `#auth_token=<token>&auth_email=<email>`.

#### `POST /api/auth/signout`
Sign out (invalidates the current token). Requires auth.

#### `GET /api/auth/me`
Get the current authenticated user. Requires auth.
```json
// Response 200
{ "user": { "id": 1, "email": "user@example.com", "name": "User Name" } }
```

### Invites

#### `GET /api/invites`
List all invited emails. Host only.
```json
// Response 200
[{ "email": "friend@example.com", "created_at": "2026-01-01 00:00:00" }]
```

#### `POST /api/invites`
Add an email to the invite list. Host only.
```json
// Request
{ "email": "friend@example.com" }
```

#### `DELETE /api/invites`
Remove an email from the invite list. Host only.
```json
// Request
{ "email": "friend@example.com" }
```

### Connection

#### `GET /api/connection`
Check if a Subsonic server is configured. Requires auth.
```json
// Response 200
{
  "configured": true,
  "baseUrl": "http://...",
  "username": "...",
  "serverName": "...",
  "isHost": true,
  "isActivePlayer": true,
  "hasActivePlayer": true
}
```

#### `POST /api/connection`
Set up the Subsonic server connection. The caller becomes the host. Requires auth.
```json
// Request
{ "baseUrl": "http://192.168.1.50:4533", "username": "admin", "password": "pass", "serverName": "My Music" }
```

#### `DELETE /api/connection`
Disconnect the server and clear all queue/now-playing state. Host only.

### Player Session

#### `POST /api/player/claim`
Claim this device as the active player. Any previous active player is released. Requires auth.
```json
// Response 200
{ "ok": true }
```

#### `GET /api/player/status`
Check whether this device is the active player and whether any device is currently active. Requires auth.
```json
// Response 200
{ "isActivePlayer": true, "hasActivePlayer": true }
```

### Queue

#### `GET /api/queue`
Get the current queue (ordered by position). Requires auth.
```json
// Response 200
[
  { "id": "song123", "title": "...", "artist": "...", "album": "...", "duration": 240, "coverArt": "...", "addedByEmail": "user@example.com", "isManual": true }
]
```

#### `POST /api/queue`
Add a song to the queue (manual add). Enforces duplicate check, cooldown, and per-user limit. Requires auth.
```json
// Request
{ "song": { "id": "song123", "title": "...", "artist": "...", "album": "...", "duration": 240, "coverArt": "..." } }
```

#### `POST /api/queue/auto`
Add an auto-picked song to the queue (host only, used by jukebox mode).
```json
// Request
{ "song": { "id": "song123", "title": "...", "artist": "...", "album": "...", "duration": 240, "coverArt": "..." } }
```

#### `POST /api/queue/dequeue`
Remove and return the first song in the queue. Host only.
```json
// Response 200
{ "song": { "id": "song123", "title": "...", "artist": "...", "album": "...", "duration": 240, "coverArt": "..." } }
```

#### `DELETE /api/queue`
Clear the entire queue. Host only.

#### `DELETE /api/queue/auto`
Clear only auto-picked songs from the queue (keeps manually added ones). Host only.

### Now Playing

#### `GET /api/now-playing`
Get the current playing state. Requires auth.
```json
// Response 200
{ "song": { "id": "...", "title": "...", "artist": "...", "album": "...", "duration": 240, "coverArt": "..." }, "isPlaying": true }
```

#### `POST /api/now-playing`
Update the now-playing state. Host only.
```json
// Request
{ "song": { "id": "...", "title": "...", "artist": "...", "album": "...", "duration": 240, "coverArt": "..." }, "isPlaying": true }
```

### Votes

#### `POST /api/vote`
Vote on the currently playing song. One vote per user per song. Requires auth.
```json
// Request
{ "vote": "up" }  // or "down"
```

#### `GET /api/votes/:songId`
Get up/down vote counts for a song. Requires auth.
```json
// Response 200
{ "up": 3, "down": 1 }
```

#### `POST /api/votes/clear/:songId`
Clear all votes for a song (called when the song changes). Host only.

### Stats

#### `POST /api/stats/play-event`
Record a play event (complete, skip, thumbs_up, thumbs_down) and update the song's auto-rating. Requires auth.
```json
// Request
{ "song_id": "...", "title": "...", "artist": "...", "album": "...", "duration": 240, "cover_art": "...", "event": "complete", "progress": 1.0 }
```

#### `POST /api/stats/batch`
Fetch stats for multiple songs by ID. Requires auth.
```json
// Request
{ "songIds": ["id1", "id2", "id3"] }

// Response 200
[{ "song_id": "id1", "title": "...", "rating": 7.5, "play_count": 12, ... }]
```

#### `GET /api/stats/top-rated?limit=50&minRating=5.05`
Get top-rated songs. Requires auth.

#### `GET /api/stats/most-played?limit=50`
Get most-played songs. Requires auth.

#### `GET /api/stats/recent?limit=30`
Get recent play history. Requires auth.

### Settings

#### `GET /api/settings`
Get current settings and host status. Requires auth.
```json
// Response 200
{ "cooldownMinutes": 30, "maxRequestsPerUser": 5, "isHost": true }
```

#### `PUT /api/settings`
Update settings. Host only.
```json
// Request
{ "cooldownMinutes": 15, "maxRequestsPerUser": 3 }
```

### Subsonic Proxy

#### `POST /api/subsonic`
Proxy a Subsonic API call (used for search, random songs, etc.). Requires auth.
```json
// Request
{ "baseUrl": "http://...", "endpoint": "search3", "query": { "query": "queen", "songCount": 40 } }

// Response 200
{ "subsonic-response": { "status": "ok", "searchResult3": { "song": [...] } } }
```

#### `GET /api/search?q=...`
Search the music library. Requires auth. Returns array of songs.

#### `GET /api/random-songs?size=100`
Get random songs from the library. Requires auth.

#### `POST /api/scrobble`
Scrobble a play to the Subsonic server. Requires auth.
```json
// Request
{ "id": "song123", "submission": true }
```

#### `GET /api/stream/:id`
Stream audio from the Subsonic server (proxied through the server). Requires auth.

#### `GET /api/cover-art/:id?size=300`
Get cover art from the Subsonic server (proxied). Requires auth.

### WebSocket

#### `GET /ws`
WebSocket connection for real-time updates. The server broadcasts messages when state changes:

```json
{ "type": "queue" }           // Queue changed — refetch /api/queue
{ "type": "now_playing" }     // Now-playing changed — refetch /api/now-playing
{ "type": "votes" }           // Votes changed — refetch vote counts
{ "type": "player_session" }  // Active player changed — refetch /api/player/status
{ "type": "force_skip" }      // Host forced a skip — skip the current song
```

## Tech Stack

- **Frontend**: React 18, TypeScript, Vite, Tailwind CSS, Lucide icons
- **Server**: Node.js, Express, better-sqlite3, ws (WebSocket)
- **Database**: SQLite (via better-sqlite3)
- **Music server**: Any Subsonic-compatible server (Navidrome recommended)

## License

Private project.
