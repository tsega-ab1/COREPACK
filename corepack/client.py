"""HTTP client for talking to a COREPACK worker on the other end of the USB cable."""
from __future__ import annotations

import http.client
import json
import time


class PeerError(Exception):
    pass


class Peer:
    def __init__(self, host: str, port: int = 8765, token: str = "", timeout: float = 600):
        self.host, self.port, self.token, self.timeout = host, int(port), token, timeout
        self.meta: dict = {}

    @classmethod
    def parse(cls, spec: str, default_port: int, token: str = "") -> "Peer":
        host, _, port = spec.partition(":")
        return cls(host, int(port) if port else default_port, token)

    @property
    def label(self) -> str:
        return f"{self.host}:{self.port}"

    @property
    def name(self) -> str:
        h = self.meta.get("hostname", "")
        if h in ("", "localhost"):
            return f"{self.meta.get('role', 'worker')}@{self.host}"
        return h

    @property
    def slots(self) -> int:
        return int(self.meta.get("cores", 1))

    def _call(self, method, path, body=None, headers=None, timeout=None) -> bytes:
        conn = http.client.HTTPConnection(self.host, self.port, timeout=timeout or self.timeout)
        h = dict(headers or {})
        if self.token:
            h["X-Corepack-Token"] = self.token
        try:
            conn.request(method, path, body=body, headers=h)
            r = conn.getresponse()
            data = r.read()
            if r.status != 200:
                raise PeerError(f"{r.status} {data[:200]!r}")
            return data
        except (OSError, http.client.HTTPException) as e:
            raise PeerError(str(e)) from e
        finally:
            conn.close()

    def info(self, timeout: float = 3) -> dict:
        d = json.loads(self._call("GET", "/info", timeout=timeout))
        if d.get("service") != "corepack":
            raise PeerError("not a corepack worker")
        self.meta = d
        return d

    def ping(self) -> float:
        t = time.perf_counter()
        self._call("GET", "/ping", timeout=5)
        return (time.perf_counter() - t) * 1000

    def run(self, task: str, args: dict):
        body = json.dumps({"task": task, "args": args}).encode()
        resp = json.loads(self._call("POST", "/run", body=body,
                                     headers={"Content-Type": "application/json"}))
        if not resp.get("ok"):
            raise PeerError(resp.get("error", "remote error"))
        return resp["result"]

    def upload(self, mb: int) -> float:
        total, block = mb * 1048576, b"\0" * 1048576

        def gen():
            for _ in range(mb):
                yield block

        t = time.perf_counter()
        self._call("POST", "/upload", body=gen(), headers={"Content-Length": str(total)})
        return time.perf_counter() - t

    def download(self, mb: int) -> float:
        t = time.perf_counter()
        self._call("GET", f"/download?mb={mb}")
        return time.perf_counter() - t
