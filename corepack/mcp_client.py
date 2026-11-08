"""A small MCP client for servers that run as a child process (the "stdio" transport). Stdlib only.

It speaks just enough of the Model Context Protocol to list a server's tools and call them: the initialize
handshake, tools/list (with paging) and tools/call. Messages are one JSON object per line.
"""
from __future__ import annotations

import json
import subprocess
import threading
from collections import deque

# The newest version first. A server that answers with another version is only accepted if it is on this list.
KNOWN_VERSIONS = ("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")
CLIENT_NAME = "corepack-gateway"
CLIENT_VERSION = "0.1"


class McpError(Exception):
    """The server failed, timed out, or answered with an error."""

    def __init__(self, message: str, code: int | None = None):
        super().__init__(message)
        self.code = code


class StdioMcpClient:
    def __init__(self, command: list[str], env: dict | None = None, timeout: float = 30.0, name: str = "mcp"):
        self.command = command
        self.env = env
        self.timeout = timeout
        self.name = name
        self.server_info: dict = {}
        self.protocol_version = ""
        self.stale = False                      # the server said its tool list changed
        self._proc: subprocess.Popen | None = None
        self._threads: list[threading.Thread] = []
        self._write_lock = threading.Lock()
        self._state_lock = threading.Lock()
        self._next_id = 1
        self._pending: dict[int, dict] = {}     # request id -> {"event": Event, "msg": dict | None}
        self._stderr: deque[str] = deque(maxlen=15)
        self._exited = False

    # ---------------------------------------------------------------- life cycle

    def alive(self) -> bool:
        return self._proc is not None and self._proc.poll() is None and not self._exited

    def start(self, startup_timeout: float = 60.0) -> None:
        try:
            self._proc = subprocess.Popen(
                self.command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                env=self.env, text=True, encoding="utf-8", errors="replace", bufsize=1)
        except OSError as e:
            raise McpError(f"Could not start {self.command[0]!r}: {e}") from e
        for target in (self._read_stdout, self._read_stderr):
            t = threading.Thread(target=target, daemon=True)
            t.start()
            self._threads.append(t)
        try:
            result = self.request("initialize", {
                "protocolVersion": KNOWN_VERSIONS[0],
                "capabilities": {},
                "clientInfo": {"name": CLIENT_NAME, "version": CLIENT_VERSION},
            }, timeout=startup_timeout)
            version = str(result.get("protocolVersion", ""))
            if version not in KNOWN_VERSIONS:
                raise McpError(f"The server speaks MCP version {version or '?'}, which this gateway does not support")
            self.protocol_version = version
            self.server_info = result.get("serverInfo") or {}
            self._send({"jsonrpc": "2.0", "method": "notifications/initialized"})
        except McpError:
            self.close()
            raise

    def close(self) -> None:
        proc = self._proc
        if proc is None:
            return
        try:
            if proc.stdin:
                proc.stdin.close()
        except OSError:
            pass
        try:                                    # closing its input is the polite way to say goodbye
            proc.wait(timeout=2.0)
        except subprocess.TimeoutExpired:
            proc.terminate()
            try:
                proc.wait(timeout=2.0)
            except subprocess.TimeoutExpired:
                proc.kill()
                try:
                    proc.wait(timeout=2.0)
                except subprocess.TimeoutExpired:
                    pass
        self._exited = True
        for t in self._threads:                 # they end by themselves once the pipes close
            t.join(timeout=1.0)
        for pipe in (proc.stdout, proc.stderr):
            try:
                if pipe:
                    pipe.close()
            except (OSError, ValueError):
                pass
        self._fail_all("The server was closed")

    # ---------------------------------------------------------------- requests

    def list_tools(self) -> list[dict]:
        tools: list[dict] = []
        cursor = None
        for _ in range(50):
            result = self.request("tools/list", {"cursor": cursor} if cursor else {})
            tools.extend(t for t in result.get("tools", []) if isinstance(t, dict) and t.get("name"))
            cursor = result.get("nextCursor")
            if not cursor:
                break
        self.stale = False
        return tools

    def call_tool(self, name: str, arguments: dict, timeout: float | None = None) -> dict:
        return self.request("tools/call", {"name": name, "arguments": arguments}, timeout=timeout)

    def request(self, method: str, params: dict | None = None, timeout: float | None = None) -> dict:
        timeout = self.timeout if timeout is None else timeout
        slot = {"event": threading.Event(), "msg": None}
        with self._state_lock:
            if self._exited:
                raise McpError(self._exit_message())
            rid = self._next_id
            self._next_id += 1
            self._pending[rid] = slot
        try:
            self._send({"jsonrpc": "2.0", "id": rid, "method": method, "params": params or {}})
        except (OSError, ValueError) as e:
            with self._state_lock:
                self._pending.pop(rid, None)
            raise McpError(self._exit_message() if not self.alive() else f"Could not talk to the server: {e}") from e
        if not slot["event"].wait(timeout):
            with self._state_lock:
                self._pending.pop(rid, None)
            raise McpError(f"{method} timed out after {timeout:g} s")
        msg = slot["msg"] or {}
        if "error" in msg:
            err = msg["error"] if isinstance(msg["error"], dict) else {}
            raise McpError(str(err.get("message") or "The server reported an error"), err.get("code"))
        if msg.get("_exited"):
            raise McpError(self._exit_message())
        result = msg.get("result")
        return result if isinstance(result, dict) else {}

    # ---------------------------------------------------------------- plumbing

    def _send(self, msg: dict) -> None:
        proc = self._proc
        if proc is None or proc.stdin is None:
            raise OSError("not started")
        line = json.dumps(msg, separators=(",", ":"), ensure_ascii=False) + "\n"
        with self._write_lock:
            proc.stdin.write(line)
            proc.stdin.flush()

    def _read_stdout(self) -> None:
        proc = self._proc
        try:
            for line in proc.stdout:
                line = line.strip()
                if not line:
                    continue
                try:
                    msg = json.loads(line)
                except ValueError:
                    continue                    # a stray log line on stdout: ignore it
                if isinstance(msg, dict):
                    self._handle(msg)
        except (OSError, ValueError):
            pass
        finally:
            self._exited = True
            self._fail_all(self._exit_message())

    def _read_stderr(self) -> None:
        try:
            for line in self._proc.stderr:
                line = line.rstrip()
                if line:
                    self._stderr.append(line[:300])
        except (OSError, ValueError):
            pass

    def _handle(self, msg: dict) -> None:
        method = msg.get("method")
        if method is None:                      # a response to one of our requests
            rid = msg.get("id")
            with self._state_lock:
                slot = self._pending.pop(rid, None) if isinstance(rid, int) else None
            if slot:
                slot["msg"] = msg
                slot["event"].set()
            return
        if "id" in msg:                         # the server is asking us something
            try:
                if method == "ping":
                    self._send({"jsonrpc": "2.0", "id": msg["id"], "result": {}})
                else:
                    self._send({"jsonrpc": "2.0", "id": msg["id"],
                                "error": {"code": -32601, "message": "This client does not support " + str(method)}})
            except (OSError, ValueError):
                pass
            return
        if method == "notifications/tools/list_changed":
            self.stale = True

    def _fail_all(self, why: str) -> None:
        with self._state_lock:
            pending, self._pending = self._pending, {}
        for slot in pending.values():
            slot["msg"] = {"_exited": True, "_why": why}
            slot["event"].set()

    def _exit_message(self) -> str:
        tail = " | ".join(list(self._stderr)[-3:])
        return "The server exited" + (f": {tail}" if tail else "")
