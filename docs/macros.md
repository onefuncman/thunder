# Macros

Record sequences of in-game actions and replay them N times. Driven by the
existing `auto.Bot` infrastructure for cancellation + threading.

## Files

- `src/thunder/macro/Macro.java` — POJO: name, defaultRepeat, list of steps.
- `src/thunder/macro/MacroStep.java` — abstract base + 7 concrete step
  classes nested inside (ItemAct, GobAct, InvDrop, FlowerChoice, Wait,
  Sleep, Cmd). Each has `type()`, `label()`, and `execute(MacroRunner)`.
- `src/thunder/macro/MacroStore.java` — JSON load/save with polymorphic
  Gson adapters keyed on a `type` discriminator.
- `src/thunder/macro/MacroRunner.java` — wraps an `auto.Bot.execute(...)`
  with a `for(repeat) for(step) step.execute(this)` loop. `bot.checkCancelled()`
  is polled inside every wait.
- `src/thunder/macro/MacroRecorder.java` — singleton listener; hooks in
  `WItem.mousedown`, `Inventory.drop`, `MapView.Click.hit`, and a
  `Reactor.FLOWER_CHOICE` subscription tee off when `current() != null`.
- `src/thunder/macro/MacroPicker.java` — one-shot "next world right-click
  becomes the GobAct target" mode used by the editor's Pick button.
- `src/thunder/macro/MacroListWnd.java` — list window (record/save/play/
  edit/delete).
- `src/thunder/macro/MacroEditorWnd.java` — per-macro editor with
  per-step inline panes.
- `src/test/java/thunder/macro/MacroStoreSerializationTest.java` —
  round-trip tests for every step type.
- Hook call sites: `WItem.java` (top of `mousedown`), `Inventory.java`
  (top of `drop`), `MapView.java` (Click.hit, before recorder hook for
  Picker, before recorder for normal capture).

## Storage

`%APPDATA%/Haven and Hearth/thunder/macros.json` (Linux/Mac fallback:
`~/thunder/macros.json`). Single file, top-level wrapper:

```json
{ "macros": [ {"name": "...", "defaultRepeat": 1, "steps": [...]} ] }
```

Polymorphic steps carry a `type` field: `ITEM_ACT | GOB_ACT | INV_DROP |
FLOWER_CHOICE | WAIT | SLEEP | CMD`. Unknown fields are ignored on load,
unset fields default per `MacroStep`'s subclass defaults.

## Console commands

```
:macro show               # toggle the list window
:macro list               # print all saved macro names + step counts
:macro run <name> [count] # play; count defaults to defaultRepeat
:macro record             # start recording (use 'save' or 'discard' next)
:macro save <name>        # stop + save the active recording
:macro discard            # stop + throw away the active recording
:macro stop               # alias for discard
:macro cancel             # cancel any running bot (macro or otherwise)
```

`Esc` already calls `auto.Bot.cancelCurrent()` (GameUI.globtype). That
covers macro cancellation since `MacroRunner` is just a Bot.

## Recording flow

1. `:macro show` → click **Record** (or `:macro record`).
2. Perform the actions in-game. Captured intents:
   - Click on inv item → `ItemAct{resid, button, modflags}` (modflags
     captures shift/ctrl/alt at click time, so shift+lclick records as
     a transfer-stack and replays as one). The cursor-following item
     (`ItemDrag`) is skipped so it doesn't intercept clicks meant for
     world targets.
   - Right-click on a gob in the world (empty hand) → `GobAct{resid,
     lastPos, button=3, modflags}` via `MapView.Click.hit`.
   - Right-click on a gob while holding a cursor item → `GobAct{...,
     useHand=true}` via `MapView.iteminteract`. Replay sends `itemact`
     instead of `click` so the server interprets it as "use held item
     on this".
   - Left-click an empty inventory slot to drop a held item →
     `InvDrop{slot}`.
   - Choose a flower-menu option → `FlowerChoice{optionName}`.
3. After every captured intent, a daemon thread polls `gui.prog` for 500
   ms; if a progress widget appears the recorder appends a
   `Wait{PROGRESS, 30000}`. If a different intent fires within the
   window the wait is suppressed (one wait per intent at most).
4. Type a name and click **Save Rec** (or `:macro save <name>`).

