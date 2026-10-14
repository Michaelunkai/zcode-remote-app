#!/usr/bin/env python3
"""
ZCode on-device agent harness — the AI that runs ON THE PHONE (Termux), with no
PC required:

    goal -> on-device model tool-calls (http://127.0.0.1:8080, llama-server)
         -> device actions via the app's own ZphoneServer (token-authed HTTP)
         -> observations fed back, multi-step, until the model calls finish()

stdlib only (Termux python has no wheels pain). Every run appends a JSONL
transcript under transcripts/ so any action is auditable afterwards.

Usage:
    python agent.py "open youtube and play lofi beats"
    python agent.py "check the battery status" --max-steps 4
    python agent.py "type hello" --allow-destructive     # guarded actions

Config: config.json next to this file (see config.example.json). The Zphone
token comes from the app's device panel; it is never hardcoded.
"""

import datetime
import json
import os
import re
import socket
import sys
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))

DEFAULT_CONFIG = {
    "model_base": "http://127.0.0.1:8080",
    "zphone_base": "http://127.0.0.1:8790",
    "zphone_token": "",
    "confirm_destructive": False,
    "max_steps": 8,
    "model_timeout": 180,
    "transcript_dir": os.path.join(HERE, "transcripts"),
}

# Actions that can reach other humans or power the device off. Blocked unless
# the operator passes --allow-destructive (or confirm_destructive=true).
DESTRUCTIVE = {"type_text", "global:power"}

TOOLS = [
    {"type": "function", "function": {"name": "open_app", "description": "Launch an app by name or package (e.g. 'youtube', 'spotify', 'gmail', 'clock')", "parameters": {"type": "object", "properties": {"app": {"type": "string"}}, "required": ["app"]}}},
    {"type": "function", "function": {"name": "open_url", "description": "Open a URL on the phone", "parameters": {"type": "object", "properties": {"url": {"type": "string"}}, "required": ["url"]}}},
    {"type": "function", "function": {"name": "play_media", "description": "Search and play media in an app", "parameters": {"type": "object", "properties": {"query": {"type": "string"}, "app": {"type": "string", "description": "youtube | ytmusic | spotify (optional)"}}, "required": ["query"]}}},
    {"type": "function", "function": {"name": "tap", "description": "Tap the screen at pixel coordinates", "parameters": {"type": "object", "properties": {"x": {"type": "integer"}, "y": {"type": "integer"}}, "required": ["x", "y"]}}},
    {"type": "function", "function": {"name": "swipe", "description": "Swipe across the screen", "parameters": {"type": "object", "properties": {"x1": {"type": "integer"}, "y1": {"type": "integer"}, "x2": {"type": "integer"}, "y2": {"type": "integer"}, "ms": {"type": "integer"}}, "required": ["x1", "y1", "x2", "y2"]}}},
    {"type": "function", "function": {"name": "type_text", "description": "Type text into the focused field. GUARDED: may send messages.", "parameters": {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]}}},
    {"type": "function", "function": {"name": "navigate", "description": "Device navigation: back | home | recents | notifications | quicksettings | power(guarded)", "parameters": {"type": "object", "properties": {"where": {"type": "string", "enum": ["back", "home", "recents", "notifications", "quicksettings", "power"]}}, "required": ["where"]}}},
    {"type": "function", "function": {"name": "screenshot", "description": "Capture the screen to a PNG file and report its size", "parameters": {"type": "object", "properties": {}}}},
    {"type": "function", "function": {"name": "media_key", "description": "Media control key: play | pause | next | previous", "parameters": {"type": "object", "properties": {"key": {"type": "string"}}, "required": ["key"]}}},
    {"type": "function", "function": {"name": "volume", "description": "Media volume: up | down | mute", "parameters": {"type": "object", "properties": {"dir": {"type": "string"}}, "required": ["dir"]}}},
    {"type": "function", "function": {"name": "torch", "description": "Flashlight: on | off", "parameters": {"type": "object", "properties": {"state": {"type": "string"}}, "required": ["state"]}}},
    {"type": "function", "function": {"name": "set_brightness", "description": "Set screen brightness 0-255", "parameters": {"type": "object", "properties": {"value": {"type": "integer"}}, "required": ["value"]}}},
    {"type": "function", "function": {"name": "device_status", "description": "Read device status: battery, screen, locked, volume, foreground app, accessibility", "parameters": {"type": "object", "properties": {}}}},
    {"type": "function", "function": {"name": "finish", "description": "Call when the goal is achieved, with a short summary of what was done", "parameters": {"type": "object", "properties": {"summary": {"type": "string"}}, "required": ["summary"]}}},
]

