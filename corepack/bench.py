"""The Phase 0 experiment: same job on this device alone vs. this device + the phone."""
from __future__ import annotations

import json
import os
import time
from pathlib import Path

from . import config, peers as peers_mod, tasks, ui
from .pool import Pool
from .scheduler import Node, run_job


def _results_dir() -> Path:
    repo = Path(__file__).resolve().parent.parent / "results"
    return repo if repo.is_dir() and os.access(repo, os.W_OK) else config.home()


def run(args) -> int:
    cfg = config.load()
    found = peers_mod.resolve(args.peer, cfg)
    if not found:
        ui.fail("No worker found.")
        ui.hint("Start one on the other device:  corepack worker")
        ui.hint("Then check the cable/tethering:  corepack ip")
        return 1

    names = list(tasks.TASK_NAMES) if args.task == "all" else [args.task]
    cores = args.local_cores or os.cpu_count() or 1
    ui.header("Benchmark")
    ui.info(f"This device: {cores} cores   Workers: " +
            ", ".join(f"{p.name} ({p.slots} cores)" for p in found))
    ui.info(f"Task: {args.task}   Size: {args.size}   Chunks: {tasks.N_CHUNKS}")
    pool = Pool(cores)
    ui.ok(f"Local {pool.kind} pool warmed up")

    report = []
    try:
        for name in names:
            chunks = tasks.build_job(name, args.size)
            print()
            ui.info(ui.bold(f"{name}"))

            local = Node("this device", cores, pool.run)
            a, t_local = run_job(name, chunks, [local],
                                 lambda d, t: ui.progress(d, t, "local only   "))
            final_a = tasks.combine(name, a)

            nodes = [Node("this device", cores, pool.run)] + \
                    [Node(p.name, p.slots, p.run) for p in found]
            b, t_both = run_job(name, chunks, nodes,
                                lambda d, t: ui.progress(d, t, "local+worker "))
            final_b = tasks.combine(name, b)

            same = final_a == final_b
            speed = t_local / t_both if t_both else 0
            split = "  ".join(f"{n.name}:{n.done}" for n in nodes)
            failed = [n for n in nodes if n.failed]
            print()
            ui.table(["Mode", "Time", "Speedup", "Chunks per device"], [
                ["local only", f"{t_local:.2f}s", "1.00x", f"this device:{local.done}"],
                ["local + worker", f"{t_both:.2f}s",
                 (ui.green if speed > 1 else ui.red)(f"{speed:.2f}x"), split],
            ])
            (ui.ok if same else ui.fail)(
                "results identical on both runs" if same else "RESULTS DIFFER - investigate!")
            for n in failed:
                ui.warn(f"{n.name} dropped out mid-job: {n.error}")
            report.append({"task": name, "size": args.size, "local_s": round(t_local, 3),
                           "both_s": round(t_both, 3), "speedup": round(speed, 3),
                           "identical": same,
                           "chunks": {n.name: n.done for n in nodes}})
    finally:
        pool.close()

    if report and not args.no_save:
        out = _results_dir() / f"bench-{time.strftime('%Y%m%d-%H%M%S')}.json"
        out.write_text(json.dumps({
            "when": time.strftime("%Y-%m-%d %H:%M:%S"), "local_cores": cores,
            "workers": [{"name": p.name, "cores": p.slots, "platform": p.meta.get("platform")}
                        for p in found], "runs": report}, indent=2))
        print()
        ui.ok(f"Saved {out}")
        ui.hint("Commit it:  git add results && git commit -m 'bench results' && git push")
    return 0
