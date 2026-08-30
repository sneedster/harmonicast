import { createHash } from 'node:crypto';
import { existsSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { mkdtemp, mkdir, readFile, readdir, rename, rm, writeFile } from 'node:fs/promises';
import { isAbsolute, join, normalize, relative, resolve } from 'node:path';
import { spawn } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import type { Router } from 'express';
import { DATA_DIR } from './db.js';

export const PLUGIN_API_VERSION = 1;
const PLUGINS_DIR = join(DATA_DIR, 'plugins');
const REGISTRY_PATH = join(PLUGINS_DIR, 'registry.json');

export interface PluginManifest {
  id: string;
  displayName: string;
  apiVersion: number;
  entry: string;
  capabilities?: string[];
  settings?: { key: string; label: string; type: 'text' | 'url' | 'password' | 'boolean' | 'number'; secret?: boolean }[];
}

interface RegistryEntry { id: string; directory: string; enabled: boolean; source: string; revision: string; checksum: string; }
export type MusicSourceStatus = 'resolving' | 'acquiring' | 'waiting_for_plex' | 'failed' | 'fulfilled';
export interface PluginHost {
  dataDir: string;
  settings: Record<string, string>;
  musicSource?: {
    getRequest(requestId: string, requesterId: number): { id: string; query: string; status: MusicSourceStatus; message: string | null } | null;
    updateRequest(requestId: string, status: Exclude<MusicSourceStatus, 'fulfilled'>, message: string): void;
    searchPrimaryLibrary(requestId: string, query: string): Promise<{ id: string; title: string; artist: string; album: string; duration: number }[]>;
    fulfill(requestId: string, plexTrackId: string, message: string): Promise<void>;
  };
}
export interface PluginInstance { router?: Router; health?: () => Promise<boolean>; }
export interface LoadedPlugin { manifest: PluginManifest; source: string; revision: string; checksum: string; enabled: boolean; status: 'loaded' | 'disabled' | 'error'; error?: string; instance?: PluginInstance; }

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
    && (manifest.capabilities === undefined || (Array.isArray(manifest.capabilities) && manifest.capabilities.every((capability) => typeof capability === 'string' && /^[a-z][a-z0-9-]{1,63}$/.test(capability))))
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

function writeRegistry(entries: RegistryEntry[]) {
  writeFileSync(`${REGISTRY_PATH}.tmp`, JSON.stringify(entries, null, 2), { mode: 0o600 });
  renameSync(`${REGISTRY_PATH}.tmp`, REGISTRY_PATH);
}

function githubArchiveUrl(source: string, revision: string): string {
  let url: URL;
  try { url = new URL(source); } catch { throw new Error('Plugin source must be a GitHub repository URL'); }
  const match = url.hostname === 'github.com' && url.pathname.match(/^\/([^/]+)\/([^/]+?)(?:\.git)?\/?$/);
  if (!match || url.username || url.password || url.search || url.hash) throw new Error('Plugin source must be a plain GitHub repository URL');
  if (!/^[A-Za-z0-9._/-]{1,200}$/.test(revision) || revision.includes('..')) throw new Error('Plugin revision must be a tag or commit');
  return `https://api.github.com/repos/${encodeURIComponent(match[1])}/${encodeURIComponent(match[2])}/tarball/${encodeURIComponent(revision)}`;
}

async function run(command: string, args: string[], cwd: string) {
  await new Promise<void>((resolvePromise, reject) => {
    const child = spawn(command, args, { cwd, stdio: 'ignore' });
    child.once('error', () => reject(new Error('Plugin installation tool is unavailable')));
    child.once('exit', (code) => code === 0 ? resolvePromise() : reject(new Error('Plugin installation failed')));
  });
}

/** Installs an immutable GitHub source without exposing repository credentials to the browser. */
export async function installPlugin(source: string, revision: string) {
  const archiveUrl = githubArchiveUrl(source, revision);
  const headers: Record<string, string> = { Accept: 'application/vnd.github+json', 'User-Agent': 'Harmonicast' };
  const token = process.env.HARMONICAST_PLUGIN_SOURCE_TOKEN?.trim();
  if (token) headers.Authorization = `Bearer ${token}`;
  const response = await fetch(archiveUrl, { headers, redirect: 'follow', signal: AbortSignal.timeout(30_000) });
  if (!response.ok) throw new Error('Could not download plugin source');
  const archive = Buffer.from(await response.arrayBuffer());
  if (archive.length === 0 || archive.length > 100 * 1024 * 1024) throw new Error('Plugin archive is invalid or too large');
  const checksum = createHash('sha256').update(archive).digest('hex');
  // Stage beside the final plugin directory: `rename` is atomic only within a
  // filesystem, and Docker commonly puts /tmp and the persistent data volume
  // on different mounts.
  await mkdir(PLUGINS_DIR, { recursive: true });
  const staging = await mkdtemp(join(PLUGINS_DIR, '.staging-'));
  try {
    const archivePath = join(staging, 'plugin.tar.gz');
    await writeFile(archivePath, archive, { mode: 0o600 });
    await run('tar', ['-xzf', archivePath, '-C', staging], staging);
    const children = (await readdir(staging, { withFileTypes: true })).filter((entry) => entry.isDirectory());
    if (children.length !== 1) throw new Error('Plugin archive has an invalid layout');
    const extracted = join(staging, children[0].name);
    const manifest = JSON.parse(await readFile(join(extracted, 'harmonicast-plugin.json'), 'utf8')) as unknown;
    if (!isManifest(manifest)) throw new Error('Plugin manifest is invalid');
    await run('npm', ['ci', '--omit=dev', '--ignore-scripts'], extracted);
    const destination = join(PLUGINS_DIR, manifest.id);
    const replacement = join(PLUGINS_DIR, `${manifest.id}.replacement`);
    await rm(replacement, { recursive: true, force: true });
    await rename(extracted, replacement);
    const entries = registryEntries().filter((entry) => entry.id !== manifest.id);
    entries.push({ id: manifest.id, directory: manifest.id, enabled: true, source, revision, checksum });
    const previous = join(PLUGINS_DIR, `${manifest.id}.previous`);
    await rm(previous, { recursive: true, force: true });
    const hadPrevious = existsSync(destination);
    try {
      if (hadPrevious) await rename(destination, previous);
      await rename(replacement, destination);
      writeRegistry(entries);
      await rm(previous, { recursive: true, force: true });
    } catch (error) {
      await rm(destination, { recursive: true, force: true });
      if (hadPrevious && existsSync(previous)) await rename(previous, destination);
      throw error;
    }
    return { id: manifest.id, displayName: manifest.displayName, revision, checksum };
  } finally {
    await rm(staging, { recursive: true, force: true });
  }
}

/** Loads only approved local entries; a broken plugin never blocks Harmonicast startup. */
export async function loadPlugins(createHost: (manifest: PluginManifest) => PluginHost): Promise<LoadedPlugin[]> {
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
      const instance = await module.default(createHost(manifest));
      if (!instance || typeof instance !== 'object') throw new Error('Plugin factory returned an invalid instance');
      loaded.push({ manifest, ...entry, status: 'loaded', instance });
    } catch {
      loaded.push({ manifest: { id: entry.id, displayName: entry.id, apiVersion: PLUGIN_API_VERSION, entry: '' }, ...entry, status: 'error', error: 'Plugin could not be loaded' });
    }
  }
  return loaded;
}
