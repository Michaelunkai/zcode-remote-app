#!/data/data/com.termux/files/usr/bin/bash
# On-device model runtime installer (Termux, Galaxy S25 Ultra / arm64).
# Idempotent: safe to re-run; skips completed stages. Output must be
# redirected by the caller (see README "run over adb" section).
set -euo pipefail

RUNTIME_DIR="$HOME/ondevice/model-runtime"
mkdir -p "$RUNTIME_DIR/models" "$RUNTIME_DIR/logs"

echo "[1/4] termux packages"
yes | pkg update -y >/dev/null 2>&1 || true
pkg install -y git cmake clang >/dev/null

echo "[2/4] llama.cpp source"
if [ ! -d "$RUNTIME_DIR/llama.cpp" ]; then
    git clone --depth 1 https://github.com/ggml-org/llama.cpp.git "$RUNTIME_DIR/llama.cpp"
fi

echo "[3/4] building llama.cpp (CPU, native flags) — this takes a while"
cd "$RUNTIME_DIR/llama.cpp"
cmake -B build -DCMAKE_BUILD_TYPE=Release \
    -DGGML_NATIVE=ON -DGGML_OPENMP=OFF -DLLAMA_CURL=OFF \
    -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=ON \
    >/dev/null
cmake --build build --config Release -j "$(nproc)" >/dev/null
test -x build/bin/llama-server

echo "[4/4] model download (skipped if already present)"
MODEL_URL="${MODEL_URL:-https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf}"
MODEL_FILE="${MODEL_FILE:-$RUNTIME_DIR/models/qwen2.5-3b-instruct-q4_k_m.gguf}"
if [ ! -s "$MODEL_FILE" ]; then
    curl -L --fail -o "$MODEL_FILE" "$MODEL_URL"
fi

echo "INSTALL_OK llama-server=$(ls -l "$RUNTIME_DIR/llama.cpp/build/bin/llama-server" | awk '{print $5}')bytes model=$(du -h "$MODEL_FILE" | cut -f1)"
