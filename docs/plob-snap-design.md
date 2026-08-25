# Plob Snap (Q/E/W/S)

While in placement mode, Q/E/W/S snap the placer flush against an existing terobj or a tile edge. Purpose: build neat rows and grids of `gfx/terobjs/*` items (walls, barrels, fences, furniture) without gaps or overlaps that the server rejects.

Status: implemented in `src/haven/MapView.java`. Last design pass 2026-04-26 reflects the actual landed code.

## Mental model

The cursor is the pointer. The direction key is the axis intent. The placer follows the cursor (`Plob.Adjust.hit` warps it on every mousemove with `forceFine` overriding quantization), so the cursor and placer share a screen pixel.

Q/E/W/S are screen directions, but the snap operates in **world coordinates**. The camera projects world axes to screen at some yaw, so screen-LEFT corresponds to one world cardinal at the current yaw -- typically world `-Y` for typical yaws. We invert the Jacobian for a unit screen vector in the pressed direction and pick whichever world cardinal axis (`±X` or `±Y`) it lies closest to. The whole snap then runs in world coords.

This matters because **screen-flush at a rotated camera is geometrically incompatible with world-flush**. An earlier screen-axis design produced placers that looked aligned on screen but had world-space overlap on one axis and a gap on the other -- the server rejected those placements.

## Algorithm

On Q/E/W/S press:

1. **`Maptest`** at `ui.mc` -> `mc`, the world coord under the cursor pixel. Maptest renders only the terrain click-pickbuffer; gobs don't occlude. Off-map cursor -> silent no-op.
2. **Determine world axis.** `screenDirToWorldAxis(rc, dir)`: invert the screen-to-world Jacobian for a unit screen vector in `dir`, return `{dxSign, dySign}` where exactly one is `±1`. At typical isometric yaw: LEFT->`-Y`, RIGHT->`+Y`, UP->`+X`, DOWN->`-X`.
3. **Pick a target** in three tiers:
   1. **Footprint contains `mc`**: walk `glob.oc`. For each `gfx/terobjs/*` gob (excluding placer and `id<0` phantoms), compute its world-space footprint AABB. If `mc` is inside, that gob wins.
   2. **Directional scan**: if no footprint contained `mc`, look for the nearest terobj whose footprint extends past `mc` in the chosen world axis direction *and* whose perpendicular extent overlaps `mc`'s row/column, within `2 * tilesz` (~22 world units). Catches thin gobs (walls, fences) that strict containment can't pick up at sub-pixel cursor precision.
   3. **Tile**: cursor's tile (`mc.floor(tilesz).mul(tilesz)`).
4. **Apply the snap** by mode:
   - **Footprint mode** (tier 1 winner): `placer ends up on target's <axis> side`. Disambiguates side when cursor is inside the target. e.g. axis `+Y` -> `placer.front = target.back + gap`.
   - **Directional mode** (tier 2 winner): `slide placer toward target, stop at target's facing edge`. The placer's leading edge in the axis direction meets the target's near side. e.g. axis `+X`, target east of placer -> `placer.right = target.left - gap`.
   - **Tile mode** (tier 3): `align placer's <axis> edge with the tile's same-side edge`. Placer stays inside the tile.
5. **Apply delta in world coords**, then `warpCursorToPlacer` to keep the cursor on the placer.

## Why two abut semantics

The split mirrors how the user thinks about the action.

When the cursor is **on** a gob (footprint mode), the gob fills the cursor pixel; "press a direction" naturally means "which side of this thing." The placer might overlap the gob; the direction key disambiguates which way to push out.

When the cursor is **near** a gob (directional mode), the gob is some distance away in a specific direction. The placer is on one side of it. "Press a direction" naturally means "slide me that way and stop when I hit something" -- the placer ends up on the side it was already on, just flush. Putting it on the *opposite* side would feel like teleporting around the target.

## Why footprint-contains-mc instead of click-pickbuffer