Things the recorder does **not** auto-capture: console commands (`Cmd`),
fixed sleeps, conditional waits other than `PROGRESS`. Add those by hand
in the editor.

## Step types

| Type | Fields | Replay behavior |
|------|--------|-----------------|
| `ITEM_ACT` | `resid`, `sdt` (optional), `button`, `modflags` | Find first matching item in `gui.maininv`. Match is `resname == resid` AND, if `sdt` is set, the item's `sdt` bytes equal `sdt`. The sdt comparison is what disambiguates layered-sprite items where the resname is generic (`lib/layspr` for mugs etc.) and the actual item identity is encoded in sdt bytes. Recorder captures sdt automatically; the editor exposes it as a hex string with a Clr button to drop it for loose matching. After find, send the appropriate wdgmsg and wait for the effect to land. `button=1` w/o shift = `take` → wait for cursor to hold matching item. `button=1`+shift = `transfer` → wait for matching count in maininv to drop. `button=1`+shift+meta = `transfer-same`. `button=3` = `iact` → brief wait for menu/progress, no error on timeout. |
| `GOB_ACT` | `resid`, `lastPos`, `button`, `modflags`, `radius=200`, `useHand` | Find nearest gob in `OCache` matching `resid` within `radius` world units of `lastPos`. If `useHand` send `itemact` and **wait for cursor item's resname to change OR menu OR progress** (else error: covers empty-source like an empty barrel). Otherwise send `click` and best-effort wait for menu/progress. Throws if no matching gob found. |
| `INV_DROP` | `slot` (Coord) | `gui.maininv.wdgmsg("drop", slot)` then wait for `gui.hand()` to be empty (else error). |
| `FLOWER_CHOICE` | `optionName`, `timeoutMs=5000` | Subscribes to `Reactor.FLOWER` once, calls `forceChoose(optionName)` on the next menu, waits for `Reactor.FLOWER_CHOICE`. Times out if no menu appears in `timeoutMs`. |
| `WAIT` | `kind`, plus `kind`-specific fields below | Polls a condition until true or timeout. Throws `MacroException` on timeout. |
| `SLEEP` | `ms` | Fixed delay, polled in 50ms increments so cancellation is responsive. |
| `CMD` | `text` | `ui.cons.run(gui, text)`. Use to chain macros (`macro run other`) or invoke any other registered console command. |

### `Wait` kinds

| Kind | Required fields | Notes |
|------|----------------|-------|
| `PROGRESS` | `timeoutMs`, `startTimeoutMs` | Wait for `gui.prog` non-null then null. If never starts within `startTimeoutMs`, returns OK (no-op). |
| `ITEM_APPEARS` | `resid`, `timeoutMs` | Wait until any item with that resname is in main inv. |
| `ITEM_GONE` | `resid`, `timeoutMs` | Wait until no item with that resname is in main inv. |
| `INV_HAS` | `resid`, `minCount`, `timeoutMs` | Wait until at least `minCount` matching items exist. |
| `INV_FREE_SLOTS` | `minCount`, `timeoutMs` | Wait until inventory has `minCount` free slots. |
| `WINDOW_APPEARS` | `windowTitle`, `timeoutMs` | Wait for a `Window` child of `GameUI` whose `caption()` equals the title. |
| `WINDOW_GONE` | `windowTitle`, `timeoutMs` | Inverse. |
| `MESSAGE` | `pattern` (regex), `timeoutMs` | Subscribes to `Reactor.IMSG`, matches via `Pattern.find()`. |
| `GOB_NEAR` | `resid`, `gobNear` (Coord2d), `gobRadius`, `timeoutMs` | Wait for any matching gob to appear within `gobRadius` of `gobNear`. |
| `BUFF_APPEARS` | `buffName`, `timeoutMs` | Wait for any buff whose tooltip text contains `buffName`. |
| `BUFF_GONE` | `buffName`, `timeoutMs` | Inverse. |

## Editor UI

`MacroEditorWnd` (one per macro, opened from list).

- Top: `Name` and `Default repeat` text fields.
- Step list (scrollable, 14 rows). Click a row to select.
- `Type` dropdown + `Add` button — inserts a default-valued step of the
  selected type after the current selection (or at end if nothing
  selected).
