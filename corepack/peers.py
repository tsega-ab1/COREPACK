"""Resolve which workers to use: explicit --peer, saved config, or auto-discovery."""
from __future__ import annotations

from . import ui
from .client import Peer, PeerError
from .netinfo import discover


def resolve(specs: list[str] | None, cfg: dict, quiet: bool = False) -> list[Peer]:
    specs = specs or cfg.get("peers") or []
    peers: list[Peer] = []
    if specs:
        for s in specs:
            p = Peer.parse(s, cfg["port"], cfg["token"])
            try:
                p.info()
                peers.append(p)
            except PeerError as e:
                if not quiet:
                    ui.warn(f"{p.label} not reachable ({e})")
    else:
        if not quiet:
            ui.info("No peer set - searching the USB link for workers...")
        peers = discover(cfg["port"], cfg["token"])
    if not quiet:
        for p in peers:
            ui.ok(f"worker {ui.bold(p.name)} at {p.label} - {p.slots} cores, "
                  f"{p.meta.get('pool', '?')} pool")
    return peers
