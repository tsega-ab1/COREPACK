"""USB link speed + latency test, no extra tools needed (the worker is the server)."""
from __future__ import annotations

from . import config, peers as peers_mod, ui
from .client import PeerError


def run(args) -> int:
    cfg = config.load()
    found = peers_mod.resolve(args.peer, cfg)
    if not found:
        ui.fail("No worker found. Start one on the other device: corepack worker")
        return 1
    p = found[0]
    ui.header(f"Link test -> {p.label}")
    try:
        lat = sorted(p.ping() for _ in range(20))
        ui.ok(f"Latency   median {lat[len(lat) // 2]:.2f} ms   best {lat[0]:.2f} ms   worst {lat[-1]:.2f} ms")
        up = args.mb * 8 / p.upload(args.mb)
        ui.ok(f"Upload    {up:7.1f} Mbit/s   (this device -> worker)")
        down = args.mb * 8 / p.download(args.mb)
        ui.ok(f"Download  {down:7.1f} Mbit/s   (worker -> this device)")
    except PeerError as e:
        ui.fail(f"Test failed: {e}")
        return 1
    print()
    ui.hint("USB 2.0 tops out near 480 Mbit/s raw; expect roughly 200-400 in practice.")
    ui.hint("Under ~100 Mbit/s? Try another cable or port - many cables are charge-only or slow.")
    return 0
