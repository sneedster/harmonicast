import { existsSync, readFileSync } from 'node:fs';
import { isAbsolute, join, normalize, relative, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { DATA_DIR } from './db.js';

export const PLUGIN_API_VERSION = 1;
const PLUGINS_DIR = join(DATA_DIR, 'plugins');
const REGISTRY_PATH = join(PLUGINS_DIR, 'registry.json');

export interface PluginManifest {
  id: string;
  displayName: string;
  apiVersion: number;
  entry: string;
  settings?: { key: string; label: string; type: 'text' | 'url' | 'password' | 'boolean' | 'number'; secret?: boolean }[];
}

interface RegistryEntry { id: string; directory: string; enabled: boolean; source: string; revision: string; checksum: string; }
export interface LoadedPlugin { manifest: PluginManifest; source: string; revision: string; checksum: string; enabled: boolean; status: 'loaded' | 'disabled' | 'error'; error?: string; }

function safePluginPath(directory: string): string | null {
  if (!directory || isAbsolute(directory) || normalize(directory).startsWith('..')) return null;
  const path = resolve(PLUGINS_DIR, directory);
  return relative(PLUGINS_DIR, path).startsWith('..') ? null : path;
}

function isManifest(value: unknown): value is PluginManifest {
  if (!value || typeof value !== 'object') return false;
  const manifest = value as Record<string, unknown>;
  return typeof manifest.id === 'string' && /^[a-z][a-z0-9-]{2,63}$/.test(manifest.id)
    && typeof manifest.displayName === 'string' && manifest.displayName.length > 0 && manifest.displayName.length <= 100
    && manifest.apiVersion === PLUGIN_API_VERSION && typeof manifest.entry === 'string'
    && !isAbsolute(manifest.entry) && !normalize(manifest.entry).startsWith('..');
}

function registryEntries(): RegistryEntry[] {
  if (!existsSync(REGISTRY_PATH)) return [];
  try {
    const parsed = JSON.parse(readFileSync(REGISTRY_PATH, 'utf8')) as unknown;
    if (!Array.isArray(parsed)) return [];
    return parsed.filter((entry): entry is RegistryEntry => !!entry && typeof entry === 'object'
      && typeof (entry as Record<string, unknown>).id === 'string'
      && typeof (entry as Record<string, unknown>).directory === 'string'
      && typeof (entry as Record<string, unknown>).enabled === 'boolean'
      && typeof (entry as Record<string, unknown>).source === 'string'
      && typeof (entry as Record<string, unknown>).revision === 'string'
      && typeof (entry as Record<string, unknown>).checksum === 'string');
  } catch { return []; }
}

/** Loads only approved local entries; a broken plugin never blocks Harmonicast startup. */
export async function loadPlugins(): Promise<LoadedPlugin[]> {
  const seen = new Set<string>();
  const loaded: LoadedPlugin[] = [];
  for (const entry of registryEntries()) {
    if (seen.has(entry.id)) { loaded.push({ manifest: { id: entry.id, displayName: entry.id, apiVersion: PLUGIN_API_VERSION, entry: '' }, ...entry, status: 'error', error: 'Duplicate plugin id' }); continue; }
    seen.add(entry.id);
    const directory = safePluginPath(entry.directory);
    try {
      if (!directory) throw new Error('Invalid plugin directory');
      const manifest = JSON.parse(readFileSync(join(directory, 'harmonicast-plugin.json'), 'utf8')) as unknown;
      if (!isManifest(manifest) || manifest.id !== entry.id) throw new Error('Invalid or mismatched plugin manifest');
      if (!entry.enabled) { loaded.push({ manifest, ...entry, status: 'disabled' }); continue; }
      const entryPath = resolve(directory, manifest.entry);
      if (relative(directory, entryPath).startsWith('..')) throw new Error('Invalid plugin entry');
      const module = await import(pathToFileURL(entryPath).href);
      if (typeof module.default !== 'function') throw new Error('Plugin entry must have a default factory export');
      loaded.push({ manifest, ...entry, status: 'loaded' });
    } catch {
      loaded.push({ manifest: { id: entry.id, displayName: entry.id, apiVersion: PLUGIN_API_VERSION, entry: '' }, ...entry, status: 'error', error: 'Plugin could not be loaded' });
    }
  }
  return loaded;
}
