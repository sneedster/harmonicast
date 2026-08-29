import { useCallback, useEffect, useRef, useState } from 'react';
import { Disc3, Search, ListMusic, BarChart3, LogOut, Radio, User, Music2, Settings, MonitorSpeaker, Loader2 } from 'lucide-react';
import type { Connection } from '@/types';
import { connectionFromInfo } from '@/lib/connectionStore';
import { PlayerProvider, usePlayer } from '@/hooks/usePlayer';
import { PlexSetup } from '@/components/PlexSetup';
import { AuthScreen } from '@/components/AuthScreen';
import { PlayerBar } from '@/components/PlayerBar';
import { SearchView } from '@/components/SearchView';
import { QueueView } from '@/components/QueueView';
import { StatsView } from '@/components/StatsView';
import { NowPlayingView } from '@/components/NowPlayingView';
import { SettingsView } from '@/components/SettingsView';
import { claimPlayer, getConnection, getPlayerStatus, getMe, signOut, listSessions, type SessionDevice } from '@/lib/api';

type Tab = 'nowplaying' | 'search' | 'queue' | 'stats' | 'settings';

const AUTO_SWITCH_DELAY = 8000;

function DeviceModal({
  onTakeOver,
  onWatchAsGuest,
}: {
  onTakeOver: () => void;
  onWatchAsGuest: () => void;
}) {
  const [claiming, setClaiming] = useState(false);

  async function handleTakeOver() {
    setClaiming(true);
    try {
      await onTakeOver();
    } finally {
      setClaiming(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-ink-950/90 backdrop-blur-sm px-4">
      <div className="w-full max-w-sm rounded-2xl border border-ink-700 bg-ink-900 p-8 shadow-2xl">
        <div className="mb-5 flex h-12 w-12 items-center justify-center rounded-xl bg-amber-500/15 ring-1 ring-amber-500/30">
          <MonitorSpeaker className="h-6 w-6 text-amber-400" />
        </div>
        <h2 className="mb-2 text-lg font-semibold text-white">Another device is playing</h2>
        <p className="mb-7 text-sm leading-relaxed text-ink-400">
          Resonance is currently playing audio on another device. Do you want to take over playback on this device, or watch along as a guest?
        </p>
        <div className="flex flex-col gap-3">
          <button
            onClick={handleTakeOver}
            disabled={claiming}
            className="flex items-center justify-center gap-2 rounded-xl bg-amber-500 py-3 text-sm font-semibold text-ink-950 transition hover:bg-amber-400 disabled:opacity-60"
          >
            {claiming ? <Loader2 className="h-4 w-4 animate-spin" /> : <MonitorSpeaker className="h-4 w-4" />}
            Take over playback
          </button>
          <button
            onClick={onWatchAsGuest}
            className="rounded-xl border border-ink-700 py-3 text-sm font-medium text-ink-300 transition hover:bg-ink-800 hover:text-white"
          >
            Watch along as guest
          </button>
        </div>
      </div>
    </div>
  );
}

function JukeboxApp({
  userEmail,
  isHostUser,
  isActivePlayer,
  onSignOut,
  onTakeOver,
}: {
  userEmail: string;
  isHostUser: boolean;
  isActivePlayer: boolean;
  onSignOut: () => void;
  onTakeOver: () => Promise<void>;
}) {
  const { connection, current, isPlaying, jukeboxMode, isHost } = usePlayer();
  const [tab, setTab] = useState<Tab>('nowplaying');
  const lastSongIdRef = useRef<string | null>(null);
  const switchTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    const songId = current?.id ?? null;
    if (songId !== lastSongIdRef.current) {
      lastSongIdRef.current = songId;
      if (songId) {
        if (switchTimerRef.current) clearTimeout(switchTimerRef.current);
        switchTimerRef.current = setTimeout(() => {
          setTab('nowplaying');
        }, AUTO_SWITCH_DELAY);
      }
    }
    return () => {
      if (switchTimerRef.current) clearTimeout(switchTimerRef.current);
    };
  }, [current?.id]);

  return (
    <div className="flex flex-col bg-ink-950" style={{ height: '100dvh' }}>
      <header className="flex items-center justify-between border-b border-ink-800 px-4 py-3 sm:px-6">
        <div className="flex items-center gap-2.5">
          <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-amber-500/15 ring-1 ring-amber-500/30">
            <Disc3 className={`h-5 w-5 text-amber-400 ${isPlaying ? 'animate-spin' : ''}`} style={{ animationDuration: '3s' }} />
          </div>
          <div>
            <h1 className="text-base font-semibold leading-none text-white">Resonance</h1>
            <p className="mt-0.5 text-[11px] text-ink-500">
              {connection.serverName || new URL(connection.baseUrl).hostname}
            </p>
          </div>
        </div>
        <div className="flex items-center gap-1.5 sm:gap-3">
          <div className="flex items-center gap-1.5 text-xs text-ink-400">
            <User className="h-3.5 w-3.5 shrink-0" />
            <span className="hidden max-w-[120px] truncate sm:inline">{userEmail}</span>
            {isHostUser && isActivePlayer && (
              <span className="ml-1 hidden rounded-full bg-amber-500/15 px-1.5 py-0.5 text-[10px] font-medium text-amber-400 sm:inline">Host</span>
            )}
            {isHostUser && !isActivePlayer && (
              <span className="ml-1 hidden rounded-full bg-ink-700 px-1.5 py-0.5 text-[10px] font-medium text-ink-300 sm:inline">Host (watching)</span>
            )}
            {!isHostUser && (
              <span className="ml-1 hidden rounded-full bg-ink-700 px-1.5 py-0.5 text-[10px] font-medium text-ink-300 sm:inline">Guest</span>
            )}
          </div>
          {isHostUser && !isActivePlayer && (
            <button
              onClick={onTakeOver}
              className="flex items-center gap-1.5 rounded-full border border-amber-500/30 px-3 py-1.5 text-xs text-amber-400 transition hover:bg-amber-500/10"
              title="Move playback to this device"
            >
              <MonitorSpeaker className="h-3.5 w-3.5" /><span className="hidden sm:inline">Play here</span>
            </button>
          )}
          <button
            onClick={onSignOut}
            className="flex items-center gap-1.5 rounded-full px-3 py-1.5 text-xs text-ink-400 transition hover:bg-ink-800 hover:text-white"
            title="Sign out of your account"
          >
            <LogOut className="h-3.5 w-3.5" /><span className="hidden sm:inline">Sign out</span>
          </button>
        </div>
      </header>

      <nav className="flex gap-1 overflow-x-auto border-b border-ink-800 px-1 sm:px-4">
        <TabButton active={tab === 'nowplaying'} onClick={() => setTab('nowplaying')} icon={<Music2 className="h-4 w-4" />} label="Now Playing" />
        <TabButton active={tab === 'search'} onClick={() => setTab('search')} icon={<Search className="h-4 w-4" />} label="Search" />
        <TabButton active={tab === 'queue'} onClick={() => setTab('queue')} icon={<ListMusic className="h-4 w-4" />} label="Queue" />
        <TabButton active={tab === 'stats'} onClick={() => setTab('stats')} icon={<BarChart3 className="h-4 w-4" />} label="Stats" />
        {isHostUser && (
          <TabButton active={tab === 'settings'} onClick={() => setTab('settings')} icon={<Settings className="h-4 w-4" />} label="Settings" />
        )}
      </nav>

      <main className="flex-1 overflow-y-auto scrollbar-thin px-4 py-6 sm:px-6">
        <div className="mx-auto max-w-3xl">
          {tab === 'nowplaying' && <NowPlayingView />}
          {tab === 'search' && <SearchView />}
          {tab === 'queue' && <QueueView />}
          {tab === 'stats' && <StatsView />}
          {tab === 'settings' && <SettingsView isHostUser={isHostUser} />}
        </div>
      </main>

      {isHost && !current && !jukeboxMode && (
        <div className="flex items-center justify-center gap-2 border-t border-ink-800 bg-ink-900/60 px-4 py-2 text-center text-xs text-ink-500">
          <Radio className="h-3.5 w-3.5" />
          Start with Search, then choose Play now. Turn on Jukebox later for continuous playback.
        </div>
      )}
      <PlayerBar />
    </div>
  );
}

