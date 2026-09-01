import test from 'node:test';
import assert from 'node:assert/strict';
import { createAndroidDownloadResolver } from '../android-download.js';

test('Android download resolver selects the newest versioned APK and caches it', async () => {
  let calls = 0;
  const resolve = createAndroidDownloadResolver(async () => {
    calls += 1;
    return new Response(JSON.stringify([
      { tag_name: 'v1.0.41', assets: [] },
      { tag_name: 'v1.0.20', assets: [{ name: 'harmonicast-1.0.20.apk' }] },
      { tag_name: 'v1.0.21', assets: [{ name: 'harmonicast-1.0.21.apk' }] },
      { tag_name: 'dev-android-v9.9.9.1', prerelease: true, assets: [{ name: 'harmonicast-9.9.9.apk' }] },
      { tag_name: 'v9.9.8', draft: true, assets: [{ name: 'harmonicast-9.9.8.apk' }] },
      { tag_name: 'v1.0.99', assets: [{ name: 'server.tar.gz' }] },
    ]), { status: 200 });
  });
  assert.equal(await resolve(), 'https://github.com/sneedster/harmonicast/releases/download/v1.0.21/harmonicast-1.0.21.apk');
  assert.equal(await resolve(), 'https://github.com/sneedster/harmonicast/releases/download/v1.0.21/harmonicast-1.0.21.apk');
  assert.equal(calls, 1);
});
