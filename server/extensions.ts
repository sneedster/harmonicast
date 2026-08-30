import { randomUUID } from 'node:crypto';
import { db } from './db.js';

export type ExtensionRequestStatus = 'resolving' | 'acquiring' | 'waiting_for_plex' | 'failed' | 'fulfilled';


/** Creates a durable request for an in-process plugin; no bridge token exists. */
export function createPluginExtensionRequest(pluginId: string, user: { id: number; email: string }, query: string, mode: 'search' | 'artist' = 'search') {
  const id = randomUUID();
  db.prepare(`INSERT INTO extension_requests
    (id, extension_id, requester_id, requester_email, query, mode, status, expires_at)
    VALUES (?, ?, ?, ?, ?, ?, 'resolving', datetime('now', '+1 day'))`
  ).run(id, pluginId, user.id, user.email, query, mode);
  return { id };
}


export function getExtensionRequest(id: string) {
  return db.prepare(`SELECT id, extension_id, requester_id, requester_email, query, mode, status, message,
    plex_track_id, created_at, updated_at FROM extension_requests WHERE id = ?`).get(id) as {
      id: string; extension_id: string; requester_id: number; requester_email: string; query: string; mode: 'search' | 'artist';
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
