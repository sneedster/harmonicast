import { createCipheriv, createDecipheriv, randomBytes } from 'node:crypto';
import { db } from './db.js';

function masterKey(): Buffer | null {
  const raw = process.env.HARMONICAST_PLUGIN_SETTINGS_KEY?.trim();
  if (!raw) return null;
  try {
    const key = Buffer.from(raw, 'base64');
    return key.length === 32 ? key : null;
  } catch { return null; }
}

function encrypt(value: string): string {
  const key = masterKey();
  if (!key) throw new Error('Secret plugin settings require HARMONICAST_PLUGIN_SETTINGS_KEY');
  const iv = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', key, iv);
  const ciphertext = Buffer.concat([cipher.update(value, 'utf8'), cipher.final()]);
  return `v1:${iv.toString('base64url')}:${cipher.getAuthTag().toString('base64url')}:${ciphertext.toString('base64url')}`;
}

export function decryptPluginSetting(value: string): string {
  const key = masterKey();
  const [version, iv, tag, ciphertext] = value.split(':');
  if (!key || version !== 'v1' || !iv || !tag || !ciphertext) throw new Error('Plugin secret cannot be decrypted');
  const decipher = createDecipheriv('aes-256-gcm', key, Buffer.from(iv, 'base64url'));
  decipher.setAuthTag(Buffer.from(tag, 'base64url'));
  return Buffer.concat([decipher.update(Buffer.from(ciphertext, 'base64url')), decipher.final()]).toString('utf8');
}

export function savePluginSettings(pluginId: string, settings: Record<string, { value: string; secret: boolean }>) {
  const save = db.transaction(() => {
    for (const [key, setting] of Object.entries(settings)) {
      if (!/^[a-zA-Z][a-zA-Z0-9_.-]{0,99}$/.test(key)) throw new Error('Invalid plugin setting key');
      const value = setting.secret ? encrypt(setting.value) : setting.value;
      db.prepare(`INSERT INTO plugin_settings (plugin_id, setting_key, value, is_secret, updated_at)
        VALUES (?, ?, ?, ?, datetime('now'))
        ON CONFLICT(plugin_id, setting_key) DO UPDATE SET value = excluded.value, is_secret = excluded.is_secret, updated_at = datetime('now')`
      ).run(pluginId, key, value, setting.secret ? 1 : 0);
    }
  });
  save();
}

/** Safe for host UI: secret values deliberately have no readable value. */
export function listPluginSettings(pluginId: string) {
  return db.prepare(`SELECT setting_key AS key, is_secret AS isSecret, updated_at AS updatedAt
    FROM plugin_settings WHERE plugin_id = ? ORDER BY setting_key`).all(pluginId) as { key: string; isSecret: number; updatedAt: string }[];
}

/** Server-only values for the plugin identified by the host loader. */
export function getPluginSettings(pluginId: string): Record<string, string> {
  const rows = db.prepare('SELECT setting_key, value, is_secret FROM plugin_settings WHERE plugin_id = ?').all(pluginId) as { setting_key: string; value: string; is_secret: number }[];
  return Object.fromEntries(rows.map((row) => [row.setting_key, row.is_secret ? decryptPluginSetting(row.value) : row.value]));
}

export function pluginSecretsAreAvailable(): boolean { return masterKey() !== null; }
