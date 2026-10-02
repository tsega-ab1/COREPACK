"""One-shot health check with plain-English fixes."""
from __future__ import annotations

import os
import shutil
import sys

from . import __version__, config, netinfo, ui
from .worker import _ram_mb


def run(args=None) -> int:
    cfg = config.load()
    problems = 0
    ui.header(f"Doctor  (corepack {__version__})")

    v = sys.version_info
    (ui.ok if v >= (3, 8) else ui.fail)(f"Python {v.major}.{v.minor}.{v.micro}")
    ui.ok(f"Role: {config.role()}   Cores: {os.cpu_count()}   RAM: {_ram_mb()} MB")
    if shutil.which("git"):
        ui.ok("git installed")
    else:
        ui.warn("git missing (pkg install git / sudo apt install git)")

    print()
    ifs = netinfo.interfaces()
    usb = [i for i in ifs if i["usb"]]
    if usb:
        for i in usb:
            ui.ok(f"USB link: {i['name']}  {i['ip']}/{i['prefix']}")
        gw = netinfo.gateways()
        for i in usb:
            if i["name"] in gw:
                ui.info(f"Phone is probably at {ui.bold(gw[i['name']])} (gateway on {i['name']})")
    else:
        problems += 1
        ui.fail("No USB network link found")
        ui.hint("Phone: Settings > Connections > Mobile Hotspot and Tethering > USB tethering ON")
        ui.hint("Use a data cable (not charge-only), then run `corepack ip` again.")

    print()
    llama = config.expand(cfg["llama_cpp_dir"]) / "build" / "bin"
    for b in ("llama-cli", "rpc-server"):
        if (llama / b).exists():
            ui.ok(f"llama.cpp {b}")
        else:
            ui.warn(f"llama.cpp {b} not built (only needed for LLM mode: corepack llm build)")

    print()
    ui.info("Saved settings: " + str(config.path()))
    ui.hint(f"port={cfg['port']}  peers={cfg['peers'] or 'auto-discover'}  "
            f"token={'set' if cfg['token'] else 'none'}")
    print()
    (ui.ok if not problems else ui.warn)("All good" if not problems else f"{problems} thing(s) to fix")
    return 0