SYSTEM_PROMPT = (
    "You are the on-device assistant of an Android phone. You control the phone "
    "only through the provided tools, ONE tool call per reply. After each tool "
    "call you receive an 'Observation'. Prefer safe, reversible actions. Use "
    "device_status to see battery/foreground state. When the goal is achieved, "
    "call finish with a one-line summary. If something is impossible, call "
    "finish and say why."
)


# ---------------------------------------------------------------- transport

def _http_json(url, payload=None, token=None, timeout=60):
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(url, data=data)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


class ModelDown(Exception):
    pass


class DeviceDown(Exception):
    pass


class Zphone:
    """Client for the app's ZphoneServer (status/exec/shot.png, bearer token)."""

    def __init__(self, base, token, timeout=30):
        self.base = base.rstrip("/")
        self.token = token
        self.timeout = timeout

    def status(self):
        try:
            return _http_json(self.base + "/status", token=self.token,
                              timeout=self.timeout)
        except (urllib.error.URLError, socket.timeout, OSError) as exc:
            raise DeviceDown("%s unreachable (%s)" % (self.base, exc))

    def shot(self, out_path):
        req = urllib.request.Request(self.base + "/shot.png")
        req.add_header("Authorization", "Bearer " + self.token)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as response:
                data = response.read()
        except (urllib.error.URLError, socket.timeout, OSError) as exc:
            raise DeviceDown("%s/shot.png failed (%s)" % (self.base, exc))
        with open(out_path, "wb") as handle:
            handle.write(data)
        return len(data)

    def exec(self, payload):
        try:
            return _http_json(self.base + "/exec", payload=payload,
                              token=self.token, timeout=self.timeout)
        except (urllib.error.URLError, socket.timeout, OSError) as exc:
            raise DeviceDown("%s/exec failed (%s)" % (self.base, exc))


def chat(model_base, messages, timeout=180):
    body = {
        "model": "local",
        "messages": messages,
        "tools": TOOLS,
        "tool_choice": "auto",
        "temperature": 0.2,
        "max_tokens": 400,
    }
    try:
        return _http_json(model_base.rstrip("/") + "/v1/chat/completions",
                          payload=body, timeout=timeout)
    except (urllib.error.URLError, socket.timeout, OSError) as exc:
        raise ModelDown("%s unreachable (%s)" % (model_base, exc))


# ------------------------------------------------- tool-call extraction

def extract_tool(message):
    """Returns (name, args) from an OpenAI message: native tool_calls first,
    then robust fallbacks for small on-device models (Qwen <tool_call> tags,
    fenced JSON, bare JSON objects)."""
    calls = message.get("tool_calls")
    if calls:
        call = calls[0]
        fn = call.get("function", call)
        try:
            return fn.get("name"), json.loads(fn.get("arguments") or "{}")
        except (ValueError, AttributeError):
            pass
    text = message.get("content") or ""
    if not text.strip():
        return None, None
    # The small on-device model often drops native tool calls after the first
    # step and answers prose in the exact shape "Tool call: name({...})".
    prose = re.search(r"Tool call:\s*(\w+)\s*\(", text)
    if prose:
        args = _first_json_object(text[prose.start():])
        if isinstance(args, dict):
            return prose.group(1), args
    for block in re.findall(r"<tool_call>\s*(\{.*?\})\s*</tool_call>",
                            text, re.S) or [text]:
        candidate = _first_json_object(block)
        if candidate and ("name" in candidate or "tool" in candidate):
            name = candidate.get("name") or candidate.get("tool")
            args = candidate.get("arguments") or candidate.get("params") or {}
            if isinstance(args, str):
                try:
                    args = json.loads(args)
                except ValueError:
                    args = {}
            if isinstance(name, str) and isinstance(args, dict):
                return name, args
    return None, None


