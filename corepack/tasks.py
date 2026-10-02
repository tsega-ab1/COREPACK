"""Compute tasks. Pure Python, picklable, deterministic -> identical result on any device.

Only the names registered in TASKS can run on a worker; no arbitrary code is accepted.
"""
from __future__ import annotations

import hashlib
import time

TASK_NAMES = ("primes", "hashchain", "mandel")
SIZES = {"small": 12, "medium": 48, "large": 160}
N_CHUNKS = 48


def _is_prime(n: int) -> bool:
    if n < 2:
        return False
    if n < 4:
        return True
    if n % 2 == 0 or n % 3 == 0:
        return False
    i = 5
    while i * i <= n:
        if n % i == 0 or n % (i + 2) == 0:
            return False
        i += 6
    return True


def t_primes(a: dict) -> int:
    return sum(1 for n in range(a["lo"], a["hi"]) if _is_prime(n))


def t_hashchain(a: dict) -> str:
    h = hashlib.sha256(str(a["seed"]).encode()).digest()
    for _ in range(a["rounds"]):
        h = hashlib.sha256(h).digest()
    return h.hex()


def t_mandel(a: dict) -> int:
    w, h, max_iter = a["width"], a["height"], a["max_iter"]
    total = 0
    for y in range(a["y0"], a["y1"]):
        ci = -1.2 + 2.4 * y / h
        for x in range(w):
            cr = -2.0 + 3.0 * x / w
            zr = zi = 0.0
            i = 0
            while i < max_iter and zr * zr + zi * zi <= 4.0:
                zr, zi = zr * zr - zi * zi + cr, 2 * zr * zi + ci
                i += 1
            total += i
    return total


def t_sleep(a: dict) -> float:
    time.sleep(a["seconds"])
    return a["seconds"]


TASKS = {"primes": t_primes, "hashchain": t_hashchain, "mandel": t_mandel, "sleep": t_sleep}


def execute(name: str, args: dict):
    if name not in TASKS:
        raise ValueError(f"unknown task: {name}")
    return TASKS[name](args)


def build_job(name: str, size: str = "medium") -> list[dict]:
    """Split a task into N_CHUNKS independent pieces."""
    k = SIZES[size]
    n = N_CHUNKS
    if name == "primes":
        top = 400_000 * k
        step = top // n
        return [{"lo": i * step, "hi": top if i == n - 1 else (i + 1) * step} for i in range(n)]
    if name == "hashchain":
        return [{"seed": i, "rounds": 20_000 * k} for i in range(n)]
    if name == "mandel":
        height, width, rows = 240, 320, 5
        return [{"width": width, "height": height, "y0": i * rows, "y1": (i + 1) * rows,
                 "max_iter": 100 * k} for i in range(n)]
    raise ValueError(f"unknown task: {name}")


def combine(name: str, results: list):
    """Merge chunk results (in chunk order) into one final value."""
    if name in ("primes", "mandel"):
        return sum(results)
    if name == "hashchain":
        return hashlib.sha256("".join(results).encode()).hexdigest()
    raise ValueError(f"unknown task: {name}")
