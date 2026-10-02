"""Distributed LLM inference over USB using llama.cpp's built-in RPC backend.

Phone runs `rpc-server`; the PC runs `llama-cli --rpc <phone>:<port>` and the model's
layers are split across both devices. (Lets you run models too big for one device.)
"""
from __future__ import annotations

import os
import subprocess
from pathlib import Path

from . import config, peers as peers_mod, ui


def _bin(cfg: dict, name: str) -> Path | None:
    p = config.expand(cfg["llama_cpp_dir"]) / "build" / "bin" / name
    return p if p.exists() else None


def _find_model(cfg: dict) -> str | None:
    if cfg["model"] and Path(config.expand(cfg["model"])).exists():
        return str(config.expand(cfg["model"]))
    roots = [Path.home() / ".cache/huggingface", Path.home() / "models", Path.home()]
    for root in roots:
        if root.exists():
            for dirpath, _dirs, files in os.walk(root):
                depth = len(Path(dirpath).relative_to(root).parts)
                if depth > 7:
                    continue
                for f in files:
                    if f.lower().endswith(".gguf"):
                        return str(Path(dirpath) / f)
    return None


def build(args) -> int:
    cfg = config.load()
    d = config.expand(cfg["llama_cpp_dir"])
    ui.header("Build llama.cpp with RPC")
    if not d.exists():
        ui.fail(f"{d} not found.")
        ui.hint(f"git clone https://github.com/ggml-org/llama.cpp {d}")
        return 1
    cmds = [["cmake", "-B", "build", "-DGGML_RPC=ON"],
            ["cmake", "--build", "build", "--config", "Release", "-j", str(os.cpu_count() or 2)]]
    ui.info("Will run in " + str(d) + ":")
    for c in cmds:
        ui.hint(" ".join(c))
    ui.hint("On the phone this takes a while - keep the screen on.")
    if not ui.confirm("Go ahead?"):
        return 0
    for c in cmds:
        if subprocess.call(c, cwd=d) != 0:
            ui.fail("Build failed")
            return 1
    ui.ok("Built with RPC support")
    return 0


def serve(args) -> int:
    cfg = config.load()
    exe = _bin(cfg, "rpc-server")
    ui.header("llama.cpp RPC server (run this on the phone)")
    if not exe:
        ui.fail("rpc-server not found. Run:  corepack llm build")
        return 1
    port = args.port or cfg["rpc_port"]
    ui.ok(f"Serving on 0.0.0.0:{port}   (Ctrl+C to stop)")
    try:
        return subprocess.call([str(exe), "-H", "0.0.0.0", "-p", str(port)])
    except KeyboardInterrupt:
        return 0


def run(args) -> int:
    cfg = config.load()
    exe = _bin(cfg, "llama-cli")
    ui.header("Distributed inference (run this on the PC)")
    if not exe:
        ui.fail("llama-cli not found. Run:  corepack llm build")
        return 1
    model = args.model or _find_model(cfg)
    if not model:
        ui.fail("No .gguf model found. Pass --model /path/to/model.gguf")
        return 1
    host = args.peer or (cfg["peers"][0] if cfg["peers"] else None)
    if not host:
        found = peers_mod.resolve(None, cfg)
        host = found[0].host if found else None
    if not host:
        ui.fail("Phone not found. Start `corepack llm serve` there and check `corepack ip`.")
        return 1
    host = host.split(":")[0]
    rpc = f"{host}:{args.rpc_port or cfg['rpc_port']}"
    ui.ok(f"Model: {model}")
    ui.ok(f"RPC worker: {rpc}")
    cmd = [str(exe), "-m", model, "--rpc", rpc, "-ngl", str(args.ngl), "-n", str(args.tokens),
           "-p", args.prompt]
    help_txt = subprocess.run([str(exe), "--help"], capture_output=True, text=True).stdout
    if "-no-cnv" in help_txt:
        cmd.append("-no-cnv")  # newer builds default to chat mode
    ui.hint(" ".join(cmd))
    print()
    try:
        return subprocess.call(cmd)
    except KeyboardInterrupt:
        return 0
