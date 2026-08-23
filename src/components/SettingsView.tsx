import { useState, useEffect, type FormEvent } from 'react';
import { Settings, Clock, Users, Loader2, Check, UserPlus, Trash2, Mail, MonitorSpeaker, Pencil } from 'lucide-react';
import { usePlayer } from '@/hooks/usePlayer';
import {
  updateSettings, listInvites, addInvite, removeInvite,
  claimPlayer, listSessions, setDeviceName,
  type Invite, type SessionDevice,
} from '@/lib/api';

export function SettingsView({ isHostUser }: { isHostUser: boolean }) {
  const { isHost, isActivePlayer, cooldownMinutes, maxRequestsPerUser } = usePlayer();
  const [cooldown, setCooldown] = useState(cooldownMinutes);
  const [maxReq, setMaxReq] = useState(maxRequestsPerUser);
  const [status, setStatus] = useState<'idle' | 'saving' | 'saved'>('idle');

  const [invites, setInvites] = useState<Invite[]>([]);
  const [inviteEmail, setInviteEmail] = useState('');
  const [inviteStatus, setInviteStatus] = useState<'idle' | 'adding' | 'error'>('idle');
  const [inviteError, setInviteError] = useState<string | null>(null);

  const [sessions, setSessions] = useState<SessionDevice[]>([]);
  const [switching, setSwitching] = useState(false);
  const [editingName, setEditingName] = useState(false);
  const [deviceName, setDeviceName] = useState('');
  const [savingName, setSavingName] = useState(false);

  useEffect(() => { setCooldown(cooldownMinutes); }, [cooldownMinutes]);
  useEffect(() => { setMaxReq(maxRequestsPerUser); }, [maxRequestsPerUser]);

  useEffect(() => {
    listInvites().then(setInvites).catch(() => {});
  }, []);

  useEffect(() => {
    if (isHostUser) {
      listSessions().then(setSessions).catch(() => {});
    }
  }, [isHostUser, isActivePlayer]);

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
      await setDeviceName(deviceName.trim());
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

  async function handleAddInvite(e: FormEvent) {
    e.preventDefault();
    if (!inviteEmail.trim()) return;
    setInviteStatus('adding');
    setInviteError(null);
    try {
      await addInvite(inviteEmail.trim());
      setInviteEmail('');
      setInviteStatus('idle');
      const updated = await listInvites();
      setInvites(updated);
    } catch (err) {
      setInviteError(err instanceof Error ? err.message : 'Could not add invite.');
      setInviteStatus('error');
    }
  }

  async function handleRemoveInvite(email: string) {
    try {
      await removeInvite(email);
      setInvites((prev) => prev.filter((i) => i.email !== email));
    } catch {}
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
                  onChange={(e) => setDeviceName(e.target.value)}
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
                  const me = sessions.find(s => s.token === localStorage.getItem('resonance_token'));
                  setDeviceName(me?.deviceName ?? '');
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

      <div>
        <div className="mb-6 flex items-center gap-2">
          <UserPlus className="h-5 w-5 text-amber-400" />
          <h2 className="text-lg font-semibold text-white">Manage Invites</h2>
        </div>

        <div className="rounded-2xl border border-ink-800 bg-ink-900/80 p-6">
          <p className="mb-4 text-xs text-ink-500 leading-relaxed">
            Only people whose Google email is on this list can sign in.
            Add someone by entering their email below.
          </p>

          <form onSubmit={handleAddInvite} className="mb-6">
            <div className="flex gap-2">
              <div className="relative flex-1">
                <Mail className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-500" />
                <input
                  type="email"
                  value={inviteEmail}
                  onChange={(e) => setInviteEmail(e.target.value)}
                  placeholder="friend@example.com"
                  className="w-full rounded-lg border border-ink-700 bg-ink-850 py-2.5 pl-10 pr-3.5 text-sm text-white placeholder:text-ink-500 outline-none transition focus:border-amber-500/60 focus:ring-2 focus:ring-amber-500/20"
                />
              </div>
              <button
                type="submit"
                disabled={inviteStatus === 'adding' || !inviteEmail.trim()}
                className="flex items-center gap-1.5 rounded-lg bg-amber-500 px-4 py-2.5 text-sm font-semibold text-ink-950 transition hover:bg-amber-400 disabled:cursor-not-allowed disabled:opacity-60"
              >
                {inviteStatus === 'adding' ? (
                  <Loader2 className="h-4 w-4 animate-spin" />
                ) : (
                  <>
                    <UserPlus className="h-4 w-4" /> Invite
                  </>
                )}
              </button>
            </div>
            {inviteError && (
              <p className="mt-2 text-sm text-red-400">{inviteError}</p>
            )}
          </form>

          {invites.length === 0 ? (
            <p className="py-4 text-center text-sm text-ink-500">
              No invites yet. Add someone above to let them sign in.
            </p>
          ) : (
            <ul className="space-y-2">
              {invites.map((invite) => (
                <li
                  key={invite.email}
                  className="flex items-center justify-between rounded-lg border border-ink-800 bg-ink-850/50 px-4 py-2.5"
                >
                  <span className="text-sm text-ink-200">{invite.email}</span>
                  <button
                    onClick={() => handleRemoveInvite(invite.email)}
                    className="rounded-md p-1.5 text-ink-500 transition hover:bg-red-500/10 hover:text-red-400"
                    title="Remove invite"
                  >
                    <Trash2 className="h-4 w-4" />
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </div>
  );
}
