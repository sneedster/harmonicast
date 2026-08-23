import { randomBytes, createHash } from 'node:crypto';
import { db } from './db.js';

const CLIENT_NAME = 'weighted-jukebox';
const API_VERSION = '1.16.1';

function randomSalt() {
  return randomBytes(8).toString('hex');
}

function md5(str) {
  return createHash('md5').update(str).digest('hex');
}

export function authParams(conn) {
  const salt = randomSalt();
  return {
    u: conn.username,
    t: md5(conn.password + salt),
    s: salt,
    v: API_VERSION,
    c: CLIENT_NAME,
  };
}

async function subsonicCall(conn, endpoint, extra = {}) {
  const params = new URLSearchParams({ ...authParams(conn), ...extra, f: 'json' });
  const url = `${conn.baseUrl.replace(/\/+$/, '')}/rest/${endpoint}.view?${params.toString()}`;
  let res;
  try {
    res = await fetch(url, { headers: { Accept: 'application/json' } });
  } catch (err) {
    throw new Error(`Could not reach the music server at ${conn.baseUrl}. ${err.message || ''}`.trim());
  }
  const text = await res.text();
  let parsed;
  try { parsed = JSON.parse(text); } catch {
    throw new Error(`The server at ${conn.baseUrl} did not return a valid response (status ${res.status}). It may not be a Subsonic-compatible server.`);
  }
  const sub = parsed['subsonic-response'];
  if (!sub) throw new Error(`The server at ${conn.baseUrl} did not return a Subsonic response. It may not be a Subsonic-compatible server.`);
  if (sub.status === 'failed') {
    const code = sub.error?.code;
    const message = sub.error?.message || 'The music server rejected the request.';
    if (code === 40 || code === 41) throw new Error(`Authentication failed. Check your username and password. (${message})`);
    throw new Error(message);
  }
  return sub;
}

export async function ping(conn) {
  await subsonicCall(conn, 'ping');
}

export async function getRandomSongs(conn, size = 100) {
  const res = await subsonicCall(conn, 'getRandomSongs', { size });
  return (res.randomSongs?.song || []).map(toSong);
}

export async function search(conn, query) {
  const res = await subsonicCall(conn, 'search3', { query, songCount: 40, albumCount: 0, artistCount: 0 });
  return (res.searchResult3?.song || []).map(toSong);
}

export async function scrobble(conn, id, submission) {
  try { await subsonicCall(conn, 'scrobble', { id, submission }); } catch {}
}

function toSong(raw) {
  return {
    id: String(raw.id ?? ''),
    title: String(raw.title ?? 'Unknown title'),
    artist: String(raw.artist ?? 'Unknown artist'),
    album: String(raw.album ?? ''),
    duration: Number(raw.duration ?? 0),
    coverArt: String(raw.coverArt ?? raw.id ?? ''),
  };
}

export function streamUrl(conn, id) {
  const params = new URLSearchParams({ ...authParams(conn), id });
  return `${conn.baseUrl.replace(/\/+$/, '')}/rest/stream.view?${params.toString()}`;
}

export function coverArtUrl(conn, coverArt, size = 300) {
  if (!coverArt) return null;
  const params = new URLSearchParams({ ...authParams(conn), id: coverArt, size: String(size) });
  return `${conn.baseUrl.replace(/\/+$/, '')}/rest/getCoverArt.view?${params.toString()}`;
}

export function getSettings() {
  return db.prepare('SELECT * FROM settings WHERE id = 1').get();
}

export function getConnection() {
  const s = getSettings();
  if (!s || !s.base_url) return null;
  return { baseUrl: s.base_url, username: s.username, password: s.password, serverName: s.server_name };
}
