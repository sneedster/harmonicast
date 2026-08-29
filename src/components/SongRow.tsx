import { Play, Plus, Check, AlertCircle } from 'lucide-react';
import { useState } from 'react';
import type { Song } from '@/types';
import { usePlayer } from '@/hooks/usePlayer';
import { CoverArt } from '@/components/CoverArt';
import { formatTime } from '@/lib/format';

export function SongRow({ song, index }: { song: Song; index?: number }) {
  const { isHost, playNow, enqueue, current } = usePlayer();
  const [added, setAdded] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const isCurrent = current?.id === song.id;

  async function handleEnqueue() {
    setError(null);
    try {
      await enqueue(song);
      setAdded(true);
      setTimeout(() => setAdded(false), 1200);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not add to queue.');
      setTimeout(() => setError(null), 3000);
    }
  }

  return (
    <div
      className={`group flex items-center gap-3 rounded-lg px-3 py-2 transition ${
        isCurrent ? 'bg-amber-500/10' : 'hover:bg-ink-800/70'
      }`}
    >
      {typeof index === 'number' && (
        <span className="hidden w-5 text-right text-xs tabular-nums text-ink-500 sm:block">
          {index + 1}
        </span>
      )}
      <CoverArt coverArt={song.coverArt} size={80} className="h-10 w-10 shrink-0" rounded="rounded-md" />
      <div className="min-w-0 flex-1">
        <p className={`truncate text-sm font-medium ${isCurrent ? 'text-amber-400' : 'text-white'}`}>
          {song.title}
        </p>
        <p className="truncate text-xs text-ink-400">
          {song.artist}
          {song.album ? ` · ${song.album}` : ''}
        </p>
        {error && (
          <p className="mt-0.5 flex items-center gap-1 text-[11px] text-red-400">
            <AlertCircle className="h-3 w-3 shrink-0" />
            {error}
          </p>
        )}
      </div>
      <span className="hidden text-xs tabular-nums text-ink-500 sm:block">
        {formatTime(song.duration)}
      </span>
      <div className="flex items-center gap-1">
        <button
          onClick={handleEnqueue}
          title="Add to queue"
          className="rounded-full p-2 text-ink-300 transition hover:bg-ink-700 hover:text-white"
        >
          {added ? <Check className="h-4 w-4 text-emerald-400" /> : <Plus className="h-4 w-4" />}
        </button>
        {isHost && (
          <button
            onClick={() => playNow(song)}
            title="Play now"
            className="rounded-full bg-white p-2 text-ink-950 transition hover:scale-105"
          >
            <Play className="h-3.5 w-3.5 translate-x-px" fill="currentColor" />
          </button>
        )}
      </div>
    </div>
  );
}
