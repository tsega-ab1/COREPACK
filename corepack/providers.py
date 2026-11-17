"""The engines the gateway can talk to (Gemini, Grok, a local server, any OpenAI-compatible URL) and how to list their models."""
from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request


PRESETS = {
    "gemini": {"label": "Google Gemini", "base_url": "https://generativelanguage.googleapis.com/v1beta/openai/",
               "needs_key": True},
    "xai": {"label": "xAI Grok", "base_url": "https://api.x.ai/v1", "needs_key": True},
    "local": {"label": "Local server (llama.cpp / Ollama)", "base_url": "http://127.0.0.1:8080/v1",
              "needs_key": False},
    "custom": {"label": "Other OpenAI-compatible", "base_url": "", "needs_key": True},
}


def is_local_engine(provider: str, base_url: str, flag=None) -> bool:
    """True if the engine runs on this PC, so private data may be sent to it. An explicit `local` setting
    (true or false) wins; otherwise it is local if it is the "local" preset or its address is this machine."""
    if isinstance(flag, bool):
        return flag
    if provider == "local":
        return True
    host = urllib.parse.urlparse(base_url or "").hostname or ""
    return host == "localhost" or host == "::1" or host.startswith("127.")


def list_models(base: str, key: str) -> list[str]:
    req = urllib.request.Request(base.rstrip("/") + "/models",
                                 headers={"Authorization": "Bearer " + key} if key else {})
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            data = json.loads(r.read())
    except (urllib.error.URLError, OSError, ValueError):
        return []
    rows = data.get("data") if isinstance(data, dict) else None
    return sorted(str(m.get("id", "")) for m in (rows or []) if isinstance(m, dict) and m.get("id"))


def _pick_models(provider: str, ids: list[str]) -> list[str]:
    if provider == "gemini":
        ids = [i.removeprefix("models/") for i in ids]
        ids = [i for i in ids if i.startswith("gemini") and not any(
            w in i for w in ("embedding", "tts", "image", "live", "aqa"))]
    return ids[:20]
