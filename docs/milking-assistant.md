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

## Detection pipeline (sfx-driven, phase machine since 2026-09-02)

The pending is a three-phase state machine driven by the player gob's
movement attrs, not a distance-estimated timeout. Rationale: H&H never
acks gob clicks, but the server *does* leak the action lifecycle -- it
homes the player on the clicked gob (`OD_HOMING` carries the target gob
id, observed at +0.084s in capture-expired-20260902-114026), the walk
end is visible as the `Moving` attr dropping, and success fires the sfx.

1. **ACCEPT** -- `MilkingAssist.armPending` on right-click captures the
   cattle UID, player/target gobs and positions. A non-adjacent target
   must see a walk start within 1.5 s (ideally `Homing` with our target's
   gob id) or the click was rejected (`rejected_no_movement`). Adjacent
   targets (<= 2 tiles) skip straight to ACTING.
2. **EN_ROUTE** -- the walk is *tracked*, not estimated: the pending stays
   alive while the `Moving` attr persists, however long the chase of a
   wandering animal takes (60 s sanity cap only). This replaced the
   `~150 ms/tile` walk budget, which expired mid-chase on the first
   animal of a batch (capture-expired-20260902-114026: still `OD_HOMING`
   at +3.565s when the 3.6 s budget hit). A homing that switches to a
   different gob cancels the pending (`cancelled_retargeted`).
3. **ACTING** -- the walk ended; the milk sfx must arrive within 3 s.
   The milking takes ~1.0-1.5 s between arrival and sfx
   (capture-expired-20260426-144457: click +0.000s, sfx +1.034s).
   On `sfx/fx/water` via `RootWidget.uimsg("sfx")`, `onSfx` resolves:
   - `entry.mark.set(false)` -- drops the roster checkbox.
   - Unless any milk container in main inventory is at capacity (`Level.cur
     >= Level.max`), `RosterWindow.unmemorize(uid)` removes the UID from
     `memorized` so `CattleId.draw` stops rendering the floating name.
     If full, the name stays so the user knows to come back for more.
   No sfx by the window's end = expired (a no-milk rejection at melee
   range is signal-free).

An sfx heard before ACTING is *held*, not judged: you cannot milk an
animal you have not reached, but "arrival" is only visible as the Moving
attr clearing, and that objdata can be ordered after the sfx uimsg in
the same server batch. The held sfx counts if the walk ends within
500 ms of it (`ARRIVAL_RACE_MS`), and is discarded if the player
demonstrably kept walking past that window -- which is what bounds the
theoretical leftover-misattribution mode (a previous animal's late sfx
landing on a freshly armed pending) without second-guessing genuine fast
resolves: own-sfx latency is a range, +0.392s to +1.034s on record
(capture-resolved-20260426-163048, 22 s isolated from any other milking,
vs capture-expired-20260426-144457). Phase transitions and windows are
regression-tested by `thunder.MilkingAssistPhaseTest`; the held-sfx race
by the two arrival-race tests in `MilkingAssistSfxLoadingTest`.

**Loading sfx must be stashed, not dropped.** `onSfx` runs synchronously at
uimsg time, and the sfx often arrives together with its first-time
`RMSG_RESID` binding (fresh session, first milk, relog) -- `resid.get()`
throws `Loading` at that instant. The message is one-shot, so `onSfx`
stashes the still-loading resid on the pending (`Pending.loadingSfx`) and
`driveTimers` re-checks it each item tick until it loads (deadline extended
to at least +5s on stash). Dropping it instead caused the original
intermittent "deselect sometimes doesn't fire" bug: compare
`capture-expired-20260426-163026-292.jsonl` (sfx + RESID at +0.373s, inside
TTL, yet expired) with `capture-resolved-20260426-163048-731.jsonl` (22s
later, resource cached, no RESID line, resolved). Regression-tested by
`thunder.MilkingAssistSfxLoadingTest`.

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

### Feature notes in the capture (2026-09-02)

The wire stream alone couldn't distinguish "sfx never sent" from "sfx
mishandled" (the 20260902-114026 expired capture: a wandering sheep
4.3 tiles out, zero server response, no way to tell whether the player
was still chasing at expiry). Two additions close that gap:

- `FeatureCapture.note(summary)` appends synthetic `type=NOTE` events
  that interleave chronologically with wire traffic. MilkingAssist notes
  every decision: arm (uid/distance/ttl), each sfx heard (milk,
  non-milk, still-loading-stashed, stash-occupied-DROPPED), stash
  load results, movement-probe verdicts, and resolve-found-no-entry.
  View with `python tools/proto_explore.py <f> timeline --type NOTE`.
- `begin_meta` now records `player_gob`/`target_gob` and both `rc`
  positions; expiry/rejection `end_meta` records `player_moving_at_end`,
  `player_displaced_tiles`, `target_displaced_tiles`,
  `dist_remaining_tiles`, `target_gone`, `movement_seen`,
  `sfx_stash_pending`, and `deadline_extended_ms` -- enough to separate
  "still walking when the deadline hit" (chase outran the static walk
  budget) from "arrived, milked, sfx late" from "server ignored the
  click".

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
