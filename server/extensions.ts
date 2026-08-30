import { createHmac, randomUUID, timingSafeEqual } from 'node:crypto';
import { db } from './db.js';

export type ExtensionRequestStatus = 'resolving' | 'acquiring' | 'waiting_for_plex' | 'failed' | 'fulfilled';

export interface MusicSourceExtension {
  id: string;
  displayName: string;
  baseUrl: string;
  secret: string;
}

export function getMusicSourceExtension(): MusicSourceExtension | null {
  const baseUrl = process.env.HARMONICAST_MUSIC_SOURCE_EXTENSION_URL?.trim().replace(/\/+$/, '');
  const secret = process.env.HARMONICAST_MUSIC_SOURCE_EXTENSION_SECRET?.trim();
  if (!baseUrl || !secret) return null;
  try {
    const url = new URL(baseUrl);
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) return null;
  } catch { return null; }
  return {
    id: 'music-source-v1',
    displayName: process.env.HARMONICAST_MUSIC_SOURCE_EXTENSION_NAME?.trim() || 'Connected music sources',
    baseUrl,
    secret,
  };
}

export function createExtensionRequest(extension: MusicSourceExtension, user: { id: number; email: string }, query: string, callbackUrl: string) {
  const id = randomUUID();
  const expiresAt = Math.floor(Date.now() / 1000) + 10 * 60;
  db.prepare(`INSERT INTO extension_requests
    (id, extension_id, requester_id, requester_email, query, status, expires_at)
    VALUES (?, ?, ?, ?, ?, 'resolving', datetime('now', '+1 day'))`
  ).run(id, extension.id, user.id, user.email, query);
  const claims = { v: 1, requestId: id, extensionId: extension.id, requesterId: user.id, query, callbackUrl, exp: expiresAt };
  return { id, token: signClaims(claims, extension.secret) };
}

/** Creates a durable request for an in-process plugin; no bridge token exists. */
export function createPluginExtensionRequest(pluginId: string, user: { id: number; email: string }, query: string) {
  const id = randomUUID();
  db.prepare(`INSERT INTO extension_requests
    (id, extension_id, requester_id, requester_email, query, status, expires_at)
    VALUES (?, ?, ?, ?, ?, 'resolving', datetime('now', '+1 day'))`
  ).run(id, pluginId, user.id, user.email, query);
  return { id };
}

export function extensionTokenIsValid(extension: MusicSourceExtension, provided: unknown): boolean {
  if (typeof provided !== 'string' || !provided) return false;
  const expected = Buffer.from(extension.secret);
  const actual = Buffer.from(provided);
  return expected.length === actual.length && timingSafeEqual(expected, actual);
}

export function getExtensionRequest(id: string) {
  return db.prepare(`SELECT id, extension_id, requester_id, requester_email, query, status, message,
    plex_track_id, created_at, updated_at FROM extension_requests WHERE id = ?`).get(id) as {
      id: string; extension_id: string; requester_id: number; requester_email: string; query: string;
      status: ExtensionRequestStatus; message: string | null; plex_track_id: string | null;
      created_at: string; updated_at: string;
    } | undefined;
}

export function updateExtensionRequest(id: string, status: ExtensionRequestStatus, message: string | null, plexTrackId: string | null) {
  db.prepare(`UPDATE extension_requests
    SET status = ?, message = ?, plex_track_id = COALESCE(?, plex_track_id), updated_at = datetime('now')
    WHERE id = ?`).run(status, message, plexTrackId, id);
  return getExtensionRequest(id);
}

function signClaims(claims: object, secret: string): string {
  const payload = Buffer.from(JSON.stringify(claims)).toString('base64url');
  const signature = createHmac('sha256', secret).update(payload).digest('base64url');
  return `${payload}.${signature}`;
}
