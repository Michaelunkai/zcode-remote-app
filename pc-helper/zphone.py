#!/usr/bin/env python3
"""zphone — PC-side agent bridge to the ZCode Android app.

Lets the desktop agent control the user's phone (launch apps, play media,
tap/swipe/type, screenshots, status) over the phone's authenticated HTTP
bridge — no adb, no USB, works on the LAN (and via a manually configured
routable address such as a Tailscale IP, from anywhere).

Config lives in ~/.zphone.json: {"host": "...", "port": 8765, "token": "..."}
Get host+token from the phone: ZCode -> long-press icon -> Device control ->
"PC agent bridge" section.

Usage:
  zphone status                       # identity + screen + foreground app
  zphone discover                     # scan the /24 for the bridge (needs token)
  zphone open <app>                   # e.g. zphone open youtube
  zphone song "<query>" [app]         # app: youtube|ytmusic|spotify (default youtube)
  zphone url <url>                    # open any URL on the phone
  zphone tap <x> <y> | swipe x1 y1 x2 y2 [ms]
  zphone text "<text>"                # type into the focused field
  zphone back|home|recents|notifications|quicksettings|power
  zphone shot                         # screenshot -> saved in phone Pictures/Screenshots
  zphone key play|next|prev | vol up|down|mute
  zphone torch on|off | brightness <1-255>
  zphone exec <action> <json-fields>  # raw: zphone exec launch_app {"app":"youtube"}
  zphone seturl <https://... | @file> # rotation heal: push a fresh session URL
                                      # to the phone app (no manual re-pairing)
"""
import json
import os
import socket
import sys
import threading
import time
import urllib.request

CONFIG_PATH = os.path.join(os.path.expanduser("~"), ".zphone.json")
PORT = 8790
TIMEOUT = 25


def load_config():
    if os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH, "r", encoding="utf-8") as handle:
            return json.load(handle)
    return {}


def save_config(config):
    with open(CONFIG_PATH, "w", encoding="utf-8") as handle:
        json.dump(config, handle, indent=2)


def request(host, token, path, body=None, timeout=TIMEOUT):
    url = f"http://{host}:{PORT}{path}"
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method="POST" if data else "GET")
    req.add_header("Authorization", f"Bearer {token}")
    if data:
        req.add_header("Content-Type", "application/json; charset=utf-8")
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def try_connect(host, token, timeout=2.0):
    try:
        with socket.create_connection((host, PORT), timeout=timeout):
            status = request(host, token, "/status", timeout=timeout)
            return status
    except Exception:
        return None


def discover(token, my_lan_prefix="192.168.1."):
    """Concurrent /24 probe for the bridge; returns (host, status) or (None, None)."""
    config = load_config()
    if config.get("host"):
        status = try_connect(config["host"], token)
        if status:
            return config["host"], status
    found = {}
    lock = threading.Lock()

    def probe(last_octet):
        host = f"{my_lan_prefix}{last_octet}"
        status = try_connect(host, token, timeout=0.7)
        if status:
            with lock:
                found[host] = status

    threads = []
    for octet in range(1, 255):
        thread = threading.Thread(target=probe, args=(octet,), daemon=True)
        thread.start()
        threads.append(thread)
    for thread in threads:
        thread.join()
    if found:
        host = next(iter(found))
        return host, found[host]
    return None, None


