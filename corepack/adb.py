"""Helpers for the USB cable: reading `adb` output and setting up `adb reverse`."""
from __future__ import annotations

import shutil
import subprocess


def parse_adb_devices(out: str) -> list[str]:
    """Serials of phones that are connected and authorised."""
    return _adb_serials(out, "device")


def parse_adb_unauthorized(out: str) -> list[str]:
    return _adb_serials(out, "unauthorized")


def _adb_serials(out: str, state: str) -> list[str]:
    """Reads 'serial  state ...' lines, so both `adb devices` and `adb devices -l` work."""
    rows = [ln.split() for ln in out.splitlines()[1:] if ln.strip()]
    return [r[0] for r in rows if len(r) >= 2 and r[1] == state]


def parse_adb_reverse(out: str, port: int) -> bool:
    """True if `adb reverse --list` shows tcp:<port> forwarded."""
    return any(f"tcp:{port}" in ln.split() for ln in out.splitlines())


def adb_reverse(port: int) -> tuple[bool, str]:
    adb = shutil.which("adb")
    if not adb:
        return False, "adb not found (Linux Mint: sudo apt install adb)"
    try:
        out = subprocess.run([adb, "devices"], capture_output=True, text=True, timeout=15).stdout
    except (OSError, subprocess.SubprocessError) as e:
        return False, f"adb failed: {e}"
    devs = parse_adb_devices(out)
    if not devs:
        return False, "no phone found over USB (enable USB debugging and accept the prompt on the phone)"
    cmd = [adb, "-s", devs[0], "reverse", f"tcp:{port}", f"tcp:{port}"]
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=15)
    except (OSError, subprocess.SubprocessError) as e:
        return False, f"adb failed: {e}"
    if r.returncode != 0:
        return False, "adb reverse failed: " + (r.stderr.strip() or r.stdout.strip())
    return True, f"phone {devs[0]} can now reach this gateway at 127.0.0.1:{port}"