An earlier draft used `Hittest`'s `objcl` (the topmost clickable gob at the cursor pixel) to pick the snap target. That broke for tall objects:

- The click pickbuffer reports the gob whose **visible mesh** covers the cursor pixel. A cupboard's mesh extends well above its ground footprint.
- The snap math operates on the **footprint AABB**.
- Pointing at a cupboard's top selected the cupboard whose mesh was there, but its footprint was sometimes 200+ screen pixels away from the cursor. Snapping flush against that distant footprint teleported the placer across the screen.

Picking by world-space footprint containment (with directional fallback for thin gobs) keeps the target selector and the snap math in the same coordinate space and the same as the server. Trade-off: the user must point at the cupboard's *base* to select it via tier 1, not its painted top -- but the directional-scan fallback usually rescues sloppy aim.

## Resource resolution: `snapMeshRes`

A gob's "snap geometry" is the obst+neg polygon set Hitbox.java would render for it -- but Plobs throw a wrench in that. A Plob's main drawable is `ui/gobcp` (a generic placement marker class `Gobcopy` loaded from server-sent resource bytecode), which has no obst/neg layers. The actual cupboard/wall/etc. footprint comes from the source gob `Gobcopy` is mirroring, which it stores in a private `Gob gob` field.

`snapMeshRes(Gob)` resolves in order:

1. `gob.getres()` -> `Hitbox.fix(gob, res)` (substitutes for horses, producesack, etc.) -> follow `RenderLink.MeshMat` if present. If the result has obst+neg polypoints, return it.
2. **Plob `Gobcopy` fallback**: if `gob.drawable` is a `ResDrawable` with a `Gobcopy`-class sprite, reflect for the `Gob` field, recurse on that source gob's resource. Reflection because `Gobcopy` is loaded from the resource and we can't import it.
3. **Plob overlay fallback**: walk `gob.ols`. For each overlay, read its resource via `Sprite.Mill.FromRes.res` (avoids forcing sprite instantiation), recurse. First overlay with non-empty footprint wins.
4. Otherwise return the level-1 result (used by callers; `polyPointsForRes` will fall back to a 0.2-tile placeholder box if it has zero points).

## Footprint geometry: `polyPointsForRes`

Returns local-space polygon vertices used by both `screenAabbForGob` (snap-time projection) and `worldFootprintAabb` (target picker, world-space invariant check):

