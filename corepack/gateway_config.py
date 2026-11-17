"""The gateway's settings file: defaults, loading, saving and checking it. Stdlib only."""
from __future__ import annotations

import copy
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
    "engines": {},            # named engines a request can ask for by name: name -> {provider, base_url, api_key, model, description}
    "mcp_servers": {},        # MCP servers whose tools the phone may use: name -> {command, args, env, allow, confirm}
    "private_window": 600,    # seconds after private tool data is used during which only a local engine may answer
    "mcp_timeout": 30,        # seconds a tool call may take
    "mcp_max_result": 4000,   # characters of a tool result passed on to the phone
    "hosted": False,          # public server: never start tool servers, testers use invite codes
    "invites": {},            # invite codes: name -> {sha256, rpm, daily} (see hosted.py)
    "invite_rpm": 6,          # default requests per minute for one invite
    "invite_daily": 30,       # default messages per day for one invite on the shared key
    "max_concurrent": 0,      # replies being made at once (0 = unlimited)
    "byo_models": {},         # model to use with a tester's own key: provider -> model
    "preset_urls": {},        # owner-only override of a provider's address (never taken from the phone)
}

ALLOWED = {"messages", "temperature", "top_p", "max_tokens", "max_completion_tokens", "stop",
           "response_format", "tools", "tool_choice", "seed"}


MAX_BODY = 1_000_000


def file_path():
    return config.home() / "gateway.json"


def load_file() -> dict:
    cfg = copy.deepcopy(DEFAULTS)           # nested settings must not be shared with the defaults
    try:
        cfg.update(json.loads(file_path().read_text()))
    except (OSError, ValueError):
        pass
    return cfg


_ENV_TEXT = {"COREPACK_API_KEY": "api_key", "COREPACK_PROVIDER": "provider", "COREPACK_BASE_URL": "base_url",
             "COREPACK_MODEL": "model", "COREPACK_TOKEN": "token", "COREPACK_SYSTEM_PROMPT": "system_prompt"}
_ENV_INT = {"COREPACK_RPM": "rpm", "COREPACK_MAX_TOKENS": "max_tokens", "COREPACK_INVITE_RPM": "invite_rpm",
            "COREPACK_INVITE_DAILY": "invite_daily", "COREPACK_MAX_CONCURRENT": "max_concurrent"}


def load() -> dict:
    """The file, then environment variables on top (so a container needs no file and no secret on disk)."""
    cfg = load_file()
    for var, key in _ENV_TEXT.items():
        if os.environ.get(var):
            cfg[key] = os.environ[var]
    for var, key in _ENV_INT.items():
        try:
            if os.environ.get(var):
                cfg[key] = int(os.environ[var])
        except ValueError:
            pass
    if os.environ.get("COREPACK_HOSTED", "").lower() in ("1", "true", "yes"):
        cfg["hosted"] = True
    try:
        byo = json.loads(os.environ.get("COREPACK_BYO_MODELS", "") or "null")
        if isinstance(byo, dict):
            cfg["byo_models"] = byo
    except ValueError:
        pass
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
