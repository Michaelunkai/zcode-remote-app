#!/usr/bin/env python3
"""zphone-watchdog — permanent zero-user session healer for the ZCode remote app.

Runs hidden at logon (pythonw, HKCU Run). Polls the phone app's bridge /status
every POLL seconds; whenever the session page is confirmed unhealthy — stale/
invalid link, orphaned sessions-menu, persistent desktop-offline, stuck relay
wizard, dead load, any non-session page — it mints a FRESH connect link from
the running ZCode desktop app (zphone-mint.ps1, zero taps, no QR, panel always
closed behind itself) and pushes it via the bridge's /seturl (the phone
reloads offscreen while backgrounded, visibly while the user is in the app).

Far-from-home: the phone is polled over the LAN first and, when configured,
via its Tailscale address (host_wan in ~/.zphone.json) — the heal pipeline
then works from any network, exactly like the session relay itself.

DESKTOP-DISTURBANCE LAW: a mint opens the app's "Mobile remote control"
flyout for ~1-2 s. To keep that a once-in-a-blue-moon event: failures back
off exponentially (2 min doubling to 30 min) and mints are capped at 12/hour,
so a broken desktop can never turn the watchdog into a panel-popping loop.
"""

import json
import os
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import zphone  # noqa: E402  (same dir; request()/load_config() reused)

LOG_PATH = os.path.join(HERE, "watchdog.log")
LOCK_PATH = os.path.join(HERE, "watchdog.lock")
MINT_PS1 = os.path.join(HERE, "zphone-mint.ps1")
URL_FILE = os.path.join(tempfile.gettempdir(), "zphone-mint-url.txt")

POLL = 10              # seconds between /status polls
MIN_MINT_GAP = 90      # a new mint invalidates the previous link — never churn
SETTLE = 25            # after a push, give the page time to load + handshake
LOG_MAX = 256 * 1024
MAX_MINTS_PER_HOUR = 3
MAX_MINTS_PER_DAY = 6
BACKOFF_BASE = 120     # seconds, doubling per consecutive mint failure
BACKOFF_MAX = 1800     # 30 min hard ceiling
PAIRING_FAIL_BACKOFF = 3600  # terminal not serving — retry the whole flow in 1 h

# (state, min age seconds) — a state must PERSIST this long before we mint.
# NO-POPUP DOCTRINE: the panel only opens when a FRESH link is the ONLY cure.
# desktop_offline / desktop_timedout (cloud terminal stuck, live-seen
# 2026-10-25) NEVER mint — the link outlives the outage and the phone's own
# retry loop reconnects the moment the terminal returns. If the link ALSO
# rotated during the outage, the page will resurface as invalid/menu and
# mint through those rules.
HEAL_RULES = [
    ("invalid", 0),
    ("menu", 240),
    ("nosession", 240),
    ("loading", 120),
    ("error", 150),
    ("connecting", 900),
]


def log(message):
    try:
        if os.path.exists(LOG_PATH) and os.path.getsize(LOG_PATH) > LOG_MAX:
            os.replace(LOG_PATH, LOG_PATH + ".old")
        stamp = time.strftime("%Y-%m-%d %H:%M:%S")
        with open(LOG_PATH, "a", encoding="utf-8") as handle:
            handle.write(f"{stamp} {message}\n")
    except OSError:
        pass


def pid_alive(pid):
    try:
        out = subprocess.run(
            ["tasklist", "/FI", f"PID eq {pid}", "/FO", "CSV", "/NH"],
            capture_output=True, text=True, timeout=10,
            creationflags=0x08000000).stdout
        return str(pid) in out
    except Exception:
        return False


def zcode_running():
    """Is the ZCode desktop app up? (CREATE_NO_WINDOW — never flash a console.)"""
    try:
        out = subprocess.run(
            ["tasklist", "/FI", "IMAGENAME eq ZCode.exe", "/FO", "CSV", "/NH"],
            capture_output=True, text=True, timeout=10,
            creationflags=0x08000000).stdout
        return "ZCode.exe" in out
    except Exception:
        return False


def acquire_lock():
    if os.path.exists(LOCK_PATH):
        try:
            with open(LOCK_PATH, "r", encoding="utf-8") as handle:
                old = int(handle.read().strip() or 0)
            if old and old != os.getpid() and pid_alive(old):
                return False  # another watchdog is already running
        except (OSError, ValueError):
            pass
    with open(LOCK_PATH, "w", encoding="utf-8") as handle:
        handle.write(str(os.getpid()))
    return True


def bridge_call(config, path, body=None):
    """Call the phone bridge on the LAN host, falling back to Tailscale.

    Returns (reply, host-that-answered) or raises the last error.
    """
    hosts = [config.get("host", "")]
    wan = config.get("host_wan", "")
    if wan and wan not in hosts:
        hosts.append(wan)
    last_error = None
    for host in hosts:
        if not host:
            continue
        try:
            return zphone.request(host, config.get("token", ""), path, body, timeout=8), host
        except Exception as error:
            last_error = error
    raise last_error or RuntimeError("no bridge host configured")


def run_mint_script(extra_args):
    """Runs the UIA mint script hidden. Returns its last stdout line."""
    try:
        result = subprocess.run(
            ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
             "-WindowStyle", "Hidden", "-File", MINT_PS1,
             "-OutFile", URL_FILE] + extra_args,
            capture_output=True, text=True, timeout=90,
            creationflags=0x08000000)
        line = (result.stdout or "").strip().splitlines()
        outcome = line[-1] if line else "mint: no-output"
        if result.returncode != 0:
            return f"mint: rc={result.returncode} ({outcome})"
        return outcome
    except Exception as error:
        return f"mint: error {type(error).__name__}"


