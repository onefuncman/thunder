# PfTestRunner scenario matrix

Allowlisted localhost scenarios driven through `DevControl` (`POST /pf/run?scenario=…`). Arbitrary remote movement commands are not exposed.

| Scenario | Kind | Notes |
|---|---|---|
| `observe` | read-only | Occupancy / scene snapshot |
| `basement_cabinet_identify` | read-only | Packed-cupboard fixture identification |
| `select_open_ground` | read-only selection | `NavigationTestSpotSelector.OPEN_GROUND` |
| `select_obstacle_corridor` | read-only selection | `LOCAL_OBSTACLE_OR_CORRIDOR` |
| `select_known_long_leg` | read-only selection | `KNOWN_MAP_LONG_LEG` |
| `select_boulder_approach` | read-only selection | Transition approach: boulder stand |
| `select_cave_transition_approach` | read-only selection | Minehole / ladder / cellar door / stairs |
| `select_door_gate_approach` | read-only selection | Door / gate approach; state reported, opening not authorized |
| `select_waterline_approach` | read-only selection | Land-side stand beside confirmed water |
| `surface_long_open_ground` | surface travel | Open-ground streaming; `NO_FIXTURE` if no spot |
| `surface_single_tree_detour` | surface travel | Local obstacle/corridor fixture |
| `surface_dense_forest` | surface travel | Local obstacle/corridor fixture |
| `surface_clustered_obstacles` | surface travel | Local obstacle/corridor fixture |
| `surface_one_tile_corridor` | surface travel | Local obstacle/corridor fixture |
| `surface_diagonal_corridor` | surface travel | Local obstacle/corridor fixture |
| `surface_buildings_fences` | surface travel | Local obstacle/corridor fixture |
| `surface_water_boundary` | surface travel | Fail closed `NO_FIXTURE` (no automatic fixture) |
| `surface_cliff_boundary` | surface travel | Fail closed `NO_FIXTURE` |
| `surface_moving_neutral` | surface travel | Fail closed `NO_FIXTURE` |
| `surface_hostile_exclusion` | surface travel | Fail closed `NO_FIXTURE` |
| `surface_unknown_geometry` | surface travel | Fail closed `NO_FIXTURE` |
| `surface_explore_frontier` | surface travel | Fail closed `NO_FIXTURE` |
| `interact_forageable` | interaction | Pose + forage gone/inventory; `NO_FIXTURE` if none |
| `interact_tree` | interaction | Pose + authoritative side effect |
| `interact_boulder` | interaction | Pose + authoritative side effect |
| `interact_cupboard` | interaction | Pose + container window |
| `interact_door_gate` | interaction | Pose + open/topology `sdt` change; no traversal |
| `interact_field_crop` | interaction | Pose + crop/inventory change |
| `interact_narrow_interior` | interaction | Narrow cupboard pose + window |
| `transition_door_gate` | transition | Phase 5 door/gate; authoritative `sdt`; `NO_FIXTURE` if none |
| `transition_cellar_stairs` | transition | Cellar door/stairs; observed landing node |
| `transition_cave` | transition | Cave entrance; no numeric layer guess |
| `transition_ladder` | transition | Ladder; observed landing |
| `transition_minehole` | transition | Minehole; observed landing |
| `boat_board` | mobility | Board; wait vehicle state |
| `boat_travel` | mobility | Water legal only while aboard |
| `boat_disembark` | mobility | Wait land state; restore policy |
| `vehicle_enter` | mobility | Cart/wagon enter |
| `vehicle_travel` | mobility | Travel under vehicle footprint |
| `vehicle_exit` | mobility | Exit confirmation |
| `hearth_travel` | transition | Observed hearth landing only |
| `explore_frontier` | explore | Nearest known-safe frontier walk; `NO_FIXTURE` if none |
| `campaign_surface_out_and_back` | critical route | Open-ground outbound then return; both directions in one run |
| `campaign_surface_and_door` | critical route | Open-ground, door/gate interaction, return |
| `campaign_door_gate_roundtrip` | critical route | Door/gate transition both ways; resume original dest |
| `campaign_cellar_roundtrip` | critical route | Cellar/stairs underground round trip; resume original dest |
| `campaign_minehole_roundtrip` | critical route | Minehole round trip; resume original dest |
| `campaign_cave_roundtrip` | critical route | Cave round trip; resume original dest |
| `campaign_boat_roundtrip` | critical route | Board/travel/disembark both directions; resume original dest |
| `campaign_recorded` | critical route | User-saved in-game route (`dev-snapshots/pf/routes/<id>.json`); both directions |

