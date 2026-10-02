#!/usr/bin/env bash
# One-step setup for BOTH the PC (Linux Mint/Ubuntu) and the phone (Termux).
set -e
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ "${PREFIX:-}" == *com.termux* ]]; then
  echo "▌ Termux detected"
  pkg install -y python git
  BIN="$PREFIX/bin"
else
  echo "▌ Linux detected"
  command -v python3 >/dev/null || sudo apt install -y python3
  command -v git >/dev/null || sudo apt install -y git
  BIN="$HOME/.local/bin"
  mkdir -p "$BIN"
fi

chmod +x "$ROOT/bin/corepack"
ln -sf "$ROOT/bin/corepack" "$BIN/corepack"

echo
echo "✓ Installed. Run:  corepack"
case ":$PATH:" in *":$BIN:"*) ;; *) echo "! Add $BIN to PATH (or open a new terminal)";; esac
