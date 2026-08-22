import {
  Pause,
  Play,
  SkipForward,
  ThumbsDown,
  ThumbsUp,
  Radio,
  Loader2,
  AlertTriangle,
  Volume2,
  Volume1,
  VolumeX,
} from 'lucide-react';
import { usePlayer } from '@/hooks/usePlayer';
import { CoverArt } from '@/components/CoverArt';
import { formatTime, ratingColor, ratingLabel } from '@/lib/format';

function VolumeControl() {
  const { volume, setVolume } = usePlayer();
  const Icon = volume === 0 ? VolumeX : volume < 0.5 ? Volume1 : Volume2;
  const pct = volume * 100;

  return (
    <div className="flex items-center gap-2">
      <button
        onClick={() => setVolume(volume > 0 ? 0 : 1)}
        title={volume > 0 ? 'Mute' : 'Unmute'}
        className="text-ink-400 transition hover:text-white"
      >
        <Icon className="h-4 w-4" />
      </button>
      <input
        type="range"
        min={0}
        max={1}
        step={0.01}
        value={volume}
        onChange={(e) => setVolume(Number(e.target.value))}
        style={{
          background: `linear-gradient(to right, #f59e0b ${pct}%, #292930 ${pct}%)`,
        }}
        className="h-1 w-20 cursor-pointer appearance-none rounded-full [&::-webkit-slider-thumb]:h-3 [&::-webkit-slider-thumb]:w-3 [&::-webkit-slider-thumb]:appearance-none [&::-webkit-slider-thumb]:rounded-full [&::-webkit-slider-thumb]:bg-white"
      />
    </div>
  );
}

