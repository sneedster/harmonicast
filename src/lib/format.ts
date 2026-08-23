export function formatTime(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return '0:00';
  const total = Math.floor(seconds);
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}:${s.toString().padStart(2, '0')}`;
}

export function ratingLabel(rating: number): string {
  if (rating >= 8.5) return 'Loved';
  if (rating >= 6.5) return 'Favored';
  if (rating >= 4.5) return 'Neutral';
  if (rating >= 2.5) return 'Cooling';
  return 'Buried';
}

export function ratingColor(rating: number): string {
  if (rating >= 8.5) return 'text-emerald-400';
  if (rating >= 6.5) return 'text-amber-400';
  if (rating >= 4.5) return 'text-ink-300';
  if (rating >= 2.5) return 'text-orange-400';
  return 'text-red-400';
}
