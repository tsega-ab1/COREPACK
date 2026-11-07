"""The `corepack gateway ...` commands."""
from __future__ import annotations

import getpass
import json
import os
import secrets
import time

from . import ui
from .providers import PRESETS, _pick_models, list_models
from .gateway_config import file_path, load, load_file, save, validate
from .upstream import Gateway
from .gateway_server import make_server
from .adb import adb_reverse
from .pairing import lan_addresses, pairing_uri
from .gateway_doctor import doctor_verdict, run_doctor


def cmd_init(args) -> int:
    cfg = load_file()
    ui.header("Phone AI gateway setup")
    prov = args.provider
    if not prov:
        pick = ui.menu("Which engine should answer?", [
            ("1", "Google Gemini", "free API key"), ("2", "xAI Grok", "API key or credits"),
            ("3", "Local server", "llama.cpp or Ollama on this PC"), ("4", "Other OpenAI-compatible", "any base URL")])
        prov = {"1": "gemini", "2": "xai", "3": "local", "4": "custom"}.get(pick)
        if not prov:
            ui.fail("Pick 1-4")
            return 1
    preset = PRESETS[prov]
    default_base = cfg["base_url"] if cfg.get("provider") == prov and cfg.get("base_url") else preset["base_url"]
    base = args.base_url or ui.ask("Base URL", default_base)
    if not base:
        ui.fail("A base URL is required")
        return 1
    key = ""
    if preset["needs_key"]:
        if args.api_key_env:
            key = os.environ.get(args.api_key_env, "")
        else:
            had = cfg.get("api_key") if cfg.get("provider") == prov else ""
            key = getpass.getpass("  API key (hidden" + ("; Enter keeps the saved one" if had else "") + "): ").strip()
            key = key or had
        if not key:
            ui.fail("An API key is required for this engine")
            return 1
    model = args.model
    if not model:
        ui.info("Asking the engine which models you can use...")
        ids = _pick_models(prov, list_models(base, key))
        if ids:
            for i, m in enumerate(ids, 1):
                print(f"   {i:>2}. {m}")
            ans = ui.ask("Pick a number, or type a model name", "1")
            model = ids[int(ans) - 1] if ans.isdigit() and 1 <= int(ans) <= len(ids) else ans
        else:
            ui.warn("Couldn't list models (bad key or no network?). Type one yourself.")
            model = ui.ask("Model name", cfg.get("model", ""))
    if prov == "gemini":
        model = model.removeprefix("models/")
    default_rpm = "0" if prov == "local" else str(cfg.get("rpm", 10))
    rpm = args.rpm if args.rpm is not None else int(ui.ask("Max requests per minute (0 = unlimited)", default_rpm))
    cfg.update({"provider": prov, "base_url": base, "api_key": key, "model": model, "rpm": rpm})
    if not cfg.get("token"):
        cfg["token"] = secrets.token_urlsafe(24)
    save(cfg)
    ui.ok(f"Saved to {file_path()} (readable only by you)")
    ui.hint("Next:  corepack gateway test     then     corepack gateway start")
    return 0


def _pick_model(prov: str, base: str, key: str, current: str = "") -> str:
    ui.info("Asking the engine which models you can use...")
    ids = _pick_models(prov, list_models(base, key))
    if ids:
        for i, m in enumerate(ids, 1):
            print(f"   {i:>2}. {m}")
        ans = ui.ask("Pick a number, or type a model name", "1")
        model = ids[int(ans) - 1] if ans.isdigit() and 1 <= int(ans) <= len(ids) else ans
    else:
        ui.warn("Couldn't list models (bad key or no network?). Type one yourself.")
        model = ui.ask("Model name", current)
    return model.removeprefix("models/") if prov == "gemini" else model


