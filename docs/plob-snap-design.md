# Plob Snap (magnetic edge snapping)

While in placement mode, the ghost is pulled flush against the collision boxes of nearby objects, tile-aligned cave walls, and lines its edges up with close neighbours — continuously, as the mouse moves. Purpose: build neat rows and grids (walls, barrels, fences, furniture) without gaps or overlaps that the server rejects, with no key vocabulary to learn.

Status: implemented in `src/haven/PlobSnap.java`, wired through `MapView.StdPlace`. Ported 2026-08-24 from Vantazz/Hurricane (`vantazz` remote, commit 8864192 and ancestors), replacing Thunder's original key-driven (Q/E/W/S) snap. Last design pass reflects the landed code.

## Mental model

There are no snap keys. `StdPlace.adjust` runs on every mousemove during placement; when snapping is enabled and Shift is not held, it asks `PlobSnap.snap()` for a snapped position before falling back to the normal placement grid. Each world axis is handled independently:

- An axis **grabs** an edge when the cursor comes within the capture radius (default 3.5 world units) of a snap candidate.
- A grabbed axis **holds** its edge until the cursor drags past the dead zone (default 6.5 units) — hysteresis, so the ghost doesn't flicker on and off at the boundary.
- The other axis keeps following the cursor, so a ghost caught on a wall slides smoothly along it.
- Snap resolution runs **two passes**, so catching one axis lets the other reconsider from where the ghost actually ended up — that is what closes a corner.

A tile is 11 world units; all distances in this doc are world units.

## Snap candidates, per axis

`axis()` scores four candidate positions against every neighbour box, keeping the closest to the cursor:

1. Our far face flush on their near face (minus their gap).
2. Our near face flush on their far face (plus their gap).
3. Near edges lined up.
4. Far edges lined up.

Faces (1–2) require the ghost to be **beside** the neighbour on the perpendicular axis (separation ≤ 2.0, i.e. actually standing alongside it). Edge alignment (3–4) requires the ghost to be **apart** from it (separation in [-0.5, 11]) and is disfavored by a 1.15 score bias. Keeping "beside" and "apart" disjoint is what stops a small object from edge-aligning with a wall it's leaning on, which would bury it in the wall.

## Terrain walls (cave walls)

Cave walls carry no gob, so the neighbour scan can't see them. `terrainaxis()`/`faceaxis()` instead search tile data for a **wall face**: a tile boundary with rock (`gfx/tiles/deep|cave|nil|rocks/*`) on one side and open floor on the other, limited to the tile rows/columns the ghost itself covers. That restriction is load-bearing: treating each rock tile as a box would offer up every tile's side faces and chop the slide along a wall into tile-sized steps; searching only the covered rows means an east-west wall offers nothing to the X axis and the slide stays smooth.

## Resolving the ghost's footprint (`plobself`)

A placement ghost is normally not the object being placed. Resolution order:

1. **Own resource** has obst/neg layers → use it.
2. **`ui/gobcp` (Gobcopy)**: the ghost mirrors a hidden source gob whose id is the first `uint32` of the ghost's `ResDrawable` sdt — one `oc.getgob()` lookup away. (Thunder's old snap reflected into Gobcopy's private `Gob` field for this; the sdt read replaces that entirely.)
3. **Overlays**: some ghosts carry their shape on an overlay; resolved via `Overlays.res()`, which handles both `OCache.OlSprite` (server-hung) and `Sprite.Mill.FromRes` (client-added) mills without forcing sprite construction.
4. **Construction plots** (`*/consobj`): footprint corners are literally in the sdt bytes.

Footprints are AABBs of all neg layers plus all obstacle layers except `id == "ext"`, resolved through `RenderLink.MeshMat` when the hitbox lives in a linked mesh resource, cached per resource name. The **"build" obstacle is included**: it's the shape the server validates placement against; snapping to the drawn collision box instead produces placements that look flush and get refused.

## Neighbour selection

All gobs within reach except: the ghost itself, the gob the ghost is mirroring (`srcid`), phantoms (`id < 0`), and "passable" resources (herbs, ground items, plants except trellises, clues, speed buffs, players, animals). Candidate gobs are collected under the `oc` lock but their resources resolved outside it, since resolution can block on loading. Neighbour boxes are rotated by the gob's angle (AABB of the rotated box) and translated to world space.

