import { useState, type FormEvent } from 'react';
import { Search, Loader2, Music2 } from 'lucide-react';
import type { Song } from '@/types';
import { usePlayer } from '@/hooks/usePlayer';
import { search as searchSongs } from '@/lib/subsonic';
import { SongRow } from '@/components/SongRow';

export function SearchView() {
  const { connection } = usePlayer();
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<Song[]>([]);
  const [status, setStatus] = useState<'idle' | 'loading' | 'done' | 'error'>('idle');
  const [error, setError] = useState<string | null>(null);

  async function handleSearch(e: FormEvent) {
    e.preventDefault();
    const q = query.trim();
    if (!q) return;
    setStatus('loading');
    setError(null);
    try {
      const songs = await searchSongs(connection, q);
      setResults(songs);
      setStatus('done');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Search failed.');
      setStatus('error');
    }
  }

  return (
    <div className="animate-fade-in">
      <h2 className="mb-1 text-lg font-semibold text-white">Search your library</h2>
      <p className="mb-4 text-sm text-ink-400">
        Find a Plex track, play it now on the active device, or add it to the shared queue.
      </p>

      <form onSubmit={handleSearch} className="mb-6 flex gap-2">
        <div className="relative flex-1">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-500" />
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Song, artist or album…"
            className="w-full rounded-lg border border-ink-700 bg-ink-850 py-2.5 pl-9 pr-3 text-sm text-white placeholder:text-ink-500 outline-none transition focus:border-amber-500/60 focus:ring-2 focus:ring-amber-500/20"
          />
        </div>
        <button
          type="submit"
          className="rounded-lg bg-amber-500 px-5 text-sm font-semibold text-ink-950 transition hover:bg-amber-400"
        >
          Search
        </button>
      </form>

      {status === 'loading' && (
        <div className="flex items-center justify-center gap-2 py-16 text-ink-400">
          <Loader2 className="h-5 w-5 animate-spin" /> Searching…
        </div>
      )}

      {status === 'error' && (
        <p className="py-16 text-center text-sm text-red-300">{error}</p>
      )}

      {status === 'done' && results.length === 0 && (
        <div className="flex flex-col items-center gap-3 py-16 text-ink-500">
          <Music2 className="h-8 w-8" />
          <p className="text-sm">No tracks matched that search.</p>
        </div>
      )}

      {status === 'idle' && (
        <div className="flex flex-col items-center gap-3 py-16 text-ink-500">
          <Search className="h-8 w-8" />
          <p className="text-sm">Search for something to get started.</p>
        </div>
      )}

      <div className="space-y-0.5">
        {results.map((song, i) => (
          <SongRow key={song.id} song={song} index={i} />
        ))}
      </div>
    </div>
  );
}
