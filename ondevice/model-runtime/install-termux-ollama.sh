#!/data/data/com.termux/files/usr/bin/bash
# On-device model runtime installer (Termux, Galaxy S25 Ultra / arm64).
# Installs Ollama (official static arm64 build — no compilation) + the
# contract adapter, and expects the GGUF staged at
#   ~/ondevice/model-runtime/models/qwen2.5-3b-instruct-q4_k_m.gguf
# (any path works: pass MODEL_FILE; see README). Idempotent.
#
# NOTE (verified on this phone 2026-10-13): building llama.cpp from source in
# this Termux miscompiles at runtime (libc++ ABI mix on a half-upgraded base),
# so the runtime ships as Ollama's static binary instead.
# NOTE (verified 2026-10-14): the ollama binary is glibc and CANNOT run
# directly in bionic Termux ("cannot execute: required file not found") — it
# runs inside proot Debian (`proot-distro login debian`), see
# start-model-server.sh; install it into the guest at /opt/ollama.
set -euo pipefail

RUNTIME_DIR="$HOME/ondevice/model-runtime"
OLLAMA_DIR="$HOME/ollama"
mkdir -p "$RUNTIME_DIR/models" "$RUNTIME_DIR/logs"

echo "[1/4] termux packages (python runs the contract adapter)"
pkg install -y python zstd >/dev/null 2>&1 || pkg install -y python >/dev/null

echo "[2/4] ollama static arm64 (downloads only if missing)"
if [ ! -x "$OLLAMA_DIR/bin/ollama" ]; then
    mkdir -p "$OLLAMA_DIR"
    TARBALL="$RUNTIME_DIR/ollama-linux-arm64.tar.zst"
    if [ ! -s "$TARBALL" ]; then
        URL="${OLLAMA_URL:-https://github.com/ollama/ollama/releases/latest/download/ollama-linux-arm64.tar.zst}"
        curl -L --fail -o "$TARBALL" "$URL"
    fi
    tar --zstd -xf "$TARBALL" -C "$OLLAMA_DIR"
fi
test -x "$OLLAMA_DIR/bin/ollama"

echo "[3/4] contract adapter"
cp "$(dirname "$0")/contract-adapter.py" "$RUNTIME_DIR/" 2>/dev/null \
    || cp contract-adapter.py "$RUNTIME_DIR/"

echo "[4/4] model"
MODEL_FILE="${MODEL_FILE:-$RUNTIME_DIR/models/qwen2.5-3b-instruct-q4_k_m.gguf}"
if [ ! -s "$MODEL_FILE" ]; then
    curl -L --fail -o "$MODEL_FILE" \
        "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf"
fi

echo "INSTALL_OK ollama=$("$OLLAMA_DIR/bin/ollama" --version 2>/dev/null | head -1) model=$(du -h "$MODEL_FILE" | cut -f1)"
