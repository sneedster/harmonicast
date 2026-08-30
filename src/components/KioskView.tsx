import { useEffect, useRef, useState } from 'react';
import { Disc3, ListMusic, Loader2, Pause, Play, RefreshCw, Search, SkipForward, X } from 'lucide-react';
import type { Song } from '@/types';
import { discoverLibrary, getMusicSourceExtension, launchMusicSourceExtension, searchLibrary, type DiscoveryShelf, type MusicSourceExtensionStatus } from '@/lib/api';
import { CoverArt } from '@/components/CoverArt';
import { usePlayer } from '@/hooks/usePlayer';

type Page = 'home' | 'picks' | 'search' | 'queue';
const pages: { id: Page; label: string; icon: typeof Disc3 }[] = [
  { id: 'home', label: 'Now playing', icon: Disc3 }, { id: 'picks', label: "Tonight's picks", icon: RefreshCw },
  { id: 'search', label: 'Find music', icon: Search }, { id: 'queue', label: 'Queue', icon: ListMusic },
];

export function KioskView({ onExit }: { onExit: () => void }) {
  const { current, isPlaying, isHost, queue, enqueue, next, startRandomPlayback, togglePlay } = usePlayer();
  const kioskHost = isHost && new URLSearchParams(window.location.search).get('host') === '1';
  const [page, setPage] = useState<Page>('home'); const [shelves, setShelves] = useState<DiscoveryShelf[]>([]); const [loadingPicks, setLoadingPicks] = useState(true);
  const [query, setQuery] = useState(''); const [results, setResults] = useState<Song[]>([]); const [searching, setSearching] = useState(false); const [message, setMessage] = useState<string | null>(null); const [musicSourceExtension, setMusicSourceExtension] = useState<MusicSourceExtensionStatus | null>(null); const [openingMusicSource, setOpeningMusicSource] = useState(false);
  const touchStart = useRef<number | null>(null); const searchRequest = useRef(0);
  const [attractMode, setAttractMode] = useState(false); const [activity, setActivity] = useState(0);
  const wakeKiosk = () => { setAttractMode(false); setActivity((value) => value + 1); };
  async function loadPicks() { setLoadingPicks(true); try { setShelves((await discoverLibrary()).shelves); } catch (error) { setMessage(error instanceof Error ? error.message : "Couldn't load picks."); } finally { setLoadingPicks(false); } }
  useEffect(() => { void loadPicks(); }, []);
  useEffect(() => {
    const clearLaunchState = () => setOpeningMusicSource(false);
    window.addEventListener('pageshow', clearLaunchState);
    return () => window.removeEventListener('pageshow', clearLaunchState);
  }, []);
  useEffect(() => {
    const timer = window.setTimeout(() => setAttractMode(true), 75_000);
    return () => window.clearTimeout(timer);
  }, [activity]);
  async function requestSong(song: Song) { try { await enqueue(song); setMessage(`Added ${song.title}`); window.setTimeout(() => setMessage(null), 2000); } catch (error) { setMessage(error instanceof Error ? error.message : 'Could not add that track.'); } }
  useEffect(() => {
    const term = query.trim();
    const request = ++searchRequest.current;
    if (!term) { setResults([]); setMusicSourceExtension(null); setSearching(false); return; }
    const timer = window.setTimeout(() => {
      setSearching(true);
      void searchLibrary(term).then(async (songs) => {
        if (request !== searchRequest.current) return;
        setResults(songs);
        if (songs.length === 0) {
          try {
            const extension = await getMusicSourceExtension();
            if (request === searchRequest.current) setMusicSourceExtension(extension?.available ? extension : null);
          } catch { if (request === searchRequest.current) setMusicSourceExtension(null); }
        } else {
          setMusicSourceExtension(null);
        }
      }).catch((error) => {
        if (request === searchRequest.current) setMessage(error instanceof Error ? error.message : 'Search failed.');
      }).finally(() => {
        if (request === searchRequest.current) setSearching(false);
      });
    }, 240);
    return () => window.clearTimeout(timer);
  }, [query]);
  async function searchConnectedSources() {
    if (!musicSourceExtension || !query.trim()) return;
    setOpeningMusicSource(true);
    try {
      const { launchUrl } = await launchMusicSourceExtension(musicSourceExtension.id, query.trim());
      // The launch request is complete at this point.  Do not retain a busy
      // state while relying on a browser navigation that may be cancelled,
      // restored from cache, or opened externally by a kiosk browser.
      setOpeningMusicSource(false);
      window.location.assign(launchUrl);
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Connected music sources are unavailable.');
      setOpeningMusicSource(false);
    }
  }
  function handleSwipe(endX: number) { if (touchStart.current === null) return; const delta = endX - touchStart.current; touchStart.current = null; if (Math.abs(delta) < 70) return; const index = pages.findIndex((x) => x.id === page); setPage(pages[Math.max(0, Math.min(pages.length - 1, index + (delta < 0 ? 1 : -1)))].id); }
  const panelIndex = pages.findIndex((x) => x.id === page);
  return <div className="h-[100dvh] overflow-hidden bg-[radial-gradient(circle_at_50%_-20%,_rgba(245,158,11,0.28),_transparent_42%),linear-gradient(135deg,_#07070b,_#18101c_52%,_#07070b)] text-ink-100" onPointerDownCapture={wakeKiosk} onKeyDownCapture={wakeKiosk}>
    <header className="flex h-16 items-center justify-between border-b border-white/10 px-4 sm:h-20 sm:px-7"><div className="flex items-center gap-3"><div className="grid h-10 w-10 place-items-center rounded-2xl bg-amber-400 text-ink-950"><Disc3 className={`h-6 w-6 ${isPlaying ? 'animate-spin' : ''}`} style={{ animationDuration: '4s' }} /></div><div><p className="text-xs font-black uppercase tracking-[0.24em] text-amber-400">The house jukebox</p><h1 className="text-lg font-black text-white sm:text-xl">Harmonicast</h1></div></div><button onClick={onExit} className="grid h-11 w-11 place-items-center rounded-full border border-ink-700 text-ink-300 hover:text-white" title="Exit kiosk"><X className="h-5 w-5" /></button></header>
    <div className="h-[calc(100dvh-8rem)] overflow-hidden sm:h-[calc(100dvh-10rem)]" onTouchStart={(e) => { touchStart.current = e.touches[0]?.clientX ?? null; }} onTouchEnd={(e) => handleSwipe(e.changedTouches[0]?.clientX ?? 0)}><div className="flex h-full transition-transform duration-500 ease-out" style={{ width: `${pages.length * 100}%`, transform: `translateX(-${panelIndex * (100 / pages.length)}%)` }}>
      <Panel><div className="grid h-full items-center gap-6 lg:grid-cols-[minmax(260px,.8fr)_minmax(0,1.2fr)] lg:gap-12"><div className="relative mx-auto w-full max-w-[min(58vh,520px)]"><div className="absolute -inset-6 rounded-[2.5rem] bg-amber-500/25 blur-3xl" />{current ? <CoverArt coverArt={current.coverArt} size={700} className="relative aspect-square h-auto w-full shadow-2xl shadow-black/60" rounded="rounded-[2rem]" /> : <div className="relative grid aspect-square place-items-center rounded-[2rem] border border-dashed border-ink-600 bg-ink-900 text-ink-400"><ListMusic className="h-14 w-14" /></div>}</div><div className="text-center lg:text-left"><p className="text-sm font-black uppercase tracking-[.26em] text-amber-400">{current ? (isPlaying ? 'Now playing' : 'Paused') : 'Make the first selection'}</p><h2 className="mt-4 text-4xl font-black leading-[.94] text-white sm:text-6xl">{current?.title ?? 'The room is listening'}</h2><p className="mt-4 text-xl text-ink-300 sm:text-3xl">{current?.artist ?? 'Choose a track from tonight’s picks.'}</p><p className="mt-2 text-sm text-ink-500">{current?.album}</p>{kioskHost ? <div className="mt-8 flex flex-wrap justify-center gap-3 lg:justify-start"><button onClick={current ? togglePlay : startRandomPlayback} className="flex min-h-16 items-center gap-3 rounded-2xl bg-amber-400 px-7 text-lg font-black text-ink-950">{current && isPlaying ? <Pause className="h-6 w-6" fill="currentColor" /> : <Play className="h-6 w-6" fill="currentColor" />}{current ? (isPlaying ? 'Pause' : 'Play') : 'Start the music'}</button>{current && <button onClick={next} className="flex min-h-16 items-center gap-2 rounded-2xl border border-ink-600 px-6 font-black text-white"><SkipForward className="h-5 w-5" /> Skip</button>}</div> : <p className="mt-8 inline-flex rounded-full border border-ink-700 bg-ink-900/80 px-4 py-2 text-sm font-bold text-ink-300">Kiosk guest mode · request music below</p>}</div></div></Panel>
      <Panel><Title eyebrow="Browse the room" title="Tonight’s picks" action={<button onClick={() => void loadPicks()} className="kiosk-action"><RefreshCw className={`h-5 w-5 ${loadingPicks ? 'animate-spin' : ''}`} /> Fresh picks</button>} /><div className="mt-4 grid h-[calc(100%-5rem)] grid-cols-1 grid-rows-4 gap-4 md:grid-cols-2 md:grid-rows-2">{loadingPicks ? Array.from({ length: 8 }, (_, index) => <div key={index} className="animate-pulse rounded-2xl bg-ink-800" />) : shelves.map((shelf) => <div key={shelf.id} className="grid min-h-0 min-w-0 overflow-hidden grid-rows-[auto_auto_minmax(0,1fr)]"><p className="truncate text-sm font-black text-white">{shelf.title}</p><p className="mb-2 truncate text-xs text-ink-500">{shelf.subtitle}</p><div className="grid min-h-0 min-w-0 grid-cols-3 gap-2">{shelf.songs.slice(0, 3).map((song) => <Tile key={song.id} song={song} onPick={requestSong} compact />)}</div></div>)}</div></Panel>
      <Panel><Title eyebrow="Any song you want" title="Find music" /><div className="mt-5 flex h-16 gap-3"><input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Artist, song, or album" className="min-w-0 flex-1 rounded-2xl border border-ink-600 bg-ink-900 px-5 text-xl text-white outline-none focus:border-amber-400" /><div className="grid w-16 place-items-center rounded-2xl bg-white text-ink-950">{searching ? <Loader2 className="h-5 w-5 animate-spin" /> : <Search className="h-5 w-5" />}</div></div><div className="mt-5 grid grid-cols-2 gap-4 md:grid-cols-4">{results.slice(0, 8).map((song) => <Tile key={song.id} song={song} onPick={requestSong} />)}</div>{query.trim() && !results.length && !searching && musicSourceExtension ? <div className="mx-auto mt-10 max-w-xl rounded-2xl border border-amber-400/30 bg-amber-400/10 p-6 text-center"><p className="text-lg font-black text-white">Not in this library</p><p className="mt-2 text-sm text-ink-300">Search connected music sources for this song.</p><button onClick={() => void searchConnectedSources()} disabled={openingMusicSource} className="mt-5 inline-flex min-h-12 items-center gap-2 rounded-xl bg-amber-400 px-5 font-black text-ink-950 disabled:opacity-60">{openingMusicSource && <Loader2 className="h-4 w-4 animate-spin" />} Search connected music sources</button></div> : !results.length && !searching && <p className="mt-12 text-center text-xl text-ink-500">{query.trim() ? 'No matching tracks in this library.' : 'Start typing to search every track. Browse stays intentionally curated.'}</p>}</Panel>
      <Panel><Title eyebrow="Everybody’s music" title="Up next" /><div className="mt-5 grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-3">{queue.slice(0, 9).map((song, index) => <div key={song.id} className="flex h-20 items-center gap-4 rounded-2xl border border-white/10 bg-ink-900/70 p-3"><span className="w-5 text-center font-black text-amber-400">{index + 1}</span><CoverArt coverArt={song.coverArt} size={96} className="h-14 w-14 shrink-0" rounded="rounded-xl" /><div className="min-w-0"><p className="truncate font-black text-white">{song.title}</p><p className="truncate text-sm text-ink-400">{song.artist}</p></div></div>)}</div>{!queue.length && <p className="mt-12 text-center text-xl text-ink-500">No requests yet—be the first.</p>}{queue.length > 9 && <p className="mt-4 text-center font-bold text-amber-300">+ {queue.length - 9} more in the shared queue</p>}</Panel>
    </div></div>
    <nav className="grid h-16 grid-cols-4 border-t border-white/10 bg-ink-950/85 sm:h-20">{pages.map((item) => { const Icon = item.icon; const selected = item.id === page; return <button key={item.id} onClick={() => setPage(item.id)} className={`flex flex-col items-center justify-center gap-1 text-xs font-black ${selected ? 'bg-amber-400 text-ink-950' : 'text-ink-400 hover:bg-ink-800 hover:text-white'}`}><Icon className="h-5 w-5" />{item.label}</button>; })}</nav>{message && <div className="pointer-events-none fixed left-1/2 top-24 -translate-x-1/2 rounded-full bg-amber-400 px-5 py-3 text-sm font-black text-ink-950 shadow-xl" role="status">{message}</div>}
    {attractMode && <button onClick={wakeKiosk} className="fixed inset-0 z-50 grid place-items-center overflow-hidden bg-ink-950 text-center" aria-label="Open Harmonicast kiosk"><div className="absolute inset-0 opacity-45 blur-3xl">{current && <CoverArt coverArt={current.coverArt} size={1200} className="h-full w-full scale-110 object-cover" rounded="rounded-none" />}</div><div className="absolute inset-0 bg-[radial-gradient(circle_at_center,_transparent_20%,rgba(7,7,11,.35)_58%,rgba(7,7,11,.92)_100%)]" /><div className="relative flex h-full w-full flex-col items-center justify-center px-6 py-[max(2rem,6vh)]"><div className="relative h-[clamp(18rem,58vmin,48rem)] w-[clamp(18rem,58vmin,48rem)] max-h-[62dvh] max-w-[82vw]">{current ? <CoverArt coverArt={current.coverArt} size={1200} className="h-full w-full shadow-2xl shadow-black/70" rounded="rounded-[2.75rem]" /> : <div className="grid h-full w-full place-items-center rounded-[2.75rem] bg-ink-800"><Disc3 className="h-[20%] w-[20%] text-amber-400" /></div>}</div><p className="mt-[clamp(1.5rem,4vh,3.5rem)] text-xs font-black uppercase tracking-[0.45em] text-amber-400 sm:text-sm">Harmonicast</p><h2 className="mt-3 max-w-[18ch] text-[clamp(2.25rem,6vw,6.5rem)] font-black leading-[.9] text-white">{current?.title ?? 'Your next song is waiting'}</h2><p className="mt-4 max-w-[28ch] text-[clamp(1.125rem,2.3vw,2.5rem)] text-ink-200">{current?.artist ?? `${queue.length} tracks in the shared queue`}</p><span className="mt-[clamp(2rem,6vh,5rem)] rounded-full border border-amber-400/60 bg-amber-400/10 px-6 py-3 text-sm font-black text-amber-300 animate-pulse">Touch to browse and request</span></div></button>}
  </div>;
}
function Panel({ children }: { children: React.ReactNode }) { return <section className="h-full shrink-0 overflow-hidden px-5 py-6 sm:px-10 sm:py-8" style={{ width: `${100 / pages.length}%` }}>{children}</section>; }
function Title({ eyebrow, title, action }: { eyebrow: string; title: string; action?: React.ReactNode }) { return <div className="flex h-14 items-end justify-between gap-4"><div><p className="text-xs font-black uppercase tracking-[.25em] text-amber-400">{eyebrow}</p><h2 className="mt-1 text-3xl font-black text-white sm:text-4xl">{title}</h2></div>{action}</div>; }
function Tile({ song, onPick, compact = false }: { song: Song; onPick: (song: Song) => Promise<void>; compact?: boolean }) { return <button onClick={() => void onPick(song)} className={`group min-w-0 overflow-hidden text-left ${compact ? 'flex min-h-0 flex-col' : ''}`}><CoverArt coverArt={song.coverArt} size={260} className={`${compact ? 'min-h-0 flex-1' : 'aspect-square h-auto'} w-full border border-ink-700 transition group-hover:-translate-y-1 group-hover:border-amber-400`} rounded={compact ? 'rounded-xl' : 'rounded-2xl'} /><span title={song.title} className={`mt-2 block w-full min-w-0 overflow-hidden font-black text-white [display:-webkit-box] [-webkit-box-orient:vertical] [-webkit-line-clamp:2] ${compact ? 'text-xs leading-4' : 'text-base leading-5'}`}>{song.title}</span><span title={song.artist} className={`block w-full min-w-0 truncate text-ink-400 ${compact ? 'text-[10px]' : 'text-sm'}`}>{song.artist}</span></button>; }
