"""Per-device settings, stored in ~/.corepack/config.json (never committed)."""
from __future__ import annotations

import json
import os
from pathlib import Path

DEFAULTS = {
    "port": 8765,          # worker HTTP port
    "token": "",           # optional shared secret (same value on both devices)
    "peers": [],           # e.g. ["192.168.42.129"]; empty = auto-discover
    "llama_cpp_dir": "~/llama.cpp",
    "rpc_port": 50052,
    "model": "",           # path to a .gguf; empty = auto-find
}


def home() -> Path:
    return Path(os.environ.get("COREPACK_HOME", str(Path.home() / ".corepack")))


def path() -> Path:
    return home() / "config.json"


def load() -> dict:
    cfg = dict(DEFAULTS)
    try:
        cfg.update(json.loads(path().read_text()))
    except (OSError, ValueError):
        pass
    return cfg


def save(cfg: dict) -> None:
    home().mkdir(parents=True, exist_ok=True)
    path().write_text(json.dumps(cfg, indent=2))


def is_termux() -> bool:
    return "com.termux" in os.environ.get("PREFIX", "") or "TERMUX_VERSION" in os.environ


def role() -> str:
    return "phone" if is_termux() else "pc"


def expand(p: str) -> Path:
    return Path(p).expanduser()
