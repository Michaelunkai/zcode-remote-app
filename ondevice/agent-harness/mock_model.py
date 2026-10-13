#!/usr/bin/env python3
"""Scripted OpenAI-compatible mock model for PC-side harness tests.

Serves:
    GET  /health                -> {"status": "ok", "model": "mock"}
    POST /v1/chat/completions   -> next scripted reply (list order)

Scenario file (JSON list); each entry is either:
    {"tool_calls": [{"name": "open_app", "arguments": {"app": "youtube"}}]}
    {"content": "plain text answer"}

Usage: MOCK_SCRIPT=scenario.json python mock_model.py [port]
"""

import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SCRIPT = []


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def _send(self, code, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            self._send(200, {"status": "ok", "model": "mock"})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        self.rfile.read(length)
        if not SCRIPT:
            self._send(500, {"error": "mock script exhausted"})
            return
        entry = SCRIPT.pop(0)
        if "content" in entry and "tool_calls" not in entry:
            message = {"role": "assistant", "content": entry["content"]}
        else:
            calls = []
            for call in entry.get("tool_calls", []):
                calls.append({
                    "id": "call-%d" % len(calls),
                    "type": "function",
                    "function": {
                        "name": call["name"],
                        "arguments": json.dumps(call.get("arguments", {})),
                    },
                })
            message = {"role": "assistant", "content": entry.get("content", ""),
                       "tool_calls": calls}
        self._send(200, {"id": "mock", "object": "chat.completion",
                         "model": "mock",
                         "choices": [{"index": 0, "message": message,
                                      "finish_reason": "stop"}]})


def main():
    global SCRIPT
    path = os.environ.get("MOCK_SCRIPT")
    if not path:
        print("MOCK_SCRIPT=<scenario.json> required", file=sys.stderr)
        return 2
    with open(path, "r", encoding="utf-8") as handle:
        SCRIPT = json.load(handle)
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8099
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print("mock model on 127.0.0.1:%d with %d scripted replies"
          % (port, len(SCRIPT)), flush=True)
    server.serve_forever()


if __name__ == "__main__":
    sys.exit(main())
