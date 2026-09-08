# Critical routes

Mark a path in Navigation Lab. The bot walks it both ways. That is how you add a house/basement/ladder route to the campaign.

Use **Thunder — Navigation Lab**, not regular Thunder. Log in first. The window is titled **Critical routes**.

## Open the window

1. Console (the `:` line, not chat): `:pf` or `:pf route`
2. Or **Ctrl+Shift+R** (if that key is still unbound, bind **Critical routes** under Options → Widget shortcuts)

If `:pf` prints a usage line, you are in the wrong client.

Yellow numbered dots are stands **on the floor you are looking at**. Basement marks disappear when you go up; they are not moved onto the cave or yard.

## Buttons

| Button | What it does |
|---|---|
| name + **Save** | Writes `dev-snapshots/pf/routes/<name>.json` (often under `bin/` because the lab’s cwd is `bin`) |
| **Here** | Stand at your current position |
| **Ground** | Next map click is a stand (right-click cancels) |
| **Object** | Next object click is a door / stairs / ladder / cave / boat / cart (right-click cancels) |
| **Undo** / **Clear** | Drop the last leg, or all legs |
| **Walk fwd** | Play the list in order |
| **Walk back** | Play the list in reverse |
| **To campaign** | Save, then add/enable this route in `tools/critical-routes.json` |
| **Load** / **Delete** / **Refresh** | Saved routes on disk |

Name the route before Save. Letters, numbers, `_` and `-` only (`Basement to yard!` → `basement_to_yard`).

## How to mark a path

Click objects in the order you travel. You must be able to see each object, so go through the route as you mark it.

1. Type a name.
2. **Here** at the start.
3. **Object** on each clickable (stairs, ladder, door, cave mouth, boat…).
4. **Here** or **Ground** on the far side (the destination you care about).
5. **Save**.
6. **Walk fwd**, then **Walk back**. Fix anything that fails before **To campaign**.

**Walk** uses the planner and waits for a real floor/vehicle change on objects. A click that does not move you is a fail. Yellow circles are stands (lines connect those); yellow squares are objects and are not joined through furniture. The bot walks around to a stand with line-of-sight, then clicks. If it jams, it replans from where it stopped. Indoor furniture (desks, cupboards, chairs, chests, tables, beds…) uses its real footprint without extra body padding, so aisles stay walkable. A stand beside a desk/wall is occupancy-snapped to the nearest free cell — arriving there counts.

Each **Here** / **Ground** is stored as a **map tile** on that floor (the same space minimap markers use). After a relog the session `x,y` numbers change; the tile does not. Objects are found by resource name near that tile, not by gob id.

Old routes saved as session coordinates (no `"space": "map"` in the JSON) only work until you log out. **Clear and mark them again.**

## Your basement → house → L1 route

Example: 1–2 basement, 3–4 house, 5–6 L1.

1. Basement: **Here** (and a second **Here** if you want).
2. **Object** → cellar stairs. Go up.
3. House: **Here** as needed.
4. **Object** → the **ladder** (or door, if that is how you leave). Change floors.
5. L1: **Here** as needed.
6. Save, Walk both ways, To campaign.

If Walk says a stand is on another floor, you started on the wrong floor of an old jar — restart Navigation Lab. **Walk fwd** now continues from the floor you are on (skips basement stands if you are already in the house). The campaign still requires starting in the basement. After a relog, mark a new route (or re-save the current one) so stands are map tiles; Walk then uses those tiles, not last session’s `x,y`.

## What Object means

The click is classified from the object’s resource name:

| You click | Role the bot uses |
|---|---|
| Cellar stairs, downstairs, or upstairs | `cellar_stairs` — wait for the floor to change |
| Ladder (`gfx/terobjs/ladder`) | `ladder` — L1 ↔ L0 |
| Minehole | `minehole` |
| Cave mouth (`cavein`, resid contains `cave` but not `cellar`) | `cave` |
| House door / gate | `door_gate` |
| Boat | `boat` (board if you are on foot, disembark if you are aboard) |
| Cart / wagon | `vehicle` (same board/exit rule) |
| Anything else | Walk to it; no click |

House **doors** (`…-door`) wait until the floor actually changes. Palisade/wall **gates** (`polebiggate`, palisade, brick, stone) open, walk the next stand through the opening, then close from the far side. A house door is never closed. If the gate is already open, the bot does not click it (that would shut it before walking through). The “landing_unknown” error leaving a house was the door treated as a gate.

The bot does not invent a path. If you never marked the ladder, it will not climb to L0. It does not search the cave/mine for an exit you cannot see.

## Built-in campaign recipes vs your marked route

`tools/critical-routes.json` has canned recipes (`campaign_cellar_roundtrip`, `campaign_cave_roundtrip`, …). Those pick a **unique** fixture of one type and go both ways. They are not “basement then stairs then ladder.”

Your marked route is `campaign_recorded`. **To campaign** appends:

```json
{
  "id": "basement_to_yard",
  "scenario": "campaign_recorded",
  "route": "basement_to_yard",
  "bidirectional": true,
  "enabled": true
}
```

Set `"enabled": false` on canned recipes you do not have fixtures for. Missing fixtures are **NOT_RUN**, never PASS.

## Campaign run

Each enabled route must succeed **both directions** in one run, **5 times in a row**, keep the original destination across floor changes, and emit a NavReplay. Stalls and fake arrivals fail.

```bash
python3 tools/pf_matrix.py --port 18762 matrix --group campaign
python3 tools/pf_matrix.py --port 18762 matrix --group campaign --smoke
```

`--smoke` is one run per route. Lab must be logged in on **18762**.

## If it will not open or will not walk

- Menu **Thunder — Navigation Lab**, not **Thunder**.
- Logged into the world, not the login screen.
- `:pf` in the **console**, not chat.
- Another bot already running → Walk refuses.
- `NO_FIXTURE` → nothing of that kind in view, or more than one.
- Save files live under `bin/dev-snapshots/pf/routes/` while the lab is running from `bin`.
