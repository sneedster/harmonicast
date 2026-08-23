import { useState, useEffect } from 'react';
import { Disc3, Loader2, AlertCircle, Music4 } from 'lucide-react';
import { getAuthConfig, googleSignInUrl } from '@/lib/api';

export function AuthScreen({ onAuth }: { onAuth: (email: string) => void }) {
  const [config, setConfig] = useState<{ googleOAuth: boolean; adminEmail?: string | null; needsAdmin?: boolean; hasUsers?: boolean } | null>(null);
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
        not_admin: 'Only the admin email configured in docker-compose.yml can create the first account.',
        not_invited: 'Your email is not on the invite list. Ask the host to add you.',
        unverified_email: 'Your Google email is not verified.',
        oauth_failed: 'Google sign-in failed. Please try again.',
        missing_code: 'Authentication failed. Please try again.',
        invalid_state: 'This sign-in link has expired or is not valid. Please start again.',
      };
      setError(messages[authError] || 'Authentication failed.');
      window.history.replaceState({}, '', '/');
    }

    getAuthConfig()
      .then(setConfig)
      .catch(() => setConfig({ googleOAuth: false }))
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

        {config?.googleOAuth ? (
          <div className="rounded-2xl border border-ink-800 bg-ink-900/80 p-6 shadow-xl shadow-black/30">
            <a
              href={googleSignInUrl()}
              className="flex w-full items-center justify-center gap-3 rounded-lg bg-white px-4 py-2.5 text-sm font-semibold text-ink-950 transition hover:bg-ink-100"
            >
              <GoogleIcon />
              Sign in with Google
            </a>
            <p className="mt-4 text-center text-xs text-ink-500 leading-relaxed">
              This is an invite-only jukebox. If your Google email is not on the
              invite list, ask the host to add you.
            </p>
          </div>
        ) : (
          <div className="rounded-2xl border border-amber-500/30 bg-amber-500/5 p-6 text-center">
            <AlertCircle className="mx-auto mb-3 h-6 w-6 text-amber-400" />
            <p className="text-sm text-ink-300 leading-relaxed">
              Google sign-in is not configured yet. The host needs to set the
              Google OAuth environment variables in the Docker configuration.
            </p>
          </div>
        )}

        {config?.adminEmail && !config?.hasUsers ? (
          <div className="mt-4 flex items-center justify-center gap-1.5 text-center text-xs text-ink-500">
            <Music4 className="h-3.5 w-3.5" />
            <span>Sign in with <span className="text-amber-400 font-medium">{config.adminEmail}</span> to set up the jukebox.</span>
          </div>
        ) : config?.needsAdmin ? (
          <div className="mt-4 flex items-center justify-center gap-1.5 text-center text-xs text-amber-400">
            <AlertCircle className="h-3.5 w-3.5" />
            <span>Set ADMIN_EMAIL in docker-compose.yml to get started.</span>
          </div>
        ) : (
          <div className="mt-4 flex items-center justify-center gap-1.5 text-center text-xs text-ink-500">
            <Music4 className="h-3.5 w-3.5" />
            <span>The first person to sign in becomes the host.</span>
          </div>
        )}
      </div>
    </div>
  );
}

function GoogleIcon() {
  return (
    <svg className="h-5 w-5" viewBox="0 0 24 24">
      <path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z" />
      <path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" />
      <path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z" />
      <path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z" />
    </svg>
  );
}