Control port (pf-test client): `http://127.0.0.1:18762/`
| `move_to_marker` | movement | Named map marker |
| `move_to_auto_open_ground` | movement | Marker-free open ground |
| `move_to_auto_obstacle_corridor` | movement | Marker-free corridor |
| `move_to_auto_known_long_leg` | movement | Marker-free long known-map leg |
| `move_to_auto_cave_transition_approach` | movement | Approach a cave transition fixture |
| `cross_cellar_door` | guarded single crossing | One right-click; source-fixture absence confirms |
| `cross_cellar_stairs` | guarded single crossing | One right-click; source-fixture absence confirms |
| `cross_minehole` | guarded single crossing | Changed-segment + material-displacement landing |

Control port (pf-test client): `http://127.0.0.1:18762/`

```
GET  /pf/scenarios
POST /pf/run?scenario=observe
GET  /pf/result?run_id=
POST /pf/cancel
```

Launch: `tools/launch-pf-test.sh` after `ant bin`. Occupancy, body clearance, eight-way A*, no-corner-cutting, and collision-checked smoothing live in `HavenNavigationCore` (`LocalPlanner` / `GridAStar`, quarter-tile `2.75` world-unit cells). Thunder adapters (`PrototypePathfinder`, `ThunderNavAdapter`) convert live `GameUI` state into core inputs. `GET /status` includes `navigation_core_git`.

Phase 1 live-gate: Navigation Lab jar launched and bound `18762`, but the session stayed on the login screen (`no matching saved account` for Rip Van Winkle). Offline NavReplay still loads without a game connection.

Phase 4 live-gate: Navigation Lab was down on `18762` at phase start (no in-game session). Interaction scenarios fail closed with `NO_FIXTURE` / `NO_GAME` when a live fixture or session is missing. Deterministic core/replay coverage is the recorded gate when login is an external blocker.

## Navigation Lab layout

`PfTestRunner` is a localhost facade. Allowlisted scenarios live in `PfScenarioRegistry`; shared checks, preflight, movement-watch, cabinet fingerprinting, and artifact IO live in `PfTestHarness`. Each allowlisted scenario is its own class (same package). Arbitrary remote movement commands are not registered.

Each completed run writes two artifacts under `dev-snapshots/pf/tests/<scenario>/`:

- `<run_id>.jsonl` — existing pf-test header + result body
- `<run_id>.navreplay.jsonl` — **NavReplay v1** (world occupancy, goal, raw/smoothed routes, observations, decisions, outcome)

```
python tools/pf_matrix.py --port 18762 status
python tools/pf_matrix.py --port 18762 matrix --smoke
python tools/pf_drive.py --port 18762 pf-wait observe
```

`NO_FIXTURE` / `NO_GAME` are classified NOT_RUN (never PASS). The 20/10 consecutive live counts require screen=game. The critical-route campaign (`tools/critical-routes.json`, `--group campaign`) requires 5 consecutive bidirectional successes per enabled route. Mark routes in-game with **Critical routes** (`:pf` or `:pf route`, or Ctrl+Shift+R): Here / Ground / Object, Save, Walk fwd/back, then **To campaign**.

Offline replay (no live session):

```
java -cp bin/hafen.jar:bin/HavenNavigationCore.jar haven.pathfinding.NavReplayRunner path/to/<run_id>.navreplay.jsonl
```

or `java -cp hafen.jar:HavenNavigationCore.jar haven.dev.DebugReplay` on a `feature=navreplay` snapshot. Replay calls `PrototypePathfinder.planFromOccupancy`, which delegates to `LocalPlanner.planFromOccupancy` in the shared core (the same `planCore` used live).

## Overlays (`:pf debug` / `CFG.DEBUG_PATHFIND`)

`PathfinderDebug` (registered from `DebugBoot`) paints: raw obstacles (`#` solid), body-inflated obstacles (`+` inflated), dynamic hazards (`hz`), start and goal regions, raw A* route, smoothed route, active waypoint (`wp`), replanning reason (HUD), and server-confirmed position (`srv`).
