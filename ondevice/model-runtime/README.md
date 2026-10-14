# ondevice/model-runtime — the AI model server on the phone

An OpenAI-compatible LLM server that runs **on the Galaxy S25 Ultra itself**
(Termux + Ollama, CPU). This is what makes the phone independent of the PC:
with the PC off, the ZCode app's offline mode loads this server, and the agent
harness (`../agent-harness`) drives the phone through it.

## Architecture (verified on-device 2026-10-14)

```
ZCode app (offline mode)  ─┐
agent harness (Termux)     ├─►  contract adapter  127.0.0.1:8080
any chat client (WebView) ─┘         │  /health · / (chat page) · /v1/* proxy
                                     ▼
                        ollama serve  127.0.0.1:11434  (INSIDE proot Debian)
                                     ▼
                        qwen3b = Qwen2.5-3B-Instruct Q4_K_M (~2 GB RAM)
```

- **Ollama static arm64 build, run inside proot Debian** — no compilation on
  the phone. (Building llama.cpp from source in this Termux was tried first
  and miscompiles at runtime — libc++ ABI mix from a half-upgraded package
  base. The ollama glibc binary also cannot execute directly in bionic
  Termux — it must run in the proot Debian guest at `/opt/ollama`, which
  `start-model-server.sh` handles. `install-termux-llama.sh` from the first
  attempt is kept for the record; use `install-termux-ollama.sh`.)
- **Contract adapter** (stdlib Python): `GET /health` → `{"status":"ok"}`,
  `GET /` → built-in offline chat page for the app's WebView, everything else
  proxied to Ollama (`/v1/chat/completions` included, tool calls work).
- Loopback only: nothing on the LAN can reach either port.

## Install (once, inside Termux on the phone)

```bash
pkg install -y git curl bash
mkdir -p ~/ondevice/model-runtime/models
# copy install-termux-ollama.sh + start-model-server.sh + contract-adapter.py
# into ~/ondevice/model-runtime (adb push /sdcard/Download then cp), then:
bash ~/ondevice/model-runtime/install-termux-ollama.sh
```

The installer downloads Ollama + the model automatically; both can be staged
offline instead (adb push the tarball and the GGUF, the installer picks them
up). GGUF source:
`https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF` — verified
`sha256 626b4a6678b86442240e33df819e00132d3ba7dddfe1cdc4fbb18e0a9615c62d`
against the phone copy.

## Start (every boot, or on demand)

```bash
bash ~/ondevice/model-runtime/start-model-server.sh
# -> SERVER_OK http://127.0.0.1:8080 (proot Debian ollama qwen3b behind adapter)
curl -s http://127.0.0.1:8080/health
```

Persistence: Termux:Boot is installed — drop a script into `~/.termux/boot/`
calling `start-model-server.sh`; `termux-wake-lock` keeps Termux alive.

## Smoke test

```bash
curl -s http://127.0.0.1:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"qwen3b","messages":[{"role":"user","content":"Say OK"}]}'
```

The ZCode app (offline mode → "Open local AI agent", or
`am start ... --ez open_local_agent true`) loads `http://127.0.0.1:8080`
in its WebView = the built-in chat page, so the phone gets a full offline
AI chat UI with the PC off.
