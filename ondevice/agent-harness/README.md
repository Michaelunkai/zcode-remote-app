# ondevice/agent-harness — the AI agent that runs ON the phone

This is the phone's own agent loop: with the PC off, a goal typed in Termux is
planned by the **on-device model** (`../model-runtime`, llama-server on
`127.0.0.1:8080`) and executed **on the phone itself** through the ZCode app's
existing device-control engine (ZphoneServer, token-authed HTTP on
`127.0.0.1:8790`). Nothing here needs a PC, the internet, or a cloud API.

```
goal (Termux) → agent.py → model tool-call (127.0.0.1:8080)
                          → ZphoneServer exec (127.0.0.1:8790, app bridge)
                          → observation → next tool call → ... → finish
```

## Files

| File | Purpose |
|---|---|
| `agent.py` | The loop: stdlib-only Python, robust tool-call parsing (native `tool_calls`, Qwen `<tool_call>` tags, JSON-in-content, one repair retry), JSONL transcript per run |
| `mock_model.py` | Scripted OpenAI-compatible mock for PC-side tests |
| `run_tests.py` | 11 end-to-end PC tests (tool execution, parsing fallbacks, repair, guardrails, server-down/device-down guidance, transcripts) |
| `config.example.json` | Template — copy to `config.json`, paste the panel token |

## Install on the phone (inside Termux)

```bash
# copy this directory to ~/ondevice/agent-harness (adb push /sdcard/Download, then cp)
bash ~/ondevice/agent-harness/deploy-to-termux.sh
cp ~/ondevice/agent-harness/config.example.json ~/ondevice/agent-harness/config.json
# edit config.json: paste the token from the ZCode app's device panel (bridge card)
```

Prereq: the ZCode app's agent bridge is enabled (device panel → bridge card)
and the model server is up (`bash ~/ondevice/model-runtime/start-model-server.sh`).

## Run

```bash
python ~/ondevice/agent-harness/agent.py "check the battery status"
python ~/ondevice/agent-harness/agent.py "open the clock app"
python ~/ondevice/agent-harness/agent.py "type good morning" --allow-destructive
```

Exit codes: `0` finished · `1` max steps · `2` model server down (with the
exact start command printed) · `3` device bridge down (with panel guidance).

## Safety

- Guarded actions (`type_text`, power dialog) are **blocked** unless
  `--allow-destructive` is passed or `confirm_destructive: true` is set.
- Every model turn, tool call, and observation is appended to
  `transcripts/run-<timestamp>.jsonl` — every action is auditable.
- The model server and the device bridge are loopback/local-network only, both
  token-guarded.

## Tests (PC)

```bash
python run_tests.py    # 11 tests, no device needed
```
