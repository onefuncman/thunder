#!/usr/bin/env python3
"""Allowlisted Navigation Lab release-matrix driver (Phase 6).

Talks only to DevControl scenario endpoints. Does not expose arbitrary
movement. NO_FIXTURE / NO_GAME are NOT_RUN, never PASS. The 30-minute
mixed-route soak is replaced by the user-defined critical-route campaign
in tools/critical-routes.json.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PORT = int(os.environ.get("HAVEN_DEV_CONTROL_PORT", "18762"))

SURFACE = [
    ("long_open_ground", "surface_long_open_ground", 20),
    ("single_tree_detour", "surface_single_tree_detour", 20),
    ("dense_forest", "surface_dense_forest", 20),
    ("clustered_obstacles", "surface_clustered_obstacles", 20),
    ("one_tile_corridor", "surface_one_tile_corridor", 20),
    ("diagonal_corridor", "surface_diagonal_corridor", 20),
    ("buildings_fences", "surface_buildings_fences", 20),
    ("water_boundary", "surface_water_boundary", 20),
    ("cliff_boundary", "surface_cliff_boundary", 20),
    ("moving_neutral", "surface_moving_neutral", 20),
    ("hostile_exclusion", "surface_hostile_exclusion", 20),
    ("unknown_geometry", "surface_unknown_geometry", 20),
    ("explore_frontier", "explore_frontier", 20),
]
INTERACT = [
    ("forageable", "interact_forageable", 10),
    ("tree", "interact_tree", 10),
    ("boulder", "interact_boulder", 10),
    ("cupboard", "interact_cupboard", 10),
    ("door_gate", "interact_door_gate", 10),
    ("field_crop", "interact_field_crop", 10),
    ("narrow_interior", "interact_narrow_interior", 10),
]
TRANSITION = [
    ("door_gate", "transition_door_gate", 10),
    ("cellar_stairs", "transition_cellar_stairs", 10),
    ("cave", "transition_cave", 10),
    ("ladder", "transition_ladder", 10),
    ("minehole", "transition_minehole", 10),
    ("hearth", "hearth_travel", 10),
]
BOAT = [
    ("board", "boat_board", 10),
    ("travel", "boat_travel", 10),
    ("disembark", "boat_disembark", 10),
]
VEHICLE = [
    ("enter", "vehicle_enter", 10),
    ("travel", "vehicle_travel", 10),
    ("exit", "vehicle_exit", 10),
]
INVALIDATION = [("moving_neutral", "surface_moving_neutral", 1)]
DEFAULT_CAMPAIGN = ROOT / "tools" / "critical-routes.json"
CAMPAIGN_SCENARIOS = {
    "campaign_surface_out_and_back",
    "campaign_surface_and_door",
    "campaign_door_gate_roundtrip",
    "campaign_cellar_roundtrip",
    "campaign_minehole_roundtrip",
    "campaign_cave_roundtrip",
    "campaign_boat_roundtrip",
    "campaign_vehicle_roundtrip",
    "campaign_recorded",
}


def base(port: int) -> str:
    return f"http://127.0.0.1:{port}"


def http(port: int, path: str, data: bytes | None = None, timeout: float = 30.0) -> dict:
    url = base(port) + path
    req = urllib.request.Request(url, data=data, method="POST" if data is not None else "GET")
    if data is not None:
        req.add_header("Content-Type", "text/plain; charset=utf-8")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def classify(body: dict | None) -> str:
    if not body:
        return "FAIL"
    if body.get("status") == "error":
        return "FAIL"
    facts = body.get("facts") or {}
    refusal = facts.get("refusal")
    if refusal in ("NO_FIXTURE", "NO_GAME"):
        return "NOT_RUN"
    if body.get("verdict") == "PASS":
        return "PASS"
    return "FAIL"


def start_run(port: int, scenario: str) -> dict:
    q = urllib.parse.urlencode({"scenario": scenario})
    return http(port, "/pf/run?" + q, data=b"", timeout=15)


def wait_run(port: int, run_id: str, timeout: float) -> dict:
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = http(port, "/pf/result?run_id=" + urllib.parse.quote(run_id), timeout=10)
        if last.get("status") != "running":
            return last
        time.sleep(0.25)
    return last or {"ok": False, "error": "timeout", "run_id": run_id}


def audit_replay(path: str | None) -> dict:
    out = {"path": path, "parse_ok": False, "recovery_count": None, "false_reached": False}
    if not path:
        return out
    p = Path(path)
    if not p.is_file():
        return out
    lines = p.read_text().splitlines()
    if len(lines) < 2:
        return out
    body = json.loads(lines[1])
    out["parse_ok"] = body.get("format") == "NavReplay" and body.get("version") == 1
    rec = 0
    sends = 0
    waits = 0
    for d in body.get("decisions") or []:
        rec = max(rec, int(d.get("recovery_count") or d.get("recovery") or 0))
        kind = d.get("kind")
        if kind == "SEND_MOVEMENT":
            sends += 1
        elif kind == "WAIT":
            waits += 1
        if d.get("kind") == "TERMINATE" and d.get("outcome") == "REACHED" and body.get("goal", {}).get("kind") in (
            "INTERACTION",
            "TRANSITION",
            "EXPLORE_FRONTIER",
        ):
            out["false_reached"] = True
    raw = body.get("raw_route") or []
    smoothed = body.get("smoothed_route") or []
    out["recovery_count"] = rec
    out["send_movement"] = sends
    out["wait"] = waits
    out["raw_len"] = len(raw)
    out["smoothed_len"] = len(smoothed)
    out["tile_by_tile"] = sends > 2 and len(raw) > 3 and sends >= max(0, len(raw) - 1)
    return out


def run_category(port: int, name: str, scenario: str, need: int, timeout: float) -> dict:
    row = {
        "category": name,
        "scenario": scenario,
        "required": need,
        "pass": 0,
        "fail": 0,
        "not_run": 0,
        "consecutive_pass": 0,
        "status": "NOT_RUN",
        "runs": [],
    }
    streak = 0
    for i in range(need):
        started = start_run(port, scenario)
        if started.get("conflict"):
            row["fail"] += 1
            row["runs"].append({"n": i + 1, "class": "FAIL", "error": started.get("error")})
            streak = 0
            break
        run_id = started.get("run_id")
        if not started.get("ok") or not run_id:
            row["fail"] += 1
            row["runs"].append({"n": i + 1, "class": "FAIL", "error": started.get("error", "start")})
            streak = 0
            break
        body = wait_run(port, run_id, timeout)
        klass = classify(body)
        facts = body.get("facts") or {}
        rec = {
            "n": i + 1,
            "class": klass,
            "verdict": body.get("verdict"),
            "refusal": facts.get("refusal"),
            "run_id": run_id,
            "navreplay": body.get("navreplay"),
            "duration_ms": body.get("duration_ms"),
        }
        if body.get("navreplay"):
            rec["replay_audit"] = audit_replay(body.get("navreplay"))
            if rec["replay_audit"].get("false_reached") or rec["replay_audit"].get("tile_by_tile"):
                if klass != "NOT_RUN":
                    klass = "FAIL"
                    rec["class"] = "FAIL"
        row["runs"].append(rec)
        if klass == "PASS":
            row["pass"] += 1
            streak += 1
            row["consecutive_pass"] = max(row["consecutive_pass"], streak)
        elif klass == "NOT_RUN":
            row["not_run"] += 1
            streak = 0
            break
        else:
            row["fail"] += 1
            streak = 0
            break
    if row["consecutive_pass"] >= need:
        row["status"] = "PASS"
    elif row["not_run"] and row["pass"] == 0 and row["fail"] == 0:
        row["status"] = "NOT_RUN"
    elif row["fail"]:
        row["status"] = "FAIL"
    elif row["pass"] > 0:
        row["status"] = "PARTIAL"
    return row


def load_campaign(path: Path) -> dict:
    data = json.loads(Path(path).read_text())
    consecutive = int(data.get("consecutive") or 5)
    if consecutive < 1:
        raise ValueError("campaign consecutive must be >= 1")
    routes = []
    for raw in data.get("routes") or []:
        scenario = raw.get("scenario")
        if not raw.get("enabled", True):
            continue
        if not scenario or scenario not in CAMPAIGN_SCENARIOS:
            raise ValueError("campaign route %s uses unknown scenario %r" % (raw.get("id"), scenario))
        routes.append(
            {
                "id": raw.get("id") or scenario,
                "scenario": scenario,
                "route": raw.get("route") or "",
                "aspects": list(raw.get("aspects") or []),
                "bidirectional": bool(raw.get("bidirectional", True)),
            }
        )
    return {"consecutive": consecutive, "routes": routes, "file": str(path)}


def activate_recorded(name: str) -> None:
    text = (name or "").strip() + "\n"
    for d in (ROOT / "dev-snapshots" / "pf" / "routes", ROOT / "bin" / "dev-snapshots" / "pf" / "routes"):
        d.mkdir(parents=True, exist_ok=True)
        (d / "current.txt").write_text(text)


def campaign_audit(body: dict, bidirectional: bool) -> tuple[str, dict]:
    klass = classify(body)
    facts = body.get("facts") or {}
    extra = {
        "original_goal": facts.get("original_goal"),
        "original_goal_preserved": bool(facts.get("original_goal_preserved")),
        "forward": bool(facts.get("forward")),
        "reverse": bool(facts.get("reverse")),
        "unrecovered_stall": bool(facts.get("unrecovered_stall")),
    }
    replay = audit_replay(body.get("navreplay")) if body.get("navreplay") else {"parse_ok": False}
    extra["replay"] = replay
    if klass == "NOT_RUN":
        return klass, extra
    if klass != "PASS":
        if extra["unrecovered_stall"] or replay.get("false_reached"):
            return "FAIL", extra
        return klass, extra
    if extra["unrecovered_stall"] or replay.get("false_reached") or replay.get("tile_by_tile"):
        return "FAIL", extra
    if not body.get("navreplay") or not replay.get("parse_ok"):
        return "FAIL", extra
    if not extra["original_goal"] or extra["original_goal"] == "null":
        return "FAIL", extra
    if not extra["original_goal_preserved"] or not extra["forward"]:
        return "FAIL", extra
    if bidirectional and not extra["reverse"]:
        return "FAIL", extra
    return "PASS", extra


def run_campaign(port: int, campaign: dict, consecutive: int, timeout: float) -> dict:
    out = {
        "file": campaign.get("file"),
        "consecutive_required": consecutive,
        "routes": [],
        "status": "NOT_RUN",
    }
    if not campaign.get("routes"):
        out["blocker"] = "no enabled critical routes"
        return out
    for route in campaign["routes"]:
        row = {
            "id": route["id"],
            "scenario": route["scenario"],
            "route": route.get("route") or "",
            "aspects": route["aspects"],
            "bidirectional": route["bidirectional"],
            "required": consecutive,
            "pass": 0,
            "fail": 0,
            "not_run": 0,
            "consecutive_pass": 0,
            "status": "NOT_RUN",
            "runs": [],
        }
        streak = 0
        for i in range(consecutive):
            if route["scenario"] == "campaign_recorded":
                activate_recorded(route.get("route") or route["id"])
            started = start_run(port, route["scenario"])
            if started.get("conflict") or not started.get("ok") or not started.get("run_id"):
                row["fail"] += 1
                row["runs"].append({"n": i + 1, "class": "FAIL", "error": started.get("error", "start")})
                streak = 0
                break
            body = wait_run(port, started["run_id"], timeout)
            klass, extra = campaign_audit(body, route["bidirectional"])
            rec = {
                "n": i + 1,
                "class": klass,
                "verdict": body.get("verdict"),
                "refusal": (body.get("facts") or {}).get("refusal"),
                "run_id": started.get("run_id"),
                "navreplay": body.get("navreplay"),
                "duration_ms": body.get("duration_ms"),
            }
            rec.update(extra)
            row["runs"].append(rec)
            if klass == "PASS":
                row["pass"] += 1
                streak += 1
                row["consecutive_pass"] = max(row["consecutive_pass"], streak)
            elif klass == "NOT_RUN":
                row["not_run"] += 1
                streak = 0
                break
            else:
                row["fail"] += 1
                streak = 0
                break
        if row["consecutive_pass"] >= consecutive:
            row["status"] = "PASS"
        elif row["not_run"] and row["pass"] == 0 and row["fail"] == 0:
            row["status"] = "NOT_RUN"
        elif row["fail"]:
            row["status"] = "FAIL"
        elif row["pass"] > 0:
            row["status"] = "PARTIAL"
        out["routes"].append(row)
    statuses = [r["status"] for r in out["routes"]]
    if any(s == "FAIL" for s in statuses):
        out["status"] = "FAIL"
    elif all(s == "PASS" for s in statuses):
        out["status"] = "PASS"
    elif all(s == "NOT_RUN" for s in statuses):
        out["status"] = "NOT_RUN"
    elif all(s in ("PASS", "NOT_RUN") for s in statuses) and any(s == "PASS" for s in statuses):
        out["status"] = "PARTIAL"
    else:
        out["status"] = "PARTIAL"
    return out


def cmd_status(args):
    try:
        body = http(args.port, "/status", timeout=3)
    except urllib.error.URLError as e:
        json.dump({"ok": False, "error": str(e), "port": args.port}, sys.stdout, indent=2)
        sys.stdout.write("\n")
        sys.exit(1)
    json.dump(body, sys.stdout, indent=2)
    sys.stdout.write("\n")


def cmd_matrix(args):
    try:
        st = http(args.port, "/status", timeout=5)
    except urllib.error.URLError as e:
        json.dump({"ok": False, "error": str(e), "gate": "FAIL"}, sys.stdout, indent=2)
        sys.stdout.write("\n")
        sys.exit(1)
    screen = st.get("screen")
    summary = {
        "ok": True,
        "status": st,
        "screen": screen,
        "surface": [],
        "interaction": [],
        "transition": [],
        "boat": [],
        "vehicle": [],
        "invalidation": [],
        "campaign": None,
    }
    if screen != "game":
        summary["gate"] = "FAIL"
        summary["blocker"] = "client is not in-game (screen=%s); live matrix not started" % screen
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text(json.dumps(summary, indent=2) + "\n")
        json.dump(summary, sys.stdout, indent=2)
        sys.stdout.write("\n")
        sys.exit(2)

    groups = [
        ("surface", SURFACE),
        ("interaction", INTERACT),
        ("transition", TRANSITION),
        ("boat", BOAT),
        ("vehicle", VEHICLE),
        ("invalidation", INVALIDATION),
    ]
    if args.group:
        groups = [g for g in groups if g[0] == args.group]
    for key, rows in groups:
        for name, sc, need in rows:
            n = 1 if args.smoke else need
            summary[key].append(run_category(args.port, name, sc, n, args.timeout))
    if not args.skip_campaign and (not args.group or args.group == "campaign"):
        try:
            campaign = load_campaign(Path(args.campaign_file))
        except (OSError, ValueError, json.JSONDecodeError) as e:
            summary["campaign"] = {"status": "FAIL", "error": str(e)}
        else:
            n = 1 if args.smoke else campaign["consecutive"]
            summary["campaign"] = run_campaign(args.port, campaign, n, args.timeout)
    statuses = [r["status"] for k, _ in groups for r in summary[k]]
    if summary.get("campaign"):
        statuses.append(summary["campaign"]["status"])
    if any(s == "FAIL" for s in statuses):
        summary["gate"] = "FAIL"
    elif all(s == "NOT_RUN" for s in statuses):
        summary["gate"] = "FAIL"
        summary["blocker"] = "no live fixtures"
    elif all(s == "PASS" for s in statuses):
        summary["gate"] = "PASS"
    elif all(s in ("PASS", "NOT_RUN") for s in statuses) and any(s == "PASS" for s in statuses):
        summary["gate"] = "PARTIAL"
    else:
        summary["gate"] = "PARTIAL"
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(json.dumps(summary, indent=2) + "\n")
    json.dump(summary, sys.stdout, indent=2)
    sys.stdout.write("\n")
    sys.exit(0 if summary.get("gate") == "PASS" else 2)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--port", type=int, default=DEFAULT_PORT)
    sub = p.add_subparsers(dest="action", required=True)
    s = sub.add_parser("status")
    s.set_defaults(func=cmd_status)
    s = sub.add_parser("matrix")
    s.add_argument("--timeout", type=float, default=250)
    s.add_argument("--out", default=str(ROOT / "dev-snapshots/pf/matrix/latest.json"))
    s.add_argument("--smoke", action="store_true", help="one run per category, one campaign run per enabled route")
    s.add_argument("--skip-campaign", action="store_true")
    s.add_argument("--campaign-file", default=str(DEFAULT_CAMPAIGN))
    s.add_argument("--group", choices=["surface", "interaction", "transition", "boat", "vehicle", "invalidation", "campaign"])
    s.set_defaults(func=cmd_matrix)
    args = p.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
