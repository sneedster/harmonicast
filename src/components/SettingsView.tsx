import { useState, useEffect, type FormEvent } from 'react';
import { Settings, Clock, Users, Loader2, Check, MonitorSpeaker, Pencil, Server, Smartphone, PlugZap } from 'lucide-react';
import { usePlayer } from '@/hooks/usePlayer';
import { AndroidAppDownload } from '@/components/AndroidAppDownload';
import {
  updateSettings, claimPlayer, listSessions, setDeviceName as setSessionDeviceName,
  getMusicSourceExtension, getPluginSettings, getPlugins, getPlexSource, getServerVersion, installPlugin, savePluginSettings, type InstalledPlugin, type MusicSourceExtensionStatus, type PlexSourceInfo, type SessionDevice,
} from '@/lib/api';

export function SettingsView({ isHostUser }: { isHostUser: boolean }) {
  const { isActivePlayer, cooldownMinutes, maxRequestsPerUser } = usePlayer();
  const [cooldown, setCooldown] = useState(cooldownMinutes);
  const [maxReq, setMaxReq] = useState(maxRequestsPerUser);
  const [status, setStatus] = useState<'idle' | 'saving' | 'saved'>('idle');

  const [sessions, setSessions] = useState<SessionDevice[]>([]);
  const [switching, setSwitching] = useState(false);
  const [editingName, setEditingName] = useState(false);
  const [deviceName, setDeviceNameInput] = useState('');
  const [savingName, setSavingName] = useState(false);
  const [plexSource, setPlexSource] = useState<PlexSourceInfo | null>(null);
  const [serverVersion, setServerVersion] = useState<string | null>(null);
  const [musicSourceExtension, setMusicSourceExtension] = useState<MusicSourceExtensionStatus | null>(null);
  const [plugins, setPlugins] = useState<InstalledPlugin[]>([]);
  const [pluginSource, setPluginSource] = useState(''); const [pluginRevision, setPluginRevision] = useState('');
  const [pluginInstallStatus, setPluginInstallStatus] = useState<string | null>(null); const [installingPlugin, setInstallingPlugin] = useState(false);
  const [pluginValues, setPluginValues] = useState<Record<string, Record<string, string>>>({}); const [pluginSettingsStatus, setPluginSettingsStatus] = useState<Record<string, string>>({});

  useEffect(() => { setCooldown(cooldownMinutes); }, [cooldownMinutes]);
  useEffect(() => { setMaxReq(maxRequestsPerUser); }, [maxRequestsPerUser]);

  useEffect(() => {
    if (isHostUser) {
      listSessions().then(setSessions).catch(() => {});
    }
  }, [isHostUser, isActivePlayer]);

  useEffect(() => {
    if (isHostUser) getPlexSource().then(setPlexSource).catch(() => {});
  }, [isHostUser]);

  useEffect(() => { getServerVersion().then(setServerVersion).catch(() => {}); }, []);
  useEffect(() => { if (isHostUser) getMusicSourceExtension().then(setMusicSourceExtension).catch(() => {}); }, [isHostUser]);
  useEffect(() => { if (isHostUser) getPlugins().then(setPlugins).catch(() => {}); }, [isHostUser]);
  useEffect(() => { for (const plugin of plugins) void getPluginSettings(plugin.id).then(({ settings }) => setPluginValues((current) => ({ ...current, [plugin.id]: Object.fromEntries(settings.map((setting) => [setting.key, setting.value ?? ''])) }))).catch(() => {}); }, [plugins]);

  const activeDevice = sessions.find(s => s.isActivePlayer);

  async function handleSwitchDevice(token: string) {
    if (token === activeDevice?.token) return;
    setSwitching(true);
    try {
      await claimPlayer(token);
      const updated = await listSessions();
      setSessions(updated);
    } finally {
      setSwitching(false);
    }
  }

  async function handleSaveName() {
    if (!deviceName.trim()) return;
    setSavingName(true);
    try {
      await setSessionDeviceName(deviceName.trim());
      const updated = await listSessions();
      setSessions(updated);
      setEditingName(false);
    } finally {
      setSavingName(false);
    }
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setStatus('saving');
    try {
      await updateSettings({ cooldownMinutes: cooldown, maxRequestsPerUser: maxReq });
      setStatus('saved');
      setTimeout(() => setStatus('idle'), 2000);
    } catch {
      setStatus('idle');
    }
  }

  async function handlePluginInstall(e: FormEvent) {
    e.preventDefault();
    if (!pluginSource.trim() || !pluginRevision.trim()) return;
    setInstallingPlugin(true); setPluginInstallStatus(null);
    try {
      const result = await installPlugin(pluginSource.trim(), pluginRevision.trim());
      setPluginInstallStatus(`${result.plugin.displayName} installed. Restart Harmonicast to activate it.`);
      setPluginSource(''); setPluginRevision('');
      setPlugins(await getPlugins());
    } catch (error) { setPluginInstallStatus(error instanceof Error ? error.message : 'Plugin installation failed.'); }
    finally { setInstallingPlugin(false); }
  }
  async function handlePluginSettings(plugin: InstalledPlugin) {
    const values = pluginValues[plugin.id] || {};
    const settings = Object.fromEntries(plugin.settings.filter((field) => !field.secret || values[field.key]).map((field) => [field.key, { value: values[field.key] || '', secret: Boolean(field.secret) }]));
    try { await savePluginSettings(plugin.id, settings); setPluginSettingsStatus((current) => ({ ...current, [plugin.id]: 'Saved' })); }
    catch (error) { setPluginSettingsStatus((current) => ({ ...current, [plugin.id]: error instanceof Error ? error.message : 'Could not save settings' })); }
  }

  return (
    <div className="animate-fade-in space-y-8">

      {/* Playback device section */}
      <div>
        <div className="mb-6 flex items-center gap-2">
          <MonitorSpeaker className="h-5 w-5 text-amber-400" />
          <h2 className="text-lg font-semibold text-white">Playback Device</h2>
        </div>
        <div className="rounded-2xl border border-ink-800 bg-ink-900/80 p-6 space-y-4">
          {isActivePlayer ? (
            <div className="flex items-center gap-3">
              <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-emerald-500/15">
                <MonitorSpeaker className="h-4 w-4 text-emerald-400" />
              </div>
              <div>
                <p className="text-sm font-medium text-white">This device is playing</p>
                <p className="text-xs text-ink-500">Audio is streaming here. Switch to another device below any time.</p>
              </div>
            </div>
          ) : activeDevice ? (
            <div className="flex items-center gap-3">
              <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-amber-500/15">
                <MonitorSpeaker className="h-4 w-4 text-amber-400" />
              </div>
              <div>
                <p className="text-sm font-medium text-white">Playing on {activeDevice.deviceName}</p>
                <p className="text-xs text-ink-500">Pick a different device to move playback.</p>
              </div>
            </div>
          ) : (
            <div className="flex items-center gap-3">
              <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-ink-800">
                <MonitorSpeaker className="h-4 w-4 text-ink-400" />
              </div>
              <div>
                <p className="text-sm font-medium text-white">No device is playing</p>
                <p className="text-xs text-ink-500">Pick a device below to start playback.</p>
              </div>
            </div>
          )}

          {isHostUser && sessions.length > 0 && (
            <div className="space-y-2">
              <label className="text-xs font-medium text-ink-400">Active playback device</label>
              <div className="relative">
                <select
                  value={activeDevice?.token ?? ''}
                  onChange={(e) => e.target.value && handleSwitchDevice(e.target.value)}
                  disabled={switching}
                  className="w-full appearance-none rounded-lg border border-ink-700 bg-ink-850 px-3.5 py-2.5 pr-10 text-sm text-white outline-none transition focus:border-amber-500/60 focus:ring-2 focus:ring-amber-500/20 disabled:opacity-60"
                >
                  {!activeDevice && <option value="">Select a device…</option>}
                  {sessions.map((s) => (
                    <option key={s.token} value={s.token}>
                      {s.deviceName}{s.isActivePlayer ? ' (playing)' : ''}
                    </option>
                  ))}
                </select>
                <div className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-ink-500">
                  {switching ? <Loader2 className="h-4 w-4 animate-spin" /> : <MonitorSpeaker className="h-4 w-4" />}
                </div>
              </div>
            </div>
          )}

          {/* Rename this device */}
          <div className="border-t border-ink-800 pt-4">
            {editingName ? (
              <div className="flex items-center gap-2">
                <input
                  type="text"
                  value={deviceName}
                  onChange={(e) => setDeviceNameInput(e.target.value)}
                  maxLength={100}
                  placeholder="e.g. Living Room Laptop"
                  autoFocus
                  className="flex-1 rounded-lg border border-ink-700 bg-ink-850 px-3.5 py-2 text-sm text-white placeholder:text-ink-500 outline-none transition focus:border-amber-500/60 focus:ring-2 focus:ring-amber-500/20"
                />
                <button
                  onClick={handleSaveName}
                  disabled={savingName || !deviceName.trim()}
                  className="flex items-center gap-1.5 rounded-lg bg-amber-500 px-3 py-2 text-sm font-semibold text-ink-950 transition hover:bg-amber-400 disabled:opacity-60"
                >
                  {savingName ? <Loader2 className="h-4 w-4 animate-spin" /> : <Check className="h-4 w-4" />}
                </button>
                <button
                  onClick={() => setEditingName(false)}
                  className="rounded-lg px-3 py-2 text-sm text-ink-400 transition hover:text-white"
                >
                  Cancel
                </button>
              </div>
            ) : (
              <button
                onClick={() => {
                  const me = sessions.find(s => s.token === localStorage.getItem('harmonicast_token'));
                  setDeviceNameInput(me?.deviceName ?? '');
                  setEditingName(true);
                }}
                className="flex items-center gap-1.5 text-xs text-ink-400 transition hover:text-white"
              >
                <Pencil className="h-3.5 w-3.5" /> Rename this device
              </button>
            )}
          </div>
        </div>
      </div>

      {isHostUser && plexSource?.configured && (
        <div>
          <div className="mb-6 flex items-center gap-2">
            <Server className="h-5 w-5 text-amber-400" />
            <h2 className="text-lg font-semibold text-white">Plex Music Source</h2>
          </div>
          <div className="rounded-2xl border border-ink-800 bg-ink-900/80 p-6">
            <p className="text-sm font-medium text-white">{plexSource.server?.name || 'Plex server'}</p>
            {plexSource.libraries?.filter((library) => library.key === plexSource.selectedLibraryKey).map((library) => (
              <div key={library.key} className="mt-3 flex items-center justify-between rounded-lg border border-ink-800 bg-ink-850/50 px-4 py-2.5">
                <span className="text-sm text-ink-200">{library.title}</span>
                <span className="text-xs font-medium text-emerald-400">Selected Music library</span>
              </div>
            ))}
            <p className="mt-4 text-xs leading-relaxed text-ink-500">Plex controls guest access: share this Music library in Plex, then guests can sign in to Harmonicast. The owner token stays in local Harmonicast data and is never shown to clients.</p>
          </div>
        </div>
      )}

      {isHostUser && (
        <div>
          <div className="mb-6 flex items-center gap-2">
            <PlugZap className="h-5 w-5 text-amber-400" />
            <h2 className="text-lg font-semibold text-white">Connected Music Sources</h2>
          </div>
          <div className="rounded-2xl border border-ink-800 bg-ink-900/80 p-6">
            {musicSourceExtension?.available ? <><p className="text-sm font-medium text-white">{musicSourceExtension.displayName} is available</p><p className="mt-2 text-xs leading-relaxed text-ink-500">Guests can use it only after a library search returns no tracks. It is configured outside Harmonicast.</p></> : <><p className="text-sm font-medium text-white">No connected source is available</p><p className="mt-2 text-xs leading-relaxed text-ink-500">Extensions are optional. See the administrator documentation to connect a compatible private source.</p></>}
          </div>
        </div>
      )}

      {isHostUser && (
        <div>
          <div className="mb-6 flex items-center gap-2">
            <PlugZap className="h-5 w-5 text-amber-400" />
            <h2 className="text-lg font-semibold text-white">Plugins</h2>
          </div>
          <div className="space-y-5 rounded-2xl border border-ink-800 bg-ink-900/80 p-6">
            <p className="text-xs leading-relaxed text-ink-500">Plugins run trusted server code. Install only a repository you control or trust. GitHub sources must be pinned to a tag or commit; a restart activates a newly installed plugin.</p>
            <form onSubmit={handlePluginInstall} className="grid gap-3 md:grid-cols-[minmax(0,1fr)_11rem_auto]">
              <input value={pluginSource} onChange={(e) => setPluginSource(e.target.value)} placeholder="https://github.com/owner/plugin" className="rounded-lg border border-ink-700 bg-ink-850 px-3.5 py-2.5 text-sm text-white placeholder:text-ink-500 outline-none focus:border-amber-500/60" />
              <input value={pluginRevision} onChange={(e) => setPluginRevision(e.target.value)} placeholder="tag or commit" className="rounded-lg border border-ink-700 bg-ink-850 px-3.5 py-2.5 text-sm text-white placeholder:text-ink-500 outline-none focus:border-amber-500/60" />
              <button type="submit" disabled={installingPlugin || !pluginSource.trim() || !pluginRevision.trim()} className="flex items-center justify-center gap-2 rounded-lg bg-amber-500 px-4 py-2.5 text-sm font-semibold text-ink-950 disabled:opacity-60">{installingPlugin && <Loader2 className="h-4 w-4 animate-spin" />} Install</button>
            </form>
            {pluginInstallStatus && <p className="text-sm text-amber-300" role="status">{pluginInstallStatus}</p>}
            <div className="space-y-2">{plugins.map((plugin) => <div key={plugin.id} className="rounded-lg border border-ink-800 bg-ink-850/50 px-4 py-3"><div className="flex items-center justify-between gap-3"><span className="font-medium text-white">{plugin.displayName}</span><span className={plugin.status === 'loaded' ? 'text-xs font-medium text-emerald-400' : 'text-xs font-medium text-amber-300'}>{plugin.status}</span></div><p className="mt-1 truncate text-xs text-ink-500">{plugin.source} · {plugin.revision}</p>{plugin.error && <p className="mt-1 text-xs text-rose-300">{plugin.error}</p>}{plugin.settings.length > 0 && <div className="mt-4 grid gap-3 md:grid-cols-2">{plugin.settings.map((field) => <label key={field.key} className="block"><span className="mb-1 block text-xs font-medium text-ink-400">{field.label}</span><input type={field.secret ? 'password' : field.type === 'number' ? 'number' : field.type === 'url' ? 'url' : 'text'} value={pluginValues[plugin.id]?.[field.key] || ''} placeholder={field.secret ? 'Saved secret is hidden' : ''} onChange={(e) => setPluginValues((current) => ({ ...current, [plugin.id]: { ...current[plugin.id], [field.key]: e.target.value } }))} className="w-full rounded-lg border border-ink-700 bg-ink-900 px-3 py-2 text-sm text-white outline-none focus:border-amber-500/60" /></label>)}</div>}{plugin.settings.length > 0 && <div className="mt-3 flex items-center gap-3"><button onClick={() => void handlePluginSettings(plugin)} className="rounded-lg bg-amber-500 px-3 py-2 text-xs font-semibold text-ink-950">Save plugin settings</button>{pluginSettingsStatus[plugin.id] && <span className="text-xs text-amber-300">{pluginSettingsStatus[plugin.id]}</span>}</div>}</div>)}{!plugins.length && <p className="text-sm text-ink-500">No plugins installed.</p>}</div>
          </div>
        </div>
      )}

      <div>
        <div className="mb-6 flex items-center gap-2">
          <Smartphone className="h-5 w-5 text-amber-400" />
          <h2 className="text-lg font-semibold text-white">Android App</h2>
        </div>
        <div className="rounded-2xl border border-ink-800 bg-ink-900/80 p-6">
          <p className="text-sm leading-relaxed text-ink-400">Install Harmonicast on an Android phone to play music directly or use Android Auto.</p>
          <div className="mt-4"><AndroidAppDownload compact /></div>
          <p className="mt-3 text-xs leading-relaxed text-ink-500">Android may ask you to allow installs from the browser that downloaded the APK.</p>
        </div>
      </div>

      {/* Jukebox settings — only editable when this device is the active player */}
      <div>
        <div className="mb-6 flex items-center gap-2">
          <Settings className="h-5 w-5 text-amber-400" />
          <h2 className="text-lg font-semibold text-white">Host Settings</h2>
        </div>

        <form
          onSubmit={handleSubmit}
          className="space-y-6 rounded-2xl border border-ink-800 bg-ink-900/80 p-6"
        >
          <label className="block">
            <span className="mb-2 flex items-center gap-1.5 text-sm font-medium text-ink-300">
              <Clock className="h-4 w-4 text-amber-400" />
              Song Cooldown
            </span>
            <p className="mb-3 text-xs text-ink-500 leading-relaxed">
              Prevents a song from being added to the queue if it has been played
              within this time window. Set to 0 to disable.
            </p>
            <div className="flex items-center gap-3">
              <input
                type="number"
                min={0}
                max={1440}
                value={cooldown}
                onChange={(e) => setCooldown(Number(e.target.value))}
                className="w-24 rounded-lg border border-ink-700 bg-ink-850 px-3.5 py-2.5 text-sm text-white outline-none transition focus:border-amber-500/60 focus:ring-2 focus:ring-amber-500/20"
              />
              <span className="text-sm text-ink-400">minutes</span>
            </div>
          </label>

          <div className="border-t border-ink-800" />

          <label className="block">
            <span className="mb-2 flex items-center gap-1.5 text-sm font-medium text-ink-300">
              <Users className="h-4 w-4 text-amber-400" />
              Max Requests Per User
            </span>
            <p className="mb-3 text-xs text-ink-500 leading-relaxed">
              Limits how many songs a single person can have in the queue at once.
              When multiple people add songs, the queue automatically alternates
              between users so nobody can dominate it.
            </p>
            <div className="flex items-center gap-3">
              <input
                type="number"
                min={1}
                max={100}
                value={maxReq}
                onChange={(e) => setMaxReq(Number(e.target.value))}
                className="w-24 rounded-lg border border-ink-700 bg-ink-850 px-3.5 py-2.5 text-sm text-white outline-none transition focus:border-amber-500/60 focus:ring-2 focus:ring-amber-500/20"
              />
              <span className="text-sm text-ink-400">songs per person</span>
            </div>
          </label>

          <button
            type="submit"
            disabled={status === 'saving'}
            className="flex items-center gap-2 rounded-lg bg-amber-500 px-5 py-2.5 text-sm font-semibold text-ink-950 transition hover:bg-amber-400 disabled:opacity-60"
          >
            {status === 'saving' ? (
              <>
                <Loader2 className="h-4 w-4 animate-spin" /> Saving…
              </>
            ) : status === 'saved' ? (
              <>
                <Check className="h-4 w-4 text-emerald-600" /> Saved
              </>
            ) : (
              'Save Settings'
            )}
          </button>
        </form>
      </div>

      {serverVersion && (
        <p className="pb-2 text-center text-xs text-ink-600">Harmonicast server v{serverVersion}</p>
      )}

    </div>
  );
}