def cmd_fallback(args) -> int:
    cfg = load_file()
    cmd = args.fb_cmd
    items = cfg.get("fallbacks") or []
    if cmd == "list" or cmd is None:
        if not items:
            ui.info("No backup engines. Add one with: corepack gateway fallback add")
        for i, f in enumerate(items, 1):
            print(f"   {i}. {f.get('provider', '?')}  {f.get('model', '?')}  {f.get('base_url', '?')}  key: {'set' if f.get('api_key') else 'none'}")
        return 0
    if cmd == "clear":
        cfg["fallbacks"] = []
        save(cfg)
        ui.ok("Backup engines removed")
        return 0
    prov = args.provider or {"1": "gemini", "2": "xai", "3": "local", "4": "custom"}.get(ui.menu("Backup engine", [
        ("1", "Google Gemini", "another key or model"), ("2", "xAI Grok", "API key or credits"),
        ("3", "Local server", "llama.cpp or Ollama on this PC (works offline)"), ("4", "Other OpenAI-compatible", "any base URL")]))
    if prov not in PRESETS:
        ui.fail("Pick 1-4")
        return 1
    preset = PRESETS[prov]
    base = args.base_url or ui.ask("Base URL", preset["base_url"])
    key = ""
    if preset["needs_key"]:
        key = os.environ.get(args.api_key_env, "") if args.api_key_env else getpass.getpass("  API key (hidden): ").strip()
        if not key:
            ui.fail("An API key is required for this engine")
            return 1
    model = (args.model or _pick_model(prov, base, key)).strip()
    if not model:
        ui.fail("A model name is required")
        return 1
    items.append({"provider": prov, "base_url": base, "api_key": key, "model": model})
    cfg["fallbacks"] = items
    save(cfg)
    ui.ok(f"Added backup engine {len(items)}: {prov} / {model}")
    ui.hint("It is used automatically when the main engine fails or is rate-limited. Check it with: corepack gateway doctor")
    return 0


def cmd_pair(args) -> int:
    cfg = load_file()
    if not cfg.get("token"):
        ui.fail("No token yet. Run: corepack gateway init")
        return 1
    port = args.port or int(cfg["port"])
    ui.header("Pairing codes")
    print("  Copy ONE line to your phone (any messenger or email to yourself), then in the app tap 'Paste pairing code'.\n")
    print(f"  USB cable (needs adb reverse, done by 'gateway start'):\n    {pairing_uri(f'http://127.0.0.1:{port}/v1', cfg['token'])}\n")
    ips = lan_addresses()
    if ips:
        print("  Wi-Fi or hotspot (start the gateway with --lan):")
        for ip in ips:
            print(f"    {pairing_uri(f'http://{ip}:{port}/v1', cfg['token'])}")
    else:
        ui.info("No Wi-Fi address found on this PC, so only the USB code applies.")
    return 0


def cmd_doctor(args) -> int:
    cfg = load()
    port = args.port or int(cfg["port"])
    ui.header("Demo preflight")
    checks = run_doctor(cfg, port)
    for st, what, detail in checks:
        {"ok": ui.ok, "warn": ui.warn, "fail": ui.fail}[st](f"{what}: {detail}")
    ready = doctor_verdict(checks)
    print()
    (ui.ok if ready else ui.fail)("READY: nothing blocking" if ready else "NOT READY: fix the failed lines above")
    return 0 if ready else 1


def cmd_test(args) -> int:
    cfg = load()
    bad = validate(cfg)
    if bad:
        for b in bad:
            ui.fail(b)
        ui.hint("Run: corepack gateway init")
        return 1
    gw = Gateway(cfg)
    payload = {"model": cfg["model"], "stream": False, "max_tokens": 64,
               "messages": [{"role": "user", "content": "Reply with one short sentence saying you are ready."}]}
    t0 = time.monotonic()
    resp, err = gw.call_upstream(payload)
    if err:
        ui.fail(gw.redact(err[1]))
        return 1
    try:
        with resp:
            text = json.loads(resp.read())["choices"][0]["message"]["content"]
    except (ValueError, KeyError, IndexError, TypeError):
        ui.fail("The engine answered, but not in the expected format")
        return 1
    ui.ok(f"{cfg['model']} replied in {time.monotonic() - t0:.1f}s:  {(text or '').strip()}")
    return 0


def cmd_token(args) -> int:
    cfg = load_file()
    if args.rotate or not cfg.get("token"):
        cfg["token"] = secrets.token_urlsafe(24)
        save(cfg)
        ui.ok("New token created. Update it in the phone app.")
    print(f"  {cfg['token']}")
    return 0


