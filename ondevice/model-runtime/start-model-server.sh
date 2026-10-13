#!/data/data/com.termux/files/usr/bin/bash
# Starts the on-device OpenAI-compatible model server:
#   http://127.0.0.1:8080  (GET /health, POST /v1/chat/completions)
# Loopback only — nothing on the network can reach it. Idempotent: restarts
# any previous instance. Caller redirects output (see README).
set -euo pipefail

RUNTIME_DIR="$HOME/ondevice/model-runtime"
MODEL_FILE="${MODEL_FILE:-$RUNTIME_DIR/models/qwen2.5-3b-instruct-q4_k_m.gguf}"
PORT="${PORT:-8080}"

if [ ! -s "$MODEL_FILE" ]; then
    echo "SERVER_FAILED model missing: $MODEL_FILE (run install-termux-llama.sh)"
    exit 1
fi

pkill -f llama-server 2>/dev/null || true
sleep 1

nohup "$RUNTIME_DIR/llama.cpp/build/bin/llama-server" \
    --host 127.0.0.1 --port "$PORT" \
    -m "$MODEL_FILE" \
    -c 4096 \
    -t "${THREADS:-6}" \
    --jinja \
    > "$RUNTIME_DIR/logs/server.log" 2>&1 &

echo "$!" > "$RUNTIME_DIR/server.pid"

for _ in $(seq 1 90); do
    if curl -s "http://127.0.0.1:$PORT/health" | grep -q '"ok"'; then
        echo "SERVER_OK http://127.0.0.1:$PORT pid=$(cat "$RUNTIME_DIR/server.pid")"
        exit 0
    fi
    sleep 1
done

echo "SERVER_FAILED — log tail:"
tail -20 "$RUNTIME_DIR/logs/server.log"
exit 1
