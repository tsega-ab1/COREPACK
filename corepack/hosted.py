"""Hosted mode: invite codes for testers, per-tester limits and bring-your-own-key. Stdlib only.

When the gateway runs on the internet, one shared token is not enough. This module adds:

- Invite codes. Each tester gets their own code. It can be revoked alone, and it has its own limits.
  Only a SHA-256 hash of a code is ever stored in `gateway.json`, so a leaked config does not leak codes.
  (The environment variable COREPACK_INVITES may also carry plain codes: hosting dashboards keep those secret.)
- Limits per tester: requests per minute, and messages per day on the owner's shared key.
- Bring-your-own-key: the phone may send its own API key for one request. The key is used for that request only,
  never stored, never logged, and scrubbed from any error text. The phone can only pick a provider by name
  (a fixed list), never an address, so a tester cannot point the gateway at an internal host.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import threading
import time
from dataclasses import dataclass

from .providers import PRESETS
from .ratelimit import RateLimiter

BYO_PROVIDERS = ("gemini", "xai")
MODEL_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]{0,79}$")
KEY_RE = re.compile(r"^[\x21-\x7e]{8,300}$")          # printable ASCII, no spaces

H_KEY = "X-Corepack-Key"
H_PROVIDER = "X-Corepack-Provider"
H_MODEL = "X-Corepack-Model"


def digest(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


@dataclass(frozen=True)
class Tenant:
    name: str
    rpm: int = 0
    daily: int = 0
    owner: bool = False


OWNER = Tenant("owner", owner=True)


def _int(v, default: int) -> int:
    try:
        return max(0, int(v))
    except (TypeError, ValueError):
        return default


class Tenants:
    """The invite codes of a hosted gateway. With none configured the gateway behaves as before (one token)."""

    def __init__(self, cfg: dict):
        self.default_rpm = _int(cfg.get("invite_rpm"), 6)
        self.default_daily = _int(cfg.get("invite_daily"), 30)
        self.table: dict[str, dict] = {}
        for source in (cfg.get("invites"), _env_invites()):
            if not isinstance(source, dict):
                continue
            for name, spec in source.items():
                if isinstance(spec, str) and spec:                      # a plain code (from the environment)
                    spec = {"sha256": digest(spec)}
                if isinstance(spec, dict) and isinstance(spec.get("sha256"), str):
                    self.table[str(name)] = {
                        "sha256": spec["sha256"].lower(),
                        "rpm": _int(spec.get("rpm"), self.default_rpm),
                        "daily": _int(spec.get("daily"), self.default_daily),
                    }
        self._lock = threading.Lock()
        self._limiters: dict[str, RateLimiter] = {}
        self._used: dict[tuple[str, str], int] = {}

    @property
    def enabled(self) -> bool:
        return bool(self.table)

    def lookup(self, token: str) -> Tenant | None:
        if not token:
            return None
        want = digest(token)
        found = None
        for name, inv in self.table.items():                          # no early exit: same work for every code
            if hmac.compare_digest(want, inv["sha256"]):
                found = Tenant(name, inv["rpm"], inv["daily"])
        return found

    def take_rate(self, t: Tenant) -> bool:
        """One request against the tester's per-minute limit. False means too fast (0 = unlimited)."""
        if t.rpm <= 0:
            return True
        with self._lock:
            lim = self._limiters.get(t.name)
            if lim is None or lim.rpm != t.rpm:
                lim = self._limiters[t.name] = RateLimiter(t.rpm)
        return lim.acquire(0)

    def take_daily(self, t: Tenant) -> bool:
        """One message on the owner's shared key. False once the tester's daily allowance is used (0 = unlimited)."""
        if t.daily <= 0:
            return True
        day = time.strftime("%Y-%m-%d", time.gmtime())
        with self._lock:
            for k in [k for k in self._used if k[1] != day]:
                del self._used[k]
            n = self._used.get((t.name, day), 0)
            if n >= t.daily:
                return False
            self._used[(t.name, day)] = n + 1
            return True


def _env_invites() -> dict:
    raw = os.environ.get("COREPACK_INVITES", "").strip()
    if not raw:
        return {}
    try:
        data = json.loads(raw)
    except ValueError:
        return {}
    return data if isinstance(data, dict) else {}


def byo_from_headers(headers, cfg: dict):
    """The tester's own engine for this request, from the request headers.

    Returns (provider_dict, None), (None, None) when no key was sent, or (None, message) when it is not acceptable.
    """
    key = (headers.get(H_KEY) or "").strip()
    if not key:
        return None, None
    if not KEY_RE.match(key):
        return None, "That API key doesn't look right (it should be one line of letters and digits, with no spaces)."
    name = (headers.get(H_PROVIDER) or "").strip().lower()
    if not name:
        name = cfg.get("provider") if cfg.get("provider") in BYO_PROVIDERS else "gemini"
    if name not in BYO_PROVIDERS:
        return None, "Your own key can be used with: " + ", ".join(BYO_PROVIDERS)
    urls = cfg.get("preset_urls") if isinstance(cfg.get("preset_urls"), dict) else {}
    base = urls.get(name) or PRESETS[name]["base_url"]
    model = (headers.get(H_MODEL) or "").strip()
    if not model:
        models = cfg.get("byo_models") if isinstance(cfg.get("byo_models"), dict) else {}
        model = models.get(name) or (cfg.get("model") if cfg.get("provider") == name else "")
    if name == "gemini":
        model = model.removeprefix("models/")
    if not model:
        return None, "Say which model to use with your key (for example in the app's Model box)."
    if not MODEL_RE.match(model):
        return None, "That model name has characters that aren't allowed."
    return {"label": "your key (" + name + ")", "base_url": base, "api_key": key, "model": model,
            "key": "byo", "local": False}, None
