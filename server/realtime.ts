import { WebSocketServer } from 'ws';
import { getUserByToken } from './auth.js';

let wss = null;
const clients = new Set();

export function initWebSocket(server) {
  // The session token is carried in the WebSocket subprotocol rather than the
  // query string, so it does not land in HTTP access logs. The browser cannot
  // set headers on a WebSocket handshake, so this is the available channel.
  wss = new WebSocketServer({
    server,
    path: '/ws',
    handleProtocols: (protocols) => {
      const first = [...protocols][0];
      return first ?? false;
    },
  });

  wss.on('connection', (ws, req) => {
    const removeClient = () => clients.delete(ws);
    // A malformed close frame from a client can cause `ws` to emit an error
    // (for example, close code 1006 is reserved). Without this listener Node
    // treats it as an unhandled EventEmitter error and terminates the server.
    ws.on('error', (err) => {
      removeClient();
      console.warn('WebSocket client error:', err?.code || err?.message || 'unknown error');
    });
    ws.on('close', removeClient);

    const token = (req.headers['sec-websocket-protocol'] || '').split(',')[0].trim();
    if (!token || !getUserByToken(token)) {
      // 1008 = policy violation.
      ws.close(1008, 'Authentication required');
      return;
    }
    clients.add(ws);
  });
}

export function broadcast(type, data) {
  const msg = JSON.stringify({ type, data });
  for (const ws of clients) {
    if (ws.readyState === 1) ws.send(msg);
  }
}

export function broadcastQueue() {
  broadcast('queue', null);
}

export function broadcastNowPlaying() {
  broadcast('now_playing', null);
}

export function broadcastVotes() {
  broadcast('votes', null);
}

export function broadcastPlayerSession() {
  broadcast('player_session', null);
}

export function broadcastForceSkip() {
  broadcast('force_skip', null);
}

export function broadcastJukebox() {
  broadcast('jukebox', null);
}

export function broadcastPlaybackPosition(position: number) {
  broadcast('playback_position', { position });
}
