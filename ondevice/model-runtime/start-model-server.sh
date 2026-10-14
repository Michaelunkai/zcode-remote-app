#!/data/data/com.termux/files/usr/bin/bash
# Starts the on-device model runtime (phone-only, loopback):
#   ollama serve          on 127.0.0.1:11434, INSIDE proot Debian
#                         (the ollama binary is glibc; Termux is bionic, so it
#                         must run in the guest — see install-termux-ollama.sh)
#   contract adapter      on 127.0.0.1:8080  (/health, chat page, /v1 proxy)
# Idempotent: imports /sdcard/Download/model.gguf as "qwen3b" on first start,
# then it is instant. Caller redirects output (see README).
set -uo pipefail

RUNTIME_DIR="$HOME/ondevice/model-runtime"
LOG_DIR="$RUNTIME_DIR/logs"
mkdir -p "$LOG_DIR"

GUEST='export OLLAMA_MODELS=/opt/ollama-models; exec /opt/ollama/bin/ollama serve'

pkill -f "contract-adapter" 2>/dev/null
pkill -f "ollama serve" 2>/dev/null
sleep 1

nohup proot-distro login debian -- bash -c "$GUEST" \
    > "$LOG_DIR/ollama.log" 2>&1 &

# Wait for ollama inside the guest. proot startup is slow; allow 3 min.
for _ in $(seq 1 180); do
    curl -s -m 2 http://127.0.0.1:11434/ >/dev/null 2>&1 && break
    sleep 1
done
if ! curl -s -m 2 http://127.0.0.1:11434/ >/dev/null 2>&1; then
    echo "SERVER_FAILED — ollama never came up; log tails:"
    tail -5 "$LOG_DIR/ollama.log" || true
    exit 1
fi

if ! curl -s http://127.0.0.1:11434/api/tags | grep -q '"name":"qwen3b"'; then
    proot-distro login debian -- bash -c \
        'export OLLAMA_MODELS=/opt/ollama-models; printf "FROM /sdcard/Download/model.gguf\n" > /opt/Modelfile; /opt/ollama/bin/ollama create qwen3b -f /opt/Modelfile' \
        >> "$LOG_DIR/ollama.log" 2>&1
fi

nohup python "$RUNTIME_DIR/contract-adapter.py" \
    > "$LOG_DIR/adapter.log" 2>&1 &

for _ in $(seq 1 60); do
    if curl -s http://127.0.0.1:8080/health | grep -q '"ok"'; then
        echo "SERVER_OK http://127.0.0.1:8080 (proot Debian ollama qwen3b behind adapter)"
        exit 0
    fi
    sleep 1
done

echo "SERVER_FAILED — log tails:"
tail -5 "$LOG_DIR/adapter.log" || true
tail -5 "$LOG_DIR/ollama.log" || true
exit 1
