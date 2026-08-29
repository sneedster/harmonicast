import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import type { Connection, Song } from '@/types';
import {
  coverArtUrl as buildCoverArtUrl,
  scrobble,
  streamUrl,
} from '@/lib/subsonic';
import { connectWebSocket, savePlaybackPosition, setJukeboxModeApi } from '@/lib/api';
import {
  addToQueue,
  clearAutoQueue,
  dequeueNext,
  fetchCooldownMinutes,
  fetchJukeboxMode,
  fetchMaxRequestsPerUser,
  fetchNowPlaying,
  fetchQueue,
  fetchQueueSongs,
  updateNowPlaying,
  voteOnCurrent,
} from '@/lib/jukeboxState';

interface PlayerContextValue {
  connection: Connection;
  isHost: boolean;
  isHostUser: boolean;
  isActivePlayer: boolean;
  current: Song | null;
  queue: Song[];
  queueRows: { id: string; addedByEmail: string; isManual: boolean }[];
  history: Song[];
  isPlaying: boolean;
  currentTime: number;
  duration: number;
  volume: number;
  jukeboxMode: boolean;
  loadingNext: boolean;
  streamError: string | null;
  cooldownMinutes: number;
  maxRequestsPerUser: number;
  coverUrl: (coverArt: string, size?: number) => string | null;
  playNow: (song: Song) => void;
  enqueue: (song: Song) => void;
  togglePlay: () => void;
  startRandomPlayback: () => void;
  next: () => void;
  seek: (seconds: number) => void;
  setVolume: (v: number) => void;
  thumbsUp: () => void;
  thumbsDown: () => void;
  toggleJukebox: () => void;
}

const PlayerContext = createContext<PlayerContextValue | null>(null);

