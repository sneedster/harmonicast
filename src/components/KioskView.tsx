import { useEffect, useState, type FormEvent } from 'react';
import { ListMusic, Loader2, Pause, Play, Plus, RefreshCw, Search, SkipForward, X } from 'lucide-react';
import type { Song } from '@/types';
import { discoverLibrary, searchLibrary } from '@/lib/api';
import { CoverArt } from '@/components/CoverArt';
import { usePlayer } from '@/hooks/usePlayer';

/** A touch-first shared-queue surface. It deliberately uses the normal player
 * context so kiosk requests, playback ownership, and WebSocket updates stay
 * identical to the regular client. */
export function KioskView({ onExit }: { onExit: () => void }) {
  const { current, isPlaying, isHost, queue, enqueue, next, startRandomPlayback, togglePlay } = usePlayer();
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<Song[]>([]);
  const [picks, setPicks] = useState<Song[]>([]);
  const [loadingPicks, setLoadingPicks] = useState(true);
  const [searching, setSearching] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  async function loadPicks() {
    setLoadingPicks(true);
    try {
      setPicks(await discoverLibrary());
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Could not load tonight\'s picks.');
    } finally {
      setLoadingPicks(false);
    }
  }

  useEffect(() => { void loadPicks(); }, []);

  async function handleSearch(event: FormEvent) {
    event.preventDefault();
    const term = query.trim();
    if (!term) return;
    setSearching(true);
    setMessage(null);
    try {
      setResults(await searchLibrary(term));
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Search failed.');
    } finally {
      setSearching(false);
    }
  }

  async function requestSong(song: Song) {
    try {
      await enqueue(song);
      setMessage(`Added ${song.title}`);
      window.setTimeout(() => setMessage(null), 2200);
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Could not add that track.');
    }
  }

  return (
    <div className="min-h-[100dvh] bg-[radial-gradient(circle_at_top,_rgba(245,158,11,0.18),_transparent_38%),linear-gradient(135deg,_#0d0d12,_#171018_48%,_#0d0d12)] px-4 py-5 text-ink-100 sm:px-8 sm:py-8">
      <header className="mx-auto flex max-w-7xl items-center justify-between pb-6">
        <div>
          <p className="text-xs font-bold uppercase tracking-[0.28em] text-amber-400">Shared music</p>
          <h1 className="mt-1 text-2xl font-black tracking-tight text-white sm:text-3xl">Resonance Kiosk</h1>
        </div>
        <button onClick={onExit} className="flex min-h-12 items-center gap-2 rounded-full border border-ink-700 bg-ink-950/40 px-4 text-sm font-semibold text-ink-300 transition hover:border-ink-500 hover:text-white">
          <X className="h-5 w-5" /> Exit kiosk
        </button>
      </header>

      <main className="mx-auto grid max-w-7xl gap-5 lg:grid-cols-[minmax(0,1.1fr)_minmax(340px,0.9fr)]">
        <section className="overflow-hidden rounded-[2rem] border border-white/10 bg-ink-950/55 p-5 shadow-2xl backdrop-blur sm:p-8">
          <div className="grid gap-7 md:grid-cols-[minmax(240px,0.85fr)_minmax(0,1fr)] md:items-center">
            <div className="relative mx-auto w-full max-w-md">
              <div className="absolute -inset-5 rounded-[2.5rem] bg-amber-500/20 blur-3xl" />
              {current ? (
                <CoverArt coverArt={current.coverArt} size={700} className="relative aspect-square h-auto w-full shadow-2xl shadow-black/60" rounded="rounded-[1.7rem]" />
              ) : (
                <div className="relative flex aspect-square items-center justify-center rounded-[1.7rem] border border-dashed border-ink-600 bg-ink-900 text-center text-ink-400">
                  <div><ListMusic className="mx-auto mb-3 h-12 w-12" /><p>Ready for music</p></div>
                </div>
              )}
            </div>
            <div className="text-center md:text-left">
              <p className="text-sm font-semibold uppercase tracking-[0.2em] text-amber-400">{current ? (isPlaying ? 'Now playing' : 'Paused') : 'The floor is yours'}</p>
              <h2 className="mt-3 text-3xl font-black leading-tight text-white sm:text-5xl">{current?.title ?? 'Pick the first song'}</h2>
              <p className="mt-3 text-xl text-ink-300 sm:text-2xl">{current?.artist ?? 'Search Plex and add it to the shared queue.'}</p>
              {current?.album && <p className="mt-1 text-sm text-ink-500">{current.album}</p>}
              {isHost && (
                <div className="mt-7 flex flex-wrap justify-center gap-3 md:justify-start">
                  <button
                    onClick={current ? togglePlay : startRandomPlayback}
                    className="flex min-h-16 items-center gap-3 rounded-2xl bg-amber-400 px-6 text-lg font-black text-ink-950 shadow-lg shadow-amber-500/20 transition hover:scale-[1.02] hover:bg-amber-300 active:scale-95"
                  >
                    {current && isPlaying ? <Pause className="h-6 w-6" fill="currentColor" /> : <Play className="h-6 w-6" fill="currentColor" />}
                    {current ? (isPlaying ? 'Pause' : 'Play') : 'Start random music'}
                  </button>
                  {current && <button onClick={next} className="flex min-h-16 items-center gap-2 rounded-2xl border border-ink-600 bg-ink-900/80 px-5 text-base font-bold text-white transition hover:border-amber-400 hover:text-amber-300"><SkipForward className="h-5 w-5" /> Skip</button>}
                </div>
              )}
            </div>
          </div>
        </section>

        <aside className="rounded-[2rem] border border-white/10 bg-ink-950/55 p-5 shadow-2xl backdrop-blur sm:p-6">
          <div className="mb-4 flex items-center justify-between"><h2 className="text-xl font-black text-white">Up next</h2><span className="rounded-full bg-amber-400/15 px-3 py-1 text-sm font-bold text-amber-300">{queue.length}</span></div>
          <div className="max-h-[48dvh] space-y-2 overflow-y-auto pr-1 scrollbar-thin lg:max-h-[62dvh]">
            {queue.length ? queue.map((song, index) => (
              <div key={song.id} className="flex items-center gap-3 rounded-2xl bg-ink-900/75 p-3">
                <span className="w-5 text-center text-sm font-bold text-ink-500">{index + 1}</span>
                <CoverArt coverArt={song.coverArt} size={96} className="h-12 w-12 shrink-0" rounded="rounded-xl" />
                <div className="min-w-0"><p className="truncate font-bold text-white">{song.title}</p><p className="truncate text-sm text-ink-400">{song.artist}</p></div>
              </div>
            )) : <p className="rounded-2xl border border-dashed border-ink-700 px-4 py-8 text-center text-ink-500">No upcoming tracks yet.</p>}
          </div>
        </aside>

        <section className="rounded-[2rem] border border-white/10 bg-ink-950/55 p-5 shadow-2xl backdrop-blur lg:col-span-2 sm:p-7">
          <div className="flex items-end justify-between gap-4"><div><p className="text-xs font-bold uppercase tracking-[0.25em] text-amber-400">Browse the room</p><h2 className="mt-1 text-2xl font-black text-white">Tonight's picks</h2></div><button onClick={() => void loadPicks()} disabled={loadingPicks} className="flex min-h-12 items-center gap-2 rounded-full border border-ink-600 px-4 text-sm font-bold text-ink-300 transition hover:border-amber-400 hover:text-amber-300 disabled:opacity-60"><RefreshCw className={`h-4 w-4 ${loadingPicks ? 'animate-spin' : ''}`} /> Fresh picks</button></div>
          <div className="mt-5 grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-5 xl:grid-cols-6">
            {picks.slice(0, 12).map((song) => <button key={song.id} onClick={() => void requestSong(song)} className="group text-left"><CoverArt coverArt={song.coverArt} size={240} className="aspect-square h-auto w-full border border-ink-700 transition group-hover:border-amber-400 group-hover:shadow-lg group-hover:shadow-amber-500/15" rounded="rounded-2xl" /><span className="mt-2 block truncate text-sm font-bold text-white">{song.title}</span><span className="block truncate text-xs text-ink-400">{song.artist}</span></button>)}
            {loadingPicks && Array.from({ length: 6 }).map((_, index) => <div key={index} className="aspect-square animate-pulse rounded-2xl bg-ink-800" />)}
          </div>
        </section>

        <section className="rounded-[2rem] border border-white/10 bg-ink-950/55 p-5 shadow-2xl backdrop-blur lg:col-span-2 sm:p-7">
          <h2 className="text-2xl font-black text-white">Request a song</h2>
          <p className="mt-1 text-sm text-ink-400">Search your Plex library and add a track for everyone.</p>
          <form onSubmit={handleSearch} className="mt-5 flex gap-3">
            <label className="sr-only" htmlFor="kiosk-search">Search music</label>
            <input id="kiosk-search" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Artist, song, or album" className="min-h-16 min-w-0 flex-1 rounded-2xl border border-ink-600 bg-ink-900 px-5 text-lg text-white outline-none placeholder:text-ink-500 focus:border-amber-400 focus:ring-2 focus:ring-amber-400/25" />
            <button type="submit" disabled={searching} className="flex min-h-16 items-center gap-2 rounded-2xl bg-white px-5 text-base font-black text-ink-950 transition hover:bg-amber-300 disabled:opacity-60"><Search className="h-5 w-5" /> {searching ? <Loader2 className="h-5 w-5 animate-spin" /> : 'Search'}</button>
          </form>
          {message && <p className="mt-3 text-sm font-medium text-amber-300" role="status">{message}</p>}
          {results.length > 0 && <div className="mt-5 grid gap-3 sm:grid-cols-2 xl:grid-cols-3">{results.map((song) => (
            <button key={song.id} onClick={() => void requestSong(song)} className="group flex min-h-24 items-center gap-3 rounded-2xl border border-ink-700 bg-ink-900/80 p-3 text-left transition hover:border-amber-400 hover:bg-amber-400/10 active:scale-[0.98]">
              <CoverArt coverArt={song.coverArt} size={120} className="h-16 w-16 shrink-0" rounded="rounded-xl" />
              <span className="min-w-0 flex-1"><span className="block truncate font-bold text-white">{song.title}</span><span className="block truncate text-sm text-ink-400">{song.artist}</span></span>
              <Plus className="h-6 w-6 shrink-0 text-amber-400" />
            </button>
          ))}</div>}
        </section>
      </main>
    </div>
  );
}