def mint():
    """Mint with the pairing panel KEPT open — the phone pairs through it."""
    outcome = run_mint_script(["-NoClose"])
    if outcome != "mint: ok":
        log(f"mint failed: {outcome}")
        # Never leave the panel open on a failed mint.
        close = run_mint_script(["-CloseOnly"])
        if close != "mint: closed":
            log(f"panel close after failed mint: {close}")
        return False
    return os.path.exists(URL_FILE)


def close_panel():
    outcome = run_mint_script(["-CloseOnly"])
    if outcome != "mint: closed":
        log(f"panel close: {outcome}")
        return False
    return True


def push(config, host):
    """Reads the minted link from the temp file and /seturl's it (never argv)."""
    try:
        with open(URL_FILE, "r", encoding="utf-8") as handle:
            url = handle.read().strip()
        if not url.startswith("https://"):
            log("push skipped: mint file did not hold a fresh link")
            return False
        reply = zphone.request(host, config.get("token", ""), "/seturl",
                               {"url": url}, timeout=15)
        ok = reply.get("status") == "ok"
        log(f"push {'ok' if ok else 'failed'} via {host} ({reply.get('reloaded', '?')})")
        return ok
    except Exception as error:
        log(f"push error: {type(error).__name__}")
        return False


def heal_reason(status):
    if not status.get("has_url", True):
        return "no-url"
    page = status.get("page", "")
    try:
        age = int(status.get("page_age_s", -1))
    except (TypeError, ValueError):
        age = -1
    for state, need in HEAL_RULES:
        if page == state and age >= need:
            return f"{state}-{age}s"
    return None


def main():
    if not acquire_lock():
        return
    log("watchdog started")
    last_mint = 0.0
    mint_gap = MIN_MINT_GAP
    fail_streak = 0
    mint_times = []
    last_unreachable_log = 0.0
    last_cap_log = 0.0
    last_terminal_trouble = 0.0
    desktop_was_up = None
    try:
        while True:
            config = zphone.load_config()

            desktop_up = zcode_running()
            if desktop_was_up is None:
                desktop_was_up = desktop_up
            elif desktop_up and not desktop_was_up:
                log("desktop app restarted; unhealthy states will heal on their thresholds")
                desktop_was_up = desktop_up
            elif not desktop_up and desktop_was_up:
                log("desktop app gone; waiting (nothing to mint until it returns)")
                desktop_was_up = desktop_up

            try:
                status, host = bridge_call(config, "/status")
            except Exception as error:
                now = time.time()
                if now - last_unreachable_log > 60:
                    log(f"bridge unreachable: {type(error).__name__}")
                    last_unreachable_log = now
                time.sleep(POLL)
                continue

            # Terminal-trouble memory: while the page shows ANY terminal
            # state (offline/timedout/connecting), the link is alive and the
            # outage is cloud-side — minting cannot help and the panel stays
            # closed. Only after 15 quiet minutes do we trust that a
            # menu/nosession page really means the link itself is dead.
            page_now = status.get("page", "")
            if page_now in ("desktop_offline", "desktop_timedout", "connecting"):
                last_terminal_trouble = time.time()
            terminal_suspect = time.time() - last_terminal_trouble < 900

            reason = heal_reason(status)
            now = time.time()
            mint_times = [t for t in mint_times if now - t < 3600]
            day_mints = [t for t in mint_times if now - t < 86400]
            hourly_capped = len(mint_times) >= MAX_MINTS_PER_HOUR
            daily_capped = len(day_mints) >= MAX_MINTS_PER_DAY
            capped = hourly_capped or daily_capped
            if (reason and desktop_up and not capped and not terminal_suspect
                    and now - last_mint >= mint_gap):
                log(f"healing: {reason}")
                if mint() and push(config, host):
                    last_mint = time.time()
                    mint_times.append(last_mint)
                    # Pairing window: the phone connects through the open
                    # panel; the panel closes itself once paired. If the page
                    # never reaches ok, close the panel and back off.
                    paired = False
                    deadline = time.time() + 45
                    while time.time() < deadline:
                        time.sleep(5)
                        try:
                            after, _ = bridge_call(config, "/status")
                        except Exception:
                            continue
                        if after.get("page") == "ok":
                            paired = True
                            break
                    if paired:
                        fail_streak = 0
                        mint_gap = MIN_MINT_GAP
                        log("paired: page=ok, panel auto-closed")
                    else:
                        close_panel()
                        fail_streak += 1
                        mint_gap = PAIRING_FAIL_BACKOFF
                        log(f"pairing failed (terminal not serving); panel closed, "
                            f"next attempt in {mint_gap}s")
                else:
                    last_mint = time.time()
                    fail_streak += 1
                    mint_gap = min(BACKOFF_MAX, BACKOFF_BASE * (2 ** (fail_streak - 1)))
                    log(f"backoff: next mint attempt in {mint_gap}s "
                        f"(streak={fail_streak})")
            elif reason and capped and time.time() - last_cap_log > 300:
                log(f"heal needed ({reason}) but hourly mint cap reached; waiting")
                last_cap_log = time.time()
            time.sleep(POLL)
    except KeyboardInterrupt:
        pass
    finally:
        try:
            os.remove(LOCK_PATH)
        except OSError:
            pass
        log("watchdog stopped")


if __name__ == "__main__":
    main()
