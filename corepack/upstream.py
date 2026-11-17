"""Talking to the real engine: the main engine plus backups, with failover and rate-limit handling."""
from __future__ import annotations

import json
import sys
import threading
import time
import urllib.error
import urllib.request

from .gateway_config import ALLOWED
from .hosted import Tenants
from .providers import is_local_engine
from .ratelimit import RateLimiter


# Seconds to leave an engine alone after it fails with a server error (like Gemini's 503 "high demand") or cannot be
# reached. With a backup configured, later requests go to the backup first instead of each paying for a failed try.
SERVER_ERROR_COOLDOWN = 20.0


def _retry_after(headers) -> float:
    try:
        return max(0.0, min(60.0, float(headers.get("Retry-After", "5"))))
    except (TypeError, ValueError):
        return 5.0


def _short(text: str) -> str:
    try:
        data = json.loads(text)
        if isinstance(data, list) and data:
            data = data[0]
        msg = data.get("error", {}).get("message")
        if msg:
            text = msg
    except (ValueError, AttributeError):
        pass
    text = " ".join(text.split())
    return text[:300]


class Gateway:
    def __init__(self, cfg: dict, verbose: bool = False):
        self.cfg = cfg
        self.token = cfg["token"]
        self.limiter = RateLimiter(int(cfg["rpm"]))
        self.verbose = verbose
        self.cooling: dict[int, float] = {}     # provider index -> time until which to avoid it
        self.last_provider = ""
        self.tenants = Tenants(cfg)
        slots = int(cfg.get("max_concurrent") or 0)
        self.slots = threading.BoundedSemaphore(slots) if slots > 0 else None

    def log(self, msg: str) -> None:
        if self.verbose:
            print(f"  {time.strftime('%H:%M:%S')}  {msg}", file=sys.stderr, flush=True)

    def redact(self, s: str) -> str:
        keys = [self.cfg.get("api_key")] + [f.get("api_key") for f in self.cfg.get("fallbacks") or [] if isinstance(f, dict)]
        keys += [e.get("api_key") for e in self.engines().values()]
        for key in keys:
            if key:
                s = s.replace(key, "***")
        return s

    def engines(self) -> dict[str, dict]:
        """The named engines a request may ask for (the `model` field of the request names one)."""
        raw = self.cfg.get("engines")
        if not isinstance(raw, dict):
            return {}
        return {str(n): e for n, e in raw.items() if isinstance(e, dict) and e.get("base_url") and e.get("model")}

    def engine_list(self) -> list[dict]:
        """What the phone may learn about them: a name and a description, never keys or addresses."""
        return [{"name": n, "description": " ".join(str(e.get("description") or "").split())[:300]}
                for n, e in self.engines().items()]

    def engine_for(self, body: dict) -> str | None:
        name = body.get("model") if isinstance(body, dict) else None
        return name if isinstance(name, str) and name in self.engines() else None

    def providers(self, engine: str | None = None) -> list[dict]:
        """The engines to try, in order: the named one if a request asked for one, then the main engine, then each
        backup. Each has a `key` that stays the same however the list is built, so a cooldown follows the engine."""
        c = self.cfg
        out = [{"label": c.get("provider") or "main", "base_url": c["base_url"], "api_key": c.get("api_key", ""), "model": c["model"], "key": 0,
                "local": is_local_engine(c.get("provider") or "", c["base_url"], c.get("local"))}]
        for f in c.get("fallbacks") or []:
            if isinstance(f, dict) and f.get("base_url") and f.get("model"):
                out.append({"label": f.get("provider") or "backup", "base_url": f["base_url"],
                            "api_key": f.get("api_key", ""), "model": f["model"], "key": len(out),
                            "local": is_local_engine(f.get("provider") or "", f["base_url"], f.get("local"))})
        named = self.engines().get(engine) if engine else None
        if named:
            out.insert(0, {"label": engine, "base_url": named["base_url"], "api_key": named.get("api_key", ""),
                           "model": named["model"], "key": "engine:" + engine,
                           "local": is_local_engine(named.get("provider") or "", named["base_url"], named.get("local"))})
        return out

    def build_payload(self, body: dict) -> dict:
        payload = {k: v for k, v in body.items() if k in ALLOWED}
        payload["model"] = self.cfg["model"]
        payload["stream"] = bool(body.get("stream"))
        cap = int(self.cfg["max_tokens"])
        mt = payload.get("max_tokens")
        if cap:
            payload["max_tokens"] = min(mt, cap) if isinstance(mt, int) and mt > 0 else cap
        sp = self.cfg.get("system_prompt")
        msgs = payload["messages"]
        if sp and not any(isinstance(m, dict) and m.get("role") == "system" for m in msgs):
            payload["messages"] = [{"role": "system", "content": sp}] + msgs
        return payload

    def open_upstream(self, payload: dict, prov: dict | None = None):
        prov = prov or self.providers()[0]
        payload = dict(payload)
        payload["model"] = prov["model"]
        headers = {"Content-Type": "application/json",
                   "Accept": "text/event-stream" if payload.get("stream") else "application/json"}
        if prov.get("api_key"):
            headers["Authorization"] = "Bearer " + prov["api_key"]
        url = prov["base_url"].rstrip("/") + "/chat/completions"
        req = urllib.request.Request(url, data=json.dumps(payload).encode(), headers=headers, method="POST")
        return urllib.request.urlopen(req, timeout=float(self.cfg["timeout"]))

    def _call_one(self, payload: dict, prov: dict, on_throttle=None, can_wait: bool = True):
        """One engine. Returns (response, None) or (None, (status, message, type, retry_after_or_None))."""
        for attempt in (0, 1):
            try:
                return self.open_upstream(payload, prov), None
            except urllib.error.HTTPError as e:
                text = e.read()[:4000].decode("utf-8", "replace")
                if e.code == 429:
                    ra = _retry_after(e.headers)
                    if can_wait and attempt == 0:
                        if on_throttle:
                            on_throttle(ra)
                        if ra <= float(self.cfg["max_wait"]):
                            time.sleep(ra)
                            if self.limiter.acquire(float(self.cfg["max_wait"])):
                                continue
                    return None, (429, "The engine is rate-limiting this key: " + _short(text), "rate_limited", ra)
                if e.code in (401, 403):
                    return None, (502, "The engine rejected the API key", "upstream_auth", None)
                status = 400 if e.code == 400 else 502
                cooldown = SERVER_ERROR_COOLDOWN if e.code >= 500 else None
                return None, (status, f"Engine error {e.code}: {_short(text)}", "upstream_error", cooldown)
            except (urllib.error.URLError, OSError) as e:
                return None, (502, f"Can't reach the engine: {getattr(e, 'reason', e)}", "upstream_unreachable",
                              SERVER_ERROR_COOLDOWN)
        return None, (502, "Engine unavailable", "upstream_error", None)

    def call_upstream(self, payload: dict, on_throttle=None, on_fallback=None, engine: str | None = None,
                      private: bool = False, byo: dict | None = None):
        """Tries the engine a request asked for by name (if any), then the main engine, then each backup.
        A private request (it carries data from a private tool server) is only ever sent to an engine on this PC.
        Returns (response, None) or (None, (status, message, type))."""
        if byo:         # the tester's own key: only that engine, never the owner's key or backups, no waiting
            if private:
                return None, (503, "This conversation includes private data from a tool server, so it can't be sent "
                                   "to an online engine.", "needs_local_engine")
            resp, err = self._call_one(payload, byo, on_throttle, can_wait=False)
            return (resp, None) if err is None else (None, err[:3])
        provs = self.providers(engine)
        if private:
            provs = [p for p in provs if p["local"]]
            if not provs:
                return None, (503, "This conversation includes private data from a tool server marked private, so only an "
                                   "engine running on this PC may answer, and none is set up. Add one with: "
                                   "corepack gateway engine add local --provider local", "needs_local_engine")
        now = time.monotonic()
        order = [p for p in provs if self.cooling.get(p["key"], 0) <= now]
        order += [p for p in provs if p not in order]                      # last resort: engines that are cooling down
        first_key = provs[0]["key"]
        first_err = None
        for pos, prov in enumerate(order):
            i = prov["key"]
            if i != first_key and on_fallback:
                on_fallback(prov["label"])
            more = pos + 1 < len(order)
            # Sitting out a Retry-After only makes sense with a single engine; with backups, fail over or fail fast.
            resp, err = self._call_one(payload, prov, on_throttle, can_wait=len(order) == 1)
            if err is None:
                self.last_provider = prov["label"]
                self.cooling.pop(i, None)      # it answered, so it is first in line again
                return resp, None
            if err[3] is not None:
                self.cooling[i] = time.monotonic() + min(max(err[3], 5.0), 120.0)
            self.log(f"{prov['label']} failed: {err[1]}" + (" -> trying the next engine" if more else ""))
            first_err = first_err or err
        return None, first_err[:3]
