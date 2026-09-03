import { Readable, type Writable } from 'node:stream';
import { pipeline } from 'node:stream/promises';

/**
 * Pipes a fetch response body into an HTTP response while keeping stream
 * errors in the promise chain.  A plain `.pipe()` leaves later errors on the
 * converted Readable unhandled, which makes Node terminate the process when
 * an upstream music server closes its socket mid-track.
 */
export async function pipeWebResponseBody(
  body: ReadableStream<Uint8Array>,
  destination: Writable,
): Promise<void> {
  await pipeline(Readable.fromWeb(body as never), destination);
}
