# Milking Assistant

Auto-deselect a cattle in the roster after the player milks it: clear the
roster mark and hide the floating in-world name so the user can visually
track which cows are still pending in a batch.

Toggle: "Milking assist" checkbox on the roster window (`RosterWindow.milkingAssist`,
persisted via `PREF_MILK_ASSIST = "croster/milking-assist"`).

## Files

- `src/thunder/MilkingAssist.java` — singleton attribution state machine.
- `src/thunder/InventoryActionObserver.java` — shared scaffolding (also used
  by TileQuality).
- `src/thunder/MilkingAssistDebug.java` — `dev.milk.*` console verbs and
  the screen-space painter.
- `src/haven/res/ui/croster/RosterWindow.java` — `unmemorize(UID)` helper.
- `src/haven/res/ui/croster/CattleRoster.java` — `uimsg "upd"` mark
  preservation.

## Detection pipeline (sfx-driven)

1. Player right-clicks a cattle gob -> `MilkingAssist.armPending` captures
   the cattle UID, snapshots the player gob's id and rc, and computes
   distance to the cow.
2. Adaptive TTL: `1000 ms + ~150 ms per tile` (run speed ~7 tiles/s),
   capped at 15 s. A nearby cow has a short window; a far cow gets the
   full walk budget.
3. **Movement probe at +500 ms** (driven by `GItem.tick`): if the player
   gob hasn't started moving (`Moving` attr non-null) and hasn't been
   displaced from arm position, AND the cow was farther than ~2 tiles
   away at arm, the server rejected the click outright -- end early as
   `rejected_no_movement`. (Adjacent cows skip this probe since no walk
   is needed.)
4. On success the server fires the milking sfx via `RootWidget.uimsg("sfx")`
   with resource id resolving to `sfx/fx/water`. `MilkingAssist.onSfx`
   sees it, confirms the resname is milk-related, and resolves:
   - `entry.mark.set(false)` -- drops the roster checkbox.
   - Unless any milk container in main inventory is at capacity (`Level.cur
     >= Level.max`), `RosterWindow.unmemorize(uid)` removes the UID from
     `memorized` so `CattleId.draw` stops rendering the floating name.
     If full, the name stays so the user knows to come back for more.
5. If no sfx arrives by the adaptive deadline, pending silently expires.

**Why sfx and not chres on inventory items?** Empirically:
- Milk pouring into a nearby barrel produces zero chres on inventory items
  (barrels are world gobs; their content isn't streamed to the client).
- The chres + tt traffic that does coincide with a cattle right-click is
  usually a curio-progress tick on an open study window -- 9 items
  updating simultaneously with study-time fields, not buckets.
- `sfx/fx/water` is the only signal that consistently fires only on
  actual milk completion, regardless of where the milk landed.

**Server movement is server-initiated.** The client sends `click(... gobid
button=3)` and the server decides whether to queue a walk: if accepted,
it sends `OD_LINBEG` on the player gob; if rejected (no milk, etc.), it
sends nothing. Hence the movement probe -- absence of `LINBEG`/move attr
within ~500 ms is a positive rejection signal.

The chres-attribution path is the **only** detection mechanism. The
server's `lactate` flag never flips back to false in practice (animals
stay lactating until they hibernate from starvation), so any backstop
keyed on a true->false transition would be dead code.

## Right-click hook placement (non-obvious)

User mouse right-clicks on a gob go through `MapView.Click.hit()`
(MapView.java around line 2929), which dispatches to the **coord-based**
`click(Coord2d, int, Object...)` overload — **not** the `click(Gob, int, ...)`
overload. So a hook in `click(Gob, ...)` will only fire for *programmatic*
clicks (bots, scripted batches like CattleRoster's recolor worker). Real
user clicks miss it entirely.

Hook sites that actually fire on user input:

| Site | Triggered by | Hook call |
|------|--------------|-----------|
| `MapView.Click.hit()` | Empty-hand left/right click on any gob | `MilkingAssist.onGobRightClick(gob, clickb)` |
| `MapView.iteminteract` `Hittest.hit()` | Right-click while an item is on the cursor (drag) | `MilkingAssist.onItemInteract(clicked)` |
| `MapView.click(Gob, int, ...)` | Programmatic invocations only | `MilkingAssist.onGobRightClick(gob, button)` |

All three funnel into `armPending(Gob)`, which short-circuits if the gob
has no `CattleId` attribute or the milking-assist toggle is off — so it's
safe to call from any of them.

## Mark vs memorize (in-world visuals)

`CattleId.draw` (CattleId.java:113) draws the floating name above a cow
when **both** `wnd.isMemorized(id)` is true and the roster window is
visible (or `hideWhenClosed` is off). The roster mark only adds a small
checkbox icon next to the name -- it does not gate name visibility.

Implications:
- Clearing the mark alone leaves the floating name visible.
- To hide the name, also remove the UID from `RosterWindow.memorized`.
- `Refresh Names` rebuilds memorized from `entries.keySet()`, so any cow
  un-memorized by the milk path will reappear after a refresh -- by
  design, since the cow is still in the herd.

`rmseq` bumps in `RosterWindow.memorize` / `clearMemorized` /
`refreshMemorized` are defensive but unnecessary for the name-render
path: `CattleId.draw` reads `isMemorized` live each frame, not through
the cached `entry()` lookup. Only `addroster` strictly needs to bump
`rmseq` (changes which rosters exist).

## Recordings

- `play/proto-recordings/retro-20260416-123312.jsonl` — actual milk
  action: line 5720 (t=7646.547) right-click on cattle gob 2085117310,
  followed at lines 6023-6033 (t=7646.872, ~325ms later) by `chres` on
  inventory items #50, #151, #52, #148, #146, #1386 (buckets transforming
  into milk-buckets).
