#!/usr/bin/env python3
"""zphone-watchdog — permanent zero-user session healer for the ZCode remote app.

Runs hidden at logon (pythonw, HKCU Run). Polls the phone app's bridge /status
every POLL seconds. MINTING IS PERMANENTLY DISABLED (owner directive
2026-10-25: the QR panel must NEVER open again). The only desktop-side heal
is the panel-free RESTART-HEAL: when an unhealthy page state persists and
the desktop user is absent (idle >= 600 s or locked), the desktop app is
restarted — this clears the ghost terminal state and the phone reconnects
BY ITSELF on its existing link ~4-5 min later (proven live 2026-10-25
17:05->17:10). While the user is at the PC, nothing is ever disturbed.

Far-from-home: the phone is polled over the LAN first and, when configured,
via its Tailscale address (host_wan in ~/.zphone.json) — the heal pipeline
then works from any network, exactly like the session relay itself.

NO-DISTURBANCE LAW (absolute): no panel, no mint, no UIA, ever. Restart-heal
fires at most once per 2 h, 3/day, only while the user is absent.
"""

import ctypes
import json
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import zphone  # noqa: E402  (same dir; request()/load_config() reused)

LOG_PATH = os.path.join(HERE, "watchdog.log")
LOCK_PATH = os.path.join(HERE, "watchdog.lock")
MINT_PS1 = os.path.join(HERE, "zphone-mint.ps1")

POLL = 10              # seconds between /status polls
LOG_MAX = 256 * 1024
MAX_MINTS_PER_HOUR = 0   # MINTING PERMANENTLY DISABLED (owner directive)
MAX_MINTS_PER_DAY = 0
USER_ABSENCE_IDLE = 600  # s without input before any desktop-side action
# RESTART-HEAL (panel-free, proven live 2026-10-25 17:05->17:10): restarting
# the desktop app clears the ghost terminal state and the phone reconnects
# BY ITSELF on its existing link ~4-5 min later — no mint, no QR, no panel.
RESTART_HEAL_AFTER = 900     # s a heal-reason persists before restarting
RESTART_HEAL_GAP = 7200      # min 2 h between restart heals
MAX_RESTARTS_PER_DAY = 3

# (state, min age seconds) — a state must PERSIST this long before we mint.
# GHOST-BLOCK DOCTRINE (root-caused live 2026-10-25): after any connection
# drop the phone re-pairs fine but the workspace sync times out
# (desktop_timedout / "Paired. Loading workspace…" → DesktopTimedOut)
# because the desktop terminal still holds the DEAD previous phone
# connection as a ghost that blocks the new sync. The ONLY cure is a fresh
# pairing through the panel (mint+push resets the phone slot — proven live:
# app restart + fresh link = instant workspace, 18 ms warm opens). So
# desktop_timedout MINTS after 4 min. Only desktop_offline (terminal truly
# gone — the app is closing/down) and a slow-but-working relay (connecting)
# stay quiet; if the link also rotated during those, the page resurfaces as
# invalid/menu/nosession and mints through those rules.
HEAL_RULES = [
    ("invalid", 0),
    ("nosession", 240),
    ("loading", 120),
    ("error", 150),
    ("desktop_timedout", 240),
    ("connecting", 900),
    ("desktop_offline", 2700),
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


def desktop_user_absent():
    """True only when the desktop user cannot see the screen: input idle
    >= USER_ABSENCE_IDLE seconds, or the workstation is locked. The QR panel
    physically cannot disturb anyone under these conditions (owner directive
    2026-10-25: NEVER pop the panel while the user is at the PC)."""
    try:
        class LASTINPUTINFO(ctypes.Structure):
            _fields_ = [("cbSize", ctypes.c_uint), ("dwTime", ctypes.c_uint)]
        info = LASTINPUTINFO(ctypes.sizeof(LASTINPUTINFO), 0)
        if ctypes.windll.user32.GetLastInputInfo(ctypes.byref(info)):
            idle_ms = ctypes.windll.kernel32.GetTickCount() - info.dwTime
            if idle_ms >= USER_ABSENCE_IDLE * 1000:
                return True
        # Locked check: the default interactive desktop is unreachable.
        h = ctypes.windll.user32.OpenInputDesktop(0, False, 0x0100)
        if h == 0:
            return True
        ctypes.windll.user32.CloseDesktop(h)
        return False
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
    """MINTING PERMANENTLY DISABLED (owner directive 2026-10-25, absolute):
    the QR panel must NEVER open again. This stub exists so no code path can
    ever invoke the UIA mint script to OPEN or COPY."""
    log("mint attempted but PERMANENTLY DISABLED (owner directive: no panel)")
    return "mint: disabled"


def mint():
    return False


def close_panel():
    """Safety net only: closes a panel that some crash may have left open.
    Runs ONLY the -CloseOnly mode — it can never open or copy."""
    try:
        result = subprocess.run(
            ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
             "-WindowStyle", "Hidden", "-File", MINT_PS1, "-CloseOnly"],
            capture_output=True, text=True, timeout=60,
            creationflags=0x08000000)
        line = (result.stdout or "").strip().splitlines()
        outcome = line[-1] if line else "mint: no-output"
        if outcome != "mint: closed":
            log(f"panel close: {outcome}")
        return outcome == "mint: closed"
    except Exception as error:
        log(f"panel close error: {type(error).__name__}")
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
    log("watchdog started (panel-free: minting permanently disabled)")
    last_unreachable_log = 0.0
    last_defer_log = 0.0
    desktop_was_up = None
    last_reason = None
    reason_since = 0.0
    restart_times = []
    try:
        while True:
            config = zphone.load_config()

            desktop_up = zcode_running()
            if desktop_was_up is None:
                desktop_was_up = desktop_up
            elif desktop_up and not desktop_was_up:
                log("desktop app restarted; phone states will settle on their own")
                desktop_was_up = desktop_up
            elif not desktop_up and desktop_was_up:
                log("desktop app gone; waiting for its return")
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

            reason = heal_reason(status)
            now = time.time()
            if reason != last_reason:
                last_reason = reason
                reason_since = now
            restart_times = [t for t in restart_times if now - t < 86400]
            user_absent = desktop_user_absent()
            restart_ok = (reason and desktop_up and user_absent
                          and now - reason_since >= RESTART_HEAL_AFTER
                          and (not restart_times
                               or now - restart_times[-1] >= RESTART_HEAL_GAP)
                          and len(restart_times) < MAX_RESTARTS_PER_DAY)
            if restart_ok:
                log(f"restart-heal: {reason} persisted "
                    f"{int(now - reason_since)}s; restarting desktop app (panel-free)")
                try:
                    subprocess.run(["taskkill", "/IM", "ZCode.exe", "/F"],
                                   capture_output=True, timeout=15,
                                   creationflags=0x08000000)
                    time.sleep(12)
                    subprocess.run(
                        ["cmd", "/c", "start", "",
                         "F:\\backup\\windowsapps\\installed\\ZCode\\ZCode.exe"],
                        capture_output=True, timeout=15,
                        creationflags=0x08000000)
                    restart_times.append(time.time())
                    log("restart-heal: app restarted; phone self-reconnects (~4-5 min)")
                    time.sleep(120)
                except Exception as error:
                    log(f"restart-heal error: {type(error).__name__}")
            elif reason and not user_absent and now - last_defer_log > 300:
                log(f"heal needed ({reason}) deferred: user at the PC — "
                    f"no desktop disturbance while present")
                last_defer_log = now
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