function TabButton({
  active,
  onClick,
  icon,
  label,
}: {
  active: boolean;
  onClick: () => void;
  icon: React.ReactNode;
  label: string;
}) {
  return (
    <button
      onClick={onClick}
      className={`flex shrink-0 items-center gap-2 border-b-2 px-3 py-3 text-sm font-medium transition sm:px-4 ${
        active
          ? 'border-amber-500 text-white'
          : 'border-transparent text-ink-400 hover:text-white'
      }`}
    >
      {icon} <span className="hidden sm:inline">{label}</span>
    </button>
  );
}

export default function App() {
  const [userEmail, setUserEmail] = useState<string | null>(null);
  const [userName, setUserName] = useState<string | null>(null);
  const [connection, setConnection] = useState<Connection | null>(null);
  const [needsSetup, setNeedsSetup] = useState(false);
  const [needsPlexSetup, setNeedsPlexSetup] = useState(false);
  const [isPlexSetupOwner, setIsPlexSetupOwner] = useState(false);
  const [isHostUser, setIsHostUser] = useState(false);
  const [isActivePlayer, setIsActivePlayer] = useState(false);
  const [showDeviceModal, setShowDeviceModal] = useState(false);
  const [sessions, setSessions] = useState<SessionDevice[]>([]);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      const me = await getMe();
      if (cancelled) return;
      if (me) {
        setUserEmail(me.email);
        setUserName(me.name || null);
      }
      setReady(true);
    })();
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    if (!userEmail) return;
    let cancelled = false;
    (async () => {
      const info = await getConnection();
      if (cancelled) return;

      if (!info.configured) {
        setNeedsSetup(true);
        setNeedsPlexSetup(!!info.needsPlexSetup);
        setIsPlexSetupOwner(!!info.isSetupOwner);
        setIsHostUser(!!info.isSetupOwner);
        setIsActivePlayer(false);
        return;
      }

      const conn = connectionFromInfo(info);
      setConnection(conn);
      setNeedsSetup(false);
      setNeedsPlexSetup(false);

      // Determine host and active player status from the full ConnectionInfo
      setIsHostUser(!!info.isHost);

      if (info.isHost) {
        if (!info.hasActivePlayer) {
          // No device is playing yet — auto-claim this one
          await claimPlayer().catch(() => {});
          if (!cancelled) setIsActivePlayer(true);
        } else if (info.isActivePlayer) {
          setIsActivePlayer(true);
        } else {
          // Another device is already the active player — ask
          setShowDeviceModal(true);
        }
      } else {
        setIsActivePlayer(false);
      }
    })();
    return () => { cancelled = true; };
  }, [userEmail]);

  const refreshSessions = useCallback(async () => {
    if (!isHostUser) return;
    try { setSessions(await listSessions()); } catch {}
  }, [isHostUser]);

  useEffect(() => { void refreshSessions(); }, [refreshSessions]);

  const handleTakeOver = useCallback(async () => {
    await claimPlayer();
    setIsActivePlayer(true);
    setShowDeviceModal(false);
    void refreshSessions();
  }, [refreshSessions]);

  const handleWatchAsGuest = useCallback(() => {
    setIsActivePlayer(false);
    setShowDeviceModal(false);
  }, []);

  const handlePlayerSessionEvent = useCallback(async () => {
    const status = await getPlayerStatus().catch(() => null);
    if (!status) return;
    setIsActivePlayer(status.isActivePlayer);
    if (!status.isActivePlayer && !status.hasActivePlayer) {
      // Session that was playing signed out — auto-reclaim
      await claimPlayer().catch(() => {});
      setIsActivePlayer(true);
    }
    void refreshSessions();
  }, [refreshSessions]);

  async function handleSignOut() {
    await signOut();
    setUserEmail(null);
    setUserName(null);
    setConnection(null);
    setNeedsSetup(false);
    setNeedsPlexSetup(false);
    setIsPlexSetupOwner(false);
    setIsHostUser(false);
    setIsActivePlayer(false);
    setShowDeviceModal(false);
  }

  function handleAuth(email: string) {
    setUserEmail(email);
  }

  if (!ready) return null;

  if (!userEmail) {
    return <AuthScreen onAuth={handleAuth} />;
  }

  if (needsPlexSetup && !connection && isPlexSetupOwner) {
    return (
      <PlexSetup
        onComplete={async () => {
          const info = await getConnection();
          if (!info.configured) throw new Error('Plex source was not saved');
          const conn = connectionFromInfo(info);
          await claimPlayer().catch(() => {});
          setConnection(conn);
          setNeedsSetup(false);
          setNeedsPlexSetup(false);
          setIsHostUser(true);
          setIsActivePlayer(true);
        }}
      />
    );
  }

  if (needsPlexSetup && !connection) {
    return (
      <main className="flex min-h-screen items-center justify-center bg-ink-950 p-5">
        <div className="w-full max-w-lg rounded-2xl border border-ink-800 bg-ink-900 p-7 text-center shadow-2xl">
          <h1 className="text-xl font-semibold text-white">Plex setup is in progress</h1>
          <p className="mt-3 text-sm leading-relaxed text-ink-400">The Plex account that began setup must choose the server and Music library before anyone else can use this installation.</p>
          <button onClick={() => void handleSignOut()} className="mt-6 rounded-xl border border-ink-700 px-4 py-2 text-sm text-ink-200">Sign out</button>
        </div>
      </main>
    );
  }

  if (needsSetup && !connection) {
    return (
      <main className="flex min-h-screen items-center justify-center bg-ink-950 p-5">
        <div className="w-full max-w-lg rounded-2xl border border-ink-800 bg-ink-900 p-7 text-center shadow-2xl">
          <h1 className="text-xl font-semibold text-white">Plex source needs setup</h1>
          <p className="mt-3 text-sm leading-relaxed text-ink-400">This installation does not have a selected Plex Music library. Sign in again with the Plex account that owns the server to complete setup.</p>
          <button onClick={() => void handleSignOut()} className="mt-6 rounded-xl border border-ink-700 px-4 py-2 text-sm text-ink-200">Sign out</button>
        </div>
      </main>
    );
  }

  if (!connection) return null;

  // isHost prop to PlayerProvider = host user AND this device is the active player
  const effectiveIsHost = isHostUser && isActivePlayer;

  return (
    <>
      {showDeviceModal && (
        <DeviceModal onTakeOver={handleTakeOver} onWatchAsGuest={handleWatchAsGuest} />
      )}
      <PlayerProvider
        connection={connection}
        isHost={effectiveIsHost}
        isHostUser={isHostUser}
        isActivePlayer={isActivePlayer}
        onWebSocketEvent={(type) => {
          if (type === 'player_session') void handlePlayerSessionEvent();
        }}
      >
        <JukeboxApp
          userEmail={userName || userEmail}
          isHostUser={isHostUser}
          isActivePlayer={isActivePlayer}
          onSignOut={handleSignOut}
          onTakeOver={handleTakeOver}
        />
      </PlayerProvider>
    </>
  );
}