- `play/proto-recordings/retro-20260416-122619.jsonl` — cattle inspect /
  Bessie roster window flow. No milk action.

Wire-level milking right-click was logged as `Widget #9 'click'` (not
`'itemact'`) -- confirming the bucket was in inventory and the click went
through `Click.hit()` (not `iteminteract`). Both paths are still hooked
since either is a valid milking gesture in principle.

## Debug tooling

Toggle the screen-space painter and dump current state:

```
:dev.milk.debug true        # turn on the painter
:dev.milk.dump              # write timestamped pretty JSON to play/dev-snapshots/milk/
:dev.milk.snapshot          # write JSONL (header + body) for DebugReplay
:dev.milk.fire <uid>        # manually inject a pending UID (test chres path)
:dev.milk.clear             # clear pending
:dev.debug                  # list all debug toggles
```

Dump fields worth knowing:
- `pending` — current in-flight UID, or `null` if none / expired.
- `pending_in_roster` — only meaningful when `pending` is non-null.
- `marked_count` — number of roster entries with `mark.a == true`.
- `lactating_count` — number of entries with `lactate == true` (info only;
  not used in the resolve path).

The live `pending` is captured at one instant -- to see the live state
during the action window, dump **within 5 seconds** of the right-click.

### Protocol capture (forensic mode)

Code instrumentation (per-event tracing of which branch fired) was
deliberately rejected: we want the actual wire stream, not a model of it.
Instead, the feature integrates with `haven.dev.FeatureCapture`, which
wraps `Session.protoBus` for one-shot recording.

Workflow:

1. `:dev.milk.capture` -- arms a one-shot. Prints "capture armed".
2. Perform a milk action (right-click cattle with bucket carried).
3. `armPending` calls `cap.beginIfArmed(sess, meta)` -- recording starts;
   every protocol event landing on `ProtoBus` is appended to a bounded
   ring buffer (5000 events).
4. Either `tryResolve` succeeds (`endIfActive("resolved", ...)`) or
   `checkExpiry` fires past the TTL (`endIfActive("expired", ...)`) or
   a re-arm supersedes it (`endIfActive("superseded", ...)`).
5. The end call writes
   `play/dev-snapshots/milk/capture-<outcome>-<ts>.jsonl`. The header
   line carries `outcome`, `begin_ms`, `end_ms`, `duration_ms`, `begin_meta`
   (uid, source, ttl_ms), `end_meta` (uid, trigger_item_res), and
   `event_count`. Each subsequent line is one `ProtoEvent` (same shape as
   `RetroCapture`'s output: `t`, `rel`, `dir`, `cat`, `type`, `summary`,
   `gob`/`wid` when relevant, optional `detail`).

Open the JSONL in your editor or grep it; it contains both directions of
the wire across the entire feature invocation, so questions like "did
the server send a chres", "did the player gob get OD_LINBEG", "was there
an err/msg" can all be answered by reading the file.

The capture is auto-armed only -- there's no rolling background mode. If
you want a particular attempt captured, arm immediately before doing it.

## Open: detecting "no milk" rejection

When a player right-clicks a cow that has no milk to give, the player
character does not move and no chres lands -- but `armPending` already
fired, so the next unrelated `chres` (e.g., autopickup) within the 5s
window will be misattributed and incorrectly deselect the original cow.

Likely server-side signals to listen for, in order of preference:

1. **`GameUI.uimsg "msg"` / `"err"`** -- info/error toasts published via
   `Reactor.IMSG` / `Reactor.EMSG` (GameUI.java:1664). If the server sends
   a "no milk" string, subscribe and clear pending on match.
2. **Player gob LINBEG absence** -- after `armPending`, watch the player
   gob for OD_LINBEG within ~200ms. No movement = action rejected.
   Brittle (missed paths, server lag) but doesn't depend on string match.
3. **Action queue rejection** -- if the server rejects via a typed
   protocol message rather than a sysmsg, look there.

Need a recording of an attempted-but-empty milk to identify the actual
signal text. Procedure:

1. Find a cow you've recently milked (still on cooldown / lactate
   exhausted).
2. Toggle proto recording on (whatever the dev switch is).
3. Right-click with bucket in hand. Observe failure.
4. Stop recording.
5. Grep the resulting JSONL for the cow's gob ID, then look at what
   widget/sysmsg traffic landed in the seconds after the right-click.

Then wire `MilkingAssist` to clear pending on the matched signal.
