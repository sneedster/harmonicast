const GITHUB_RELEASES_URL = 'https://api.github.com/repos/sneedster/harmonicast/releases?per_page=100';
const FALLBACK_ANDROID_APK_URL = 'https://github.com/sneedster/harmonicast/releases/download/v1.0.21/harmonicast-1.0.21.apk';
const CACHE_MS = 6 * 60 * 60 * 1_000;

type FetchLike = typeof fetch;

interface CachedDownload { url: string; expiresAt: number; }

function apkFromReleases(payload: unknown): string | null {
  if (!Array.isArray(payload)) return null;
  const candidates: { version: number[]; url: string }[] = [];
  for (const release of payload) {
    if (!release || typeof release !== 'object') continue;
    const tag = (release as { tag_name?: unknown }).tag_name;
    const assets = (release as { assets?: unknown }).assets;
    if (typeof tag !== 'string' || !Array.isArray(assets)) continue;
    for (const asset of assets) {
      const name = asset && typeof asset === 'object' ? (asset as { name?: unknown }).name : null;
      if (typeof name !== 'string') continue;
      const match = /^harmonicast-(\d+)\.(\d+)\.(\d+)\.apk$/.exec(name);
      if (!match) continue;
      candidates.push({
        version: match.slice(1).map(Number),
        url: `https://github.com/sneedster/harmonicast/releases/download/${encodeURIComponent(tag)}/${encodeURIComponent(name)}`,
      });
    }
  }
  candidates.sort((a, b) => b.version[0] - a.version[0] || b.version[1] - a.version[1] || b.version[2] - a.version[2]);
  return candidates[0]?.url ?? null;
}

/** Finds the newest published Android asset without conflating it with server releases. */
export function createAndroidDownloadResolver(fetcher: FetchLike = fetch, now: () => number = Date.now) {
  let cached: CachedDownload | null = null;
  return async (): Promise<string> => {
    if (cached && cached.expiresAt > now()) return cached.url;
    let url = FALLBACK_ANDROID_APK_URL;
    try {
      const response = await fetcher(GITHUB_RELEASES_URL, { headers: { Accept: 'application/vnd.github+json' } });
      if (response.ok) url = apkFromReleases(await response.json()) ?? url;
    } catch {
      // The fallback remains usable during a GitHub outage or rate limit.
    }
    cached = { url, expiresAt: now() + CACHE_MS };
    return url;
  };
}
