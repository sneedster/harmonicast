import assert from 'node:assert/strict';
import { PassThrough } from 'node:stream';
import test from 'node:test';
import { pipeWebResponseBody } from '../streaming.js';

test('stream proxy resolves after a complete upstream body', async () => {
  const body = new Response('complete track').body;
  assert.ok(body);
  const destination = new PassThrough();
  const chunks: Buffer[] = [];
  destination.on('data', (chunk) => chunks.push(Buffer.from(chunk)));

  await pipeWebResponseBody(body, destination);

  assert.equal(Buffer.concat(chunks).toString(), 'complete track');
});

test('stream proxy reports an upstream disconnect through its promise', async () => {
  const terminated = Object.assign(new TypeError('terminated'), { code: 'UND_ERR_SOCKET' });
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(new TextEncoder().encode('partial track'));
      controller.error(terminated);
    },
  });
  const destination = new PassThrough();

  await assert.rejects(pipeWebResponseBody(body, destination), (error) => error === terminated);
});
