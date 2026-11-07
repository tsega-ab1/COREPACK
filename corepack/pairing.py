"""The one-line pairing codes for the phone app, and the PC's Wi-Fi addresses."""
from __future__ import annotations

import ipaddress
import socket
import subprocess
import urllib.parse


def pairing_uri(base_url: str, token: str) -> str:
    """One line the phone app can read to fill in the address and token: corepack://pair?url=...&token=..."""
    return "corepack://pair?url=" + urllib.parse.quote(base_url, safe="") + "&token=" + urllib.parse.quote(token, safe="")


def lan_addresses() -> list[str]:
    """This PC's private IPv4 addresses (Wi-Fi, hotspot, USB tethering), most likely first."""
    found: list[str] = []
    try:
        found += subprocess.run(["hostname", "-I"], capture_output=True, text=True, timeout=3).stdout.split()
    except (OSError, subprocess.SubprocessError):
        pass
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        found.insert(0, s.getsockname()[0])
        s.close()
    except OSError:
        pass
    out: list[str] = []
    for ip in found:
        try:
            a = ipaddress.ip_address(ip)
        except ValueError:
            continue
        if a.version == 4 and a.is_private and not a.is_loopback and not a.is_link_local and ip not in out:
            out.append(ip)
    return out
