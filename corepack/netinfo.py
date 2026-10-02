"""Find the USB network link and discover workers on it."""
from __future__ import annotations

import ipaddress
import re
import socket
import subprocess
from concurrent.futures import ThreadPoolExecutor

from .client import Peer, PeerError

USB_PREFIXES = ("usb", "rndis", "ncm", "enx", "enp0s20u")
ANDROID_TETHER_NET = "192.168.42."


def _run(cmd: list[str]) -> str:
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=3).stdout
    except Exception:
        return ""


def _prefix(mask: str) -> int:
    try:
        if mask.startswith("0x"):
            return bin(int(mask, 16)).count("1")
        return sum(bin(int(p)).count("1") for p in mask.split("."))
    except ValueError:
        return 24


def _udp_ip() -> str | None:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except OSError:
        return None


def interfaces() -> list[dict]:
    """IPv4 interfaces. Tries `ip`, then `ifconfig`, then a socket trick (Termux-safe)."""
    found: list[dict] = []
    for m in re.finditer(r"^\d+:\s+(\S+)\s+inet\s+(\d+\.\d+\.\d+\.\d+)/(\d+)",
                         _run(["ip", "-4", "-o", "addr", "show"]), re.M):
        found.append({"name": m.group(1), "ip": m.group(2), "prefix": int(m.group(3))})
    if not found:
        name = None
        for line in _run(["ifconfig"]).splitlines():
            m = re.match(r"^([A-Za-z0-9_.-]+):?\s", line)
            if m:
                name = m.group(1)
            m = re.search(r"inet (?:addr:)?(\d+\.\d+\.\d+\.\d+)", line)
            if m and name:
                mk = re.search(r"(?:netmask |Mask:)(\S+)", line)
                found.append({"name": name, "ip": m.group(1),
                              "prefix": _prefix(mk.group(1)) if mk else 24})
    if not found:
        ip = _udp_ip()
        if ip:
            found.append({"name": "default", "ip": ip, "prefix": 24})
    out = []
    for i in found:
        if i["ip"].startswith("127."):
            continue
        i["usb"] = i["name"].lower().startswith(USB_PREFIXES) or i["ip"].startswith(ANDROID_TETHER_NET)
        out.append(i)
    return out


def gateways() -> dict[str, str]:
    """dev -> default gateway. On the PC, the USB-tethered phone is the gateway."""
    res = {}
    for m in re.finditer(r"default via (\S+) dev (\S+)", _run(["ip", "route", "show", "default"])):
        res[m.group(2)] = m.group(1)
    return res


def usb_interfaces() -> list[dict]:
    return [i for i in interfaces() if i["usb"]]


def _scan_targets(ifs: list[dict]) -> list[str]:
    own = {i["ip"] for i in ifs}
    gw = gateways()
    first, rest = [], []
    for i in ifs:
        if i["name"] in gw:
            first.append(gw[i["name"]])
        net = ipaddress.ip_network(f"{i['ip']}/{max(i['prefix'], 24)}", strict=False)
        rest += [str(h) for h in net.hosts()]
    seen, out = set(), []
    for h in first + rest:
        if h not in seen and h not in own:
            seen.add(h)
            out.append(h)
    return out


def discover(port: int, token: str = "", timeout: float = 0.35) -> list[Peer]:
    """Scan the USB subnet (or any private subnet if no USB link) for workers."""
    ifs = usb_interfaces() or [i for i in interfaces()
                               if ipaddress.ip_address(i["ip"]).is_private]
    targets = _scan_targets(ifs)

    def probe(host: str) -> Peer | None:
        try:
            with socket.create_connection((host, port), timeout=timeout):
                pass
            p = Peer(host, port, token)
            p.info(timeout=2)
            return p
        except (OSError, PeerError, ValueError):
            return None

    with ThreadPoolExecutor(max_workers=64) as ex:
        return [p for p in ex.map(probe, targets) if p]
