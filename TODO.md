# Loftar merge follow-up (2026-05-02)

The 2026-05-02 merge of `loftar/master` brought in a new marker class
hierarchy and supporting refactors that collide with Thunder's own marker
infrastructure (`me.ender.minimap.{Marker,PMarker,SMarker,CustomMarker}`).
The merge resolution kept Thunder's hierarchy and parked loftar's incoming
classes / wiring as `*Old` dead code or removed them with TODO markers.
Each item below is one chunk of the consolidation that still needs doing.

When you adopt one, search for the matching `TODO(loftar-merge)` comment
in the source — that's the parking spot for the corresponding loftar code.

## 1. Marker class consolidation

**Where:** `src/haven/MapFile.java` (look for `MarkerOld` / `PMarkerOld` /
`SMarkerOld`); `src/me/ender/minimap/{Marker,PMarker,SMarker,CustomMarker}.java`.

**What loftar wants:** `MapFile.{Marker,PMarker,SMarker}` are the canonical
marker classes, with new fields:
- `final MapFile file` — back-pointer for `update(boolean save)`
- `volatile int seq` — change counter, bumped by `MapFile.add/remove/update`
- `MapFile.markerseq` is `volatile`
- `loadmarker` is an instance method (not static) so it can pass `this` to ctors
- `SMarker.oid` is `UID` (was `long`), and `SMarker` carries `byte[] data`
- `Marker.toString()` defined for both subclasses

**What Thunder has instead:** Top-level `me.ender.minimap.Marker` with
abstract `draw(GOut, Coord, Text, float, MapFile)` and `area()`, plus
`PMarker`/`SMarker`/`CustomMarker` subclasses. `me.ender.minimap.SMarker`
also carries `questConditions`, `questIterator`, custom `equals/hashCode`,
and `data`. `MapFile.markers` is `Collection<me.ender.minimap.Marker>`.

**Adoption path:**
1. Move `draw()` and `area()` abstract methods onto `MapFile.Marker`.
2. Port `me.ender.minimap.PMarker`'s flag rendering (`flagbg/flagfg/flagcc`,
   `draw`, `area`, `equals`) into `MapFile.PMarker`.
3. Port `me.ender.minimap.SMarker`'s `questConditions`/`questIterator`/
   `draw`/`area`/`equals/hashCode` into `MapFile.SMarker` (already has
   `data` field from this merge).
4. Update `me.ender.minimap.CustomMarker` to extend `MapFile.PMarker` (or
   `MapFile.SMarker` — pick whichever the class actually behaves like).
5. Delete `src/me/ender/minimap/{Marker,PMarker,SMarker}.java`.
6. Re-rename `MarkerOld` / `PMarkerOld` / `SMarkerOld` away — they were
   only kept as dead code to avoid the import collision. Remove them.
7. Add `import haven.MapFile.{Marker,PMarker,SMarker};` to all consumers
   that previously got these via `import me.ender.minimap.*;`. Wildcard
   imports do not pull in nested classes.
8. Update construction sites to pass the `file` arg:
   - `MapWnd.java:436` `new PMarker(loc.seg.id, ...)` → `new PMarker(file, loc.seg.id, ...)`
   - `MapWnd.java:1096` `new SMarker(info.seg, ...)` → restore loftar's `new SMarker(file, info.seg, ...)`
   - `MapWnd2.java:74` and `MapWnd2.java:143` likewise
   - `MiniMap.java:1322` likewise
   - `MapFile.loadmarker` switches back to instance method calling `new PMarker(this, ...)` etc.
9. Restore `mark.seq++` calls in `MapFile.add/remove/update` (and the
   segment-merge loop near `MapFile.java:~1648`) once `Marker.seq` exists.
