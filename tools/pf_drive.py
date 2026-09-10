#!/usr/bin/env python3
"""Drive a live Thunder client over the localhost dev-control port.

The client must be running with -Dhaven.dev.control.port=18761 (the niri
launch script sets this). Haven allows one session per account: if you are
already in-game, this talks to that UI. Do not launch a second client.

Examples:
  python tools/pf_drive.py status
  python tools/pf_drive.py wait
  python tools/pf_drive.py cmd pf probe
  python tools/pf_drive.py probe step rel 0 -11
  python tools/pf_drive.py launch --auto
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PORT = int(os.environ.get("HAVEN_DEV_CONTROL_PORT", "18761"))


def base(port: int) -> str:
    return f"http://127.0.0.1:{port}"


def call(port: int, path: str, data: bytes | None = None, timeout: float = 30.0):
    url = base(port) + path
    req = urllib.request.Request(url, data=data, method="POST" if data is not None else "GET")
    if data is not None:
        req.add_header("Content-Type", "text/plain; charset=utf-8")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
    except urllib.error.URLError as e:
        sys.exit(f"dev-control not reachable at {url} ({e})\nRelaunch the client so it prints [dev-control] http://127.0.0.1:{port}/")
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        sys.exit(raw)


def cmd_status(args):
    body = call(args.port, "/occupancy" if args.occupancy else "/status")
    json.dump(body, sys.stdout, indent=2)
    sys.stdout.write("\n")
    if not body.get("ok", True):
        sys.exit(1)


def cmd_wait(args):
    deadline = time.time() + args.timeout
    last = None
    while time.time() < deadline:
        try:
            last = call(args.port, "/status", timeout=3)
            screen = last.get("screen")
            if screen == "game":
                json.dump(last, sys.stdout, indent=2)
                sys.stdout.write("\n")
                return
            if screen == "login" and args.login:
                call(args.port, "/login" + q(user=args.user))
            elif screen == "chars" and args.login:
                call(args.port, "/play" + q(**{"char": args.char} if args.char else {}))
        except SystemExit:
            last = {"screen": "down"}
        time.sleep(0.5)
    json.dump(last or {"ok": False, "error": "timeout"}, sys.stdout, indent=2)
    sys.stdout.write("\n")
    sys.exit(1)


def q(**kwargs) -> str:
    items = {k: v for k, v in kwargs.items() if v}
    if not items:
        return ""
    return "?" + urllib.parse.urlencode(items)


def cmd_cmd(args):
    text = " ".join(args.words).strip()
    if text.startswith(":"):
        text = text[1:]
    body = call(args.port, "/cmd", data=text.encode("utf-8"), timeout=args.timeout)
    json.dump(body, sys.stdout, indent=2)
    sys.stdout.write("\n")
    if body.get("out"):
        sys.stderr.write(body["out"])
        if not body["out"].endswith("\n"):
            sys.stderr.write("\n")
    if not body.get("ok"):
        sys.exit(1)


def cmd_login(args):
    body = call(args.port, "/login" + q(user=args.user))
    json.dump(body, sys.stdout, indent=2)
    sys.stdout.write("\n")
    if not body.get("ok"):
        sys.exit(1)


def cmd_play(args):
    body = call(args.port, "/play" + q(**{"char": args.char} if args.char else {}))
    json.dump(body, sys.stdout, indent=2)
    sys.stdout.write("\n")
    if not body.get("ok"):
        sys.exit(1)


def cmd_probe(args):
    words = ["pf", "probe"] + list(args.words)
    args.words = words
    cmd_cmd(args)


def cmd_pf_run(args):
    q = urllib.parse.urlencode({"scenario": args.scenario})
    body = call(args.port, "/pf/run?" + q, data=b"", timeout=args.timeout if hasattr(args, "timeout") else 30)
    json.dump(body, sys.stdout, indent=2)
    sys.stdout.write("\n")
    if not body.get("ok"):
        sys.exit(1)


def cmd_pf_result(args):
    body = call(args.port, "/pf/result?run_id=" + urllib.parse.quote(args.run_id))
    json.dump(body, sys.stdout, indent=2)
    sys.stdout.write("\n")
    if not body.get("ok", True):
        sys.exit(1)


def cmd_pf_wait(args):
    started = call(args.port, "/pf/run?" + urllib.parse.urlencode({"scenario": args.scenario}), data=b"", timeout=15)
    if not started.get("ok"):
        json.dump(started, sys.stdout, indent=2)
        sys.stdout.write("\n")
        sys.exit(1)
    run_id = started["run_id"]
    deadline = time.time() + args.timeout
    last = started
    while time.time() < deadline:
        last = call(args.port, "/pf/result?run_id=" + urllib.parse.quote(run_id), timeout=10)
        if last.get("status") != "running":
            json.dump(last, sys.stdout, indent=2)
            sys.stdout.write("\n")
            if last.get("verdict") == "PASS":
                return
            sys.exit(1)
        time.sleep(0.25)
    json.dump(last, sys.stdout, indent=2)
    sys.stdout.write("\n")
    sys.exit(1)


def cmd_launch(args):
    script = ROOT / "launch-thunder-nvidia.sh"
    if not script.is_file():
        sys.exit(f"missing {script}")
    env = os.environ.copy()
    env["HAVEN_DEV_CONTROL_PORT"] = str(args.port)
    if args.auto:
        env["HAVEN_AUTOLOGIN"] = "true"
        if args.char:
            env["HAVEN_AUTOPLAY"] = args.char
    # Already up?
    try:
        st = call(args.port, "/status", timeout=1)
        if st.get("ok"):
            json.dump({"ok": True, "already": True, **st}, sys.stdout, indent=2)
            sys.stdout.write("\n")
            return
    except SystemExit:
        pass
    subprocess.Popen([str(script)], cwd=str(ROOT), env=env,
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                     start_new_session=True)
    json.dump({"ok": True, "launched": True, "autologin": bool(args.auto)}, sys.stdout, indent=2)
    sys.stdout.write("\n")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--port", type=int, default=DEFAULT_PORT)
    sub = p.add_subparsers(dest="action", required=True)

    s = sub.add_parser("status", help="login / chars / game + player pos")
    s.add_argument("--occupancy", action="store_true", help="also raster occupancy (slower)")
    s.set_defaults(func=cmd_status)

    s = sub.add_parser("wait", help="poll until in-game")
    s.add_argument("--timeout", type=float, default=180)
    s.add_argument("--login", action="store_true", help="click saved account / character while waiting")
    s.add_argument("--user", default="")
    s.add_argument("--char", default="")
    s.set_defaults(func=cmd_wait)

    s = sub.add_parser("cmd", help="run a console command (without leading :)")
    s.add_argument("words", nargs="+")
    s.add_argument("--timeout", type=float, default=60)
    s.set_defaults(func=cmd_cmd)

    s = sub.add_parser("login", help="click a saved native account on the login screen")
    s.add_argument("--user", default="")
    s.set_defaults(func=cmd_login)

    s = sub.add_parser("play", help="click Play on the character list")
    s.add_argument("--char", default="")
    s.set_defaults(func=cmd_play)

    s = sub.add_parser("probe", help="shortcut for: cmd pf probe ...")
    s.add_argument("words", nargs="*")
    s.add_argument("--timeout", type=float, default=60)
    s.set_defaults(func=cmd_probe)

    s = sub.add_parser("launch", help="start launch-thunder-nvidia.sh if control port is down")
    s.add_argument("--auto", action="store_true", help="HAVEN_AUTOLOGIN=true")
    s.add_argument("--char", default="", help="HAVEN_AUTOPLAY character name")
    s.set_defaults(func=cmd_launch)

    s = sub.add_parser("pf-run", help="POST /pf/run?scenario= (allowlisted only)")
    s.add_argument("scenario")
    s.add_argument("--timeout", type=float, default=30)
    s.set_defaults(func=cmd_pf_run)

    s = sub.add_parser("pf-result", help="GET /pf/result?run_id=")
    s.add_argument("run_id")
    s.set_defaults(func=cmd_pf_result)

    s = sub.add_parser("pf-wait", help="start an allowlisted scenario and wait for completion")
    s.add_argument("scenario")
    s.add_argument("--timeout", type=float, default=180)
    s.set_defaults(func=cmd_pf_wait)

    args = p.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
