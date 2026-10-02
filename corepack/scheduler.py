"""Work-stealing scheduler: every core on every device pulls the next chunk from one queue.

Fast devices naturally take more chunks, slow ones fewer. If a remote device fails
mid-job (cable pulled), its chunk goes back in the queue and the others finish the job.
"""
from __future__ import annotations

import queue
import threading
import time
from dataclasses import dataclass
from typing import Callable

_MISSING = object()


@dataclass
class Node:
    name: str
    slots: int
    run: Callable          # run(task, args) -> result
    done: int = 0
    busy: float = 0.0
    failed: bool = False
    error: str = ""


def run_job(task: str, chunks: list[dict], nodes: list[Node],
            on_progress: Callable[[int, int], None] | None = None):
    """Returns (results_in_chunk_order, elapsed_seconds)."""
    q: queue.Queue = queue.Queue()
    for i, c in enumerate(chunks):
        q.put((i, c))
    results = [_MISSING] * len(chunks)
    lock = threading.Lock()
    finished = [0]

    def loop(node: Node):
        while True:
            try:
                i, c = q.get_nowait()
            except queue.Empty:
                return
            t = time.perf_counter()
            try:
                r = node.run(task, c)
            except Exception as e:
                q.put((i, c))
                node.failed, node.error = True, str(e)
                return
            dt = time.perf_counter() - t
            with lock:
                results[i] = r
                node.done += 1
                node.busy += dt
                finished[0] += 1
                if on_progress:
                    on_progress(finished[0], len(chunks))

    t0 = time.perf_counter()
    threads = [threading.Thread(target=loop, args=(n,), daemon=True)
               for n in nodes for _ in range(n.slots)]
    for th in threads:
        th.start()
    for th in threads:
        th.join()

    # Safety net: finish anything orphaned by a node that died near the end.
    healthy = next((n for n in nodes if not n.failed), nodes[0])
    for i, r in enumerate(results):
        if r is _MISSING:
            results[i] = healthy.run(task, chunks[i])
            healthy.done += 1
            if on_progress:
                finished[0] += 1
                on_progress(finished[0], len(chunks))
    return results, time.perf_counter() - t0