- `Up` / `Down` / `Delete` — operate on the selected step.
- `Play once` / `x[N] Play N` / `Save` — play with default or custom
  count, save changes to disk. Renaming the macro removes the old name
  from disk and saves under the new name.
- **Inline step editor pane** at the bottom — switches based on the
  selected step's type. Each has its own widgets (text/dropdown/checkbox)
  and an `Apply` button that writes back to the step. The `Save` button
  at the top commits the macro to disk.

For `GOB_ACT` steps specifically:
- The pane shows `Found: <resid> @ (x, y)` or `Not found within Ru of
  (x, y)` so you immediately know if the saved coord still resolves.
- The matching gob is highlighted in the world via `gob.highlight()`,
  re-pulsed every tick while the step is selected.
- **Pick from world** button → enters `MacroPicker.start(callback)` mode.
  The next right-click on a gob in the world updates the step's `resid`
  and `lastPos`. The pick consumes the click without sending it to the
  server (and without recording it, if a recording is also active).

## Per-step trailing waits

Each step that produces an observable server-side effect blocks until that
effect lands before returning. This means the next step starts in a known
state — no inter-step polling, no implicit waits to add by hand.

Trailing-wait cap is `EFFECT_WAIT_MS = 1500` for all step types. On
timeout, the step throws a clear error so you know which action didn't
take effect (e.g. "useHand had no observable effect on barrel (source
empty?)" — the empty-barrel case for the drink loop).

| Step | Effect-landed signal | Timeout behavior |
|------|---------------------|------------------|
| `ItemAct btn=1` (take) | `gui.hand()` holds an item with matching resid | error |
| `ItemAct btn=1`+shift (transfer) | matching count in maininv drops | error |
| `ItemAct btn=3` (iact) | flower menu opens or `gui.prog` appears | proceed |
| `GobAct` rclick (no useHand) | flower menu opens or `gui.prog` appears | proceed |
| `GobAct` useHand (itemact) | cursor item's `(resname, sdt)` identity changes OR menu OR `gui.prog` | error |
| `InvDrop` | `gui.hand()` is empty | error |
| `FlowerChoice` | `Reactor.FLOWER_CHOICE` fires | error (existing behavior) |

## Cancellation

`MacroRunner` is just an `auto.Bot.execute(...)` wrapper. The Bot's
existing cancellation flow:

- `Bot.cancelCurrent()` flips `cancelled = true` and calls
  `task.cancel()` on the underlying `Defer.Future`.
- Steps poll `bot.checkCancelled()` inside every wait loop and inside
  the `for(step)` loop in `MacroRunner`. `checkCancelled()` was made
  public for cross-package access.
- Esc handler in `GameUI.globtype` (line ~1955) calls
  `Bot.cancelCurrent()`. Left-click on the world also calls it (existing
  MapView behavior); intentional.

Mid-step cancellation: every wait sleeps in 10–100 ms increments and
checks the flag. Worst-case latency is one of those increments plus
network round-trip if the step is mid-`wdgmsg`.

## Editor opening hook for picker

`MacroEditorWnd.tick()` calls `gob.highlight()` on the selected
GobAct's resolved target every tick. The window is added to GameUI as a
top-level child so its tick runs every frame. `destroy()` cancels any
pending `MacroPicker` so closing the editor doesn't leave the picker
armed.

## Known limitations

- **Recorder doesn't capture `Cmd` or fixed sleeps.** Add them in the
  editor via the Type dropdown + Add button.
- **Auto-injected `Wait` is always `PROGRESS`.** Other kinds (window
  appears, message text, etc.) need to be added manually. A future
  refinement could detect window opens via `Reactor.WINDOW` after an
  action and emit `Wait{WINDOW_APPEARS, ...}`.
- **Inventory ops only target main inventory.** `InvDrop` and item
  lookup go through `gui.maininv`. Drops into containers / belt / other
  windows aren't recorded or replayed yet.
- **No control flow.** No `If`, `Loop`, `Stop`. Chain macros via `Cmd
  "macro run other"`.
- **Single recorder, single playback.** `MacroRecorder.start` cancels
  any prior recording. `Bot.setCurrent` cancels any prior bot when a
  macro starts.
- **Hotbar integration not done.** Originally scoped but deferred per
  user request.
- **Per-character storage was considered and rejected** — single global
  `macros.json` per user.
