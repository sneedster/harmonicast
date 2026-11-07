"""Narrow, authenticated entry point for shared Harmonicast track requests."""

import hashlib
import hmac
import json
import os
import re
import threading
import time
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener


REQUEST_KEY = re.compile(r"hcr_[0-9a-f]{64}\Z")
MAX_BODY = 8192


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class SoulSync:
    def __init__(self, base_url, key):
        parsed = urlsplit(base_url)
        if (parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username
                or parsed.password or parsed.query or parsed.fragment or parsed.path not in {"", "/"}):
            raise ValueError("SOULSYNC_URL must be the trusted server origin")
        if not key or key.startswith("hcr_") or any(char.isspace() for char in key):
            raise ValueError("Invalid SoulSync server key")
        self.base_url = base_url.rstrip("/")
        self.key = key
        self.http = build_opener(ProxyHandler({}), NoRedirect())

    def call(self, path, body=None):
        data = None if body is None else json.dumps(body).encode()
        headers = {"Authorization": f"Bearer {self.key}", "Accept": "application/json"}
        if data is not None:
            headers["Content-Type"] = "application/json"
        request = Request(self.base_url + path, data=data, headers=headers)
        try:
            with self.http.open(request, timeout=6) as reply:
                raw = reply.read(131073)
        except HTTPError as error:
            error.close()
            raise
        if len(raw) > 131072:
            raise ValueError("SoulSync response too large")
        envelope = json.loads(raw)
        if not isinstance(envelope, dict) or envelope.get("success") is not True:
            raise ValueError("Invalid SoulSync response")
        return envelope.get("data")

    def request(self, artist, title, duration):
        identity = hashlib.sha256(f"{artist}\0{title}".encode()).hexdigest()
        track = {"id": f"harmonicast:{identity}", "name": title, "artists": [{"name": artist}]}
        if duration:
            track["duration_ms"] = duration
        result = self.call("/api/v1/wishlist", {"track_data": track, "source_type": "manual",
                                                "failure_reason": "Requested by Harmonicast"})
        receipt = result["track"]["track_id"]
        if not isinstance(receipt, str) or not receipt.strip():
            raise ValueError("SoulSync did not return a receipt")
        try:
            self.call("/api/v1/wishlist/process", {})
        except (URLError, OSError, ValueError):
            # The wishlist already accepted the track. Scheduled processing can retry it.
            pass
        return receipt


def track_input(raw):
    body = json.loads(raw)
    if not isinstance(body, dict) or not {"artist", "title"} <= body.keys() <= {"artist", "title", "duration_ms"}:
        raise ValueError("Supply artist, title and optional duration_ms")
    values = []
    for field in ("artist", "title"):
        value = body[field]
        if (not isinstance(value, str) or not value.strip() or len(value) > 500
                or any(ord(char) < 32 or 0xD800 <= ord(char) <= 0xDFFF for char in value)):
            raise ValueError(f"Invalid {field}")
        values.append(value.strip())
    duration = body.get("duration_ms", 0)
    if type(duration) is not int or not 0 <= duration <= 2_147_483_647:
        raise ValueError("Invalid duration_ms")
    return (*values, duration)


class RelayServer(ThreadingHTTPServer):
    request_queue_size = 16

    def __init__(self, address, soul_sync, request_key):
        if not REQUEST_KEY.fullmatch(request_key):
            raise ValueError("Invalid Harmonicast request key")
        self.soul_sync = soul_sync
        self.request_auth = f"Bearer {request_key}".encode("ascii")
        self.workers = threading.BoundedSemaphore(16)
        self.limit_lock = threading.Lock()
        self.submissions = deque()
        super().__init__(address, RelayHandler)

    def process_request(self, request, address):
        if not self.workers.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            request.settimeout(5)
            super().process_request(request, address)
        except BaseException:
            self.workers.release()
            raise

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.workers.release()

    def permit_submission(self):
        now = time.monotonic()
        with self.limit_lock:
            while self.submissions and self.submissions[0] <= now - 86400:
                self.submissions.popleft()
            if len(self.submissions) >= 100 or (len(self.submissions) >= 10 and self.submissions[-10] > now - 60):
                return False
            self.submissions.append(now)
            return True


class RelayHandler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        # Don't log authorization, request paths, or requested music.
        return

    def reply(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def authorized(self):
        values = self.headers.get_all("Authorization", [])
        if len(values) == 1 and hmac.compare_digest(values[0].encode("latin-1"), self.server.request_auth):
            return True
        self.reply(401, {"error": "Request key required"})
        return False

    def do_GET(self):
        if self.path != "/v1/status":
            self.reply(404, {"error": "Unknown route"})
        elif self.authorized():
            try:
                # A read-only SoulSync key can reach system status but cannot submit.
                data = self.server.soul_sync.call("/api/v1/api-keys")
                if not isinstance(data, dict) or not isinstance(data.get("keys"), list):
                    raise ValueError("Invalid SoulSync API permissions response")
            except (URLError, OSError, ValueError):
                self.reply(503, {"error": "SoulSync is unavailable"})
                return
            self.reply(200, {"version": 1, "scope": "track-request"})

    def do_POST(self):
        if self.path != "/v1/requests":
            self.reply(404, {"error": "Unknown route"})
            return
        if not self.authorized():
            return
        lengths = self.headers.get_all("Content-Length", [])
        if (len(lengths) != 1 or not re.fullmatch(r"[0-9]{1,4}", lengths[0]) or self.headers.get("Transfer-Encoding")
                or not 0 < int(lengths[0]) <= MAX_BODY or self.headers.get_content_type() != "application/json"):
            self.reply(400, {"error": "Expected a small JSON body"})
            return
        try:
            artist, title, duration = track_input(self.rfile.read(int(lengths[0])))
        except (OSError, ValueError, RecursionError):
            self.reply(400, {"error": "Invalid track request"})
            return
        if not self.server.permit_submission():
            self.reply(429, {"error": "Request limit reached"})
            return
        try:
            receipt = self.server.soul_sync.request(artist, title, duration)
        except HTTPError as error:
            status = 400 if error.code in {400, 409, 422} else 502
            self.reply(status, {"error": "SoulSync did not confirm the request"})
        except (URLError, OSError, KeyError, TypeError, ValueError):
            self.reply(502, {"error": "SoulSync did not confirm the request"})
        else:
            self.reply(202, {"receipt": receipt})


def main():
    upstream_key = Path(os.environ["SOULSYNC_KEY_FILE"]).read_text().strip()
    request_key = Path(os.environ["REQUEST_KEY_FILE"]).read_text().strip()
    upstream = SoulSync(os.environ["SOULSYNC_URL"], upstream_key)
    with RelayServer((os.environ.get("BIND_HOST", "127.0.0.1"), int(os.environ.get("PORT", "8097"))), upstream, request_key) as server:
        server.serve_forever()


if __name__ == "__main__":
    main()
