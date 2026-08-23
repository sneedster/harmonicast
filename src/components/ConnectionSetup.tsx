import { useState, type FormEvent } from 'react';
import { Disc3, Loader2, ServerCog, AlertCircle } from 'lucide-react';
import type { Connection } from '@/types';

export function ConnectionSetup({ onConnected }: { onConnected: (conn: Connection) => Promise<void> | void }) {
  const [baseUrl, setBaseUrl] = useState('');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [status, setStatus] = useState<'idle' | 'saving'>('idle');
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);

    const trimmed = baseUrl.trim().replace(/\/+$/, '');
    const normalized = /^https?:\/\//i.test(trimmed) ? trimmed : `http://${trimmed}`;
    const conn: Connection = { baseUrl: normalized, username: username.trim(), password };

    if (!conn.baseUrl || !conn.username || !conn.password) {
      setError('Please fill in the server address, username and password.');
      return;
    }

    setStatus('saving');
    try {
      await onConnected(conn);
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Could not connect to the server.';
      setError(
        msg.includes('fetch')
          ? 'Could not reach the music server. Check the address and make sure it is accessible from your Resonance server.'
          : msg.includes('Authentication')
          ? 'Wrong username or password for your music server.'
          : msg.includes('valid')
          ? 'The server at that address does not appear to be a Subsonic-compatible music server.'
          : msg,
      );
      setStatus('idle');
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center px-4 py-10 bg-gradient-to-b from-ink-900 to-ink-950">
      <div className="w-full max-w-md animate-fade-in">
        <div className="flex flex-col items-center text-center mb-8">
          <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-amber-500/15 ring-1 ring-amber-500/30 mb-4">
            <Disc3 className="h-7 w-7 text-amber-400" />
          </div>
          <h1 className="text-2xl font-semibold text-white tracking-tight">Resonance</h1>
          <p className="mt-2 text-sm text-ink-400 leading-relaxed">
            Connect to your Subsonic-compatible music server and let the weighted
            jukebox learn what you love.
          </p>
        </div>

        <form
          onSubmit={handleSubmit}
          className="rounded-2xl border border-ink-800 bg-ink-900/80 p-6 shadow-xl shadow-black/30"
        >
          <label className="block mb-4">
            <span className="mb-1.5 flex items-center gap-1.5 text-xs font-medium uppercase tracking-wide text-ink-400">
              <ServerCog className="h-3.5 w-3.5" /> Server address
            </span>
            <input
              type="text"
              value={baseUrl}
              onChange={(e) => setBaseUrl(e.target.value)}
              placeholder="http://192.168.1.50:4533"
              autoComplete="url"
              className="w-full rounded-lg border border-ink-700 bg-ink-850 px-3.5 py-2.5 text-sm text-white placeholder:text-ink-500 outline-none transition focus:border-amber-500/60 focus:ring-2 focus:ring-amber-500/20"
            />
          </label>

          <label className="block mb-4">
            <span className="mb-1.5 block text-xs font-medium uppercase tracking-wide text-ink-400">
              Username
            </span>
            <input
              type="text"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoComplete="username"
              className="w-full rounded-lg border border-ink-700 bg-ink-850 px-3.5 py-2.5 text-sm text-white placeholder:text-ink-500 outline-none transition focus:border-amber-500/60 focus:ring-2 focus:ring-amber-500/20"
            />
          </label>

          <label className="block mb-5">
            <span className="mb-1.5 block text-xs font-medium uppercase tracking-wide text-ink-400">
              Password
            </span>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
              className="w-full rounded-lg border border-ink-700 bg-ink-850 px-3.5 py-2.5 text-sm text-white placeholder:text-ink-500 outline-none transition focus:border-amber-500/60 focus:ring-2 focus:ring-amber-500/20"
            />
          </label>

          {error && (
            <div className="mb-4 flex items-start gap-2 rounded-lg border border-red-500/30 bg-red-500/10 px-3 py-2.5 text-sm text-red-300">
              <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
              <span>{error}</span>
            </div>
          )}

          <button
            type="submit"
            disabled={status === 'saving'}
            className="flex w-full items-center justify-center gap-2 rounded-lg bg-amber-500 px-4 py-2.5 text-sm font-semibold text-ink-950 transition hover:bg-amber-400 disabled:cursor-not-allowed disabled:opacity-60"
          >
            {status === 'saving' ? (
              <>
                <Loader2 className="h-4 w-4 animate-spin" /> Connecting…
              </>
            ) : (
              'Connect'
            )}
          </button>
        </form>

        <p className="mt-4 text-center text-xs text-ink-500">
          Works with Navidrome, Airsonic, Gonic and other Subsonic-compatible servers.
        </p>
      </div>
    </div>
  );
}
