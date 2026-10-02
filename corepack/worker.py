"""The COREPACK worker: runs on the phone (and optionally the PC), executes task chunks."""
from __future__ import annotations

import json
import os
import platform
import socket
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from . import __version__, config, netinfo, tasks, ui
from .pool import Pool

BLOCK = b"\0" * 1048576


def _ram_mb() -> int:
    try:
        with open("/proc/meminfo") as f:
            return int(f.readline().split()[1]) // 1024
    except (OSError, ValueError, IndexError):
        return 0


class Stats:
    def __init__(self):
        self.lock = threading.Lock()
        self.start = time.time()
        self.jobs = 0
        self.busy = 0
        self.errors = 0
        self.clients: set[str] = set()


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, addr, pool: Pool, token: str = ""):
        super().__init__(addr, Handler)
        self.pool, self.token, self.stats = pool, token, Stats()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "corepack"

    def log_message(self, *a):  # quiet; the dashboard shows activity
        pass

    def _json(self, code: int, obj) -> None:
        data = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _auth(self) -> bool:
        tok = self.server.token
        if tok and self.headers.get("X-Corepack-Token") != tok:
            self.close_connection = True
            self._json(401, {"ok": False, "error": "bad token"})
            return False
        self.server.stats.clients.add(self.client_address[0])
        return True

    def do_GET(self):
        if not self._auth():
            return
        u = urlparse(self.path)
        if u.path == "/ping":
            return self._json(200, {"pong": True})
        if u.path == "/info":
            return self._json(200, {
                "service": "corepack", "version": __version__,
                "hostname": socket.gethostname(), "platform": platform.platform(),
                "role": config.role(),
                "cores": self.server.pool.workers, "pool": self.server.pool.kind,
                "ram_mb": _ram_mb(), "tasks": list(tasks.TASKS)})
        if u.path == "/download":
            mb = max(1, min(2048, int(parse_qs(u.query).get("mb", ["10"])[0])))
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(mb * 1048576))
            self.end_headers()
            for _ in range(mb):
                self.wfile.write(BLOCK)
            return
        self._json(404, {"ok": False, "error": "not found"})

    def do_POST(self):
        if not self._auth():
            return
        n = int(self.headers.get("Content-Length", 0))
        if self.path == "/upload":
            left = n
            while left > 0:
                chunk = self.rfile.read(min(left, 1048576))
                if not chunk:
                    break
                left -= len(chunk)
            return self._json(200, {"ok": True, "bytes": n})
        if self.path == "/run":
            st = self.server.stats
            try:
                req = json.loads(self.rfile.read(n))
                with st.lock:
                    st.busy += 1
                try:
                    result = self.server.pool.run(req["task"], req["args"])
                finally:
                    with st.lock:
                        st.busy -= 1
                        st.jobs += 1
                return self._json(200, {"ok": True, "result": result})
            except Exception as e:  # report, never crash the worker
                with st.lock:
                    st.errors += 1
                return self._json(200, {"ok": False, "error": f"{type(e).__name__}: {e}"})
        self._json(404, {"ok": False, "error": "not found"})


def make_server(host: str, port: int, pool: Pool, token: str = "") -> Server:
    return Server((host, port), pool, token)


def _dashboard(srv: Server, stop: threading.Event) -> None:
    while not stop.wait(1.0):
        st = srv.stats
        up = int(time.time() - st.start)
        line = (f"  {ui.orange('●')} up {up // 60:02d}:{up % 60:02d}   "
                f"busy {st.busy}/{srv.pool.workers}   done {st.jobs}   "
                f"errors {st.errors}   clients {len(st.clients)}")
        sys.stdout.write("\r" + line + " " * 6)
        sys.stdout.flush()


def run(args) -> int:
    cfg = config.load()
    port = args.port or cfg["port"]
    token = args.token if args.token is not None else cfg["token"]
    cores = args.cores or os.cpu_count() or 1

    ui.header(f"Worker  ({config.role()})")
    ui.info(f"Starting {cores}-core pool (first start takes a few seconds)...")
    pool = Pool(cores)
    try:
        srv = make_server(args.bind, port, pool, token)
    except OSError as e:
        ui.fail(f"Cannot listen on port {port}: {e}")
        ui.hint("Is another worker already running? Try --port 8766")
        pool.close()
        return 1

    ui.ok(f"Ready: {cores} cores, {pool.kind} pool, listening on port {port}")
    if token:
        ui.ok("Token required for clients")
    ui.info("Reachable at:")
    for i in netinfo.interfaces():
        tag = ui.green("  <- USB link") if i["usb"] else ""
        print(f"      {ui.bold(i['ip'])}:{port}  {ui.dim(i['name'])}{tag}")
    print()
    ui.info("On the other device run:  " + ui.bold("corepack bench"))
    ui.hint("Ctrl+C to stop. Keep Termux awake: pull down the notification and tap 'Acquire wakelock'.")
    print()

    stop = threading.Event()
    if sys.stdout.isatty():
        threading.Thread(target=_dashboard, args=(srv, stop), daemon=True).start()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
        srv.server_close()
        pool.close()
        print("\n")
        ui.ok("Worker stopped")
    return 0