export function PlayerBar() {
  const {
    isHost,
    current,
    currentStats,
    isPlaying,
    currentTime,
    duration,
    jukeboxMode,
    loadingNext,
    streamError,
    voteCounts,
    togglePlay,
    next,
    seek,
    thumbsUp,
    thumbsDown,
    toggleJukebox,
  } = usePlayer();

  const total = duration || current?.duration || 0;
  const pct = total > 0 ? Math.min(100, (currentTime / total) * 100) : 0;
  const rating = currentStats?.rating ?? 5;

  // ── Guest view: now-playing info + voting, no transport controls ──────
  if (!isHost) {
    return (
      <div className="border-t border-ink-800 bg-ink-900/95 backdrop-blur">
        <div className="mx-auto flex max-w-6xl flex-col gap-3 px-4 py-3 sm:flex-row sm:items-center">
          <div className="flex min-w-0 flex-1 items-center gap-3">
            {current ? (
              <>
                <CoverArt coverArt={current.coverArt} size={100} className="h-14 w-14 shrink-0" />
                <div className="min-w-0">
                  <p className="truncate text-sm font-semibold text-white">{current.title}</p>
                  <p className="truncate text-xs text-ink-400">{current.artist}</p>
                  {currentStats && (
                    <p className={`mt-0.5 text-[11px] font-medium ${ratingColor(rating)}`}>
                      {ratingLabel(rating)} · {rating.toFixed(1)}
                      <span className="text-ink-500"> / 10</span>
                    </p>
                  )}
                </div>
              </>
            ) : (
              <div className="flex items-center gap-3 text-ink-500">
                <div className="flex h-14 w-14 items-center justify-center rounded-lg bg-ink-800">
                  {loadingNext ? (
                    <Loader2 className="h-5 w-5 animate-spin" />
                  ) : (
                    <Radio className="h-5 w-5" />
                  )}
                </div>
                <div>
                  <p className="text-sm font-medium text-ink-300">Nothing playing</p>
                  <p className="text-xs text-ink-500">Waiting for the host to start music.</p>
                </div>
              </div>
            )}
            {isPlaying && (
              <div className="ml-auto flex items-end gap-0.5 sm:ml-0 sm:pl-3">
                {[3, 5, 7, 5, 3].map((h, i) => (
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

          <div className="flex flex-1 items-center justify-center gap-4">
            <button
              onClick={thumbsDown}
              disabled={!current}
              title="Thumbs down"
              className="flex items-center gap-1.5 rounded-full px-3 py-2 text-ink-400 transition hover:bg-ink-800 hover:text-red-400 disabled:opacity-40"
            >
              <ThumbsDown className="h-4 w-4" />
              {voteCounts.down > 0 && (
                <span className="text-xs tabular-nums text-ink-500">{voteCounts.down}</span>
              )}
            </button>

            <div className="flex items-center gap-1.5 text-ink-500">
              <Volume2 className="h-4 w-4" />
              <span className="text-xs">Playing on host</span>
            </div>

            <button
              onClick={thumbsUp}
              disabled={!current}
              title="Thumbs up"
              className="flex items-center gap-1.5 rounded-full px-3 py-2 text-ink-400 transition hover:bg-ink-800 hover:text-emerald-400 disabled:opacity-40"
            >
              <ThumbsUp className="h-4 w-4" />
              {voteCounts.up > 0 && (
                <span className="text-xs tabular-nums text-ink-500">{voteCounts.up}</span>
              )}
            </button>
          </div>

          <div className="hidden flex-1 sm:block" />
        </div>
      </div>
    );
  }

  // ── Host view: full transport controls ────────────────────────────────
  return (
    <div className="border-t border-ink-800 bg-ink-900/95 backdrop-blur">
      {streamError && (
        <div className="flex items-center justify-center gap-2 bg-red-500/10 px-4 py-1.5 text-xs text-red-300">
          <AlertTriangle className="h-3.5 w-3.5" /> {streamError}
        </div>
      )}
      <div className="mx-auto flex max-w-6xl flex-col gap-3 px-4 py-3 sm:flex-row sm:items-center">
        <div className="flex min-w-0 flex-1 items-center gap-3">
          {current ? (
            <>
              <CoverArt coverArt={current.coverArt} size={100} className="h-14 w-14 shrink-0" />
              <div className="min-w-0">
                <p className="truncate text-sm font-semibold text-white">{current.title}</p>
                <p className="truncate text-xs text-ink-400">{current.artist}</p>
                {currentStats && (
                  <p className={`mt-0.5 text-[11px] font-medium ${ratingColor(rating)}`}>
                    {ratingLabel(rating)} · {rating.toFixed(1)}
                    <span className="text-ink-500"> / 10</span>
                  </p>
                )}
              </div>
            </>
          ) : (
            <div className="flex items-center gap-3 text-ink-500">
              <div className="flex h-14 w-14 items-center justify-center rounded-lg bg-ink-800">
                {loadingNext ? (
                  <Loader2 className="h-5 w-5 animate-spin" />
                ) : (
                  <Radio className="h-5 w-5" />
                )}
              </div>
              <div>
                <p className="text-sm font-medium text-ink-300">Nothing playing</p>
                <p className="text-xs text-ink-500">
                  Start the jukebox or pick a track to begin.
                </p>
              </div>
            </div>
          )}
        </div>

        <div className="flex flex-1 flex-col items-center gap-1.5">
          <div className="flex items-center gap-3">
            <button
              onClick={thumbsDown}
              disabled={!current}
              title="Thumbs down"
              className="flex items-center gap-1.5 rounded-full p-2 text-ink-400 transition hover:bg-ink-800 hover:text-red-400 disabled:opacity-40"
            >
              <ThumbsDown className="h-4 w-4" />
              {voteCounts.down > 0 && (
                <span className="text-xs tabular-nums text-ink-500">{voteCounts.down}</span>
              )}
            </button>

            <button
              onClick={togglePlay}
              disabled={!current}
              className="flex h-11 w-11 items-center justify-center rounded-full bg-white text-ink-950 transition hover:scale-105 disabled:opacity-40"
            >
              {isPlaying ? (
                <Pause className="h-5 w-5" fill="currentColor" />
              ) : (
                <Play className="h-5 w-5 translate-x-0.5" fill="currentColor" />
              )}
            </button>

            <button
              onClick={next}
              title="Skip"
              className="rounded-full p-2 text-ink-300 transition hover:bg-ink-800 hover:text-white"
            >
              <SkipForward className="h-5 w-5" fill="currentColor" />
            </button>

            <button
              onClick={thumbsUp}
              disabled={!current}
              title="Thumbs up"
              className="flex items-center gap-1.5 rounded-full p-2 text-ink-400 transition hover:bg-ink-800 hover:text-emerald-400 disabled:opacity-40"
            >
              <ThumbsUp className="h-4 w-4" />
              {voteCounts.up > 0 && (
                <span className="text-xs tabular-nums text-ink-500">{voteCounts.up}</span>
              )}
            </button>
          </div>

          <div className="flex w-full max-w-md items-center gap-2">
            <span className="w-9 text-right text-[11px] tabular-nums text-ink-500">
              {formatTime(currentTime)}
            </span>
            <input
              type="range"
              min={0}
              max={total || 0}
              step={1}
              value={Math.min(currentTime, total)}
              onChange={(e) => seek(Number(e.target.value))}
              disabled={!current || total === 0}
              style={{
                background: `linear-gradient(to right, #f59e0b ${pct}%, #292930 ${pct}%)`,
              }}
              className="h-1 flex-1 cursor-pointer appearance-none rounded-full disabled:cursor-default [&::-webkit-slider-thumb]:h-3 [&::-webkit-slider-thumb]:w-3 [&::-webkit-slider-thumb]:appearance-none [&::-webkit-slider-thumb]:rounded-full [&::-webkit-slider-thumb]:bg-white"
            />
            <span className="w-9 text-[11px] tabular-nums text-ink-500">
              {formatTime(total)}
            </span>
          </div>
        </div>

        <div className="flex items-center justify-between gap-3 sm:flex-1 sm:justify-end">
          <VolumeControl />
          <button
            onClick={toggleJukebox}
            className={`flex items-center gap-2 rounded-full px-3.5 py-2 text-xs font-semibold transition ${
              jukeboxMode
                ? 'bg-amber-500/15 text-amber-400 ring-1 ring-amber-500/40'
                : 'bg-ink-800 text-ink-300 hover:bg-ink-700 hover:text-white'
            }`}
          >
            <Radio className={`h-4 w-4 ${jukeboxMode ? 'animate-pulse' : ''}`} />
            Jukebox {jukeboxMode ? 'On' : 'Off'}
          </button>
        </div>
      </div>
    </div>
  );
}
