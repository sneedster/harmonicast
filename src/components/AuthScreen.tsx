import { useState, useEffect } from 'react';
import { Disc3, Loader2, AlertCircle, Music4, Smartphone } from 'lucide-react';
import { getAuthConfig, plexSignInUrl } from '@/lib/api';

const ANDROID_APP_DOWNLOAD_URL = 'https://github.com/sneedster/resonance/releases/latest/download/resonance.apk';

export function AuthScreen({ onAuth }: { onAuth: (email: string) => void }) {
  const [config, setConfig] = useState<{ plexOAuth: boolean; setupInProgress?: boolean } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    // The session token arrives in the URL fragment, never the query string:
    // fragments are not sent to the server and so never reach access logs,
    // proxy logs, or Referer headers.
    const hash = new URLSearchParams(window.location.hash.replace(/^#/, ''));
    const params = new URLSearchParams(window.location.search);
    const token = hash.get('auth_token');
    const authError = params.get('auth_error');
    const email = hash.get('auth_email');

    if (token && email) {
      localStorage.setItem('resonance_token', token);
      window.history.replaceState({}, '', '/');
      onAuth(email);
      return;
    }

    if (authError) {
      const messages: Record<string, string> = {
        not_shared: 'Your Plex account does not have access to this jukebox’s Music library.',
        setup_required: 'Initial Plex setup is being completed by another account.',
        oauth_failed: 'Plex sign-in failed. Please try again.',
        invalid_state: 'This sign-in link has expired or is not valid. Please start again.',
      };
      setError(messages[authError] || 'Authentication failed.');
      window.history.replaceState({}, '', '/');
    }

    getAuthConfig()
      .then(setConfig)
      .catch(() => setConfig({ plexOAuth: false }))
      .finally(() => setLoading(false));
  }, [onAuth]);

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gradient-to-b from-ink-900 to-ink-950">
        <Loader2 className="h-8 w-8 animate-spin text-amber-400" />
      </div>
    );
  }

  return (
    <div className="min-h-screen flex items-center justify-center px-4 py-10 bg-gradient-to-b from-ink-900 to-ink-950">
      <div className="w-full max-w-md animate-fade-in">
        <div className="flex flex-col items-center text-center mb-8">
          <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-amber-500/15 ring-1 ring-amber-500/30 mb-4">
            <Disc3 className="h-7 w-7 text-amber-400" />
          </div>
          <h1 className="text-2xl font-semibold text-white tracking-tight">Resonance</h1>
          <p className="mt-2 text-sm text-ink-400 leading-relaxed">
            Sign in to join the jukebox — search, queue, and rate songs together.
          </p>
        </div>

        {error && (
          <div className="mb-4 flex items-start gap-2 rounded-lg border border-red-500/30 bg-red-500/10 px-3 py-2.5 text-sm text-red-300">
            <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
            <span>{error}</span>
          </div>
        )}

        {config?.plexOAuth ? (
          <div className="rounded-2xl border border-ink-800 bg-ink-900/80 p-6 shadow-xl shadow-black/30">
            <a
              href={plexSignInUrl()}
              className="flex w-full items-center justify-center gap-3 rounded-lg bg-[#e5a00d] px-4 py-2.5 text-sm font-semibold text-black transition hover:bg-[#f0b22e]"
            >
              <PlexIcon />
              Sign in with Plex
            </a>
            <p className="mt-4 text-center text-xs text-ink-500 leading-relaxed">
              Access is controlled by Plex. Ask the owner to share the Music library
              with your Plex account, then sign in again.
            </p>
          </div>
        ) : (
          <div className="rounded-2xl border border-amber-500/30 bg-amber-500/5 p-6 text-center">
            <AlertCircle className="mx-auto mb-3 h-6 w-6 text-amber-400" />
            <p className="text-sm text-ink-300 leading-relaxed">
              Plex sign-in is not configured yet. The host needs to set PUBLIC_URL
              in the Docker configuration.
            </p>
          </div>
        )}

        {config?.setupInProgress ? (
          <div className="mt-4 flex items-center justify-center gap-1.5 text-center text-xs text-ink-500">
            <Music4 className="h-3.5 w-3.5" />
            <span>The first Plex account is choosing this installation’s music library.</span>
          </div>
        ) : (
          <div className="mt-4 flex items-center justify-center gap-1.5 text-center text-xs text-ink-500">
            <Music4 className="h-3.5 w-3.5" />
            <span>The first person to sign in becomes the host.</span>
          </div>
        )}

        <a
          href={ANDROID_APP_DOWNLOAD_URL}
          target="_blank"
          rel="noreferrer"
          className="mt-6 flex items-center justify-center gap-2 text-sm text-amber-400 transition hover:text-amber-300"
        >
          <Smartphone className="h-4 w-4" /> Get the Android app
        </a>
      </div>
    </div>
  );
}

function PlexIcon() {
  return (
    <svg className="h-5 w-5" viewBox="0 0 24 24" aria-hidden="true">
      <path fill="currentColor" d="M3 5h11.3c4.4 0 6.7 2.5 6.7 5.8 0 3.3-2.3 5.8-6.7 5.8H9.2V20H3V5Zm6.2 4.2v3.2h4.2c1.1 0 1.7-.6 1.7-1.6s-.6-1.6-1.7-1.6H9.2Z" />
    </svg>
  );
}
