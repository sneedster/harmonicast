import { useState } from 'react';
import { Music } from 'lucide-react';
import { usePlayer } from '@/hooks/usePlayer';

interface CoverArtProps {
  coverArt: string;
  size?: number;
  className?: string;
  rounded?: string;
}

export function CoverArt({ coverArt, size = 300, className = '', rounded = 'rounded-lg' }: CoverArtProps) {
  const { coverUrl } = usePlayer();
  const [failed, setFailed] = useState(false);
  const url = coverArt ? coverUrl(coverArt, size) : null;

  if (!url || failed) {
    return (
      <div
        className={`flex items-center justify-center bg-ink-800 text-ink-500 ${rounded} ${className}`}
      >
        <Music className="h-1/3 w-1/3" strokeWidth={1.5} />
      </div>
    );
  }

  return (
    <img
      src={url}
      alt=""
      loading="lazy"
      onError={() => setFailed(true)}
      className={`object-cover ${rounded} ${className}`}
    />
  );
}
