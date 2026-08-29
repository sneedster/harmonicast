import { ListMusic, Play, User } from 'lucide-react';
import { usePlayer } from '@/hooks/usePlayer';
import { CoverArt } from '@/components/CoverArt';
import { formatTime } from '@/lib/format';

export function QueueView() {
  const { isHost, queue, queueRows, current, playNow } = usePlayer();

  return (
    <div className="animate-fade-in">
      <div className="mb-4 flex items-center gap-2">
        <ListMusic className="h-5 w-5 text-amber-400" />
        <h2 className="text-lg font-semibold text-white">Up next</h2>
        <span className="text-sm text-ink-500">({queue.length})</span>
      </div>

      {queue.length === 0 ? (
        <div className="flex flex-col items-center gap-3 py-16 text-ink-500">
          <ListMusic className="h-8 w-8" />
          <p className="text-sm">
            {isHost
              ? 'The queue is empty. Turn on the jukebox to auto-fill it, or add tracks from search.'
              : 'The queue is empty. Add tracks from search to get the party going.'}
          </p>
        </div>
      ) : (
        <div className="space-y-0.5">
          {queue.map((song, i) => {
            const row = queueRows[i];
            return (
              <div
                key={`${song.id}-${i}`}
                className="group flex items-center gap-3 rounded-lg px-3 py-2 hover:bg-ink-800/70"
              >
                <span className="w-5 text-right text-xs tabular-nums text-ink-500">{i + 1}</span>
                <CoverArt coverArt={song.coverArt} size={80} className="h-10 w-10 shrink-0" rounded="rounded-md" />
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium text-white">{song.title}</p>
                  <p className="truncate text-xs text-ink-400">{song.artist}</p>
                  {row?.addedByEmail && (
                    <p className="mt-0.5 flex items-center gap-1 text-[10px] text-ink-500">
                      <User className="h-2.5 w-2.5" />
                      {row.addedByEmail}
                    </p>
                  )}
                </div>
                <span className="hidden text-xs tabular-nums text-ink-500 sm:block">
                  {formatTime(song.duration)}
                </span>
                {isHost && (
                  <button
                    onClick={() => playNow(song)}
                    title="Play now"
                    className="rounded-full bg-white p-2 text-ink-950 opacity-0 transition hover:scale-105 group-hover:opacity-100"
                  >
                    <Play className="h-3.5 w-3.5 translate-x-px" fill="currentColor" />
                  </button>
                )}
              </div>
            );
          })}
        </div>
      )}

      {current && (
        <div className="mt-6 border-t border-ink-800 pt-4">
          <h3 className="mb-2 text-xs font-medium uppercase tracking-wide text-ink-500">
            Recently played
          </h3>
          <p className="text-xs text-ink-500">
            History appears here as tracks play. Plex stores the shared ratings
            and listening history used by Jukebox.
          </p>
        </div>
      )}
    </div>
  );
}
