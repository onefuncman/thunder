# Tile Quality Tracker

Track the max observed quality per tile for mining, digging, and filling (water/saltwater). Stored per map grid, searchable via a UI window that navigates the map.

## Terminology

- **Mine** — player mines a cave wall; stone/ore appears in inventory then hand
- **Dig** — player digs terrain; dirt/clay appears in inventory then hand
- **Fill (water)** — player fills a container from a `gfx/tiles/water` or `gfx/tiles/deep` tile; quality shows on the water content of the container
- **Fill (saltwater)** — same but from `gfx/tiles/owater`, `gfx/tiles/odeep`, `gfx/tiles/odeeper`

## Data model

### Storage (parallel to Minesweeper)

| Key | Contents |
|-----|----------|
| `"thunder-tq-index"` | Set of grid IDs that have data |
| `"thunder-tq-grid-%x"` | Per-grid sparse tile quality entries |

Per grid: sparse `Map<tileIndex, Map<kind, quality>>`. Serialized as a flat list of `(tile_index:i16, kind:u8, quality:i16)` tuples (5 bytes/entry), compressed with ZMessage. Only tiles with observations are stored.

Quality stored as `(int)(quality * 10)` — e.g., 47.3 → 473. Range 0–6000 fits in a short.

### Kind constants

| Byte | Kind | Source |
|------|------|--------|
| 1 | Stone | `gfx/invobjs/<rockname>` matching tile `gfx/tiles/rocks/<rockname>` |
| 2 | Strange Crystal | `gfx/invobjs/strangecrystal` |
| 3 | Petrified Shell | `gfx/invobjs/petrifiedshell` |
| 4 | Quarryartz | `gfx/invobjs/quarryquartz` |
| 5 | Cat's Gold | `gfx/invobjs/catgold` |
| 6 | Dig | dirt/clay from digging |
| 7 | Water | fill from freshwater tile |
| 8 | Saltwater | fill from ocean tile |

A single tile can have multiple kinds (stone + crystal + shell etc from one mine location).

### Class: `TileQuality` in `me.ender.minimap`

Instance held on `GameUI` as `gui.tileQuality`, initialized from `MapFile` alongside `minesweeper`.

Persistence follows Minesweeper: `storeIndex()` / `storeGrid()` via `MapFile.sstore()`, loaded lazily per grid via `MapFileUtils.load()`, compressed with `ZMessage`.

## Detection: how quality is captured

### General approach

A `PendingAction` queue tracks what the player is doing and where. Items from the action produce quality observations.

```
PendingAction { byte group; Coord2d rc; long timestamp; }
```

The pending action is NOT consumed by the first item — it stays alive (15s expiry) so multiple items from one action (stone + minerals) all resolve against the same tile. `peekLast()` always attributes to the most recent pending action.

### Mine detection (implemented)

**Trigger**: `gfx/terobjs/mineout` sprite spawn fires `TileQuality.markPendingMine(owner)` in `AnimSprite.java`. This is the wall-collapse animation that plays when a mining swing successfully destroys a wall — exactly when items drop. Registers a `GROUP_MINE` pending action at the wall Gob's tile coordinate.

Note: `gfx/fx/cavewarn` (class `Cavein`) is NOT the right hook — that's the cave-in warning effect (falling dust, used by Minesweeper to track tile instability). It only fires on unstable tiles, not on every mine swing.

**Quality source**: mining adds items to inventory (stone first, then optional minerals). Each item eventually gets a "tt" server message → `GItem.uimsg("tt")` → `TileQuality.onItemInfoUpdate(item)`.

On info update:
1. Check if a pending mine action exists (`peekLast()`)
2. Walk widget tree to verify item is in main inventory (handles direct items AND stacked items via ItemStack)
3. Classify item by resource name:
   - `gfx/invobjs/strangecrystal` → KIND_STRANGE_CRYSTAL
   - `gfx/invobjs/petrifiedshell` → KIND_PETRIFIED_SHELL
   - `gfx/invobjs/quarryquartz` → KIND_QUARRYARTZ
   - `gfx/invobjs/catgold` → KIND_CATS_GOLD
   - `gfx/invobjs/<name>` matching tile `gfx/tiles/rocks/<name>` → KIND_STONE
