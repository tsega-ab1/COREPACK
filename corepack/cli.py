"""Command-line interface and interactive menu."""
from __future__ import annotations

import argparse
import sys

from . import __version__, bench, config, doctor, llm, netinfo, nettest, tasks, ui, worker


def cmd_ip(args) -> int:
    ui.header("Network interfaces")
    ifs = netinfo.interfaces()
    if not ifs:
        ui.fail("No interfaces found")
        return 1
    gw = netinfo.gateways()
    ui.table(["Interface", "IPv4", "Kind", "Gateway"], [
        [i["name"], f"{i['ip']}/{i['prefix']}",
         ui.green("USB link") if i["usb"] else ui.dim("other"), gw.get(i["name"], "")]
        for i in ifs])
    print()
    usb = [i for i in ifs if i["usb"]]
    if not usb:
        ui.warn("No USB link yet. Enable USB tethering on the phone and re-run.")
    elif config.role() == "pc":
        for i in usb:
            if i["name"] in gw:
                ui.ok(f"Phone address: {ui.bold(gw[i['name']])}")
                ui.hint(f"Save it:  corepack config peers {gw[i['name']]}")
    else:
        ui.ok(f"This phone's USB address: {ui.bold(usb[0]['ip'])}  (the PC will find it automatically)")
    return 0


def cmd_discover(args) -> int:
    cfg = config.load()
    ui.header("Discover workers")
    ui.info("Scanning the USB link...")
    found = netinfo.discover(cfg["port"], cfg["token"])
    if not found:
        ui.fail("No workers found. Is `corepack worker` running on the other device?")
        return 1
    for p in found:
        ui.ok(f"{ui.bold(p.name)}  {p.label}  {p.slots} cores  {p.meta.get('platform', '')}")
    if ui.confirm("Save as default peer?", True):
        cfg["peers"] = [p.host for p in found]
        config.save(cfg)
        ui.ok("Saved")
    return 0


def cmd_config(args) -> int:
    cfg = config.load()
    if len(args.kv) >= 2:
        key, val = args.kv[0], " ".join(args.kv[1:])
        if key not in config.DEFAULTS:
            ui.fail(f"Unknown key. Valid: {', '.join(config.DEFAULTS)}")
            return 1
        if key == "peers":
            cfg[key] = [v.strip() for v in val.replace(",", " ").split() if v.strip()]
        elif isinstance(config.DEFAULTS[key], int):
            cfg[key] = int(val)
        else:
            cfg[key] = "" if val in ("-", "none") else val
        config.save(cfg)
        ui.ok(f"{key} = {cfg[key]}")
        return 0
    ui.header("Settings  " + ui.dim(str(config.path())))
    for k, v in cfg.items():
        print(f"  {ui.bold(k):<28} {v}")
    print()
    ui.hint("Change:  corepack config peers 192.168.42.129   (use '-' to clear a value)")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="corepack",
                                description="COREPACK Phase 0 - share compute between phone and PC over USB.")
    p.add_argument("--version", action="version", version=f"corepack {__version__}")
    sub = p.add_subparsers(dest="cmd")

    sub.add_parser("doctor", help="check this device and the USB link")
    sub.add_parser("ip", help="show interfaces and find the USB link")
    sub.add_parser("discover", help="find workers on the USB link")

    w = sub.add_parser("worker", help="run a worker (start this on the phone)")
    w.add_argument("--port", type=int)
    w.add_argument("--bind", default="0.0.0.0")
    w.add_argument("--cores", type=int, help="limit cores offered")
    w.add_argument("--token", help="require this shared secret")

    n = sub.add_parser("net", help="USB speed + latency test")
    n.add_argument("--peer", action="append")
    n.add_argument("--mb", type=int, default=50)

    b = sub.add_parser("bench", help="local vs local+worker benchmark")
    b.add_argument("--peer", action="append", help="host[:port] (repeatable)")
    b.add_argument("--task", default="primes", choices=list(tasks.TASK_NAMES) + ["all"])
    b.add_argument("--size", default="medium", choices=list(tasks.SIZES))
    b.add_argument("--local-cores", type=int, help="limit this device's cores")
    b.add_argument("--no-save", action="store_true")

    l = sub.add_parser("llm", help="distributed LLM via llama.cpp RPC")
    ls = l.add_subparsers(dest="llm_cmd")
    ls.add_parser("build", help="build llama.cpp with RPC")
    s = ls.add_parser("serve", help="phone: start rpc-server")
    s.add_argument("--port", type=int)
    r = ls.add_parser("run", help="PC: run a prompt across both devices")
    r.add_argument("--peer")
    r.add_argument("--model")
    r.add_argument("--prompt", default="Explain what a smartphone cover supercomputer is in two sentences.")
    r.add_argument("--tokens", type=int, default=128)
    r.add_argument("--rpc-port", type=int)

    c = sub.add_parser("config", help="show or change settings")
    c.add_argument("kv", nargs="*", help="key value")
    return p


