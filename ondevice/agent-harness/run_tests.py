#!/usr/bin/env python3
"""PC test suite for the on-device agent harness.

Starts a scripted mock model + a mock ZphoneServer in-process, then runs the
harness end-to-end against them. No Android device required.

Run:  python run_tests.py
"""

import json
import os
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import agent  # noqa: E402

PNG_1PX = bytes.fromhex(
    "89504e470d0a1a0a0000000d494844520000000100000001080600000"
    "01f15c4890000000d49444154789c626001000000ffff030000060005"
    "57bfabd40000000049454e44ae426082")


def _make_device_server(log):
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
            if self.path == "/status":
                self._send(200, {"status": "ok", "device": "TestPhone",
                                 "battery": 88, "screen_on": True,
                                 "locked": False, "accessibility": True})
            elif self.path.startswith("/shot.png"):
                self.send_response(200)
                self.send_header("Content-Type", "image/png")
                self.send_header("Content-Length", str(len(PNG_1PX)))
                self.end_headers()
                self.wfile.write(PNG_1PX)
            else:
                self._send(404, {"status": "error"})

        def do_POST(self):
            length = int(self.headers.get("Content-Length", 0))
            body = json.loads(self.rfile.read(length).decode("utf-8")
                              or "{}")
            log.append(body)
            self._send(200, {"status": "ok",
                             "result": "OK %s" % body.get("action")})

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def _make_model_server(script):
    state = {"calls": 0}

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
            state["calls"] += 1
            if state["calls"] > len(script):
                self._send(500, {"error": "script exhausted"})
                return
            entry = script[state["calls"] - 1]
            if "content" in entry and "tool_calls" not in entry:
                message = {"role": "assistant",
                           "content": entry["content"]}
            else:
                calls = []
                for call in entry.get("tool_calls", []):
                    calls.append({
                        "id": "call-%d" % len(calls), "type": "function",
                        "function": {"name": call["name"],
                                     "arguments": json.dumps(
                                         call.get("arguments", {}))}})
                message = {"role": "assistant",
                           "content": entry.get("content", ""),
                           "tool_calls": calls}
            self._send(200, {"id": "m", "object": "chat.completion",
                             "model": "mock",
                             "choices": [{"index": 0, "message": message,
                                          "finish_reason": "stop"}]})

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, state


