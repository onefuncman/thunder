# Organizer integration guide (headless client)

How the headless client uses the **placement logic** to organize picked-up objects
into shapes. This documents the planning seam only — taking, carrying, and
placing objects on the ground is the client's own job.

## What you get, and what you don't

The organizer logic answers one question: **for each of N objects, where do I stand
and where does it go?**

- **Provided** (this repo): footprint derivation + a deterministic planner that
  returns, per object, a **world anchor** (where the object lands) and a
  **proven-reachable stand** (where to stand while placing), without overlapping
  anything already there.
- **Not provided**: picking the object up, walking to the stand, dropping/placing
  it, or confirming the server accepted it. The client owns all of that.

## Where the code lives

| Piece | Jar / package | Notes |
| --- | --- | --- |
| Layout planner (renderer-independent) | `HavenNavigationCore.jar` → `haven.layout.*` | Pure Java 8, no AWT/renderer. The actual logic. |
| Occupancy grid (renderer-independent) | `HavenNavigationCore.jar` → `haven.pathfinding.OccupancyGrid` | The world obstacle model the planner reads. |
| `ObjectFootprints` (live seam) | Thunder tree → `src/haven/pathfinding/ObjectFootprints.java` | Derives a footprint from a resource's collision geometry. |
| `ObjectOrganizer` (live seam) | Thunder tree → `src/haven/pathfinding/ObjectOrganizer.java` | Plan-only entry point. |

The headless client is a separate hafen fork (`HavenHeadlessWorker`, based on
`dolda2000/hafen-client`) and does **not** currently contain `haven.layout` or the
seam classes. To integrate:

1. Add `HavenNavigationCore.jar` to the headless client's compile + runtime
   classpath (build it with `ant jar` in `HavenNavigationCore/`, or depend on the
   published jar).
2. Copy `ObjectFootprints.java` and `ObjectOrganizer.java` into the headless
   client's `haven.pathfinding` package. They depend only on base hafen-client
   classes (`haven.Coord2d`, `haven.Resource`, `haven.MCache`, `haven.Loading`)
   plus the core jar.

## Concepts

- **Footprint** (`LayoutFootprint`): the object's shape on the placement grid, in
  cells. `rect(w, h)` for a solid block, `ofCells(...)` for arbitrary shapes
  (holes/concavities allowed). `blocksApproach` is `true` for things you stand
  *beside* (containers, furniture, stockpiles) and `false` for things you stand
  *on/over*.
- **Pitch**: the placement grid's cell size in world units. A `w × h` footprint
  reserves `w*pitch × h*pitch` world units. Footprints derived by `ObjectFootprints`
  use **11 world units per cell** (`MCache.tilesz.x` = one game tile), so pass
  `pitch = 11.0` to match.
- **Occupancy** (`OccupancyGrid`): the world obstacle model. Cells are `FREE`,
  `SOLID` (obstacle), `DILATED`, or `CARVED`. The planner treats `SOLID` as
  blocked for both placement and approach. Its cell size is independent of pitch;
  the planner projects footprint cells onto occupancy cells.
- **Approach from** (`approachFrom`): a world position the actor starts from. Every
  stand must stay reachable from it.
- **Keep / occupied shapes** (`LayoutShape`): existing objects that must not be
  moved. The planner rejects placements that would overlap them
  (`overlap_keep` / `overlap_occupied`).

## End-to-end usage

1. Decide the object's **footprint** (from its resname, or build one directly).
2. Capture an **occupancy grid** of the current world.
3. Build the **request** (area, count, pitch, approachFrom, keep/occupied).
4. Call **`ObjectOrganizer.plan`**.
5. For each returned **placement**, in order: walk to `stand`, take the object,
   place it at `world`, verify, then **re-capture occupancy** before the next one
   (already-placed objects must become solid).

### Example (Java)