def cmd_start(args) -> int:
    cfg = load()
    bad = validate(cfg)
    if bad:
        for b in bad:
            ui.fail(b)
        ui.hint("Run: corepack gateway init")
        return 1
    host = args.host or ("0.0.0.0" if args.lan else cfg["host"])
    port = args.port or int(cfg["port"])
    try:
        srv = make_server(cfg, host, port, verbose=True)
    except OSError as e:
        ui.fail(f"Can't listen on {host}:{port}: {e}")
        return 1
    ui.header("Phone AI gateway")
    ui.ok(f"Engine   {cfg['model']}  via {PRESETS.get(cfg['provider'], {}).get('label', cfg['provider'])}")
    ui.ok(f"Limit    {cfg['rpm'] or 'none'} requests/min")
    ui.ok(f"URL      http://127.0.0.1:{port}/v1")
    ui.ok(f"Token    {cfg['token']}")
    ui.ok(f"Pairing  {pairing_uri(f'http://127.0.0.1:{port}/v1', cfg['token'])}   (USB; paste it into the app)")
    if cfg.get("fallbacks"):
        ui.ok(f"Backup   {len(cfg['fallbacks'])} engine(s) ready if the main one fails")
    if host not in ("127.0.0.1", "localhost", "::1"):
        for ip in lan_addresses():
            ui.ok(f"Wi-Fi    http://{ip}:{port}/v1   {pairing_uri(f'http://{ip}:{port}/v1', cfg['token'])}")
    if host not in ("127.0.0.1", "localhost", "::1"):
        ui.warn(f"Listening on {host}: anyone who reaches this port with the token can spend your API quota.")
    if not args.no_adb:
        ok, msg = adb_reverse(port)
        (ui.ok if ok else ui.warn)(msg)
        if not ok:
            ui.hint(f"Later:  adb reverse tcp:{port} tcp:{port}")
    ui.hint("Phone app: set the URL and token above, then tap the pet to chat. Ctrl-C stops the gateway.")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print()
    finally:
        srv.server_close()
    return 0


def add_parsers(sub) -> None:
    g = sub.add_parser("gateway", help="AI gateway for the phone overlay app")
    gs = g.add_subparsers(dest="gw_cmd")
    i = gs.add_parser("init", help="set the engine, API key and model")
    i.add_argument("--provider", choices=list(PRESETS))
    i.add_argument("--base-url")
    i.add_argument("--model")
    i.add_argument("--api-key-env", help="read the API key from this environment variable")
    i.add_argument("--rpm", type=int)
    gs.add_parser("test", help="check that the API key and model work")
    s = gs.add_parser("start", help="run the gateway (and wire up the USB cable with adb)")
    s.add_argument("--host")
    s.add_argument("--port", type=int)
    s.add_argument("--no-adb", action="store_true", help="don't run adb reverse")
    s.add_argument("--lan", action="store_true", help="also listen on Wi-Fi / hotspot so phones without a cable can connect")
    t = gs.add_parser("token", help="show or rotate the phone token")
    t.add_argument("--rotate", action="store_true")
    pr = gs.add_parser("pair", help="print one-line pairing codes for the phone app")
    pr.add_argument("--port", type=int)
    d = gs.add_parser("doctor", help="preflight check before a demo")
    d.add_argument("--port", type=int)
    fb = gs.add_parser("fallback", help="backup engines used if the main one fails or is rate-limited")
    fbs = fb.add_subparsers(dest="fb_cmd")
    fa = fbs.add_parser("add", help="add a backup engine")
    fa.add_argument("--provider", choices=list(PRESETS))
    fa.add_argument("--base-url")
    fa.add_argument("--model")
    fa.add_argument("--api-key-env")
    fbs.add_parser("list", help="show backup engines")
    fbs.add_parser("clear", help="remove all backup engines")


def dispatch(args, parser) -> int:
    fn = {"init": cmd_init, "test": cmd_test, "start": cmd_start, "token": cmd_token, "pair": cmd_pair,
          "doctor": cmd_doctor, "fallback": cmd_fallback}.get(args.gw_cmd)
    if not fn:
        parser.parse_args(["gateway", "--help"])
        return 0
    return fn(args)