export function PlayerProvider({
  connection,
  isHost,
  isHostUser,
  isActivePlayer,
  onWebSocketEvent,
  children,
}: {
  connection: Connection;
  isHost: boolean;
  isHostUser: boolean;
  isActivePlayer: boolean;
  onWebSocketEvent?: (type: string) => void;
  children: ReactNode;
}) {
  const audioRef = useRef<HTMLAudioElement | null>(null);

  const [current, setCurrent] = useState<Song | null>(null);
  const [queue, setQueue] = useState<Song[]>([]);
  const [queueRows, setQueueRows] = useState<{ id: string; addedByEmail: string; isManual: boolean }[]>([]);
  const [history, setHistory] = useState<Song[]>([]);
  const [isPlaying, setIsPlaying] = useState(false);
  const [currentTime, setCurrentTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const [volume, setVolumeState] = useState(1);
  const [jukeboxMode, setJukeboxMode] = useState(false);
  const loadingNext = false;
  const [streamError, setStreamError] = useState<string | null>(null);
  const [cooldownMinutes, setCooldownMinutes] = useState(30);
  const [maxRequestsPerUser, setMaxRequestsPerUser] = useState(5);

  const connRef = useRef(connection);
  const currentRef = useRef<Song | null>(null);
  const queueRef = useRef<Song[]>([]);
  const jukeboxRef = useRef(false);
  const isHostRef = useRef(isHost);
  const endedHandlerRef = useRef<() => void>(() => {});
  const pendingSeekRef = useRef(0);
  const lastPosTimeRef = useRef(0);
  const currentIsAutoRef = useRef(false);
  const currentTimeRef = useRef(0);

  useEffect(() => { connRef.current = connection; }, [connection]);
  useEffect(() => { currentRef.current = current; }, [current]);
  useEffect(() => { currentTimeRef.current = currentTime; }, [currentTime]);
  useEffect(() => { queueRef.current = queue; }, [queue]);
  useEffect(() => { jukeboxRef.current = jukeboxMode; }, [jukeboxMode]);
  useEffect(() => { isHostRef.current = isHost; }, [isHost]);

  const coverUrl = useCallback(
    (coverArt: string, size = 300) => buildCoverArtUrl(connRef.current, coverArt, size),
    [],
  );

  // ── Host: audio playback ──────────────────────────────────────────────

  const startPlaying = useCallback((song: Song, isAutoQueue = false) => {
    const audio = audioRef.current;
    if (!audio) return;
    setStreamError(null);
    setCurrent(song);
    currentRef.current = song;
    currentIsAutoRef.current = isAutoQueue;
    setCurrentTime(0);
    setDuration(song.duration || 0);
    pendingSeekRef.current = 0;
    audio.src = streamUrl(connRef.current, song.id);
    audio.volume = volume;
    audio.play().catch(() => {});
    setIsPlaying(true);
    void scrobble(connRef.current, song.id, false);
    void updateNowPlaying(song, true, isAutoQueue);
  }, [volume]);

  const pushHistory = useCallback((song: Song) => {
    setHistory((h) => [song, ...h].slice(0, 100));
  }, []);

  const dequeueOrPick = useCallback(async (): Promise<{ song: Song | null; isAutoQueue: boolean }> => {
    const result = await dequeueNext();
    if (result.song) {
      const updated = await fetchQueueSongs();
      setQueue(updated);
      queueRef.current = updated;
      return { song: result.song, isAutoQueue: !result.isManual };
    }
    return { song: null, isAutoQueue: false };
  }, []);

  const advance = useCallback(
    async (reason: 'ended' | 'skip') => {
      const prev = currentRef.current;
      const audio = audioRef.current;
      const progress = audio && audio.duration ? audio.currentTime / audio.duration : 0;
      if (prev) {
        if (reason === 'ended') {
          void scrobble(connRef.current, prev.id, true);
        }
        pushHistory(prev);
      }
      const { song: nextSong, isAutoQueue } = await dequeueOrPick();
      if (nextSong) {
        startPlaying(nextSong, isAutoQueue);
      } else {
        const audioEl = audioRef.current;
        if (audioEl) audioEl.pause();
        setCurrent(null);
        currentRef.current = null;
        setIsPlaying(false);
        void updateNowPlaying(null, false);
      }
    },
    [dequeueOrPick, pushHistory, startPlaying],
  );

  endedHandlerRef.current = () => { void advance('ended'); };

  // Host: set up the audio element.
  useEffect(() => {
    if (!isHost) return;
    const audio = new Audio();
    audio.preload = 'auto';
    audio.volume = volume;
    audioRef.current = audio;

    const onTime = () => setCurrentTime(audio.currentTime);
    const onMeta = () => {
      setDuration(audio.duration || 0);
      if (pendingSeekRef.current > 0) {
        audio.currentTime = Math.min(pendingSeekRef.current, audio.duration || pendingSeekRef.current);
        setCurrentTime(audio.currentTime);
        pendingSeekRef.current = 0;
      }
    };
    const onPlay = () => setIsPlaying(true);
    const onPause = () => setIsPlaying(false);
    const onEnded = () => endedHandlerRef.current();
    const onError = () => {
      setStreamError('This track could not be played. Skipping to the next one.');
      if (jukeboxRef.current || queueRef.current.length > 0) {
        void advance('skip');
      }
    };

    audio.addEventListener('timeupdate', onTime);
    audio.addEventListener('loadedmetadata', onMeta);
    audio.addEventListener('play', onPlay);
    audio.addEventListener('pause', onPause);
    audio.addEventListener('ended', onEnded);
    audio.addEventListener('error', onError);

    return () => {
      audio.removeEventListener('timeupdate', onTime);
      audio.removeEventListener('loadedmetadata', onMeta);
      audio.removeEventListener('play', onPlay);
      audio.removeEventListener('pause', onPause);
      audio.removeEventListener('ended', onEnded);
      audio.removeEventListener('error', onError);
      audio.pause();
      audio.src = '';
      audioRef.current = null;
      // Do NOT call updateNowPlaying(null, false) here. When the host switches
      // devices, isHost flips to false and this cleanup runs — but the now-playing
      // state must persist so the new device can resume from it. The server
      // retains the song/position until a new host actively changes it.
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isHost]);

  useEffect(() => {
    if (!isHost || !current || !audioRef.current) return;
    const audio = audioRef.current;
    if (audio.src.endsWith(`/api/stream/${encodeURIComponent(current.id)}`)) return;
    audio.src = streamUrl(connRef.current, current.id);
    audio.volume = volume;
    const playWhenReady = () => {
      if (isPlaying) audio.play().catch(() => {});
    };
    audio.addEventListener('canplay', playWhenReady, { once: true });
    if (isPlaying) audio.play().catch(() => {});
    // Re-broadcast now-playing so all clients see the new device is active.
    void updateNowPlaying(current, true, currentIsAutoRef.current);
    return () => audio.removeEventListener('canplay', playWhenReady);
  }, [current?.id, isHost]);

  useEffect(() => {
    if (!isHost || !current) return;
    const save = () => {
      const audio = audioRef.current;
      if (audio && Number.isFinite(audio.currentTime)) void savePlaybackPosition(audio.currentTime);
    };
    const interval = window.setInterval(save, 2000);
    return () => {
      window.clearInterval(interval);
      save();
    };
  }, [current?.id, isHost]);

  // ── Shared state: load queue + now-playing from server, subscribe via WS ──

  useEffect(() => {
    let cancelled = false;

    async function loadInitial() {
      const [queueSongs, nowPlaying, cd, maxReq, jukebox] = await Promise.all([
        fetchQueueSongs(),
        fetchNowPlaying(),
        fetchCooldownMinutes(),
        fetchMaxRequestsPerUser(),
        fetchJukeboxMode(),
      ]);
      if (cancelled) return;
      setQueue(queueSongs);
      queueRef.current = queueSongs;
      setCooldownMinutes(cd);
      setMaxRequestsPerUser(maxReq);
      setJukeboxMode(jukebox);
      jukeboxRef.current = jukebox;

      if (nowPlaying.song) {
        setCurrent(nowPlaying.song);
        currentRef.current = nowPlaying.song;
        currentIsAutoRef.current = nowPlaying.isAutoQueue;
        setIsPlaying(nowPlaying.isPlaying);
        setDuration(nowPlaying.song.duration || 0);
        const pos = nowPlaying.playbackPosition || 0;
        pendingSeekRef.current = pos;
        setCurrentTime(pos);
        lastPosTimeRef.current = Date.now();
      }
    }

    void loadInitial();

    const ws = connectWebSocket((type, data) => {
      if (cancelled) return;
      onWebSocketEvent?.(type);

      if (type === 'force_skip') {
        if (isHostRef.current && audioRef.current) {
          void advance('skip');
        }
        return;
      }

      if (type === 'queue') {
        void fetchQueueSongs().then((songs) => {
          if (cancelled) return;
          setQueue(songs);
          queueRef.current = songs;
        });
        void fetchQueue().then((rows) => {
          if (cancelled) return;
          setQueueRows(rows);
        });
      } else if (type === 'now_playing') {
        void fetchNowPlaying().then((np) => {
          if (cancelled || !np) return;
          if (np.song) {
            if (currentRef.current?.id !== np.song.id) {
              setCurrent(np.song);
              currentRef.current = np.song;
              currentIsAutoRef.current = np.isAutoQueue;
              setDuration(np.song.duration || 0);
              const pos = np.playbackPosition || 0;
              pendingSeekRef.current = pos;
              setCurrentTime(pos);
              lastPosTimeRef.current = Date.now();
            } else {
              currentIsAutoRef.current = np.isAutoQueue;
            }
            setIsPlaying(np.isPlaying);
            if (np.playbackPosition && !isHostRef.current) {
              const local = currentTimeRef.current;
              const serverPos = np.playbackPosition;
              // Only accept server position if it's a real seek (large delta) —
              // small backwards jumps are just the host's 2s save lag stutter.
              if (Math.abs(serverPos - local) > 3) {
                setCurrentTime(serverPos);
                lastPosTimeRef.current = Date.now();
              }
            }
          } else {
            setCurrent(null);
            currentRef.current = null;
            setIsPlaying(false);
          }
        });
      } else if (type === 'jukebox') {
        void fetchJukeboxMode().then((mode) => {
          if (cancelled) return;
          setJukeboxMode(mode);
          jukeboxRef.current = mode;
        });
      } else if (type === 'playback_position') {
        const pos = typeof data?.position === 'number' ? data.position : 0;
        if (isHostRef.current) {
          // Host doesn't use server position for its own progress bar
        } else {
          const local = currentTimeRef.current;
          // Only accept server position if it's a real seek (large delta) —
          // small backwards jumps are just the host's 2s save lag stutter.
          if (Math.abs(pos - local) > 3) {
            setCurrentTime(pos);
            lastPosTimeRef.current = Date.now();
          }
          // Otherwise leave the local timer's anchor untouched so it keeps
          // advancing smoothly without a zero-elapsed stutter every 2s.
        }
      }
    });

    return () => {
      cancelled = true;
      if (ws) ws.close();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Non-host clients advance currentTime locally between server updates so
  // the progress bar moves smoothly even though they have no audio element.
  useEffect(() => {
    if (isHost) return;
    if (!isPlaying || !current) return;
    // Reset the anchor when the timer (re)starts so a pause/resume gap
    // isn't counted as elapsed playback time.
    lastPosTimeRef.current = Date.now();
    const interval = window.setInterval(() => {
      const elapsed = (Date.now() - lastPosTimeRef.current) / 1000;
      const next = currentTimeRef.current + elapsed;
      if (!duration || next < duration) {
        setCurrentTime(next);
        lastPosTimeRef.current = Date.now();
      }
    }, 1000);
    return () => window.clearInterval(interval);
  }, [isHost, isPlaying, current?.id, duration]);

  // ── Host-only actions ─────────────────────────────────────────────────

  const playNow = useCallback(
    (song: Song) => {
      if (!isHost) return;
      const prev = currentRef.current;
      const audio = audioRef.current;
      const progress = audio && audio.duration ? audio.currentTime / audio.duration : 0;
      if (prev && prev.id !== song.id) {
        pushHistory(prev);
      }
      startPlaying(song, false);
    },
    [isHost, pushHistory, startPlaying],
  );

  const enqueue = useCallback(
    async (song: Song) => {
      const hadManual = queueRows.some((r) => r.isManual);
      const currentIsManual = !!currentRef.current && !currentIsAutoRef.current;

      setQueue((q) => {
        if (q.some((s) => s.id === song.id)) return q;
        return [...q, song];
      });

      try {
        await addToQueue(song, true);
      } catch (err) {
        const updated = await fetchQueueSongs();
        setQueue(updated);
        queueRef.current = updated;
        throw err;
      }

      if (isHost && !currentIsManual && !hadManual) {
        const result = await dequeueNext();
        if (result.song) {
          const updated = await fetchQueueSongs();
          setQueue(updated);
          queueRef.current = updated;
          startPlaying(result.song, !result.isManual);
        }
      }
    },
    [isHost, queueRows, startPlaying],
  );

  const togglePlay = useCallback(() => {
    if (!isHost) return;
    const audio = audioRef.current;
    if (!audio || !currentRef.current) return;
    if (audio.paused) {
      audio.play().catch(() => {});
      void updateNowPlaying(currentRef.current, true, currentIsAutoRef.current);
    } else {
      audio.pause();
      void updateNowPlaying(currentRef.current, false, currentIsAutoRef.current);
    }
  }, [isHost]);

  const next = useCallback(() => {
    if (!isHost) return;
    void advance('skip');
  }, [advance, isHost]);

  const seek = useCallback((seconds: number) => {
    if (!isHost) return;
    const audio = audioRef.current;
    if (audio && Number.isFinite(seconds)) audio.currentTime = seconds;
  }, [isHost]);

  const setVolume = useCallback((v: number) => {
    const clamped = Math.max(0, Math.min(1, v));
    setVolumeState(clamped);
    if (audioRef.current) audioRef.current.volume = clamped;
  }, []);

  const vote = useCallback(
    async (event: 'thumbs_up' | 'thumbs_down') => {
      const song = currentRef.current;
      if (!song) return;
      try {
        await voteOnCurrent(event === 'thumbs_up' ? 'up' : 'down');
      } catch {
        // No song playing, or the vote was rejected.
      }
      // Host-side fallback: if the host downvotes an auto-queued song, skip it
      // immediately even if the server's force_skip broadcast hasn't arrived yet.
      if (isHost && event === 'thumbs_down' && currentIsAutoRef.current) {
        void advance('skip');
      }
    },
    [advance, isHost],
  );

  const thumbsUp = useCallback(() => { void vote('thumbs_up'); }, [vote]);
  const thumbsDown = useCallback(() => { void vote('thumbs_down'); }, [vote]);

  const toggleJukebox = useCallback(() => {
    if (!isHost) return;
    const nextOn = !jukeboxRef.current;
    setJukeboxMode(nextOn);
    jukeboxRef.current = nextOn;
    if (nextOn) {
      void (async () => {
        try {
          await setJukeboxModeApi(true);
          if (!currentRef.current) {
            const result = await dequeueNext();
            if (result.song) startPlaying(result.song, !result.isManual);
          }
        } catch {
          setJukeboxMode(false);
          jukeboxRef.current = false;
        }
      })();
    } else {
      void (async () => {
        try {
          await setJukeboxModeApi(false);
          await clearAutoQueue();
          const updated = await fetchQueueSongs();
          setQueue(updated);
          queueRef.current = updated;
        } catch {
          setJukeboxMode(true);
          jukeboxRef.current = true;
        }
      })();
    }
  }, [isHost, startPlaying]);

  /** Starts automatic playback from the selected source without requiring a manual search. */
  const startRandomPlayback = useCallback(() => {
    if (!isHost || currentRef.current) return;
    const wasEnabled = jukeboxRef.current;
    // Asking the server to enable Jukebox also makes sure its random queue is
    // populated. Do this even when it was already enabled but happened to be
    // empty, so the idle Play button is always a reliable way to start music.
    setJukeboxMode(true);
    jukeboxRef.current = true;
    void (async () => {
      try {
        await setJukeboxModeApi(true);
        const result = await dequeueNext();
        if (result.song) startPlaying(result.song, !result.isManual);
      } catch {
        setJukeboxMode(wasEnabled);
        jukeboxRef.current = wasEnabled;
      }
    })();
  }, [dequeueNext, isHost, startPlaying]);

  const value = useMemo<PlayerContextValue>(
    () => ({
      connection, isHost, isHostUser, isActivePlayer, current, queue, queueRows, history,
      isPlaying, currentTime, duration, volume, jukeboxMode, loadingNext,
      streamError, cooldownMinutes, maxRequestsPerUser,
      coverUrl, playNow, enqueue, togglePlay, next, seek, setVolume,
      thumbsUp, thumbsDown, toggleJukebox, startRandomPlayback,
    }),
    [
      connection, isHost, isHostUser, isActivePlayer, current, queue, queueRows, history,
      isPlaying, currentTime, duration, volume, jukeboxMode, loadingNext,
      streamError, cooldownMinutes, maxRequestsPerUser,
      coverUrl, playNow, enqueue, togglePlay, next, seek, setVolume,
      thumbsUp, thumbsDown, toggleJukebox, startRandomPlayback,
    ],
  );

  return <PlayerContext.Provider value={value}>{children}</PlayerContext.Provider>;
}

export function usePlayer(): PlayerContextValue {
  const ctx = useContext(PlayerContext);
  if (!ctx) throw new Error('usePlayer must be used within a PlayerProvider');
  return ctx;
}