```java
import haven.Coord2d;
import haven.layout.*;
import haven.pathfinding.*;

// 1. Footprint for the object being organized.
LayoutFootprint fp = ObjectFootprints.footprintFor("gfx/invobjs/board-pine");
// or supply a fallback when the resource has no obstacle layer:
// ObjectFootprints.footprintFor(resname, Coord2d.of(5.5, 5.5));
// or build one directly:
// LayoutFootprint fp = LayoutFootprint.rect(1, 4, true); // 1x4 tiles, stand beside

// 2. Occupancy. In the Thunder client this comes from the pathfinder; the
//    headless client must produce an equivalent grid of its own observation.
//    (Thunder: PrototypePathfinder.observe(gui, false).occupancy)
OccupancyGrid occ = observeOccupancy();

// 3. Plan N placements inside the selected rectangle.
Coord2d areaMin = Coord2d.of(x0, y0);
Coord2d areaMax = Coord2d.of(x1, y1);
Coord2d approachFrom = playerPosition();
LayoutPlanResult plan = ObjectOrganizer.plan(
    fp, occ, areaMin, areaMax, approachFrom, /*count*/ 12, /*pitch*/ 11.0);

// 4. Handle the outcome.
if (plan.status == LayoutPlanResult.Status.FAILED) {
    // plan.reason explains why nothing could be placed.
    return;
}
if (plan.status == LayoutPlanResult.Status.PARTIAL) {
    // plan.placements.size() < count; plan.reason + plan.rejectCounts explain.
}

// 5. Execute (the client's job), re-observing between placements.
for (LayoutPlacement p : plan.placements) {
    walkTo(p.stand);                 // p.standRoute is the waypoint list
    takeObject();                    // lift the next item into hand
    placeAt(p.world);                // drop/place so its (0,0) cell lands at p.world
    verifyPlaced(p.world);
    occ = observeOccupancy();        // the new object is now solid
}
```

## The execution contract

The planner only guarantees geometry. The executor must:

- **Walk to `stand` before placing.** `stand` is proven reachable from
  `approachFrom`; `standRoute` is the waypoint list (last point = `stand`).
- **Re-observe occupancy after every placement.** Otherwise the next plan treats
  the spot as still free. If you re-plan per object rather than once up front,
  rebuild the request each time with the fresh grid.
- **Land the object with its (0,0) footprint cell at `world`.** `world` is the
  corner of the footprint's (0,0) cell, not the object's center. For a
  `w × h` footprint the center is `world + (w*pitch/2, h*pitch/2)`.
- **Confirm the server accepted the placement.** The planner cannot know whether
  the drop actually happened; treat a failed confirmation as an executor error.

## Status and rejection reasons

`LayoutPlanner.plan` returns `PLANNED` (all `count` placed), `PARTIAL` (some), or
`FAILED` (none). `reason` is empty for `PLANNED`; otherwise it names the dominant
rejection, and `rejectCounts` has per-candidate tallies:

| Constant | Meaning |
| --- | --- |
| `overlap_placed` | Would overlap a placement already accepted this plan. |
| `overlap_keep` / `overlap_occupied` | Would overlap an immovable preserved shape. |
| `outside_area` | Footprint extends past the requested rectangle. |
| `no_free_space` | No free candidate remained. |
| `unreachable` | No stand could be proven reachable for this candidate. |
| `lane_blocked` | Candidate would orphan the stand of an earlier placement. |
| `no_interaction_pose` | No legal stand pose exists geometrically. |

## Footprint guidance

- **Derive from the resource** when possible: `ObjectFootprints.footprintFor(resname)`
  reads the resource's `Obstacle` layer (the same geometry the occupancy grid uses)
  and converts its bounding box to 11u cells. This is what makes the placement
  respect the object's real hit box.
- **Fallback**: `footprintFor(resname, fallbackHalf)` uses `fallbackHalf` (world
  half-extents) when the resource has no obstacle layer. `ObjectFootprints.DEFAULT_HALF`
  is one 11u tile `(5.5, 5.5)`.
- **`blocksApproach`**: `true` for solid objects you stand beside — the planner
  proves a stand *around* the footprint. `false` for things you stand on — the
  stand becomes the footprint's anchor cell.
- **Preserve immovables**: pass existing objects as `keep`/`occupied` `LayoutShape`s
  (`LayoutShape.at(footprint, worldAnchor)`) so the planner leaves them alone.

## Caveats

- **Pitch must match footprint cell size.** `ObjectFootprints` returns footprints in
  11u cells; pass `pitch = 11.0`. If you build footprints by hand, keep the two
  consistent.
- **`approachFrom` must be a free cell** (not inside a solid/obstacle cell).
- **Determinism**: placements are enumerated row-major, so the same inputs give the
  same plan — useful for tests and replays.
- **The planner is a snapshot.** It does not know the world changed underneath it;
  that's why re-observation between placements matters.
- **`ObjectOrganizer` is plan-only.** For the stockpile-specific wire sequence
  (itemact → wait for placer → `place`), see `StockpileOrganizer` as the reference
  executor; a generic pick/place executor replaces only its itemact/place step with
  the client's own take/place action.