def _first_json_object(text):
    """First balanced top-level {...} in text, tolerating fenced blocks."""
    fence = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", text, re.S)
    if fence:
        try:
            return json.loads(fence.group(1))
        except ValueError:
            pass
    start = text.find("{")
    while start != -1:
        depth = 0
        in_string = False
        escaped = False
        for index in range(start, len(text)):
            char = text[index]
            if in_string:
                if escaped:
                    escaped = False
                elif char == "\\":
                    escaped = True
                elif char == '"':
                    in_string = False
                continue
            if char == '"':
                in_string = True
            elif char == "{":
                depth += 1
            elif char == "}":
                depth -= 1
                if depth == 0:
                    try:
                        return json.loads(text[start:index + 1])
                    except ValueError:
                        break
        start = text.find("{", start + 1)
    return None


# ------------------------------------------------------------- execution

ACTION_MAP = {
    "open_app": lambda a: {"action": "launch_app", "app": a.get("app", "")},
    "open_url": lambda a: {"action": "open_url", "url": a.get("url", "")},
    "play_media": lambda a: {"action": "song", "query": a.get("query", ""),
                             "app": a.get("app", "")},
    "tap": lambda a: {"action": "tap", "x": a.get("x", 0), "y": a.get("y", 0)},
    "swipe": lambda a: {"action": "swipe", "x1": a.get("x1", 0),
                        "y1": a.get("y1", 0), "x2": a.get("x2", 0),
                        "y2": a.get("y2", 0), "ms": a.get("ms", 300)},
    "type_text": lambda a: {"action": "text", "text": a.get("text", "")},
    "navigate": lambda a: {"action": "global", "where": a.get("where", "")},
    "media_key": lambda a: {"action": "media_key", "key": a.get("key", "")},
    "volume": lambda a: {"action": "volume", "dir": a.get("dir", "")},
    "torch": lambda a: {"action": "torch", "state": a.get("state", "")},
    "set_brightness": lambda a: {"action": "brightness",
                                 "value": a.get("value", 128)},
}