def dispatch(args, parser) -> int:
    if args.cmd == "doctor":
        return doctor.run(args)
    if args.cmd == "ip":
        return cmd_ip(args)
    if args.cmd == "discover":
        return cmd_discover(args)
    if args.cmd == "worker":
        return worker.run(args)
    if args.cmd == "net":
        return nettest.run(args)
    if args.cmd == "bench":
        return bench.run(args)
    if args.cmd == "config":
        return cmd_config(args)
    if args.cmd == "llm":
        fn = {"build": llm.build, "serve": llm.serve, "run": llm.run}.get(args.llm_cmd)
        if not fn:
            parser.parse_args(["llm", "--help"])
        return fn(args)
    return 0


def interactive(parser) -> int:
    phone = config.role() == "phone"
    star = ui.orange("★")
    while True:
        ui.banner(f"Phase 0 - compute over USB   |   this device: {config.role().upper()}")
        choice = ui.menu("Main menu", [
            ("1", "Check this device + USB link", "doctor"),
            ("2", "Show network / find the phone", "ip"),
            ("3", "Start worker" + (f" {star}" if phone else ""), "phone: run this first"),
            ("4", "Find workers on the cable" + ("" if phone else f" {star}"), ""),
            ("5", "Link speed test", ""),
            ("6", "Benchmark: PC alone vs PC + phone" + ("" if phone else f" {star}"), ""),
            ("7", "LLM across both devices", "llama.cpp RPC"),
            ("8", "Settings", ""),
            ("0", "Quit", ""),
        ])
        argv = {"1": ["doctor"], "2": ["ip"], "3": ["worker"], "4": ["discover"],
                "5": ["net"], "6": ["bench"], "8": ["config"]}.get(choice)
        if choice in ("0", "q", "quit", "exit"):
            return 0
        if choice == "7":
            sub = ui.menu("LLM across both devices", [
                ("1", "Build llama.cpp with RPC", "once per device"),
                ("2", "Phone: start rpc-server", ""),
                ("3", "PC: run a prompt", ""),
            ])
            argv = {"1": ["llm", "build"], "2": ["llm", "serve"], "3": ["llm", "run"]}.get(sub)
        if argv is None:
            ui.warn("Pick a number from the menu")
            continue
        if argv == ["bench"]:
            size = ui.ask("Size (small/medium/large)", "medium")
            task = ui.ask(f"Task ({'/'.join(tasks.TASK_NAMES)}/all)", "primes")
            argv = ["bench", "--size", size, "--task", task]
        try:
            dispatch(parser.parse_args(argv), parser)
        except KeyboardInterrupt:
            print()
        except SystemExit:
            pass
        ui.pause()


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        if not args.cmd:
            return interactive(parser)
        return dispatch(args, parser)
    except KeyboardInterrupt:
        print()
        return 130
