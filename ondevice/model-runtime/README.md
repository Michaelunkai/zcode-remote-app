# ondevice/model-runtime — the AI model server on the phone

An OpenAI-compatible LLM server that runs **on the Galaxy S25 Ultra itself**
(Termux + llama.cpp, CPU). This is what makes the phone independent of the PC:
with the PC off, the ZCode app's offline mode loads this server, and the agent
harness (`../agent-harness`) drives the phone through it.

## What runs where

| Piece | Location | Endpoint |
|---|---|---|
| Qwen2.5-3B-Instruct Q4_K_M (~2 GB) | `~/ondevice/model-runtime/models/` | — |
| llama-server (llama.cpp, `--jinja`) | `~/ondevice/model-runtime/llama.cpp/build/bin/` | `http://127.0.0.1:8080` |
| Health check | any termux shell | `curl http://127.0.0.1:8080/health` |

Loopback only: nothing on the LAN can reach the model server.

## Install (once, inside Termux on the phone)

```bash
pkg install -y git curl bash
mkdir -p ~/ondevice/model-runtime
# copy install-termux-llama.sh + start-model-server.sh into
# ~/ondevice/model-runtime (adb push to /sdcard/Download, then cp), then:
bash ~/ondevice/model-runtime/install-termux-llama.sh
```

The installer is idempotent: it installs packages, clones llama.cpp, builds it
(CPU, native flags, ~10–20 min on the 8 Elite), and downloads the model
(override with `MODEL_URL`/`MODEL_FILE` env vars). A pre-downloaded GGUF can be
copied into `models/` to skip the download.

## Start (every boot, or on demand)

```bash
bash ~/ondevice/model-runtime/start-model-server.sh
# -> SERVER_OK http://127.0.0.1:8080 pid=...
curl -s http://127.0.0.1:8080/health          # {"status":"ok",...}
```

Persistence options: Termux:Boot is installed on this phone — put a tiny script
in `~/.termux/boot/` calling `start-model-server.sh` to have the model ready
after every reboot. `termux-wake-lock` (already used by the control scripts)
keeps Termux from being frozen.

## Quick smoke test

```bash
curl -s http://127.0.0.1:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","content":"Say OK and nothing else."}]}'
```

The ZCode app (offline mode → "Open local AI agent") loads
`http://127.0.0.1:8080` in its WebView, so the same server is the phone's
chat UI when the PC is off.
