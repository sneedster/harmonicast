import { useEffect, useState } from 'react';
import { Flame, TrendingUp, History, Star } from 'lucide-react';
import type { PlayHistoryRow, SongStats } from '@/types';
import {
  fetchMostPlayed,
  fetchRecentlyPlayed,
  fetchTopRated,
} from '@/lib/stats';
import { CoverArt } from '@/components/CoverArt';
import { ratingColor } from '@/lib/format';

export function StatsView() {
  const [topRated, setTopRated] = useState<SongStats[]>([]);
  const [mostPlayed, setMostPlayed] = useState<SongStats[]>([]);
  const [recent, setRecent] = useState<PlayHistoryRow[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoading(true);
      const [rated, played, hist] = await Promise.all([
        fetchTopRated(20),
        fetchMostPlayed(20),
        fetchRecentlyPlayed(30),
      ]);
      if (cancelled) return;
      setTopRated(rated);
      setMostPlayed(played);
      setRecent(hist);
      setLoading(false);
    })();
    return () => { cancelled = true; };
  }, []);

  if (loading) {
    return (
      <div className="flex items-center justify-center py-24 text-ink-400">
        Loading your listening stats…
      </div>
    );
  }

  const hasData = topRated.length > 0 || mostPlayed.length > 0 || recent.length > 0;

  if (!hasData) {
    return (
      <div className="flex flex-col items-center gap-3 py-24 text-ink-500">
        <Star className="h-8 w-8" />
        <p className="text-sm">
          No stats yet. Play some music and the jukebox will start learning
          your taste — ratings, play counts and skip history all appear here.
        </p>
      </div>
    );
  }

  return (
    <div className="animate-fade-in space-y-8">
      <StatsSection
        icon={<Flame className="h-5 w-5 text-amber-400" />}
        title="Top rated"
        empty="No ratings yet."
      >
        {topRated.map((s) => (
          <StatRow key={`r-${s.song_id}`} stats={s} primary={`${s.rating.toFixed(1)} / 10`} />
        ))}
      </StatsSection>

      <StatsSection
        icon={<TrendingUp className="h-5 w-5 text-emerald-400" />}
        title="Most played"
        empty="No plays recorded yet."
      >
        {mostPlayed.map((s) => (
          <StatRow
            key={`p-${s.song_id}`}
            stats={s}
            primary={`${s.play_count} plays`}
            secondary={`${s.skip_count} skips`}
          />
        ))}
      </StatsSection>

      <StatsSection
        icon={<History className="h-5 w-5 text-ink-300" />}
        title="Recent activity"
        empty="Nothing played yet."
      >
        {recent.map((row) => (
          <div
            key={row.id}
            className="flex items-center gap-3 rounded-lg px-3 py-2 hover:bg-ink-800/70"
          >
            <EventBadge event={row.event} />
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm text-white">{row.title}</p>
              <p className="truncate text-xs text-ink-400">{row.artist}</p>
            </div>
            <span className="text-xs text-ink-500">
              {new Date(row.created_at).toLocaleDateString(undefined, {
                month: 'short',
                day: 'numeric',
                hour: '2-digit',
                minute: '2-digit',
              })}
            </span>
          </div>
        ))}
      </StatsSection>
    </div>
  );
}

function StatsSection({
  icon,
  title,
  empty,
  children,
}: {
  icon: React.ReactNode;
  title: string;
  empty: string;
  children: React.ReactNode;
}) {
  const items = Array.isArray(children) ? children : [children];
  const hasItems = items.filter(Boolean).length > 0;
  return (
    <section>
      <div className="mb-3 flex items-center gap-2">
        {icon}
        <h2 className="text-lg font-semibold text-white">{title}</h2>
      </div>
      {hasItems ? (
        <div className="space-y-0.5">{children}</div>
      ) : (
        <p className="px-3 py-8 text-center text-sm text-ink-500">{empty}</p>
      )}
    </section>
  );
}

function StatRow({
  stats,
  primary,
  secondary,
}: {
  stats: SongStats;
  primary: string;
  secondary?: string;
}) {
  return (
    <div className="flex items-center gap-3 rounded-lg px-3 py-2 hover:bg-ink-800/70">
      <CoverArt coverArt={stats.cover_art} size={80} className="h-10 w-10 shrink-0" rounded="rounded-md" />
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium text-white">{stats.title}</p>
        <p className="truncate text-xs text-ink-400">{stats.artist}</p>
      </div>
      <div className="text-right">
        <p className={`text-sm font-semibold ${ratingColor(stats.rating)}`}>{primary}</p>
        {secondary && <p className="text-xs text-ink-500">{secondary}</p>}
      </div>
    </div>
  );
}

function EventBadge({ event }: { event: PlayHistoryRow['event'] }) {
  const styles: Record<string, { bg: string; text: string; label: string }> = {
    complete: { bg: 'bg-emerald-500/15', text: 'text-emerald-400', label: 'Played' },
    skip: { bg: 'bg-orange-500/15', text: 'text-orange-400', label: 'Skipped' },
    thumbs_up: { bg: 'bg-amber-500/15', text: 'text-amber-400', label: 'Thumbs up' },
    thumbs_down: { bg: 'bg-red-500/15', text: 'text-red-400', label: 'Thumbs down' },
  };
  const s = styles[event] ?? styles.complete;
  return (
    <span className={`shrink-0 rounded-full px-2 py-1 text-[10px] font-semibold ${s.bg} ${s.text}`}>
      {s.label}
    </span>
  );
}
