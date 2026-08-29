import { ThumbsDown, ThumbsUp, Volume2, Loader2, Radio } from 'lucide-react';
import { usePlayer } from '@/hooks/usePlayer';
import { CoverArt } from '@/components/CoverArt';
import { formatTime, ratingColor, ratingLabel } from '@/lib/format';

export function NowPlayingView() {
  const {
    isHost,
    isHostUser,
    current,
    currentStats,
    isPlaying,
    currentTime,
    duration,
    voteCounts,
    thumbsUp,
    thumbsDown,
  } = usePlayer();

  const total = duration || current?.duration || 0;
  const pct = total > 0 ? Math.min(100, (currentTime / total) * 100) : 0;
  const rating = currentStats?.rating ?? 5;

  if (!current) {
    return (
      <div className="flex flex-col items-center justify-center gap-4 py-24 text-ink-500 animate-fade-in">
        <div className="flex h-20 w-20 items-center justify-center rounded-2xl bg-ink-800">
          {isPlaying ? (
            <Loader2 className="h-8 w-8 animate-spin" />
          ) : (
            <Radio className="h-8 w-8" />
          )}
        </div>
        <p className="text-sm font-medium text-ink-300">Nothing playing right now</p>
        <p className="text-xs text-ink-500">
          {isHost
            ? 'Press Play to start a random queue, or search your Plex library for a specific track.'
            : isHostUser
              ? 'Select Play here above to make this device active, then choose a track from Search.'
            : 'Waiting for the host to start music.'}
        </p>
      </div>
    );
  }

  return (
    <div className="flex flex-col items-center gap-6 py-6 animate-fade-in sm:py-10">
      {/* Large album art */}
      <div className="relative">
        <div
          className={`absolute -inset-3 rounded-3xl bg-amber-500/10 blur-2xl transition-opacity duration-700 ${
            isPlaying ? 'opacity-100' : 'opacity-40'
          }`}
        />
        <CoverArt
          coverArt={current.coverArt}
          size={600}
          className="relative h-56 w-56 shrink-0 shadow-2xl shadow-black/50 sm:h-72 sm:w-72"
          rounded="rounded-2xl"
        />
        {isPlaying && (
          <div className="absolute bottom-3 right-3 flex items-end gap-0.5 rounded-lg bg-ink-950/80 px-2 py-1.5 backdrop-blur">
            {[4, 7, 10, 7, 4].map((h, i) => (
              <span
                key={i}
                className="w-1 rounded-full bg-amber-400"
                style={{
                  height: `${h}px`,
                  animation: `eq-bar 0.8s ease-in-out ${i * 0.12}s infinite alternate`,
                }}
              />
            ))}
          </div>
        )}
      </div>

      {/* Track info */}
      <div className="text-center">
        <h2 className="text-xl font-bold text-white sm:text-2xl">{current.title}</h2>
        <p className="mt-1 text-sm text-ink-400 sm:text-base">{current.artist}</p>
        {current.album && (
          <p className="mt-0.5 text-xs text-ink-500">{current.album}</p>
        )}
        {currentStats && (
          <p className={`mt-2 text-sm font-medium ${ratingColor(rating)}`}>
            {ratingLabel(rating)} · {rating.toFixed(1)}
            <span className="text-ink-500"> / 10</span>
          </p>
        )}
      </div>

      {/* Progress bar (read-only for guests) */}
      <div className="flex w-full max-w-md items-center gap-3">
        <span className="w-10 text-right text-xs tabular-nums text-ink-500">
          {formatTime(currentTime)}
        </span>
        <div className="relative h-1.5 flex-1 overflow-hidden rounded-full bg-ink-700">
          <div
            className="absolute left-0 top-0 h-full rounded-full bg-amber-500 transition-all duration-200"
            style={{ width: `${pct}%` }}
          />
        </div>
        <span className="w-10 text-xs tabular-nums text-ink-500">
          {formatTime(total)}
        </span>
      </div>

      {/* Voting */}
      <div className="flex items-center gap-6">
        <button
          onClick={thumbsDown}
          title="Thumbs down"
          className="flex items-center gap-2 rounded-full border border-ink-700 px-5 py-3 text-ink-400 transition hover:border-red-500/40 hover:bg-red-500/10 hover:text-red-400"
        >
          <ThumbsDown className="h-5 w-5" />
          {voteCounts.down > 0 && (
            <span className="text-sm tabular-nums">{voteCounts.down}</span>
          )}
        </button>

        {!isHost && (
          <div className="flex items-center gap-1.5 text-ink-500">
            <Volume2 className="h-4 w-4" />
            <span className="text-xs">Playing on host</span>
          </div>
        )}

        <button
          onClick={thumbsUp}
          title="Thumbs up"
          className="flex items-center gap-2 rounded-full border border-ink-700 px-5 py-3 text-ink-400 transition hover:border-emerald-500/40 hover:bg-emerald-500/10 hover:text-emerald-400"
        >
          <ThumbsUp className="h-5 w-5" />
          {voteCounts.up > 0 && (
            <span className="text-sm tabular-nums">{voteCounts.up}</span>
          )}
        </button>
      </div>
    </div>
  );
}
