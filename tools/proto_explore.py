#!/usr/bin/env python3
"""
proto_explore.py -- inspect Thunder client protocol captures and feature dumps.

Handles three on-disk artifact types:
  - dump-*.json                  (single-instant feature state, JSON object)
  - capture-*-*.jsonl            (one feature invocation, JSONL: header + events)
  - retro-*.jsonl                (long rolling protocol log, JSONL: header + events)

Type is auto-detected: a JSON object => dump; JSONL whose first line has
{"type":"header"} => capture/retro.

Subcommands operate on whichever shape the file has. Invalid combos
(e.g. --timeline on a dump) are rejected.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path
from typing import Any, Iterable


# --- Loaders ---------------------------------------------------------------

def detect_kind(path: Path):
    """Whole-file parse first (covers pretty-printed dumps); fall back to JSONL."""
    raw = path.read_text(encoding="utf-8").strip()
    if not raw:
        sys.exit(f"empty file: {path}")
    try:
        obj = json.loads(raw)
        if isinstance(obj, dict) and obj.get("type") != "header":
            return "dump"
    except json.JSONDecodeError:
        pass
    # Try JSONL: first non-blank line should parse as an object.
    with path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                json.loads(line)
                return "jsonl"
            except json.JSONDecodeError as e:
                sys.exit(f"unrecognized format (first line not JSON): {e}")
    sys.exit(f"unrecognized format: {path}")


def load_dump(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def load_jsonl(path: Path):
    with path.open("r", encoding="utf-8") as f:
        lines = [ln for ln in f if ln.strip()]
    if not lines:
        sys.exit("empty jsonl")
    header = json.loads(lines[0])
    events = [json.loads(ln) for ln in lines[1:]]
    return header, events


# --- Helpers ---------------------------------------------------------------

NOISE_PATTERNS = [
    re.compile(r"OD_MOVE,OD_LINBEG,OD_LINSTEP,OD_CMPPOSE"),
    re.compile(r"OD_MOVE,OD_LINSTEP,OD_CMPPOSE"),
    re.compile(r"OD_MOVE,OD_CMPPOSE,OD_LINSTEP"),
    re.compile(r"OD_MOVE,OD_LINSTEP\b"),
    re.compile(r"OD_MOVE,OD_LINBEG,OD_LINSTEP\b"),
    re.compile(r"OD_MOVE,OD_LINSTEP done"),
    re.compile(r"^Gob \d+ frame=\d+ OD_MOVE\b"),
]
WIDGET_NAME_RE = re.compile(r"msg '([^']+)'")
HUD_BAR_NAMES = {"glut", "set", "tip", "m"}


def is_movement_noise(summary: str) -> bool:
    return any(p.search(summary) for p in NOISE_PATTERNS)


def widget_name(summary: str) -> str | None:
    m = WIDGET_NAME_RE.search(summary)
    return m.group(1) if m else None


def fmt_event(e: dict, anchor_t: float | None = None) -> str:
    t = e.get("t")
    if anchor_t is not None and t is not None:
        ts = f"+{t - anchor_t:7.3f}s"
    else:
        ts = f"t={t}"
    cat = e.get("cat", "?")
    typ = e.get("type", "?")
    direction = e.get("dir", "")
    s = e.get("summary", "")
    if len(s) > 160:
        s = s[:157] + "..."
    parts = [ts, f"{direction:>3}", f"{cat:<8}", f"{typ:<14}", s]
    return "  ".join(parts)


def filter_events(events: list[dict], args) -> list[dict]:
    out = events
    if args.gob is not None:
        out = [e for e in out if e.get("gob") == args.gob]
    if args.wid is not None:
        out = [e for e in out if e.get("wid") == args.wid]
    if args.dir:
        out = [e for e in out if e.get("dir") == args.dir]
    if args.cat:
        out = [e for e in out if e.get("cat") == args.cat]
    if args.type:
        out = [e for e in out if e.get("type") == args.type]
    if args.msg:
        out = [e for e in out if widget_name(e.get("summary", "")) == args.msg]
    if args.match:
        rx = re.compile(args.match)
        out = [e for e in out if rx.search(e.get("summary", "")) or rx.search(e.get("detail", ""))]
    if args.no_movement:
        out = [e for e in out if not is_movement_noise(e.get("summary", ""))]
    if args.no_hud:
        out = [e for e in out if widget_name(e.get("summary", "")) not in HUD_BAR_NAMES]
    return out


# --- Subcommands -----------------------------------------------------------

def cmd_summary(args):
    kind = detect_kind(args.file)
    if kind == "dump":
        d = load_dump(args.file)
        scalar_keys = sorted(k for k, v in d.items() if not isinstance(v, (list, dict)))
        print(f"DUMP {args.file.name}")
        for k in scalar_keys:
            print(f"  {k}: {d[k]}")
        list_keys = [k for k, v in d.items() if isinstance(v, list)]
        for k in list_keys:
            print(f"  {k}: list[{len(d[k])}]")
        dict_keys = [k for k, v in d.items() if isinstance(v, dict)]
        for k in dict_keys:
            sub = d[k]
            inner = ", ".join(f"{ik}={iv}" for ik, iv in sub.items() if not isinstance(iv, (list, dict)))
            print(f"  {k}: {{ {inner} }}")
    else:
        header, events = load_jsonl(args.file)
        print(f"JSONL {args.file.name}")
        print(f"  header keys: {sorted(header.keys())}")
        for k in sorted(header):
            v = header[k]
            if isinstance(v, (dict, list)):
                print(f"    {k}: {json.dumps(v)[:120]}")
            else:
                print(f"    {k}: {v}")
        print(f"  event count (file): {len(events)}")
        if events:
            ts = [e["t"] for e in events if "t" in e]
            if ts:
                print(f"  span seconds:       {ts[-1] - ts[0]:.3f}")
        cats = Counter(e.get("cat", "?") for e in events)
        for c, n in cats.most_common():
            print(f"  cat {c:<8} {n}")
        dirs = Counter(e.get("dir", "?") for e in events)
        for d, n in dirs.most_common():
            print(f"  dir {d:<3} {n}")


def cmd_widgets(args):
    _, events = load_jsonl(args.file)
    counts = Counter()
    for e in events:
        if "WDG" not in e.get("type", ""):
            continue
        nm = widget_name(e.get("summary", ""))
        if nm:
            counts[(e.get("dir", "?"), nm)] += 1
    print(f"WIDGET MESSAGES (dir, name) -- {sum(counts.values())} total")
    for (d, nm), n in counts.most_common():
        print(f"  {n:5d}  {d:>3}  {nm}")


def cmd_timeline(args):
    _, events = load_jsonl(args.file)
    events = filter_events(events, args)
    if not events:
        print("(no events match filters)")
        return
    anchor = events[0].get("t") if args.relative else None
    limit = args.limit or len(events)
    for e in events[:limit]:
        print(fmt_event(e, anchor))
    if limit < len(events):
        print(f"... ({len(events) - limit} more; pass --limit 0 for all)")


def cmd_count(args):
    _, events = load_jsonl(args.file)
    events = filter_events(events, args)
    key_fn = {
        "type":   lambda e: e.get("type", "?"),
        "cat":    lambda e: e.get("cat", "?"),
        "dir":    lambda e: e.get("dir", "?"),
        "msg":    lambda e: widget_name(e.get("summary", "")) or "(non-widget)",
        "gob":    lambda e: e.get("gob", "?"),
        "wid":    lambda e: e.get("wid", "?"),
    }[args.by]
    counts = Counter(key_fn(e) for e in events)
    for k, n in counts.most_common(args.limit or None):
        print(f"  {n:6d}  {k}")
    print(f"-- {sum(counts.values())} events grouped into {len(counts)} keys (filtered)")


def cmd_field(args):
    """Extract a top-level field from a dump file."""
    if detect_kind(args.file) != "dump":
        sys.exit("--field only works on dump-*.json files")
    d = load_dump(args.file)
    for f in args.fields:
        v = d.get(f, "<missing>")
        print(f"{f}: {json.dumps(v) if isinstance(v, (dict, list)) else v}")


def cmd_header(args):
    if detect_kind(args.file) != "jsonl":
        sys.exit("--header only works on JSONL captures")
    h, _ = load_jsonl(args.file)
    print(json.dumps(h, indent=2))


# --- CLI -------------------------------------------------------------------

def add_filter_args(p):
    p.add_argument("--gob", type=int, help="filter to events on this gob id")
    p.add_argument("--wid", type=int, help="filter to events on this widget id")
    p.add_argument("--dir", choices=["in", "out"], help="filter by direction")
    p.add_argument("--cat", help="filter by category (OBJECT, WIDGET, MAP, ...)")
    p.add_argument("--type", help="filter by raw type name (e.g. RMSG_WDGMSG)")
    p.add_argument("--msg", help="filter by widget message name (e.g. chres, tt, click)")
    p.add_argument("--match", help="regex filter on summary/detail")
    p.add_argument("--no-movement", action="store_true",
                   help="drop noisy OD_MOVE/OD_LIN* gob update events")
    p.add_argument("--no-hud", action="store_true",
                   help="drop HUD bar widget messages (glut/set/tip/m)")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("file", type=Path, help="capture/dump path")
    sub = ap.add_subparsers(dest="cmd", required=False)

    p_summary = sub.add_parser("summary", help="auto-summary of file (default)")
    p_summary.set_defaults(func=cmd_summary)

    p_widgets = sub.add_parser("widgets", help="counts of widget messages by (dir, name)")
    p_widgets.set_defaults(func=cmd_widgets)

    p_timeline = sub.add_parser("timeline", help="chronological event list")
    p_timeline.add_argument("--relative", action="store_true", default=True,
                            help="show timestamps relative to first event (default)")
    p_timeline.add_argument("--absolute", dest="relative", action="store_false")
    p_timeline.add_argument("--limit", type=int, default=80, help="max rows (0 = all)")
    add_filter_args(p_timeline)
    p_timeline.set_defaults(func=cmd_timeline)

    p_count = sub.add_parser("count", help="group events by a key, show counts")
    p_count.add_argument("--by", choices=["type", "cat", "dir", "msg", "gob", "wid"],
                         default="type")
    p_count.add_argument("--limit", type=int, default=0, help="top N (0 = all)")
    add_filter_args(p_count)
    p_count.set_defaults(func=cmd_count)

    p_field = sub.add_parser("field", help="dump-only: extract top-level fields")
    p_field.add_argument("fields", nargs="+", help="field names to print")
    p_field.set_defaults(func=cmd_field)

    p_header = sub.add_parser("header", help="jsonl-only: print header object")
    p_header.set_defaults(func=cmd_header)

    args = ap.parse_args()
    if not args.file.exists():
        sys.exit(f"no such file: {args.file}")
    if not getattr(args, "func", None):
        args.func = cmd_summary
    args.func(args)


if __name__ == "__main__":
    main()
