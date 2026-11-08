"""Phone AI gateway: an authenticated, rate-limited, OpenAI-compatible front door.

The phone app talks to this. This talks to the real engine (Gemini, Grok, a local
llama.cpp / Ollama server, or later a big model hosted by COREPACK). API keys live
here on the PC and never on the phone. Stdlib only.

Streaming replies are plain OpenAI-style server-sent events, plus SSE *comment*
lines that carry gateway events (ignored by ordinary OpenAI clients):

    : corepack {"event":"queued","wait":12.3}
    : corepack {"event":"upstream_start"}
    : corepack {"event":"throttle","retry_after":5}
"""
from __future__ import annotations

# Everything used to live in this one file. It is now split by job; these imports keep the old names working.
from .providers import (  # noqa: F401
    PRESETS,
    list_models,
    _pick_models,
)
from .gateway_config import (  # noqa: F401
    ALLOWED,
    DEFAULTS,
    MAX_BODY,
    file_path,
    load,
    load_file,
    save,
    validate,
)
from .ratelimit import (  # noqa: F401
    RateLimiter,
)
from .upstream import (  # noqa: F401
    Gateway,
    _retry_after,
    _short,
)
from .gateway_server import (  # noqa: F401
    Handler,
    make_server,
)
from .adb import (  # noqa: F401
    adb_reverse,
    parse_adb_devices,
    parse_adb_reverse,
    parse_adb_unauthorized,
    _adb_serials,
)
from .pairing import (  # noqa: F401
    lan_addresses,
    pairing_uri,
)
from .gateway_doctor import (  # noqa: F401
    doctor_verdict,
    run_doctor,
)
from .gateway_cli import (  # noqa: F401
    add_parsers,
    cmd_doctor,
    cmd_fallback,
    cmd_init,
    cmd_mcp,
    cmd_pair,
    cmd_start,
    cmd_test,
    cmd_token,
    dispatch,
    _pick_model,
)

__all__ = ['PRESETS', 'list_models', 'ALLOWED', 'DEFAULTS', 'MAX_BODY', 'file_path', 'load', 'load_file', 'save', 'validate', 'RateLimiter', 'Gateway', 'Handler', 'make_server', 'adb_reverse', 'parse_adb_devices', 'parse_adb_reverse', 'parse_adb_unauthorized', 'lan_addresses', 'pairing_uri', 'doctor_verdict', 'run_doctor', 'add_parsers', 'cmd_doctor', 'cmd_fallback', 'cmd_init', 'cmd_mcp', 'cmd_pair', 'cmd_start', 'cmd_test', 'cmd_token', 'dispatch']
