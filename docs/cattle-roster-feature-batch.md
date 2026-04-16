# Cattle roster feature batch

Adds five features to the cattle roster window (`src/haven/res/ui/croster/`):

1. Primary + secondary sort
2. Milking assist (auto-deselect on milk)
3. Draggable column resize
4. Color swatch picker (recolor all selected)
5. Larger select-by / count text, relocated count

All changes build with `ant bin`. No persisted prefs beyond the milking-assist checkbox (which lives on `RosterWindow`).

---

## Protocol findings (from captures)

### A. Re-color a cattle

Recording: `play/proto-recordings/retro-20260416-122619.jsonl`, gob `250334905`.

```
OUT  Widget #9 (MapView) 'click' [..., 3, 0, 0, 250334905, ...]       // right-click cattle, no mods
IN   RMSG_ADDWDG parent=#8 args=["misc", pos, ["obj", 250334905]]     // server opens cattle window
IN   NEWWDG type=grp   parent=<cattle-wnd> CArgs=[0]                  // current color index
IN   NEWWDG type=text  parent=<cattle-wnd> CArgs=[150, "Bessie..."]   // editable name
OUT  Widget #<grp> 'ch' [5]                                           // pick color 5
IN   Gob 250334905 OD_RESATTR ui/obj/buddy-n
IN   Widget #<roster> 'upd' [UID, name, 5, ...]                       // roster receives update
```

The `grp` widget is the standard `BuddyWnd.GroupSelector`. Same `ch N` protocol as the kin list. Bonus: the `text` child is a standard `TextEntry`; renaming uses `'activate' <new-name>` outbound — save for later use.

### B. Milk a cattle

Recording: `play/proto-recordings/retro-20260416-123312.jsonl`, gob `2085117310`.

```
(earlier) OUT  Widget #<inv-slot> 'act' ["carry", ...]    // pick up bucket into carry cursor
OUT       Widget #9 'click' [..., 3, 0, 0, 2085117310, ...]   // right-click cattle with bucket
IN        sfx 'sfx/fx/water'                              // milking sound (res 12879)
IN        Widget #<item-N> 'chres' [<filled-bucket-resid>]    // empty -> full bucket
IN        Widget #<item-N> 'tt' [[attrs incl. liquid contents]]
          (multiple items in sequence -- all eligible containers fill)
```

No flower menu. Milking fires off a single right-click with a container carried. The detectable client-side signal is `chres` + `tt` on GItem widgets -- the same hook point `TileQuality` uses.

---

## Feature plans

### 1. Primary + secondary sort

**Files:** `CattleRoster.java` only. `Column.java` unchanged.

- Add fields: `Column<? super T> ordercol2; boolean revorder2; Comparator<? super T> order2;`.
- `mousedown` on a header:
  - Left-click: set primary to this col, toggle reverse if same col, clear secondary.
  - Right-click: set secondary to this col, toggle reverse if same secondary col. Don't touch primary.
- `tick` builds effective comparator: `order` (reversed if needed) `.thenComparing` `order2` (reversed if needed). Append `namecmp` as final tiebreaker so sort is stable across equal keys.
- `drawcols`: keep existing yellow tint for primary; add a dimmer tint (alpha ~10) for `ordercol2`. No extra glyph -- tint is enough.
- Session-only; no prefs.

### 2. Milking assist

**Files:** `RosterWindow.java` (checkbox + pref), `CattleRoster.java` (mark preservation + lactate-flip hook), `src/thunder/MilkingAssist.java` (new), hook into `GItem.uimsg` / `GItem.tick`, hook into `MapView.Selector.mmousedown` for pending.

#### Layer A -- lactate flip on `upd` (simple fallback)

- In `CattleRoster.uimsg("upd")`:
  - Snapshot old entry's `mark.a` and `lactate` (via reflection helper).
  - Destroy + recreate as today.
  - If `milkingAssist && wasLactating && !nowLactating`: skip mark restore (auto-deselect).
  - Otherwise restore `mark.a`.
- Side benefit: `mark` is now preserved across every `upd`, which fixes the latent bug where selection vanishes whenever the server updates an entry.

#### Layer B -- observe milk item arriving (primary mechanism)

New `thunder.MilkingAssist`:

- `currentPending`: UID of the cattle that was just right-clicked while the player held a carry cursor over a cattle gob with `CattleId`.
- Set pending from a hook in `MapView.Selector.mmousedown`: when the player right-clicks a `CattleId` gob while `ui.root.cursor` is a carry cursor (bucket-shaped). The pending is replaced on each new right-click, cleared when the carry cursor goes away.
- `onItemChres(GItem item, String newResName)` + `onItemInfoUpdate(GItem item)` -- mirror of `TileQuality.onItemInfoUpdate` / `onItemTick`. If pending is set and the item resource (post-chres) matches our milk resource set, deselect the pending cattle's roster entry and clear pending.
- Milk resource match: start with a small configurable allowlist (e.g. `gfx/invobjs/bucket-milk`, any `/bucket-*` variant that the capture reveals). First live run will pin down exact names -- tweak the allowlist from there.

