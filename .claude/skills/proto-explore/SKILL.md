---
name: proto-explore
description: Inspect Thunder client protocol captures and feature dumps. Use whenever working with files under play/proto-recordings/ or play/dev-snapshots/ -- summary, timeline, widget message stats, gob/widget filtering. Replaces ad-hoc grep+jq+python one-liners.
---

# proto-explore

Use the `tools/proto_explore.py` script in this repo for every protocol-investigation question. It auto-detects three on-disk formats and exposes a small set of subcommands. Prefer it to one-off `grep` / `python -c` invocations -- the answers are more accurate (proper JSON parsing, no regex misses) and reproducible.

## When to invoke

Reach for this skill any time you're answering a question about:

- A `dump-*.json` (feature state snapshot from `dev.<feature>.dump`)
- A `capture-*-*.jsonl` (one-feature-invocation protocol capture from `dev.<feature>.capture` -> auto-end)
- A `retro-*.jsonl` (long rolling protocol log from RetroCapture, under `play/proto-recordings/`)

Symptoms of "should run proto-explore":

- "what does the dump show for X?"
- "did chres / tt / OD_LINBEG fire on gob N?"
- "what's the timeline of widget messages after the click?"
- "how many resolved/expired captures exist?"
- "which gobs got non-movement traffic?"

## How to invoke

Always run as `python tools/proto_explore.py <file> <subcommand> [flags]`. The file path is positional and required.

```bash
# Auto-summary (header / scalars / event counts) -- start here.
python tools/proto_explore.py play/dev-snapshots/milk/dump-XXXX.json
python tools/proto_explore.py play/dev-snapshots/milk/capture-resolved-XXXX.jsonl

# Header only (capture/retro)
python tools/proto_explore.py <file> header

# Specific dump fields
python tools/proto_explore.py <dump.json> field pending marked_count toggle_on

# Widget message breakdown (dir, name)
python tools/proto_explore.py <jsonl> widgets

# Counts grouped by a key
python tools/proto_explore.py <jsonl> count --by msg --no-movement
python tools/proto_explore.py <jsonl> count --by gob --cat OBJECT
python tools/proto_explore.py <jsonl> count --by type --dir in

# Timeline with filters (most powerful)
python tools/proto_explore.py <jsonl> timeline --no-movement --no-hud
python tools/proto_explore.py <jsonl> timeline --gob 2085117310
python tools/proto_explore.py <jsonl> timeline --msg chres
python tools/proto_explore.py <jsonl> timeline --match 'OD_OVERLAY|chres' --limit 0
python tools/proto_explore.py <jsonl> timeline --dir out --no-movement
```

## Subcommand cheat-sheet

| Subcommand | Works on | Purpose |
|---|---|---|
| `summary` (default) | dump + jsonl | Top-level scalars; jsonl gets header + cat/dir counts |
| `header` | jsonl | Pretty-print just the header object |
| `field <names>` | dump | Print specific top-level dump fields |
| `widgets` | jsonl | Counts of widget messages by `(dir, name)` |
| `count --by KEY` | jsonl | Group counts by `type` / `cat` / `dir` / `msg` / `gob` / `wid` |
| `timeline` | jsonl | Chronological event list with filters |

## Filter flags (timeline + count)

- `--gob N` -- events for one gob
- `--wid N` -- events for one widget
- `--dir in|out`
- `--cat OBJECT|WIDGET|...`
- `--type RMSG_WDGMSG|OBJDATA|WDGMSG_OUT|...`
- `--msg chres|tt|click|...` (widget message name)
- `--match REGEX` (regex on summary/detail)
- `--no-movement` (drop OD_MOVE / OD_LIN* noise)
- `--no-hud` (drop HUD bar wdgmsgs: glut/set/tip/m)

## Standard recipes

**"Was a milk attempt successful?"**
```bash
python tools/proto_explore.py play/dev-snapshots/milk/capture-XXX.jsonl header
python tools/proto_explore.py play/dev-snapshots/milk/capture-XXX.jsonl widgets
python tools/proto_explore.py play/dev-snapshots/milk/capture-XXX.jsonl timeline --msg chres
```

**"What did the player do during this capture (no movement noise)?"**
```bash
python tools/proto_explore.py <jsonl> timeline --no-movement --no-hud --limit 0
```

**"What server response came back for cow gob N?"**
```bash
python tools/proto_explore.py <jsonl> timeline --gob N --no-movement
```

**"Which dumps still have a pending UID?"**
```bash
for f in play/dev-snapshots/milk/dump-*.json; do
    python tools/proto_explore.py "$f" field pending
done
```

## Don't

- Don't write ad-hoc python one-liners for these files -- the tool already handles JSON parsing, format detection, header/event split, and sane filters.
- Don't `grep` for `'chres'` or message names -- `--msg chres` does it correctly across both summary and detail formatting.
- Don't read raw event JSONLs into the conversation context for inspection -- they can be 1000s of lines and the tool's filters/summaries are designed exactly for that.
