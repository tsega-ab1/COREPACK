"""The gateway's settings file: defaults, loading, saving and checking it. Stdlib only."""
from __future__ import annotations

import json
import os

from . import config


DEFAULTS = {
    "provider": "",
    "base_url": "",
    "api_key": "",
    "model": "",
    "token": "",              # what the phone must send as "Authorization: Bearer <token>"
    "host": "127.0.0.1",      # loopback only; the phone reaches it through `adb reverse`
    "port": 8787,
    "rpm": 10,                # requests per minute sent upstream (0 = unlimited)
    "max_wait": 60,           # seconds a request may queue for a free slot
    "max_tokens": 1024,       # cap on reply length
    "timeout": 180,           # seconds to wait on the upstream engine
    "system_prompt": "",      # optional default persona if the client sends none
    "fallbacks": [],          # backup engines, tried in order if the main one fails or is rate-limited
    "mcp_servers": {},        # MCP servers whose tools the phone may use: name -> {command, args, env, allow, confirm}
    "mcp_timeout": 30,        # seconds a tool call may take
    "mcp_max_result": 4000,   # characters of a tool result passed on to the phone
}

ALLOWED = {"messages", "temperature", "top_p", "max_tokens", "max_completion_tokens", "stop",
           "response_format", "tools", "tool_choice", "seed"}


MAX_BODY = 1_000_000


def file_path():
    return config.home() / "gateway.json"


def load_file() -> dict:
    cfg = dict(DEFAULTS)
    try:
        cfg.update(json.loads(file_path().read_text()))
    except (OSError, ValueError):
        pass
    return cfg


def load() -> dict:
    cfg = load_file()
    env_key = os.environ.get("COREPACK_API_KEY")
    if env_key:
        cfg["api_key"] = env_key
    return cfg


def save(cfg: dict) -> None:
    config.home().mkdir(parents=True, exist_ok=True)
    p = file_path()
    fd = os.open(str(p), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(json.dumps(cfg, indent=2))
    try:
        os.chmod(p, 0o600)
    except OSError:
        pass


def validate(cfg: dict) -> list[str]:
    bad = []
    if not cfg.get("base_url"):
        bad.append("No upstream base URL set")
    if not cfg.get("model"):
        bad.append("No model set")
    if not cfg.get("token"):
        bad.append("No phone token set")
    if cfg.get("provider") != "local" and not cfg.get("api_key"):
        bad.append("No API key set")
    return bad