class HarnessTests(unittest.TestCase):
    def setUp(self):
        self.device_log = []
        self.device = _make_device_server(self.device_log)
        self.tmp = tempfile.mkdtemp(prefix="zagent-test-")

    def tearDown(self):
        self.device.shutdown()

    def _cfg(self, model_port):
        return {
            "model_base": "http://127.0.0.1:%d" % model_port,
            "zphone_base": "http://127.0.0.1:%d"
                           % self.device.server_address[1],
            "zphone_token": "test-token",
            "confirm_destructive": False,
            "max_steps": 8,
            "transcript_dir": self.tmp,
        }

    def _run(self, cfg, goal, allow=False):
        lines = []
        harness = agent.Harness(cfg, allow_destructive=allow,
                                log=lines.append)
        code = harness.run(goal)
        return code, lines

    def test_native_tool_call_executes_and_finishes(self):
        model, state = _make_model_server([
            {"tool_calls": [{"name": "open_app",
                             "arguments": {"app": "youtube"}}]},
            {"tool_calls": [{"name": "finish",
                             "arguments": {"summary": "opened"}}]},
        ])
        code, lines = self._run(self._cfg(model.server_address[1]),
                                "open youtube")
        self.assertEqual(code, 0)
        self.assertIn({"action": "launch_app", "app": "youtube"},
                      self.device_log)
        self.assertIn("final: opened", lines)

    def test_json_in_content_is_parsed(self):
        model, _ = _make_model_server([
            {"content": 'Sure! {"name": "device_status", "arguments": {}}'},
            {"tool_calls": [{"name": "finish",
                             "arguments": {"summary": "done"}}]},
        ])
        cfg = self._cfg(model.server_address[1])
        code, _ = self._run(cfg, "check status")
        self.assertEqual(code, 0)
        # device_status is a GET /status (not logged in the POST log): prove
        # the parsed call really executed by reading the transcript's
        # observation, which carries the mock device's status payload.
        run_files = sorted(name for name in os.listdir(self.tmp)
                           if name.startswith("run-") and
                           name.endswith(".jsonl"))
        self.assertTrue(run_files)
        with open(os.path.join(self.tmp, run_files[-1]),
                  encoding="utf-8") as handle:
            entries = [json.loads(line) for line in handle if line.strip()]
        observations = [entry["data"] for entry in entries
                        if entry["kind"] == "observation"]
        self.assertTrue(any("TestPhone" in str(observation)
                            for observation in observations), entries)

    def test_qwen_tool_call_tags_are_parsed(self):
        model, _ = _make_model_server([
            {"content": '<tool_call>\n{"name": "torch", '
                        '"arguments": {"state": "on"}}\n</tool_call>'},
            {"tool_calls": [{"name": "finish",
                             "arguments": {"summary": "torch on"}}]},
        ])
        code, _ = self._run(self._cfg(model.server_address[1]), "torch on")
        self.assertEqual(code, 0)
        self.assertIn({"action": "torch", "state": "on"}, self.device_log)

    def test_prose_tool_call_line_is_parsed(self):
        model, _ = _make_model_server([
            {"content": 'Tool call: torch({"state": "on"})'},
            {"tool_calls": [{"name": "finish",
                             "arguments": {"summary": "torch on"}}]},
        ])
        code, _ = self._run(self._cfg(model.server_address[1]), "torch on")
        self.assertEqual(code, 0)
        self.assertIn({"action": "torch", "state": "on"}, self.device_log)

    def test_prose_answer_gets_one_repair_then_finish(self):
        model, state = _make_model_server([
            {"content": "I would open youtube for you."},
            {"tool_calls": [{"name": "finish",
                             "arguments": {"summary": "ok"}}]},
        ])
        code, lines = self._run(self._cfg(model.server_address[1]),
                                "open youtube")
        self.assertEqual(code, 0)
        self.assertEqual(state["calls"], 2)
        self.assertIn("final: ok", lines)

    def test_destructive_blocked_without_flag(self):
        model, _ = _make_model_server([
            {"tool_calls": [{"name": "type_text",
                             "arguments": {"text": "hi"}}]},
            {"tool_calls": [{"name": "finish",
                             "arguments": {"summary": "gave up"}}]},
        ])
        code, lines = self._run(self._cfg(model.server_address[1]),
                                "type hi")
        self.assertEqual(code, 0)
        self.assertNotIn("text", [entry.get("action")
                                  for entry in self.device_log])
        self.assertIn("final: gave up", lines)

    def test_destructive_allowed_with_flag(self):
        model, _ = _make_model_server([
            {"tool_calls": [{"name": "type_text",
                             "arguments": {"text": "hi"}}]},
            {"tool_calls": [{"name": "finish",
                             "arguments": {"summary": "typed"}}]},
        ])
        code, _ = self._run(self._cfg(model.server_address[1]),
                            "type hi", allow=True)
        self.assertEqual(code, 0)
        self.assertIn({"action": "text", "text": "hi"}, self.device_log)

    def test_power_dialog_is_guarded(self):
        model, _ = _make_model_server([
            {"tool_calls": [{"name": "navigate",
                             "arguments": {"where": "power"}}]},
            {"tool_calls": [{"name": "finish",
                             "arguments": {"summary": "done"}}]},
        ])
        code, _ = self._run(self._cfg(model.server_address[1]), "power")
        self.assertEqual(code, 0)
        self.assertNotIn("global", [entry.get("action")
                                    for entry in self.device_log])

    def test_model_down_exit_code_and_guidance(self):
        code, lines = self._run(self._cfg(1), "anything")  # port 1: dead
        self.assertEqual(code, 2)
        self.assertTrue(any("MODEL SERVER DOWN" in line for line in lines))
        self.assertTrue(any("start-model-server.sh" in line
                            for line in lines))

    def test_device_down_guidance(self):
        model, _ = _make_model_server([
            {"tool_calls": [{"name": "open_app",
                             "arguments": {"app": "youtube"}}]},
        ])
        cfg = self._cfg(model.server_address[1])
        cfg["zphone_base"] = "http://127.0.0.1:1"  # dead port
        cfg["zphone_token"] = "t"
        lines = []
        harness = agent.Harness(cfg, log=lines.append)
        code = harness.run("open youtube")
        self.assertEqual(code, 3)
        self.assertTrue(any("DEVICE BRIDGE DOWN" in line for line in lines))

    def test_screenshot_saves_png(self):
        model, _ = _make_model_server([
            {"tool_calls": [{"name": "screenshot", "arguments": {}}]},
            {"tool_calls": [{"name": "finish",
                             "arguments": {"summary": "shot"}}]},
        ])
        code, lines = self._run(self._cfg(model.server_address[1]),
                                "take a screenshot")
        self.assertEqual(code, 0)
        self.assertTrue(any("screenshot saved" in line for line in lines))

    def test_transcript_written(self):
        model, _ = _make_model_server([
            {"tool_calls": [{"name": "finish",
                             "arguments": {"summary": "done"}}]},
        ])
        code, _ = self._run(self._cfg(model.server_address[1]), "hello")
        self.assertEqual(code, 0)
        files = os.listdir(self.tmp)
        self.assertTrue(any(name.startswith("run-") and
                            name.endswith(".jsonl") for name in files),
                        files)


if __name__ == "__main__":
    unittest.main(verbosity=2)
