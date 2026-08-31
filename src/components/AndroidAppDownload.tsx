import { useState } from 'react';
import { Download, QrCode, Smartphone, X } from 'lucide-react';
import { QRCodeSVG } from 'qrcode.react';

// Harmonicast selects the newest versioned Android asset separately from its
// server releases, which may be newer than the APK.
const ANDROID_APP_DOWNLOAD_PATH = '/api/downloads/android';

export function AndroidAppDownload({ compact = false }: { compact?: boolean }) {
  const [showQr, setShowQr] = useState(false);
  const androidDownloadUrl = new URL(ANDROID_APP_DOWNLOAD_PATH, window.location.origin).toString();

  return (
    <div className={compact ? 'text-center' : 'w-full max-w-md text-center'}>
      <div className="flex flex-wrap items-center justify-center gap-2">
        <a
          href={ANDROID_APP_DOWNLOAD_PATH}
          target="_blank"
          rel="noreferrer"
          className="inline-flex items-center gap-2 rounded-full border border-amber-500/40 px-4 py-2 text-xs font-semibold text-amber-300 transition hover:border-amber-400 hover:bg-amber-500/10"
        >
          <Smartphone className="h-4 w-4" /> Download Android app <Download className="h-3.5 w-3.5" />
        </a>
        <button
          type="button"
          onClick={() => setShowQr((visible) => !visible)}
          aria-expanded={showQr}
          className="inline-flex items-center gap-2 rounded-full border border-ink-700 px-4 py-2 text-xs font-semibold text-ink-300 transition hover:border-ink-500 hover:bg-ink-800 hover:text-white"
        >
          {showQr ? <X className="h-4 w-4" /> : <QrCode className="h-4 w-4" />}
          {showQr ? 'Hide QR code' : 'Show QR code'}
        </button>
      </div>
      {showQr && (
        <div className="mx-auto mt-4 inline-flex flex-col items-center gap-3 rounded-2xl border border-ink-700 bg-white p-4 shadow-xl shadow-black/30">
          <QRCodeSVG value={androidDownloadUrl} size={176} level="M" includeMargin />
          <span className="max-w-44 text-center text-xs font-medium text-ink-800">Scan to download Harmonicast for Android</span>
        </div>
      )}
    </div>
  );
}
