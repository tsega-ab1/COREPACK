"""A small rate limiter that keeps requests to the engine under its per-minute limit."""
from __future__ import annotations

import threading
import time
from collections import deque


class RateLimiter:
    """Sliding window (default one minute). acquire() blocks (up to max_wait) until a slot is free."""

    def __init__(self, rpm: int, window: float = 60.0):
        self.rpm = rpm
        self.window = window
        self.lock = threading.Lock()
        self.stamps: deque[float] = deque()

    def acquire(self, max_wait: float, on_wait=None) -> bool:
        deadline = time.monotonic() + max_wait
        announced = False
        while True:
            with self.lock:
                now = time.monotonic()
                while self.stamps and now - self.stamps[0] >= self.window:
                    self.stamps.popleft()
                if self.rpm <= 0 or len(self.stamps) < self.rpm:
                    self.stamps.append(now)
                    return True
                wait = self.window - (now - self.stamps[0])
            if time.monotonic() + wait > deadline:
                return False
            if not announced and on_wait:
                on_wait(wait)
                announced = True
            time.sleep(min(wait, 1.0))