4. Record max quality at the pending action's tile

### Dig detection (TODO)

Need to identify a trigger for above-ground digging (no Cavein sprite). Options:
- Player pose change to dig animation
- Detect shovel in equipment + terrain interaction

### Fill detection (TODO)

**Trigger**: detect when player uses container on water/saltwater tile.

**Saltwater tile detection**: extend `MapHelper` with `isSaltWaterTile()`:
```java
public static boolean isSaltWaterTile(GameUI gui, Coord tc) {
    MCache mcache = gui.ui.sess.glob.map;
    int t = mcache.gettile(tc);
    Resource res = mcache.tilesetr(t);
    if(res == null) return false;
    String name = res.name;
    return name.equals("gfx/tiles/owater") || name.equals("gfx/tiles/odeep") || name.equals("gfx/tiles/odeeper");
}
```

**Quality source**: container's content quality updates after fill. `GItem.itemq` reads `contains.q` (content quality).

## Search UI

### Window: `TileQualityWnd`

A searchable list window (similar to existing marker list in `MapWnd`). Accessible from the map window or a menu action.

**Features**:
- List of all recorded tile quality observations, showing: quality value, action type icon (mine/dig/water/salt), grid coordinates
- Filter by action kind (mine, dig, water, saltwater)
- Filter by quality range (min/max input)
- Sort by quality (descending default)
- Click an entry → map window centers on that tile via `view.center(new SpecLocator(seg, tc))`

**Mapping grid ID → map segment**: `MapFile` tracks which segment contains which grid. The `SpecLocator` needs `(seg, tc)`. We need to resolve `gridId` → `(segmentId, tileCoord)` at display time by looking up the grid in the map file's segment index.

## Implementation status

### Done: Storage layer + mine detection
- `TileQuality.java` in `me/ender/minimap/` — sparse per-grid storage, pending action queue, item classification
- `GameUI.java` — `gui.tileQuality` field, initialized alongside minesweeper
- `MCache.java` — trim hooks for grid eviction
- `AnimSprite.java` — `TileQuality.markPendingMine(owner)` when `gfx/terobjs/mineout` spawns (wall destruction)
- `GItem.java` — `TileQuality.onItemInfoUpdate(this)` on "tt" message arrival

### TODO: Dig detection
- Identify trigger for above-ground digging
- Wire `TileQuality.markPendingDig()` from trigger

### Done: Water fill detection
- `MapHelper.isSaltWaterTile()` — owater/odeep/odeeper classifier
- `MapView.iteminteract()` Hittest hook — on item-drop click, calls `TileQuality.markPendingFillFromMap(gui, mc, clickedGob)`
- `TileQuality.markPendingFillFromMap()` — picks group:
  - `gfx/terobjs/wellspring` (natural spring) → `GROUP_FILL_SPRING_WATER`, keyed to the spring's tile
  - `gfx/terobjs/well` (constructed well) → `GROUP_FILL_WATER`, keyed to the well's tile
  - fresh water tile → `GROUP_FILL_WATER`
  - salt water tile → `GROUP_FILL_SALT_WATER`
  - anything else → no-op
- Fill pendings carry a 5s `deadline` (TTL) since there's no cursor-clear signal — stale pendings drop on the next item-info attempt.
- Resolution checks the **hand item** (`gui.hand`), not main inventory — see `isEligibleItem`.
- `classifyFilledItem()` only records when `item.contains` holds liquid content, so unrelated tt updates on the hand item don't pollute the log. Quality comes from `item.quality()` which already falls back to `contains.q`.

### TODO: Visualization — map overlay
- Quality overlay rendering (parallel to Minesweeper's `SweeperNode`)
- Toggle button in map window
- Color coding by quality value range

### TODO: Visualization — search UI
- `TileQualityWnd` — searchable/filterable list
- Filter by kind (stone, crystal, shell, quarryartz, catgold, dig, water, salt) and quality range
- Grid ID → map segment lookup for navigation
- Click-to-navigate via `SpecLocator`

### Future: trees/seeds/fruit
The pending action + info update pattern generalizes. Kind byte is extensible (9=tree, 10=seed, 11=fruit, etc.).
