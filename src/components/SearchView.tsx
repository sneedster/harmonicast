import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ArrowLeft, Loader2, Music2, Search, Sparkles, X } from 'lucide-react';
import type { Song } from '@/types';
import {
  acquireMusicSourceRecording, browseLibraryArtist, getMusicSourceAlbums,
  getMusicSourceExtension, getMusicSourceRecordings, getMusicSourceReleaseTracks,
  getMusicSourceRequest, launchMusicSourceExtension, searchLibrary,
  type MusicSourceAlbum, type MusicSourceExtensionStatus, type MusicSourceRecording,
} from '@/lib/api';
import { SongRow } from '@/components/SongRow';

type SourceDialog = { id: string; displayName: string; requestId: string };

export function SearchView({ initialQuery = '' }: { initialQuery?: string }) {
  const [query, setQuery] = useState(initialQuery);
  const [results, setResults] = useState<Song[]>([]);
  const [artist, setArtist] = useState<{ name: string; songs: Song[] } | null>(null);
  const [status, setStatus] = useState<'idle' | 'loading' | 'done' | 'error'>('idle');
  const [error, setError] = useState<string | null>(null);
  const [extension, setExtension] = useState<MusicSourceExtensionStatus | null>(null);
  const [openingSource, setOpeningSource] = useState(false);
  const [sourceDialog, setSourceDialog] = useState<SourceDialog | null>(null);
  const [sourceRecordings, setSourceRecordings] = useState<MusicSourceRecording[]>([]);
  const [sourceAlbums, setSourceAlbums] = useState<MusicSourceAlbum[]>([]);
  const [sourceArtist, setSourceArtist] = useState<{ id: string; name: string } | null>(null);
  const [viewingTracks, setViewingTracks] = useState(false);
  const [hasMoreAlbums, setHasMoreAlbums] = useState(false);
  const [sourceLoading, setSourceLoading] = useState(false);
  const [sourceMessage, setSourceMessage] = useState<string | null>(null);
  const searchRequest = useRef(0);
  const acquisitionRequest = useRef(0);

  async function runSearch(rawQuery: string) {
    const q = rawQuery.trim();
    if (!q) return;
    const request = ++searchRequest.current;
    setStatus('loading'); setError(null); setExtension(null); setArtist(null);
    try {
      const [songs, artistResult] = await Promise.all([searchLibrary(q), browseLibraryArtist(q).catch(() => null)]);
      if (request !== searchRequest.current) return;
      setResults(songs); setArtist(artistResult);
      if (songs.length === 0 || artistResult) {
        const source = await getMusicSourceExtension().catch(() => null);
        if (request === searchRequest.current) setExtension(source?.available ? source : null);
      }
      if (request === searchRequest.current) setStatus('done');
    } catch (err) {
      if (request === searchRequest.current) { setError(err instanceof Error ? err.message : 'Search failed.'); setStatus('error'); }
    }
  }

  useEffect(() => { if (initialQuery.trim()) void runSearch(initialQuery); }, [initialQuery]);
  useEffect(() => {
    if (!sourceDialog) return;
    setSourceLoading(true); setSourceMessage(null); setSourceAlbums([]); setSourceRecordings([]); setSourceArtist(null); setViewingTracks(false);
    void getMusicSourceRecordings(sourceDialog.id, sourceDialog.requestId)
      .then(async (response) => {
        if (response.artist) {
          setSourceArtist(response.artist);
          const albums = await getMusicSourceAlbums(sourceDialog.id, sourceDialog.requestId, response.artist.id);
          setSourceAlbums(albums.albums); setHasMoreAlbums(albums.albums.length === 25);
        } else setSourceRecordings(response.recordings);
      })
      .catch((err) => setSourceMessage(err instanceof Error ? err.message : 'Connected music sources are unavailable.'))
      .finally(() => setSourceLoading(false));
  }, [sourceDialog]);

  async function openConnectedSource(mode: 'search' | 'artist') {
    if (!extension || !query.trim()) return;
    setOpeningSource(true);
    try {
      const sourceQuery = mode === 'artist' && artist ? artist.name : query.trim();
      const { requestId } = await launchMusicSourceExtension(extension.id, sourceQuery, mode);
      setSourceDialog({ id: extension.id, displayName: extension.displayName, requestId });
    } catch (err) { setError(err instanceof Error ? err.message : 'Connected music sources are unavailable.'); }
    finally { setOpeningSource(false); }
  }

  function closeSourceDialog() {
    acquisitionRequest.current += 1;
    setSourceDialog(null); setSourceLoading(false); setSourceMessage(null);
  }

  async function selectAlbum(album: MusicSourceAlbum) {
    if (!sourceDialog || !sourceArtist) return;
    setSourceLoading(true); setSourceMessage(null);
    try {
      setSourceRecordings(await getMusicSourceReleaseTracks(sourceDialog.id, sourceDialog.requestId, album.id, sourceArtist.name));
      setViewingTracks(true);
    } catch (err) { setSourceMessage(err instanceof Error ? err.message : 'Could not load this album.'); }
    finally { setSourceLoading(false); }
  }

  async function loadMoreAlbums() {
    if (!sourceDialog || !sourceArtist) return;
    setSourceLoading(true);
    try {
      const more = await getMusicSourceAlbums(sourceDialog.id, sourceDialog.requestId, sourceArtist.id, sourceAlbums.length);
      setSourceAlbums((albums) => [...albums, ...more.albums]); setHasMoreAlbums(more.albums.length === 25);
    } catch (err) { setSourceMessage(err instanceof Error ? err.message : 'Could not load more albums.'); }
    finally { setSourceLoading(false); }
  }

  async function requestRecording(recording: MusicSourceRecording) {
    if (!sourceDialog) return;
    const request = ++acquisitionRequest.current;
    setSourceLoading(true); setSourceMessage('Requesting the best available source…');
    try {
      await acquireMusicSourceRecording(sourceDialog.id, sourceDialog.requestId, recording);
      for (let attempt = 0; attempt < 8; attempt += 1) {
        await new Promise((resolve) => window.setTimeout(resolve, 2_000));
        if (request !== acquisitionRequest.current) return;
        const progress = await getMusicSourceRequest(sourceDialog.requestId);
        const statusMessage: Record<string, string> = {
          acquiring: 'Finding the best available source…',
          waiting_for_plex: 'Waiting for it to reach your Plex library…',
        };
        setSourceMessage(progress.message || statusMessage[progress.status] || 'Processing your request…');
        if (progress.status === 'fulfilled') { closeSourceDialog(); await runSearch(query); return; }
        if (progress.status === 'failed') { setSourceLoading(false); return; }
      }
      setSourceMessage('Your request is still being processed. It will join the queue once Plex finds it.'); setSourceLoading(false);
    } catch (err) {
      if (request === acquisitionRequest.current) { setSourceMessage(err instanceof Error ? err.message : 'Could not request this recording.'); setSourceLoading(false); }
    }
  }

  return <div className="animate-fade-in">
    <h2 className="mb-1 text-lg font-semibold text-white">Search your library</h2>
    <p className="mb-4 text-sm text-ink-400">Find a Plex track, play it now on the active device, or add it to the shared queue.</p>
    <form onSubmit={(e: FormEvent) => { e.preventDefault(); void runSearch(query); }} className="mb-6 flex gap-2">
      <div className="relative flex-1"><Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-500" /><input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Song, artist or album…" className="w-full rounded-lg border border-ink-700 bg-ink-850 py-2.5 pl-9 pr-3 text-sm text-white placeholder:text-ink-500 outline-none transition focus:border-amber-500/60 focus:ring-2 focus:ring-amber-500/20" /></div>
      <button type="submit" className="rounded-lg bg-amber-500 px-5 text-sm font-semibold text-ink-950 transition hover:bg-amber-400">Search</button>
    </form>
    {status === 'loading' && <div className="flex items-center justify-center gap-2 py-16 text-ink-400"><Loader2 className="h-5 w-5 animate-spin" /> Searching…</div>}
    {status === 'error' && <p className="py-16 text-center text-sm text-red-300">{error}</p>}
    {status === 'done' && results.length === 0 && <div className="flex flex-col items-center gap-3 py-12 text-ink-500"><Music2 className="h-8 w-8" /><p className="text-sm">No tracks matched that search.</p>{extension && <ConnectedSourceButton title="Search connected music sources" description="Find a verified recording and request it for the jukebox." busy={openingSource} onClick={() => void openConnectedSource('search')} />}</div>}
    {status === 'idle' && <div className="flex flex-col items-center gap-3 py-16 text-ink-500"><Search className="h-8 w-8" /><p className="text-sm">Search for something to get started.</p></div>}
    {artist && extension && status === 'done' && <ConnectedSourceButton title={`Find songs by ${artist.name}`} description="Browse releases beyond your local library." busy={openingSource} onClick={() => void openConnectedSource('artist')} />}
    <div className="space-y-0.5">{results.map((song, i) => <SongRow key={song.id} song={song} index={i} />)}</div>
    {sourceDialog && <SourcePicker dialog={sourceDialog} recordings={sourceRecordings} albums={sourceAlbums} artist={sourceArtist} viewingTracks={viewingTracks} hasMoreAlbums={hasMoreAlbums} loading={sourceLoading} message={sourceMessage} onClose={closeSourceDialog} onBack={() => { setViewingTracks(false); setSourceRecordings([]); }} onAlbum={selectAlbum} onMore={loadMoreAlbums} onRecording={requestRecording} />}
  </div>;
}