10. Switch `MapFile.savemarker` back to instance method if needed (loftar's
    version was the same shape as Thunder's static one — no action likely).
11. Re-fetch loftar's full `MapFile` diff to verify nothing else snuck past
    the parked changes.

**Acceptance:** `ant bin` clean; `ant test` clean; markers placed via
in-game UI persist across restart and reload correctly; quest-helper
highlights still render.

## 2. MapWnd marker placement (`MarkButton`)

**Where:** `src/haven/MapWnd.java` — look for the `TODO(loftar-merge)`
in the marker-button class around `MarkButton`.

**What loftar wants:** Marker placement uses `ui.grabmouse(this)` plus a
`PlaceMarker` `Widget.PointerEvent`. Right-click ungrabs; left-click on
the minimap calls `MiniMap.xlate(c)`; left-click on the MapView raycasts
via `FindMark` (extends `MapView.Maptest`). Cleaner than a flag.

**What Thunder has instead:** `domark` boolean flag. The button toggles it,
`MiniMap.clickloc` checks it before creating a `PMarker`. Right-click
clears `domark`. The cursor swaps to `markcurs` while `domark` is on.

**Adoption path:**
1. Bring back loftar's added methods on `MarkButton`: `state()`, `click()`,
   `mark(Location, boolean)`, `ungrab()`, inner `FindMark` (extends
   `MapView.Maptest`), inner `PlaceMarker` (extends `Widget.PointerEvent`),
   and `mousedown(MouseDownEvent)`.
2. Drop the `domark` flag and the `clickloc` branch that uses it.
3. Update `getcurs` to return the mark cursor while a grab is active rather
   than while `domark` is true.
4. Make sure `mark()` uses the consolidated marker classes from item 1
   (passes `file` to the `PMarker` ctor).

**Acceptance:** Click the mark button on the map window, then click on
either the minimap or the world view — a new `PMarker` lands at the
clicked location. Right-click cancels.

## 3. `MiniMap.DisplayMarker` rewrite (`Markers` / `MarkerIcon` cache)

**Where:** `src/haven/MiniMap.java` — look for the `TODO(loftar-merge)`
on the `DisplayMarker` class declaration.

**What loftar wants:** `DisplayMarker` becomes a thin `(MiniMap mm, Marker
m)` wrapper that delegates `icon()` and `tooltip()` to a per-MiniMap
`Markers` cache (shared across all `DisplayGrid`s). The cache keys icon
state by `Marker` and tracks `Marker.seq` to invalidate when the marker
mutates. `MarkerIcon` is the per-marker cache cell, holding a deferred
`Loader.Future<GobIcon.Icon>` so icon resolution can be async without
blocking draw. `DisplayMarker.sc` (screen coord, set during draw) feeds
into `MiniMap.mousehover` for marker hover handling.

**What Thunder has instead:** `DisplayMarker` is full-featured — it owns
its own icon resolution, info caching, tooltip rendering, quest-highlight
drawing, scaling (`iconmult`), PVP-mode filtering, dynamic tip text via
`checkTip`, and a richer `OwnerContext.ClassResolver`. There is no
shared `Markers` cache; each `DisplayGrid.markers(boolean, UI)` returns
fresh `DisplayMarker` instances per remark.

**Adoption path (only if you want loftar's caching benefits):**
1. Re-add the `Markers` and `MarkerIcon` classes (parked under
   `TODO(loftar-merge)` — currently deleted but recoverable from
   `git show <merge-commit>^2:src/haven/MiniMap.java`).
2. Re-add the `Markers markers = new Markers(this);` field on `MiniMap`.
3. Re-introduce `DisplayMarker.sc` field and the loftar `mousehover` loop
   (parked under `TODO(loftar-merge)` near the Thunder hover handler).
4. Decide whether icon/info caching moves entirely into `MarkerIcon` or
   remains on `DisplayMarker`. If moving, keep Thunder's quest highlight,
   PVP filter, dynamic tip, and scaling in `DisplayMarker`'s `draw()`
   while delegating icon resolution to `mm.markers.get(m)`.
5. Restore `DisplayGrid.markers(boolean)` single-arg overload (the loftar
   call site at the parked hover loop uses it) — Thunder's existing
   two-arg variant takes a `UI`, the no-UI form would need to read `ui`
   off `mm`.
6. Depends on consolidation item 1 (needs `Marker.seq`).

**Acceptance:** Markers still render with quest highlights, custom icons,
and scaling; hovering a marker on the minimap surfaces tooltips even when
the cursor isn't over a `DisplayIcon`.

## 4. Notes on what was integrated cleanly (no follow-up)

These loftar changes landed in this same merge with no parking:
- `BinHeap.java` (new), `Coord.isect`, `Coord2d` corner-based isect helpers, `Line2d` extensions
- `GOut.line(Coord, Coord, double)` now clips natively against `ul/br`
  using `Line2d.twixt(...).clip(...)`. Thunder's `clippedLine` was kept
  as a deprecated thin wrapper that delegates to `line` (`GOut.java:279`).
- `GobIcon.java` cached-icon resource error handling (more robust load failures)
- `MapFile.markerseq` is now `volatile` (kept Thunder's `markerids` field alongside)

---

# Ender Cherry-picks

Cherry-picking from `ender/master` (since Jan 2026).

## Bug fixes

- [x] `789ef370b` — added null check for mousewheel transfer
- [x] `8b5539050` — fixed hitbox for produce sacks
- [x] `a2b94b06b` — fixed Gob quality and alchemy crashes related to loading resources in rich text
- [~] `286f2e10b` — fixed minimap grid and view box (SKIPPED: conflicts with our minimap zoom implementation)
- [~] `5f30f5ac6` — fixed gob radius not showing (SKIPPED: we have our own ToggleRadius system)
- [x] `cc576de99` — fixed loading crash when showing smelter timer

## Features

- [ ] `f25f10dd7` — added crane to auto aggro animal list
- [ ] `f0dd7b9cc` — allow customizing mine support colors + alpha
- [ ] `135b1ae63` — reset mine support colors on config load if too old
- [ ] `d66c3f5a0` — use loftar's mine support overlay with custom configs + HP threshold option (may conflict with ToggleRadius)
- [ ] `e64d8eb70` — config options to record/replay protocol dumps

## Minimap magnification (apply in order)

- [ ] `26274a0a0` — add minimap magnification
- [ ] `6ecef7409` — undo UI scaling for minimap grids
- [ ] `e980f0fc7` — use UI scale to determine initial minimap magnification
- [ ] `d6cbdbfde` — fix minimap rounding error
- [ ] `e77cacf2d` — fix Area.closest
- [ ] `7d0ee6aa3` — hack around rounding errors in minimap tile lookup
- [ ] `79d046641` — fix terrain tooltip on zoomed-out minimap
- [ ] `b888faa21` — improve minimap tooltips
- [ ] `9bbe03fd8` — make minimap scale handling more well-defined
- [ ] `00ff713d9` — let minimap mag-factor be a factor rather than power-of-two
- [ ] `fdaca3ddc` — calculate minimap marker coordinates consistently
- [ ] `64665f456` — translate minimap marker coordinates consistently between drawing and clicktesting
- [ ] `7e56b56a5` — fix minimap icons with notifications being retained indefinitely
- [ ] `9f11907a1` — fix minimap update bug
- [ ] `1263f6f12` — defer map-window marker focusing so filter can be changed
- [ ] `62a22ac17` — make wound-info-box not flicker on initial render
- [ ] `6bb51ad60` — fix minimap overlay magnification bug
- [ ] `8314c6859` — fix minimap magnification update bug
- [ ] `a670c6657` — fix minimap overlay scaling
- [ ] `b5ddc0beb` — add minimap location toString
- [ ] `77277c4cb` — fix session-locator segment invalidation bug

---

# Protocol Debug & State Inspection Facility

Thunder has no real-time way to inspect what the server is telling the client. The only existing facility is `Transport.Callback.Recorder` which dumps raw hex to a file (enabled via `-Dhaven.record=path`), and `Transport.Playback` which replays it. There's no decoded view, no filtering, no live game state inspection, and no statistics. This makes feature development and debugging a guessing game.

This plan adds a comprehensive in-game debugging suite: a live protocol inspector with human-readable message decoding, a game state inspector (gobs, widget tree, resource mappings), a protocol statistics dashboard, enhanced recording, and console commands.

## Architecture

```
Network Thread                    UI Thread
     |                                |
Connection callbacks ──> ProtoBus ──> drainToUI() ──> ProtoInspector (live view)
  (handle/objdata/map)   (queue)                  ──> ProtoStats (rates/bandwidth)
                                                  ──> StateInspector (gob/widget/res)
                                                  ──> EnhancedRecorder (annotated file)
```

**Key design**: `ProtoBus` implements `Transport.Callback`, gets registered alongside the existing `conncb` in `Session`. On the network thread it clones messages and decodes them into immutable `ProtoEvent` objects, enqueuing to a `ConcurrentLinkedQueue`. The UI thread drains this queue during `GameUI.tick()` and distributes to consumers. Zero overhead when disabled (`capturing = false` short-circuits).

## Phase 1: Core Infrastructure + Live Inspector

- [x] **1.1** `src/haven/proto/ProtoEvent.java` — Immutable event record (`timestamp`, `Direction`, `Category`, `typeName`, `summary`, `detail`, `gobId`, `widgetId`, `sizeBytes`)
- [x] **1.2** `src/haven/proto/ProtoDecoder.java` — Stateless decoder: `decodeRel()`, `decodeObjDelta()`, `decodeMapData()`, `decodeOutgoing()`. Maps all RMSG_* and OD_* types to human-readable names. Falls back to hex for unknown types.
- [x] **1.3** `src/haven/proto/ProtoBus.java` — Central hub implementing `Transport.Callback`. `ConcurrentLinkedQueue` for network→UI thread transfer. Ring buffer history. `drainToUI()` called from `GameUI.tick()`.
- [x] **1.4** Modify `Session.java` — Add `protoBus` field, register as callback in constructor (after `conn.add(conncb)`)
- [x] **1.5** Modify `RemoteUI.java` — Capture outgoing widget messages in `rcvmsg()`
- [x] **1.6** `src/haven/proto/ProtoEventList.java` — Custom `Listbox<ProtoEvent>` with color-coded categories, direction arrows, auto-scroll
- [x] **1.7** `src/haven/proto/ProtoInspector.java` — `GameUI.Hidewnd` window with category/direction/text filters, event list, detail panel, pause/clear/record buttons
- [x] **1.8** Config & integration — `CFG.java` entries, `GameUI.java` field + toggle + keybinding + tick drain
- [x] **1.9** `OptWnd.java` — Debug panel with enable checkbox and open buttons

## Phase 2: Game State Inspector

- [x] **2.1** `src/haven/proto/GobInspectorPanel.java` — Live gob state display (position, resource, attributes, overlays, recent protocol events). Text entry for gob ID + "Pick from map" button.
- [x] **2.2** `src/haven/proto/WidgetTreePanel.java` — Recursive widget hierarchy from `ui.root`. Shows widget ID, class, position, size, visibility. Refresh on demand.
- [x] **2.3** `src/haven/proto/ResourceMapPanel.java` — `sess.rescache` display with `FilteredListBox` (ID → name, version)
- [x] **2.4** `src/haven/proto/StateInspector.java` — `GameUI.Hidewnd` with `Tabs` containing gob/widget/resource panels. Wire up toggle + keybinding in `GameUI.java`.

## Phase 3: Protocol Statistics Dashboard

- [x] **3.1** `src/haven/proto/ProtoStats.java` — Rolling per-type counts/bytes in 1-second windows. Total bandwidth + message rate. Per-gob update frequency. 60-second sparkline history.
- [x] **3.2** `src/haven/proto/StatsPanel.java` — `GameUI.Hidewnd` showing rate/bandwidth, per-type table, sparkline chart. Wire up toggle + keybinding.

## Phase 4: Enhanced Recording

- [x] **4.1** `src/haven/proto/EnhancedRecorder.java` — Annotated recording (existing binary format + `# human-readable` comment lines, backward-compatible with `Playback`). Category filter, bookmark insertion, start/stop from UI.

## Phase 5: Console Commands

- [x] **5.1** Add to `GameUI.cmdmap`: `proto stats|pause|resume|clear|record`, `gob inspect <id>`, `widget tree`, `res lookup <id>`

## Files Summary

### New files (12)
| File | Est. Lines | Purpose |
|------|-----------|---------|
| `src/haven/proto/ProtoEvent.java` | ~120 | Immutable decoded event record |
| `src/haven/proto/ProtoDecoder.java` | ~400 | Stateless message decoder |
| `src/haven/proto/ProtoBus.java` | ~180 | Central event hub + Transport.Callback |
| `src/haven/proto/ProtoEventList.java` | ~200 | Custom Listbox for event display |
| `src/haven/proto/ProtoInspector.java` | ~350 | Live protocol inspector window |
| `src/haven/proto/GobInspectorPanel.java` | ~250 | Gob state display panel |
| `src/haven/proto/WidgetTreePanel.java` | ~200 | Widget tree display panel |
| `src/haven/proto/ResourceMapPanel.java` | ~120 | Resource ID→name display panel |
| `src/haven/proto/StateInspector.java` | ~150 | Tabbed state inspector window |
| `src/haven/proto/ProtoStats.java` | ~200 | Rolling statistics accumulator |
| `src/haven/proto/StatsPanel.java` | ~250 | Statistics dashboard window |
| `src/haven/proto/EnhancedRecorder.java` | ~200 | Annotated recording facility |

### Modified files (5)
| File | Change |
|------|--------|
| `Session.java` | Add `protoBus` field, register in constructor |
| `RemoteUI.java` | Capture outgoing widget messages |
| `GameUI.java` | Fields, toggle methods, keybindings, tick integration, console commands |
| `CFG.java` | Add debug config entries |
| `OptWnd.java` | Add Debug panel with toggles and buttons |

### Key existing code to reuse
- `GameUI.Hidewnd` (GameUI.java:835) — toggleable window base
- `Listbox<T>` (Listbox.java:31) — virtualized scrolling list
- `Tabs` (Tabs.java:31) — tabbed panel container
- `CFG<T>` (CFG.java) — type-safe persistent config
- `Transport.Callback` (Transport.java:39) — protocol tap interface
- `PMessage.clone()` / `ObjDelta.clone()` / `AttrDelta.clone()` — safe message cloning
- `FilteredListBox`, `TextEntry`, `CheckBox` — UI widgets

## Threading Safety
- Network thread: clones messages, enqueues immutable `ProtoEvent` to `ConcurrentLinkedQueue` (lock-free)
- UI thread: drains queue in `tick()`, all widget updates here
- `capturing` is `volatile boolean`
- `ProtoEvent` immutable after construction
- State inspector reads live game state on UI thread (same thread as rendering)

---

# Testing Progress

**Last updated:** 2026-04-10
**Total tests:** 673 passing (`ant test`)
**Test files:** 32

## Infrastructure

- [x] JUnit 5 standalone JAR in `lib/junit-platform-console-standalone-1.10.2.jar`
- [x] `compile-tests`, `test`, `clean-tests` Ant targets in `build.xml`
- [x] Test source root at `src/test/java/`
- [x] Main javac excludes `test/**` to avoid classpath conflicts
- [ ] GitHub Actions CI (`.github/workflows/build.yml`) — not yet created

## Completed Test Files

### Phase 1: Pure Logic (344 tests)

| Test file | Tests | Class under test |
|-----------|-------|-----------------|
| `CoordTest` | 31 | `Coord` — factory, arithmetic, distance, angle, clip, compare |
| `Coord2dTest` | 40 | `Coord2d` — factory, arithmetic, rounding, trig, norm |
| `Coord3fTest` | 30 | `Coord3f` — factory, arithmetic, dot/cross, norm, dist |
| `AreaTest` | 24 | `Area` — factories, contains, isects, overlap, iterator |
| `FColorTest` | 22 | `FColor` — constructors, mul, blend, sRGB/linear |
| `MessageBufTest` | 50 | `MessageBuf` — all int types, string, coord, TTO round-trips |
| `UtilsTest` | 55 | `Utils` — encode/decode, math, hex, base64, splitwords |
| `HalfFloatTest` | 17 | `HalfFloat` — round-trips, special values, equality |
| `MiniFloatTest` | 14 | `MiniFloat` — round-trips, special values, equality |
| `UIDTest` | 14 | `UID` — nil, bits, conversions, hex toString |
| `NormNumberTest` | 27 | `NormNumber` — all 9 types, TTO round-trips, validity |
| `PMessageTest` | 7 | `PMessage` — constructors, clone, write/read |
| `IDPoolTest` | 10 | `IDPool` — next, claim, release, save/restore |
| `ReflectTest` | 23 | `Reflect` — getField, hasField, invoke, is/like |

### Phase 2: Protocol Decoding (49 tests)

| Test file | Tests | Class under test |
|-----------|-------|-----------------|
| `ProtoDecoderTest` | 33 | `ProtoDecoder` — type names, describeArg, decodeRel all RMSG types |
| `ProtoEventTest` | 5 | `ProtoEvent` — builder, category labels/colors, direction |
| `ProtoStatsTest` | 11 | `ProtoStats` — record, tick, rates, history, reset |

### Phase 3 (partial): Algorithms, Data Structures, Utilities (280 tests)

| Test file | Tests | Class under test |
|-----------|-------|-----------------|
| `HomoCoord4fTest` | 22 | `HomoCoord4f` — factory, equals, clipped (all planes), pdiv, toview |
| `ResIDTest` | 15 | `ResID` — factory, Number conversions, equals, toString |
| `TopoSortTest` | 24 | `TopoSort` — chain, diamond, cycle detection, Graph CRUD |
| `WeightListTest` | 11 | `WeightList` — pick, weight distribution, wraparound |
| `DRandomTest` | 13 | `DRandom` — determinism, all types, range checks |
| `AstronomyTest` | 18 | `Astronomy` — seasons, year length, time calculations |
| `IntMapTest` | 17 | `IntMap` — put/get, remove, grow, sparse keys |
| `FastArrayListTest` | 17 | `FastArrayList` — add/get/set/remove (swap), iterator |
| `Volume3fTest` | 22 | `Volume3f` — factories, contains, isects, closest, margin, include |
| `CachedFunctionTest` | 8 | `CachedFunction` — caching, LRU eviction, dispose |
| `HashedSetTest` | 15 | `HashedSet` — add/remove/contains, find/intern, growth |
| `HashedMapTest` | 13 | `HashedMap` — put/get/remove, iteration, growth |
| `EnumIntervalTest` | 13 | `EnumInterval` — size, get, contains, indexOf, iteration |
| `PosixArgsTest` | 12 | `PosixArgs` — flags, args, double-dash, validation |
| `ClientUtilsTest` | 27 | `ClientUtils` — clamp, hex/color, bits, time format, Liang-Barsky |

## Known Bugs Found By Tests

- **IntMap.sz not maintained** — `put()` and `remove()` never update the `sz` field, so `size()` always returns 0. Test commented out with `// TODO: Upstream bug` in `IntMapTest.java`. Waiting for upstream fix (loftar/ender).
- **PosixArgs `--` fallthrough** — After `--` sets `acc=false`, the code uses `} if(` instead of `} else if(`, so the `--` token itself is added to `rest`. Test documents this behavior.

## Next: What to Test

### Phase 2b: Resource Parsing
- [ ] `Resource.Named` — equals/hashCode for resource references
- [ ] `Resource.ResourceMap.decode()` — parse from byte arrays / object arrays

### Phase 3 continued: Pure Logic (source already read, ready to write)

| Class | What to test |
|-------|-------------|
| `Matrix4f` | identity, add, mul (matrix×matrix, matrix×vector, matrix×HomoCoord4f), transpose, invert, get/set, equals/hashCode, trim3 |
| `Line2d` | from, twixt, end, at, cross (intersection + parallel), GridIsect iteration |
| `SNoise3` | determinism (same seed → same output), output range [-1,1], getr/geti range mapping |
| `Pair` | of, equals, hashCode, toString |
| `HashBMap` | bidirectional put/get/remove |
| `KeywordArgs` | argument parsing with format specs |
| `NamedSocketAddress` | parse IPv4, IPv6, with/without port |
| `Blake2b` / `Argon2` | hash round-trips, known test vectors |

### Phase 3a: Session Protocol (mock server) — not started
- [ ] `MockServer.java` — minimal socket server speaking H&H protocol
- [ ] `SessionTest.java` — connection handshake, message dispatch

### Phase 4: Shader Compilation — not started
- [ ] `render/sl/*` — GLSL code generation produces valid strings

### Phase 5: E2E Bot Tests — stretch goal
- [ ] Requires local H&H server

## How to Run

```bash
ant test           # Build + run all tests
ant compile-tests  # Just compile tests (no run)
ant clean-tests    # Clean test artifacts
```