class Harness:
    def __init__(self, cfg, allow_destructive=False, log=None):
        self.cfg = cfg
        self.allow = allow_destructive or cfg.get("confirm_destructive")
        self.zphone = Zphone(cfg["zphone_base"], cfg.get("zphone_token", ""))
        self.transcript_dir = cfg.get("transcript_dir") or os.path.join(HERE, "transcripts")
        self.log = log if log is not None else _stdout_log
        os.makedirs(self.transcript_dir, exist_ok=True)
        stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
        self.transcript_path = os.path.join(
            self.transcript_dir, "run-%s.jsonl" % stamp)

    def _record(self, kind, data):
        entry = {"ts": datetime.datetime.now().isoformat(timespec="seconds"),
                 "kind": kind, "data": data}
        with open(self.transcript_path, "a", encoding="utf-8") as handle:
            handle.write(json.dumps(entry, ensure_ascii=False) + "\n")

    def execute(self, name, args):
        """Runs one tool; returns the observation string (never raises except
        DeviceDown, which the loop reports as guidance)."""
        if name == "finish":
            return "FINAL: " + str(args.get("summary", ""))
        if name == "screenshot":
            out = os.path.join(self.transcript_dir,
                               "shot-%d.png" % int(datetime.datetime.now()
                                                   .timestamp()))
            size = self.zphone.shot(out)
            return "screenshot saved: %s (%d bytes)" % (out, size)
        if name == "device_status":
            return json.dumps(self.zphone.status(), ensure_ascii=False)
        guard_key = "global:%s" % args.get("where") if name == "navigate" else name
        if guard_key in DESTRUCTIVE and not self.allow:
            return ("BLOCKED: '%s' is a guarded action; rerun the agent with "
                    "--allow-destructive to permit it" % name)
        payload = ACTION_MAP[name](args)
        result = self.zphone.exec(payload)
        text = json.dumps(result, ensure_ascii=False)
        return text[:500]

    def run(self, goal, max_steps=None):
        messages = [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": goal},
        ]
        steps = max_steps or self.cfg.get("max_steps", 8)
        self._record("goal", goal)
        self.log("goal: %s" % goal)
        repair_used = False
        for step in range(1, steps + 1):
            try:
                response = chat(self.cfg["model_base"], messages,
                                timeout=self.cfg.get("model_timeout", 180))
            except ModelDown as exc:
                self._record("model_down", str(exc))
                self.log("MODEL SERVER DOWN: %s" % exc)
                self.log("Start it on the phone in Termux:")
                self.log("  bash ~/ondevice/model-runtime/start-model-server.sh")
                return 2
            message = response["choices"][0]["message"]
            self._record("model", message)
            name, args = extract_tool(message)
            if name is None:
                if not repair_used and (message.get("content") or "").strip():
                    # Small model answered in prose: give one repair round.
                    repair_used = True
                    messages.append({"role": "assistant",
                                     "content": message.get("content") or ""})
                    messages.append({"role": "user", "content":
                                     "Reply with ONLY one JSON tool call: "
                                     '{"name": "...", "arguments": {...}}. '
                                     "Use finish when done."})
                    self._record("repair", step)
                    continue
                self._record("final", message.get("content") or "")
                self.log("final: %s" % (message.get("content") or "").strip())
                return 0
            self.log("step %d: %s(%s)" % (step, name,
                                          json.dumps(args, ensure_ascii=False)))
            if name == "finish":
                self._record("final", args.get("summary", ""))
                self.log("final: %s" % args.get("summary", ""))
                return 0
            try:
                observation = self.execute(name, args)
            except DeviceDown as exc:
                self._record("device_down", str(exc))
                self.log("DEVICE BRIDGE DOWN: %s" % exc)
                self.log("Open the ZCode app's device panel, enable the agent "
                         "bridge, and put its token in config.json "
                         "(zphone_token).")
                return 3
            self._record("observation", {"tool": name, "result": observation})
            self.log("  observation: %s" % observation[:200])
            messages.append({"role": "assistant",
                             "content": "Tool call: %s(%s)" % (
                                 name, json.dumps(args, ensure_ascii=False))})
            messages.append({"role": "user",
                             "content": "Observation: %s" % observation})
        self._record("final", "max steps reached")
        self.log("stopped: max steps (%d) reached without finish" % steps)
        return 1


def _stdout_log(line):
    print(line, flush=True)


def load_config(path=None):
    cfg = dict(DEFAULT_CONFIG)
    config_path = path or os.path.join(HERE, "config.json")
    if os.path.exists(config_path):
        with open(config_path, "r", encoding="utf-8") as handle:
            cfg.update(json.load(handle))
    return cfg


def main(argv):
    goal_parts = []
    allow_destructive = False
    config_path = None
    max_steps = None
    index = 0
    while index < len(argv):
        arg = argv[index]
        if arg == "--allow-destructive":
            allow_destructive = True
        elif arg == "--config" and index + 1 < len(argv):
            index += 1
            config_path = argv[index]
        elif arg == "--max-steps" and index + 1 < len(argv):
            index += 1
            max_steps = int(argv[index])
        elif arg == "--help" or arg == "-h":
            print(__doc__)
            return 0
        else:
            goal_parts.append(arg)
        index += 1
    if not goal_parts:
        print('usage: agent.py "goal" [--allow-destructive] '
              "[--max-steps N] [--config path]", file=sys.stderr)
        return 4
    cfg = load_config(config_path)
    if max_steps:
        cfg["max_steps"] = max_steps
    if not cfg.get("zphone_token"):
        print("missing zphone_token in config.json — get it from the ZCode "
              "app's device panel (bridge card)", file=sys.stderr)
        return 4
    return Harness(cfg, allow_destructive).run(" ".join(goal_parts))


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