function ConnectedSourceButton({ title, description, busy, onClick }: { title: string; description: string; busy: boolean; onClick: () => void }) {
  return <button onClick={onClick} disabled={busy} className="mt-2 flex w-full max-w-md items-center gap-3 rounded-2xl border border-amber-400/35 bg-amber-400/10 p-4 text-left transition hover:border-amber-400 disabled:opacity-60 sm:w-auto"><span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-amber-400 text-ink-950">{busy ? <Loader2 className="h-4 w-4 animate-spin" /> : <Sparkles className="h-4 w-4" />}</span><span><span className="block text-sm font-bold text-amber-200">{title}</span><span className="mt-0.5 block text-xs text-ink-400">{description}</span></span></button>;
}

function SourcePicker({ dialog, recordings, albums, artist, viewingTracks, hasMoreAlbums, loading, message, onClose, onBack, onAlbum, onMore, onRecording }: { dialog: SourceDialog; recordings: MusicSourceRecording[]; albums: MusicSourceAlbum[]; artist: { id: string; name: string } | null; viewingTracks: boolean; hasMoreAlbums: boolean; loading: boolean; message: string | null; onClose: () => void; onBack: () => void; onAlbum: (album: MusicSourceAlbum) => void; onMore: () => void; onRecording: (recording: MusicSourceRecording) => void }) {
  const heading = viewingTracks ? 'Choose a track' : artist ? `Albums by ${artist.name}` : 'Choose the recording you meant';
  return <div className="fixed inset-0 z-[60] bg-black/70 backdrop-blur-sm sm:grid sm:place-items-center sm:p-4">
    <div className="flex h-[100dvh] w-full flex-col overflow-hidden bg-ink-950 sm:h-auto sm:max-h-[min(48rem,calc(100dvh-2rem))] sm:max-w-3xl sm:rounded-3xl sm:border sm:border-ink-700 sm:shadow-2xl">
      <div className="flex items-center justify-between border-b border-ink-700 px-4 py-4 sm:px-6 sm:py-5"><div className="flex min-w-0 items-center gap-3"><span className="grid h-10 w-10 shrink-0 place-items-center rounded-2xl bg-amber-400 text-ink-950"><Sparkles className="h-5 w-5" /></span><div className="min-w-0"><p className="truncate text-xs font-black uppercase tracking-[.18em] text-amber-400">{dialog.displayName}</p><h2 className="truncate text-lg font-black text-white sm:text-xl">{heading}</h2></div></div><button onClick={onClose} disabled={loading} className="rounded-lg p-3 text-ink-300 hover:bg-ink-800 hover:text-white disabled:opacity-50" aria-label="Close connected music sources"><X className="h-5 w-5" /></button></div>
      <div className="min-h-0 flex-1 overflow-y-auto p-4 sm:p-5">
        {message && <p className="mb-4 rounded-xl bg-amber-400/10 p-4 text-sm font-semibold text-amber-100">{message}</p>}
        {loading && !message && <p className="text-ink-300"><Loader2 className="mr-2 inline h-4 w-4 animate-spin" /> Looking up music…</p>}
        {viewingTracks && <button onClick={onBack} disabled={loading} className="mb-4 inline-flex min-h-11 items-center gap-2 text-sm font-bold text-amber-300 disabled:opacity-50"><ArrowLeft className="h-4 w-4" /> Back to albums</button>}
        {!viewingTracks && albums.length > 0 && <div className="grid gap-3 sm:grid-cols-2">{albums.map((album) => <button key={album.id} onClick={() => onAlbum(album)} disabled={loading} className="min-h-20 rounded-2xl border border-ink-700 bg-ink-900 p-4 text-left transition hover:border-amber-400 disabled:opacity-60"><p className="font-black text-white">{album.title}</p><p className="mt-1 text-sm text-ink-400">{[album.type, album.year].filter(Boolean).join(' · ')}</p></button>)}</div>}
        {!viewingTracks && hasMoreAlbums && <button onClick={onMore} disabled={loading} className="mt-4 min-h-11 w-full rounded-xl border border-ink-700 p-3 text-sm font-bold text-amber-300 hover:border-amber-400 disabled:opacity-60">Show more albums</button>}
        {!loading && !message && !albums.length && !recordings.length && <p className="text-ink-400">No matching recordings were found.</p>}
        {recordings.length > 0 && <div className="space-y-2">{recordings.map((recording) => <button key={recording.id} onClick={() => onRecording(recording)} disabled={loading} className="min-h-20 w-full rounded-2xl border border-ink-700 bg-ink-900 p-4 text-left transition hover:border-amber-400 disabled:opacity-60"><p className="font-black text-white">{recording.title}</p><p className="mt-1 text-sm text-ink-300">{recording.artist}</p><p className="mt-1 text-xs text-ink-500">{[recording.album, recording.year, recording.durationMs ? `${Math.round(recording.durationMs / 1000)} sec` : null, recording.disambiguation].filter(Boolean).join(' · ')}</p></button>)}</div>}
      </div>
    </div>
  </div>;
}
