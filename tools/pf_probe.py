#!/usr/bin/env python3
"""Summarize pathfinder mechanics-probe JSONL.

Usage:
  python tools/pf_probe.py [bin/dev-snapshots/pf/probe.jsonl]
  python tools/pf_probe.py --invariants
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path


def load(path: Path):
    rows = []
    if not path.exists():
        sys.exit(f"missing {path} — stand idle in the cupboard room and run :pf probe")
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        rows.append(json.loads(line))
    return rows


def summary(rows):
    kinds = Counter(r.get("kind") for r in rows)
    print(f"records: {len(rows)}  {dict(kinds)}")
    idle_solid = 0
    idle = 0
    clicks = 0
    opened = 0
    for r in rows:
        moving = r.get("moving") or r.get("moving_before")
        if r.get("kind") == "occupancy" and not moving:
            idle += 1
            if r.get("player_in_solid"):
                idle_solid += 1
        if r.get("kind") == "click":
            clicks += 1
            if r.get("opened"):
                opened += 1
        if r.get("kind") in ("occupancy", "step") and r.get("player_in_solid") and not moving:
            print(f"  INVARIANT FAIL  t={r.get('t')}  idle player marked SOLID  terrain={r.get('terrain')}")
        if r.get("kind") == "click":
            print(
                f"  click #{r.get('target')}  opened={r.get('opened')}  "
                f"moved={r.get('moved')}  poly={r.get('poly_dist')}t  "
                f"stop_gob={r.get('stop_dist_gob')}t  {r.get('outcome')}"
            )
        if r.get("kind") == "step":
            print(
                f"  step travelled={r.get('travelled')}t  "
                f"start_solid={r.get('start_in_solid')}  stop_solid={r.get('stop_in_solid')}  "
                f"{r.get('outcome')}"
            )
    if idle:
        print(f"idle occupancy snapshots: {idle}  marked SOLID: {idle_solid}")
    if clicks:
        print(f"clicks: {clicks}  windows: {opened}")
    last = rows[-1]
    if last.get("ascii"):
        print("last occupancy:")
        print(last["ascii"])


def main():
    p = argparse.ArgumentParser()
    p.add_argument("file", nargs="?", default="bin/dev-snapshots/pf/probe.jsonl")
    args = p.parse_args()
    rows = load(Path(args.file))
    if not rows:
        sys.exit("empty probe log")
    summary(rows)


if __name__ == "__main__":
    main()