def main():
    args = sys.argv[1:]
    config = load_config()
    token = config.get("token", "")
    if len(args) >= 2 and args[0] == "set":
        config.update({"host": args[1], "port": int(args[2]) if len(args) > 2 else PORT,
                       "token": args[3] if len(args) > 3 else token})
        save_config(config)
        print(f"saved: http://{config['host']}:{config['port']}")
        return
    if len(args) >= 3 and args[0] == "token":
        config["token"] = args[2]
        if len(args) > 3:
            config["host"] = args[3]
        save_config(config)
        print("token saved")
        return

    action = args[0] if args else "status"
    body = None
    path = "/exec"

    if action == "shot":
        # save a live screenshot to a local file so the agent can see the phone
        out_file = args[1] if len(args) > 1 else os.path.join(os.path.expanduser("~"), "zphone-shot.png")
        host = config.get("host")
        if not host:
            print("bridge not found; run: zphone set <host> 8790 <token>")
            sys.exit(2)
        url = f"http://{host}:{PORT}/shot.png"
        req = urllib.request.Request(url)
        req.add_header("Authorization", f"Bearer {token}")
        with urllib.request.urlopen(req, timeout=15) as response:
            data = response.read()
        with open(out_file, "wb") as handle:
            handle.write(data)
        print(f"saved {len(data)} bytes -> {out_file}")
        return
    if action == "discover":
        host, status = discover(token)
        if not host:
            print("bridge not found on the network (phone asleep? different network? token?)")
            sys.exit(2)
        config["host"] = host
        save_config(config)
        print(f"found at {host}")
        print(json.dumps(status, ensure_ascii=False, indent=2))
        return
    if action == "status":
        if not config.get("host"):
            host, status = discover(token)
            if not host:
                print("bridge not found; run: zphone set <host> 8765 <token>")
                sys.exit(2)
            config["host"] = host
            save_config(config)
            print(json.dumps(status, ensure_ascii=False, indent=2))
            return
        try:
            print(json.dumps(request(config["host"], token, "/status"),
                             ensure_ascii=False, indent=2))
        except Exception as error:
            print(f"unreachable ({error}) — retrying discovery...")
            host, status = discover(token)
            if not host:
                print("bridge not found")
                sys.exit(2)
            config["host"] = host
            save_config(config)
            print(json.dumps(status, ensure_ascii=False, indent=2))
        return

    def arg(index):
        return args[index] if len(args) > index else ""

    if action == "seturl":
        # Rotation heal: push the freshly minted session URL to the phone app.
        # '@file' reads the URL from a file so the token never hits argv/logs.
        url = arg(1)
        if url.startswith("@"):
            with open(url[1:], "r", encoding="utf-8") as handle:
                url = handle.read().strip()
        if not url.startswith("http"):
            print("usage: zphone seturl <https://... | @file>")
            sys.exit(1)
        host = config.get("host")
        if not host:
            host, _ = discover(token)
            if not host:
                print("bridge not found; run: zphone set <host> 8765 <token>")
                sys.exit(2)
            config["host"] = host
            save_config(config)
        try:
            result = request(host, token, "/seturl", {"url": url})
        except Exception as error:
            print(f"error: {error}")
            sys.exit(1)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return

    if action == "open":
        body = {"action": "launch_app", "app": arg(1)}
    elif action == "song":
        body = {"action": "song", "query": arg(1), "app": arg(2) or "youtube"}
    elif action == "url":
        body = {"action": "open_url", "url": arg(1)}
    elif action == "tap":
        body = {"action": "tap", "x": int(arg(1)), "y": int(arg(2))}
    elif action == "swipe":
        body = {"action": "swipe", "x1": int(arg(1)), "y1": int(arg(2)),
                "x2": int(arg(3)), "y2": int(arg(4)), "ms": int(arg(5) or 400)}
    elif action == "text":
        body = {"action": "text", "text": arg(1)}
    elif action in ("back", "home", "recents", "notifications", "quicksettings", "power"):
        body = {"action": "global", "where": action}
    elif action == "shot":
        body = {"action": "screenshot"}
    elif action == "key":
        body = {"action": "media_key", "key": arg(1) or "play"}
    elif action == "vol":
        body = {"action": "volume", "dir": arg(1) or "up"}
    elif action == "torch":
        body = {"action": "torch", "state": arg(1) or "on"}
    elif action == "brightness":
        body = {"action": "brightness", "value": int(arg(1) or 128)}
    elif action == "foreground":
        body = {"action": "foreground"}
    else:
        print(__doc__)
        sys.exit(1)

    if action == "exec":
        body = json.loads(args[2]) if len(args) > 2 else {}

    host = config.get("host")
    if not host:
        host, _ = discover(token)
        if not host:
            print("bridge not found; run: zphone set <host> 8765 <token>")
            sys.exit(2)
        config["host"] = host
        save_config(config)
    try:
        result = request(host, token, path, body)
    except Exception as error:
        print(f"error: {error}")
        sys.exit(1)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
