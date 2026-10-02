"""Local execution pool: processes where possible (real multi-core), threads as fallback."""
from __future__ import annotations

import multiprocessing as mp
from concurrent.futures import ProcessPoolExecutor, ThreadPoolExecutor

from . import tasks


class Pool:
    def __init__(self, workers: int, force_threads: bool = False):
        self.workers = max(1, workers)
        self.kind = "process"
        self._ex = None
        if not force_threads:
            try:
                self._ex = ProcessPoolExecutor(max_workers=self.workers,
                                               mp_context=mp.get_context("spawn"))
                warm = [self._ex.submit(tasks.execute, "sleep", {"seconds": 0.15})
                        for _ in range(self.workers)]
                for f in warm:
                    f.result(timeout=90)
            except Exception:
                self._ex = None
        if self._ex is None:
            self.kind = "thread"
            self._ex = ThreadPoolExecutor(max_workers=self.workers)

    def run(self, name: str, args: dict):
        return self._ex.submit(tasks.execute, name, args).result()

    def close(self) -> None:
        self._ex.shutdown(wait=False, cancel_futures=True)
