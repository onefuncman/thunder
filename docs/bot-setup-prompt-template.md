# New-bot setup prompt (template)

Not auto-loaded — copy the block below, fill the brackets, paste as your first
message when starting a new bot-building session. It gives Claude Code enough
to read the right files and do the upfront design/plan work unprompted.

## Template

```
Build a bot for [TASK — what it does, e.g. "auto-milking a cattle roster"].

Trigger/loop: [what starts it, what it repeats on, what stops it]
Resources it consumes/needs: [e.g. water, specific tools, none]
Interactions involved: [containers? specific gobs/resids if known? placement?
  a UI menu/flowermenu? just walking+clicking?]
UI: [does it need a setup window with fields, or is a console command enough?]

Before writing anything:
1. Read docs/bot-automation-api.md — mandatory gotchas for this client
   (container right-click vs itemact, FlowerMenu's real attach point, gob
   arrival radius, Defer thread starvation, diagnostic logging pattern).
2. Read src/auto/MiningBot.java + src/auto/MiningMaterials.java as the
   reference implementation for a full live-loop bot (zone-based fetch,
   resupply logic, diagnostic file logging, safety cap, cancellation).
3. Skim src/auto/Bot.java, MapHelper.java, InvHelper.java, GobHelper.java,
   PositionHelper.java, BotUtil.java for existing helpers before writing new
   ones — don't reimplement walkTo/inventory predicates/flower selection.
4. If the bot needs a setup window, use src/thunder/mining/MiningBotSetupWnd.java
   as the layout pattern (fields + Start/Stop, console command wiring in
   GameUI.java's cmdmap). If it needs a map-zone selection, reuse
   src/thunder/mining/ZonePicker.java + MiningZoneStore.java as-is rather than
   building a new picker.

I can't run the game — you can't either. Testing is: you build it with
diagnostic file logging from the start (same pattern as MiningBot's
openDiagLog/diag), I run it live and report back what happened (with logs),
you iterate. Don't guess at a fix without a log or my description of what I
observed. Ask one clarifying question if genuinely blocked; otherwise make the
reasonable call and keep going.

Give me a short plan before implementing.
```

## Why each piece is there

- **docs/bot-automation-api.md first** — every rule in it was a multi-hour live
  debugging session the first time (wrong click type, wrong widget root,
  wrong eat mechanism, thread starvation). Skipping it means re-deriving the
  same bugs.
- **MiningBot as reference, not a library** — there's no extracted bot-building
  API in this codebase (deliberately — see that doc's intro). A new bot copies
  the *pattern* (diagnostic logging, cancellation via `Bot.checkCancelled()`,
  zone/resupply structure if relevant) from working code, not a shared base
  class.
- **`auto.CheeseTrayFiller`** is worth a look instead of MiningBot when the new
  bot is a tight decision algorithm rather than a full live-interaction loop —
  it separates the algorithm from the game via an `Env` interface, so the
  logic itself is unit-testable without the live game. Use that shape if the
  "what to do next" decision is the hard part; use MiningBot's shape if
  walking/container/placement sequencing is the hard part.
- **"I can't run the game"** — this client has no test harness for live
  interactions; every mechanism in the gotchas doc was found by shipping a
  diagnostic build, having the user run it, and reading back a log or a
  description. State this up front so the session defaults to
  build-in-logging-first instead of guessing fixes blind.
- **Plan before implementing** — matches how MiningBot itself was built:
  agree on the shape (what state it tracks, what triggers a resupply/retry,
  what the setup window exposes) before writing the interaction code.
