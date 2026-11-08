"""The preflight checks behind `corepack gateway doctor`."""
from __future__ import annotations

import json
import shutil
import socket
import subprocess
import time
import urllib.request

from .gateway_config import validate
from .mcp_host import McpHost
from .upstream import Gateway
from .adb import parse_adb_devices, parse_adb_reverse, parse_adb_unauthorized
from .pairing import lan_addresses


def run_doctor(cfg: dict, port: int) -> list[tuple[str, str, str]]:
    """Preflight checks. Each is (status, what, detail) with status ok / warn / fail."""
    out: list[tuple[str, str, str]] = []
    bad = validate(cfg)
    if bad:
        return [("fail", "Setup", "; ".join(bad) + "  (run: corepack gateway init)")]
    out.append(("ok", "Setup", f"{cfg.get('provider') or 'engine'} / {cfg['model']}, phone token set"))
    gw = Gateway(cfg)
    provs = gw.providers()
    payload = {"stream": False, "max_tokens": 32, "messages": [{"role": "user", "content": "Reply with one word: ready"}]}
    results = []
    for i, prov in enumerate(provs):
        t0 = time.monotonic()
        resp, err = gw._call_one(payload, prov, None, can_wait=False)
        if err:
            results.append((i, False, gw.redact(err[1])))
            continue
        try:
            with resp:
                json.loads(resp.read())["choices"][0]
            results.append((i, True, f"replied in {time.monotonic() - t0:.1f}s"))
        except (ValueError, KeyError, IndexError, TypeError):
            results.append((i, False, "answered, but not in the expected format"))
    main_ok = results[0][1]
    backup_ok = any(ok for i, ok, _ in results if i > 0)
    for i, ok, detail in results:
        name = "Main engine" if i == 0 else f"Backup engine {i}"
        label = f"{name} ({provs[i]['label']} / {provs[i]['model']})"
        if ok:
            out.append(("ok", label, detail))
        elif i == 0:
            out.append(("warn" if backup_ok else "fail", label, detail + ("  (a backup works, so the demo would still run)" if backup_ok else "")))
        else:
            out.append(("warn", label, detail))
    for state, name, detail in McpHost(cfg).status():
        out.append((state, f"Tool server {name}", detail))
    if len(provs) == 1:
        out.append(("warn", "Backup engine", "none set: a rate limit during the demo would stop it. Add one: corepack gateway fallback add"))
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=2) as r:
            out.append(("ok", "Gateway", f"already running on port {port}"))
    except (OSError, ValueError):
        sk = socket.socket()
        try:
            sk.bind(("127.0.0.1", port))
            out.append(("ok", "Port", f"{port} is free (the gateway is not running yet)"))
        except OSError:
            out.append(("warn", "Port", f"{port} is in use by something else; use --port"))
        finally:
            sk.close()
    adb = shutil.which("adb")
    ips = lan_addresses()
    if not adb:
        out.append(("warn", "Phone over USB", "adb not found (sudo apt install adb); Wi-Fi mode still works"))
    else:
        try:
            devs_out = subprocess.run([adb, "devices"], capture_output=True, text=True, timeout=15).stdout
            devs = parse_adb_devices(devs_out)
            if devs:
                rev = subprocess.run([adb, "-s", devs[0], "reverse", "--list"], capture_output=True, text=True, timeout=15).stdout
                usb_ok = parse_adb_reverse(rev, port)
                out.append(("ok" if usb_ok else "warn", "Phone over USB", f"{devs[0]} connected" + ("" if usb_ok else f"; link not set up yet (the gateway does it on start, or: adb reverse tcp:{port} tcp:{port})")))
            elif parse_adb_unauthorized(devs_out):
                out.append(("warn", "Phone over USB", "phone is connected but has not accepted USB debugging: look at the phone's screen"))
            else:
                out.append(("warn", "Phone over USB", "no phone found (cable, USB debugging); Wi-Fi mode still works"))
        except (OSError, subprocess.SubprocessError) as e:
            out.append(("warn", "Phone over USB", f"adb failed: {e}"))
    if ips:
        out.append(("ok", "Wi-Fi mode", "this PC is at " + ", ".join(ips) + "; start with --lan and use a pairing code"))
    else:
        out.append(("warn", "Wi-Fi mode", "no Wi-Fi address found"))
    return out


def doctor_verdict(checks: list[tuple[str, str, str]]) -> bool:
    """Ready when nothing failed. Warnings are advice."""
    return not any(st == "fail" for st, _, _ in checks)