## Server placement tolerance

The server rejects placements whose footprint exactly meets a neighbour's, so each neighbour box carries a gap the flush snap stops short by:

- Ordinary objects: `abutgap = 0.1` (sub-pixel at normal zoom).
- Walls/arches (`gfx/terobjs/arch/*`): `wallgap = 1.0` — empirically walls behave as though they carry clearance past their declared obstacle layers. (Thunder's old snap converged on the same two values independently.)

Both are prefs (`plobsnapgap`, `plobsnapwallgap`), as are capture/dead zone (`plobsnapdist`, `plobsnapdead`).

## Rotation and auto-facing

- A ghost rotated off the 90° grid has no meaningful axis-aligned footprint, so snapping disables until it's square again.
- While the ghost is caught, `StdPlace` holds its current angle instead of auto-rotating to face the player — a footprint that keeps turning cannot stay flush, and would flip on its own as you slide it past yourself.

## Fallback grid

When snapping is on but nothing caught, placement is **smooth** (the `plobpgran` fine grid, same as holding Shift) rather than vanilla tile-centers — a half-snapped ghost jumping between tile centers on the free axis would fight the snap. Vanilla tile-center placement only applies when the snap checkbox is off. Shift always bypasses snapping.

## UI / diagnostics

- Toggle: `CFG.PLOB_SNAP` ("Snap placement to nearby hitbox edges", Options → Interface settings), default on.
- Console: `:placesnap` prints grab/dead-zone values (and the last placement's diagnostic); `:placesnap <grab> <deadzone>` tunes them.
- Diagnostics: `PlobSnap.diag` records what the last snap decided, **only while `CFG.DEBUG_PLOB_SNAP` is on** ("Placement snap diagnostics", Options → Debug) — `snap()` runs per mousemove and unconditionally formatting a string nobody reads would be waste.
- In-game manual: Thunder Docs → Placement Snap (`docs/ingame/plob-snap.txt`).

## What replaced what (2026-08 port)

Removed from `MapView`: `snapPlob`/`applyPlobSnap`, the three-tier target picker (`findGobByFootprint`, `findGobInDirection`), the `snapMeshRes` reflection chain, `screenDirToWorldAxis`/`screenDeltaToWorld` (Jacobian), `warpCursorToPlacer`, `forceFine` on `Plob`, the Q/E/W/S keybindings, the snap invariant checks, and the old pure-math `PlobSnap` (`abutAgainst`/`alignEdgeWith`/`jacobianInvert`). The continuous design makes each of those unnecessary: no screen-direction keys → no Jacobian; snap inside `adjust` → no quantization fighting, no cursor warp; sdt-based Gobcopy resolution → no reflection.

Known deltas vs the old implementation, accepted at port time:

- `resbox` does not negate obstacle y-coords the way the old `polyPointsForRes` did. Harmless for y-symmetric footprints (nearly all); worth an in-game check if something asymmetric ever snaps oddly.
- No `Hitbox.fix` substitutions (horses, producesack). Animals are excluded as snap targets anyway.

## File map

- `src/haven/PlobSnap.java` — the whole snap: boxes, footprint resolution, neighbour scan, terrain faces, per-axis scoring, hysteresis.
- `src/haven/Overlays.java` — mill-agnostic overlay resource/sdt access (ported alongside; also useful generally).
- `src/haven/MapView.java` — `StdPlace.adjust` wiring; `placesnap` console command.
- `src/haven/CFG.java` — `PLOB_SNAP`, `DEBUG_PLOB_SNAP`.
- `src/haven/OptWnd.java` — Interface-panel checkbox; Debug-panel diagnostics checkbox.
- `src/test/java/haven/PlobSnapTest.java` — ported from Hurricane's `PlobSnapCheck`: walls, corners, edge-alignment gating, gaps, hysteresis, cave faces.
- `docs/ingame/plob-snap.txt` + `resources/src/local/paginae/add/doc/plob_snap.res` — in-game manual.
