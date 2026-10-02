"""Tiny terminal UI helpers: colours, tables, progress bar, menus. Stdlib only."""
from __future__ import annotations

import os
import re
import shutil
import sys

_ANSI = re.compile(r"\033\[[0-9;]*m")


def _on() -> bool:
    return sys.stdout.isatty() and "NO_COLOR" not in os.environ


def _c(code: str, s) -> str:
    return f"\033[{code}m{s}\033[0m" if _on() else str(s)


def bold(s): return _c("1", s)
def dim(s): return _c("2", s)
def red(s): return _c("31", s)
def green(s): return _c("32", s)
def yellow(s): return _c("33", s)
def cyan(s): return _c("36", s)
def orange(s): return _c("38;5;208", s)


def strip(s: str) -> str:
    return _ANSI.sub("", s)


def width() -> int:
    return shutil.get_terminal_size((60, 20)).columns


BANNER = r"""
  ____ ___  ____  _____ ____   _    ____ _  __
 / ___/ _ \|  _ \| ____|  _ \ / \  / ___| |/ /
| |  | | | | |_) |  _| | |_) / _ \| |   | ' /
| |__| |_| |  _ <| |___|  __/ ___ \ |___| . \
 \____\___/|_| \_\_____|_| /_/   \_\____|_|\_\
"""


def banner(subtitle: str = "") -> None:
    if width() >= 48:
        print(orange(BANNER.rstrip("\n")))
    else:
        print(orange(bold("\n  C O R E P A C K")))
    if subtitle:
        print("  " + dim(subtitle))
    print()


def header(title: str) -> None:
    print()
    print(orange("▌ ") + bold(title))
    print(dim("─" * min(width() - 1, 60)))


def ok(msg: str) -> None: print(f"  {green('✓')} {msg}")
def warn(msg: str) -> None: print(f"  {yellow('!')} {msg}")
def fail(msg: str) -> None: print(f"  {red('✗')} {msg}")
def info(msg: str) -> None: print(f"  {cyan('•')} {msg}")
def hint(msg: str) -> None: print(f"    {dim(msg)}")


def table(headers: list[str], rows: list[list]) -> None:
    rows = [[str(c) for c in r] for r in rows]
    cols = len(headers)
    w = [max(len(strip(x)) for x in [headers[i]] + [r[i] for r in rows]) for i in range(cols)]
    pad = lambda s, n: s + " " * (n - len(strip(s)))
    print("  " + "  ".join(bold(pad(headers[i], w[i])) for i in range(cols)))
    print("  " + dim("  ".join("─" * w[i] for i in range(cols))))
    for r in rows:
        print("  " + "  ".join(pad(r[i], w[i]) for i in range(cols)))


def progress(done: int, total: int, label: str = "") -> None:
    if not sys.stdout.isatty():
        return
    bar_w = max(10, min(30, width() - len(label) - 16))
    filled = int(bar_w * done / max(total, 1))
    bar = orange("█" * filled) + dim("░" * (bar_w - filled))
    end = "\n" if done >= total else ""
    sys.stdout.write(f"\r  {label} {bar} {done}/{total}{end}")
    sys.stdout.flush()


def ask(prompt: str, default: str = "") -> str:
    suffix = f" [{default}]" if default else ""
    try:
        v = input(f"  {bold(prompt)}{suffix}: ").strip()
    except EOFError:
        v = ""
    return v or default


def confirm(prompt: str, default: bool = True) -> bool:
    d = "Y/n" if default else "y/N"
    v = ask(f"{prompt} ({d})").lower()
    return default if not v else v.startswith("y")


def menu(title: str, items: list[tuple[str, str, str]]) -> str:
    """items: (key, label, hint). Returns the chosen key ('0' on EOF)."""
    header(title)
    for key, label, h in items:
        print(f"  {orange(bold(key))}  {label}" + (f"  {dim(h)}" if h else ""))
    print()
    try:
        return input(f"  {bold('Choose')}: ").strip().lower()
    except EOFError:
        return "0"


def pause() -> None:
    try:
        input(dim("\n  Press Enter to continue..."))
    except EOFError:
        pass