- Includes **all** obstacle layers, including `id == "build"`. The "build" obstacle is what the server uses for placement validation; excluding it (Hitbox.getMesh does, since it's rendering the visible hitbox) leaves the snap visually flush but server-rejected.
- Includes neg layers as 4 corners each.
- All y-coords negated. The gob's render transform mirrors local y, and Hitbox compensates; we have to match or our world AABB lands somewhere different from the rendered hitbox for any polygon that isn't y-symmetric or y-zero-centered.
- Empty polypoint set -> 0.2-tile (2.2 world units) fallback box. This means snap math still functions on gobs without footprint data, but the result is geometrically arbitrary -- in practice this fallback only ever fired on Plobs before the resource-resolution chain above caught the actual mesh.

## Server placement tolerance: `extraGap`

The server rejects abuts where the placer's footprint exactly meets the target's. Mode-dependent extra gap on the abut delta:

- **Tile-align mode**: `0`. No abut, deliberate inside-tile alignment.
- **General gob mode**: `ABUT_GAP = 0.1` world units. Sub-pixel at typical zoom -- invisible but enough to clear the server's tolerance for cupboards, barrels, furniture.
- **Walls/arches** (target res starts with `gfx/terobjs/arch/`): `WALL_GAP = 1.0` world units. Empirically the server rejects 0.1 cupboard-against-wall placements; walls appear to carry implicit clearance beyond their declared obstacle layers. 1.0 is ~10 screen pixels -- visible but still adjacent.

If the wall gap turns out to be the wrong knob (e.g. the server enforces tile-grid alignment for walls rather than a flat distance), this is the place that needs revisiting.

## Preserving the abutted position

After a snap, `placing.rc` is at a sub-pixel-precise world position that almost never lands on a `plobpgran` cell. Without intervention, the very next mouse twitch fires `Plob.Adjust.hit -> StdPlace.adjust`, which quantizes to either tile centers (coarse mode) or `1/plobpgran`-tile cells (fine mode), destroying the abutment.

Two-layer fix:

- `forceFine` is set to `true` on the placer the first time any snap key is pressed. This ORs `MOD_SHIFT` into the modflags passed to `StdPlace.adjust`, switching it out of coarse mode.
- In `StdPlace.adjust`, when `plob.forceFine` is true, the `plobpgran` quantization branch is skipped entirely (`nc = mc`). The placer takes the raw cursor world coord. Sub-pixel positions survive.

`forceFine` is sticky for the lifetime of the placer: once you've entered precision placement by snapping, subsequent mouse movement remains pixel-precise until placement is cancelled (which destroys the placer; the next placer constructs with `forceFine = false`).

`warpCursorToPlacer` + `lastmc` preload stops the immediate post-warp mousemove from triggering `adjust` at all -- the `lastmc.equals(ev.c)` short-circuit in `MapView.mousemove` skips it, so even the first frame after the warp doesn't risk re-quantization.

## What this design rejects

- **Screen-axis snap.** The original "screen-flush" math (`PlobSnap.abutAgainst` / `alignEdgeWith` operating on screen AABBs) produced visually-flush placers with arbitrary world-space offsets, because screen and world axes don't align under camera rotation. The pure functions still exist and are still tested, but no longer on the snap's hot path.
- **Click-pickbuffer hover (`Hittest.objcl`).** Mesh-pick + footprint-snap mismatch causes cross-screen teleports for tall gobs.
- **`snapAnchor`** (a stable world coord captured across snap presses to stop tile-fallback from chasing the placer). Only existed to paper over the old multi-tier picker chain.
- **Proximity-based hover acceptance** (radius around placer center). Footprint containment + directional scan are strict tests; no proximity tuning.
- **Per-tick fine-mode reset.** Once `forceFine` is on it stays on for the placer's lifetime. Resetting on each mouse move would destroy the abutment.

## File map

- `src/haven/PlobSnap.java` -- legacy pure math (`abutAgainst`, `alignEdgeWith`, `jacobianInvert`). Not used in production snap; kept for unit tests.
- `src/test/java/haven/PlobSnapTest.java` -- covers all three above.
- `src/haven/MapView.java`:
  - `snapPlob` / `applyPlobSnap`: top-level entry.
  - `findGobByFootprint`, `findGobInDirection`: tier 1 + 2 picker.
  - `worldFootprintAabb`, `screenAabbForGob`, `polyPointsForRes`: geometry primitives.
  - `snapMeshRes` (+ `findEmbeddedGob`, `overlayRes`): resource resolution chain.
  - `screenDirToWorldAxis`: screen-direction -> world-cardinal mapping via Jacobian.
  - `screenDeltaToWorld` (Jacobian inversion): used only by `screenDirToWorldAxis` now; not on snap delta.
  - `warpCursorToPlacer`: cursor warp + `lastmc` preload.
  - `checkWorldSnapInvariant`: post-condition check (skipped when `extraGap > 0`).
  - `kb_plobSnap{Left,Right,Up,Down}` bindings + dispatch in keydown.
  - `forceFine` field on `Plob` + integration in `StdPlace.adjust`.
- Diagnostic logging (`snapLog`, `fmtBox`, `fmtWorldGap`, `gobDesc`, `resName`, `polyPointCount`, `describeNearbyTerobjs`) -- one line per snap with cursor/mc/target, then one line with rc/world/screen/axis/gap/worldGap. Stays on for now; remove when the design feels stable.