Checkbox lives on `RosterWindow` beside "Highlight" / "Hide when closed"; pref key `croster/milking-assist`.

#### Reusability note (future water-fill for tile quality)

Both mining and milking (and later water-fill) share the pattern: action on a world object -> items in inventory created or transformed -> `chres` / `tt` updates are the signal. The helper lives in `src/thunder/InventoryActionObserver.java`, small and single-purpose: it owns a `currentPending<T>` + `retries<GItem, T>` map, and exposes `onItemInfoUpdate`/`onItemTick`/`setPending`/`clearPending`. `MilkingAssist` uses it; `TileQuality` stays as-is for now (too tangled with map state); water-fill will construct its own `InventoryActionObserver<TilePending>` when we add it. The helper's javadoc documents the steps to plug in a new action.

### 3. Draggable column resize

**Files:** `CattleRoster.java` + `Column.java` (`int minw` default, no migration).

- Edge zone: within `UI.scale(4)` of `col.x + col.w`, header band only (`y < HEADH`).
- `getcurss`: horizontal-resize cursor when hovering an edge zone or actively resizing.
- `mousedown` button 1 on edge zone: capture `resizingCol`, `grabStartX`, `grabStartW`; `ui.grabmouse(this)`. Suppress the sort-click branch.
- `mousemove` while resizing: `col.w = max(minw, grabStartW + (ev.c.x - grabStartX))`, then repack downstream column `x` positions (extracted helper `packColumns()` that mirrors `initcols` on the existing list).
- `mouseup`: release grab, clear `resizingCol`, `dirty = true`.
- Per-row content already uses `col.x`/`col.w`, so it reflows automatically.
- Session-only; no prefs.

### 4. Color swatch picker

**Files:** `CattleRoster.java` (new button + new `SetColorWnd` + worker), uses `BuddyWnd.GroupSelector` for visuals.

- Add a "Set color" button in the button bar. Click opens `SetColorWnd` showing the 8 `BuddyWnd.gc[]` swatches (reuse `GroupSelector` rendering).
- Pick a swatch -> worker thread loops through `entries.values()` where `mark.a`:
  1. Find gob in OCache (via `CattleId` attribute).
  2. `ui.gui.map.click(gob, 3)` to open the cattle "misc" window.
  3. Wait up to ~2s for a GameUI child window whose "misc" args match `["obj", gobid]`; locate its `GroupSelector` child by walking children.
  4. `groupSelector.select(color)` (which dispatches `ch N` upstream exactly as the manual click would).
  5. `wnd.wdgmsg("close")` to close.
  6. Small settle delay (~80ms) before next cattle.
- Reuse the `scanning` guard pattern from `scanCastration`. Selection left intact after the run (user can re-issue).

### 5. Larger select-by text + relocated count

**Files:** `CattleRoster.java`.

- Larger foundry (e.g. bump from `CharWnd.attrf` to a ~UI.scale(14) foundry -- match whichever size the surrounding label widgets use when they want to read bigger). Applies to:
  - `SelDrop.drawitem` rendering.
  - The dropdown's selected-item display.
  - The count `Label`.
- Relocate count to the right side of the button row, immediately left of "Remove selected". That removes any crowding against "Refresh Names".
- Reserve width for at least 9 chars at the new size (pre-render `"888888888"` to size the label) so values like `9999/9999` never truncate or overlap neighbors.

---

## Implementation order

1. Larger font + relocated count (feature 5).
2. Primary + secondary sort (feature 1).
3. Column resize drag (feature 3).
4. Mark preservation + lactate-flip milking assist -- layer A of feature 2.
5. `InventoryActionObserver` helper + chres/tt milking detection -- layer B of feature 2.
6. Color swatch picker (feature 4).

Build after each step with `ant bin`.

## Tests

Pure logic is extracted into `thunder.roster.RosterLogic` so unit tests don't
need a Haven UI wired up. Covered:

- `src/test/java/thunder/InventoryActionObserverTest.java` - the reusable
  pending/retry store behind MilkingAssist (and future water-fill).
- `src/test/java/thunder/roster/RosterLogicTest.java` - `shouldRestoreMark`
  (milk-assist decision across every lactate transition), `combineOrder`
  (primary/secondary/tiebreaker precedence), `packColumns` and
  `columnAtEdge` (resize hit-testing and layout math), and `applyResize`
  (min-width clamping).
- `src/test/java/haven/res/ui/croster/RecolorReportTest.java` - message text
  built for each combination of success/failure categories.

Run with `ant test`. Tests compile into `build/test-classes` alongside the
existing JUnit 5 suite.

## Recolor failure handling

`RecolorReport` (public static inner class on `CattleRoster`) tracks five
categories: `recolored`, `alreadyColor`, `offscreen`, `windowMissed`,
`grpMissed`. The batch's pre-flight computes how many selected cattle have a
gob in OCache; if any are missing the user is warned before the worker
starts, and the final summary line breaks down which categories accumulated.
The worker no longer returns a simple boolean per cattle -- it mutates the
report so the final message is precise about what happened.

Window dismissal uses the explicit `SetColorWnd.this.reqdestroy()` to tear
down the whole window (not just the enclosing `GroupSelector`).
