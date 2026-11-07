import json
import threading
import unittest
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from relay import RelayServer, SoulSync


@contextmanager
def running(server):
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}"
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


class SoulSyncStub(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, status, data):
        self.send_response(status)
        self.end_headers()
        self.wfile.write(json.dumps({"success": status < 300, "data": data}).encode())

    def do_GET(self):
        self.server.calls.append((self.path, self.headers["Authorization"], None))
        if self.path == "/api/v1/api-keys" and self.headers["Authorization"] == "Bearer sk_server":
            self.reply(200, {"keys": [{"label": "private"}]})
        else:
            self.reply(403, None)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        self.server.calls.append((self.path, self.headers["Authorization"], body))
        if self.path == "/api/v1/wishlist":
            self.reply(201, {"track": {"track_id": "stored-id", "private": "value"}})
        else:
            self.reply(409, None)


class RelayTest(unittest.TestCase):
    key = "hcr_" + "a" * 64

    def call(self, base, path, body=None, key=None, extra=None):
        headers = {"Authorization": f"Bearer {key or self.key}", "Content-Type": "application/json"}
        headers.update(extra or {})
        try:
            with urlopen(Request(base + path, None if body is None else json.dumps(body).encode(), headers), timeout=5) as reply:
                return reply.status, json.load(reply)
        except HTTPError as error:
            with error:
                return error.code, json.load(error)

    def test_submits_only_track_fields_and_preserves_receipt_when_processor_is_busy(self):
        upstream = ThreadingHTTPServer(("127.0.0.1", 0), SoulSyncStub)
        upstream.calls = []
        with running(upstream) as target:
            relay = RelayServer(("127.0.0.1", 0), SoulSync(target, "sk_server"), self.key)
            with running(relay) as base:
                self.assertEqual(self.call(base, "/v1/status"), (200, {"version": 1, "scope": "track-request"}))
                status = self.call(base, "/v1/requests", {"artist": "Artist", "title": "Song", "duration_ms": 180000},
                                   extra={"X-Profile-Id": "2"})
                self.assertEqual(status, (202, {"receipt": "stored-id"}))
        self.assertEqual([call[:2] for call in upstream.calls], [(path, "Bearer sk_server") for path in
                         ("/api/v1/api-keys", "/api/v1/wishlist", "/api/v1/wishlist/process")])
        self.assertEqual(upstream.calls[1][2]["track_data"]["artists"], [{"name": "Artist"}])
        self.assertEqual(upstream.calls[1][2]["track_data"]["duration_ms"], 180000)
        self.assertEqual(upstream.calls[2][2], {})

    def test_blocks_unapproved_routes_keys_inputs_and_excess_submissions(self):
        upstream = ThreadingHTTPServer(("127.0.0.1", 0), SoulSyncStub)
        upstream.calls = []
        with running(upstream) as target:
            relay = RelayServer(("127.0.0.1", 0), SoulSync(target, "sk_server"), self.key)
            with running(relay) as base:
                self.assertEqual(self.call(base, "/v1/status", key="sk_server")[0], 401)
                self.assertEqual(self.call(base, "/api/v1/api-keys")[0], 404)
                self.assertEqual(self.call(base, "/v1/requests?profile_id=2", {})[0], 404)
                self.assertEqual(self.call(base, "/v1/requests", {"artist": "A", "title": "B", "profile_id": 2})[0], 400)
                self.assertEqual(self.call(base, "/v1/requests", {"artist": "A", "title": "B", "duration_ms": True})[0], 400)
                self.assertEqual(upstream.calls, [])
                for _ in range(10):
                    self.assertEqual(self.call(base, "/v1/requests", {"artist": "A", "title": "B"})[0], 202)
                self.assertEqual(self.call(base, "/v1/requests", {"artist": "A", "title": "B"})[0], 429)
        self.assertEqual(len(upstream.calls), 20)

    def test_status_rejects_a_read_only_upstream_key(self):
        upstream = ThreadingHTTPServer(("127.0.0.1", 0), SoulSyncStub)
        upstream.calls = []
        with running(upstream) as target:
            relay = RelayServer(("127.0.0.1", 0), SoulSync(target, "sk_read_only"), self.key)
            with running(relay) as base:
                self.assertEqual(self.call(base, "/v1/status")[0], 503)
