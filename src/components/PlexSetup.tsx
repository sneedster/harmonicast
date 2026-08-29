import { useEffect, useState } from 'react';
import { Loader2, Music2, Server } from 'lucide-react';
import { getPlexSetupLibraries, getPlexSetupServers, selectPlexSetupSource, type PlexSetupLibrary, type PlexSetupServer } from '@/lib/api';

export function PlexSetup({ onComplete }: { onComplete: () => Promise<void> | void }) {
  const [servers, setServers] = useState<PlexSetupServer[]>([]);
  const [selectedServer, setSelectedServer] = useState('');
  const [libraries, setLibraries] = useState<PlexSetupLibrary[]>([]);
  const [selectedLibrary, setSelectedLibrary] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    getPlexSetupServers()
      .then(({ servers }) => setServers(servers))
      .catch((err) => setError(err instanceof Error ? err.message : 'Could not load your Plex servers'))
      .finally(() => setLoading(false));
  }, []);

  async function chooseServer(machineIdentifier: string) {
    setSelectedServer(machineIdentifier);
    setSelectedLibrary('');
    setLibraries([]);
    setError('');
    if (!machineIdentifier) return;
    setLoading(true);
    try {
      const result = await getPlexSetupLibraries(machineIdentifier);
      setLibraries(result.libraries);
      if (!result.libraries.length) setError('That server has no Music libraries available.');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not reach that Plex server');
    } finally {
      setLoading(false);
    }
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!selectedServer || !selectedLibrary) return;
    setSaving(true);
    setError('');
    try {
      await selectPlexSetupSource(selectedServer, selectedLibrary);
      await onComplete();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not save your Plex source');
      setSaving(false);
    }
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-ink-950 p-5">
      <form onSubmit={submit} className="w-full max-w-lg rounded-2xl border border-ink-800 bg-ink-900 p-7 shadow-2xl">
        <div className="mb-6 flex h-12 w-12 items-center justify-center rounded-xl bg-amber-500/15 text-amber-400">
          <Music2 className="h-6 w-6" />
        </div>
        <h1 className="text-xl font-semibold text-white">Choose your Plex music</h1>
        <p className="mt-2 text-sm leading-relaxed text-ink-400">
          Resonance found the Plex servers you own. Pick the server and Music library this installation should use.
        </p>
        {error && <p className="mt-5 rounded-lg border border-red-500/30 bg-red-500/10 p-3 text-sm text-red-300">{error}</p>}
        <label className="mt-6 block text-sm font-medium text-ink-200">Plex server</label>
        <select value={selectedServer} onChange={(event) => void chooseServer(event.target.value)} disabled={loading || saving}
          className="mt-2 w-full rounded-xl border border-ink-700 bg-ink-950 px-3 py-3 text-sm text-white disabled:opacity-60">
          <option value="">{loading ? 'Loading servers…' : 'Select a Plex server'}</option>
          {servers.map((server) => <option key={server.machineIdentifier} value={server.machineIdentifier}>{server.name}</option>)}
        </select>
        <label className="mt-5 block text-sm font-medium text-ink-200">Music library</label>
        <select value={selectedLibrary} onChange={(event) => setSelectedLibrary(event.target.value)} disabled={!selectedServer || loading || saving}
          className="mt-2 w-full rounded-xl border border-ink-700 bg-ink-950 px-3 py-3 text-sm text-white disabled:opacity-60">
          <option value="">{loading && selectedServer ? 'Loading libraries…' : 'Select a Music library'}</option>
          {libraries.map((library) => <option key={library.key} value={library.key}>{library.title}</option>)}
        </select>
        {!loading && !servers.length && !error && <p className="mt-4 text-sm text-amber-300">No owned Plex servers were found for this account.</p>}
        <button disabled={!selectedServer || !selectedLibrary || loading || saving} className="mt-7 flex w-full items-center justify-center gap-2 rounded-xl bg-amber-500 py-3 text-sm font-semibold text-ink-950 disabled:opacity-50">
          {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Server className="h-4 w-4" />}
          Use this Plex library
        </button>
      </form>
    </main>
  );
}
