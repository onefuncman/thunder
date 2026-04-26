# Dev iteration toolkit

This doc describes three small pieces of shared infrastructure that shorten the develop-test loop for any client-side feature in Thunder. The cost of the toolkit is paid once, and every future feature inherits visualization, scripting, and replay support in tens of lines, not hundreds.

The toolkit is implemented under `src/haven/dev/`. The milking-assist feature (`src/thunder/MilkingAssist.java`) is used throughout as the worked example — the live wiring lives in `src/thunder/MilkingAssistDebug.java`, which is the template for adding a new feature. Other features that already pay the same costs — `Hitbox`, `partydraw`, `poldraw`, the proto retro-recordings — appear as either precedent (existing instances of the same pattern, ad hoc) or as candidates for migration. Plob-snap (see `docs/snap-debug-cycle.md`) is a likely next adopter.

## The shared iteration loop

Most client features have a develop-test loop that breaks into the same four phases:

1. **Get into the right scene state.** Launch through Steam, log in, walk to the relevant area, set up whatever input precondition the feature needs (drag a placer from inventory, target a gob, open a window).
2. **Observe the feature's internal state.** What does the picker think the cursor is over? What does the resolver think `ols[0]` is? What does the click handler think the screen rect is?
3. **Mutate inputs and re-trigger.** Press a different direction, change CFG, hover a different gob, replay the same input against a new state.
4. **Verify the math in isolation.** Did the picker pick correctly? Did the formatter produce the right string? Did the snap solver converge?

Today, phase 1 is amortized by sticking inside one game session. Phases 2-4 are reinvented per feature: `Debug.log` lines for one, an opt-in CFG overlay for another, JUnit-tested pure helpers for a third, no shared convention.

Three primitives address phases 2-4 directly:

| # | Primitive | Targets phase | One-line summary |
|---|---|---|---|
| 1 | `DebugDraw` registry | 2 | A pluggable screen-space painter list called once at the end of `MapView.draw` |
| 2 | `dev.<feature>.<verb>` console convention | 3 | A namespacing rule plus a tiny helper for registering a feature's dev commands together |
| 3 | `DebugSnapshot` capture + replay | 4 | A JSONL-format state snapshot framework + a `main()` driver, mirroring `proto-recordings/` |

Phase 1 is excluded by request — launch optimizations are a separate (and small) win.

A fourth piece, the `Feature` interface, sits on top of the three primitives so a feature can plug in via a single declarative type instead of five separate registration calls. It is the recommended entry point; the primitives below are still public for cases that don't fit the shape (e.g. a debug overlay with no snapshot, or a snapshot-only utility with no console verbs).

---

## Primitive 1: `DebugDraw` registry

### Why

Every feature with screen-space state eventually wants a "show me what you think you see" overlay. Today each one is bespoke:

- `partydraw` (`MapView.java:1833`, called at `:1869`) — party-member markers.
- `poldraw` (`MapView.java:1744`, called at `:1868`) — polity territory borders.
- Milking-assist overlay (`MilkingAssistDebug.java`, this toolkit's first user) — pending UID/TTL, retry count, roster summary.
- Future plob-snap overlay (Option 1 in `snap-debug-cycle.md`) — placer/target AABBs, mode label.
- Future custom-area overlay, custom path-preview, fight-move range, target-lock indicator, ...

`partydraw`/`poldraw` are *features*, not debug toggles, so they live as `MapView` methods. But every debug overlay added on top of them follows the same shape: gate on a CFG flag, run some math against current state, draw rects and text in screen coordinates. There is no reason each one needs its own hand-wired hook in `MapView.draw`.

(Note: `Hitbox` is a *3D world-space* overlay — a per-gob `Rendered`/`SlottedNode` attached to the render tree. That's a different pattern with different physics, and stays as is. The registry below is for screen-space painters that draw on top of `super.draw(g)` via a `GOut`.)

### What

A small interface and a static registry:

```java
// src/haven/dev/DebugDraw.java
public interface DebugDraw {
    CFG<Boolean> toggle();      // gate; painter is skipped when false
    void paint(GOut g, MapView mv);

    final class Registry {
        private static final List<DebugDraw> painters = new CopyOnWriteArrayList<>();
        public static void register(DebugDraw d)   { painters.add(d); }
        public static void unregister(DebugDraw d) { painters.remove(d); }
        public static void paintAll(GOut g, MapView mv) {
            for(DebugDraw d : painters) {
                if(d.toggle().get()) {
                    try { d.paint(g, mv); }
                    catch(Loading l) { /* swallow — debug only */ }
                    catch(RuntimeException re) { Debug.log.printf("[debugdraw] %s threw: %s%n", d, re); }
                }
            }
        }
    }
}
```

A single hook in `MapView.draw` after the existing per-frame painters:

```java
// MapView.java around line 1869, immediately after partydraw(g)
DebugDraw.Registry.paintAll(g, this);
```

A feature registers a painter once at startup from its `*Debug` class's static block. From `MilkingAssistDebug.java`:

```java
public final class MilkingAssistDebug implements DebugDraw {
    private static final MilkingAssistDebug INSTANCE = new MilkingAssistDebug();
    static { DebugDraw.Registry.register(INSTANCE); /* ...console + replay... */ }

    public CFG<Boolean> toggle() { return CFG.DEBUG_MILKING_ASSIST; }

    public void paint(GOut g, MapView mv) {
        UI ui = mv.ui;
        if(ui == null || ui.sess == null) return;
        State s = capture(ui.sess.glob, System.currentTimeMillis());
        // ... draw "milk: ON | pending uid=... ttl=...ms | retries: N roster: M entries"
    }
}
```

Class loading: `DebugDraw.Registry`'s static block calls `DebugBoot.init()`, which in turn `Class.forName`s each feature's `*Debug` class so the registrations fire on the first frame. To add a feature, append one line to `DebugBoot.init`.

### Where

- `src/haven/dev/DebugDraw.java` — interface and `Registry`.
- `src/haven/dev/DebugBoot.java` — force-loads feature debug classes so their static blocks run.
- `MapView.java:1869+1` — one line: `haven.dev.DebugDraw.Registry.paintAll(g, this);`
- `CFG.java` — `CFG<Boolean>` entries per feature, namespaced `debug.<feature>` (mirroring `debug.proto_inspector.*` at `:217`). The first one is `DEBUG_MILKING_ASSIST`.
- Per-feature: a `*Debug.java` (e.g. `src/thunder/MilkingAssistDebug.java`) containing the painter + console commands + replay handler, all registered from one static block.

### Invariants and safety

- **Errors are swallowed and logged.** A debug overlay must never crash the main draw loop. `Registry.paintAll` catches `RuntimeException` from both `toggle()` and `paint()`.
- **`Loading` is swallowed too.** A debug painter that asks for an unloaded resource shouldn't block the rest of the frame; the toggle is opt-in, the cost of "no overlay this frame" is acceptable.
- **No state mutation.** Painters read `MapView`/`Glob`/feature state; they never write. (Enforced by convention, not the type system.)
- **Painters run after `partydraw`/`poldraw`.** They draw on top of game features, never below — debug should always be visible.

### Effort

- Framework (registry + boot + hook + CFG entry): ~80 lines, landed in this commit.
- Per-feature painter: ~40 lines for the milking-assist case, including the state-capture helper that's shared with the snapshot writer.

### What it doesn't do

- 3D world-space overlays. Those continue to use the `Rendered`/`SlottedNode` pattern that `Hitbox` already establishes (`Hitbox.java:59-117`). A possible future extension is a `WorldDebugDraw` registry that hangs a per-`MapView` `RenderList` of debug `Rendered` instances, but that's not needed for screen-space cases and isn't in scope here.
- Anything tied to a non-`MapView` widget. If a debug overlay belongs in a window (e.g. inventory layout debugging), it goes in that widget's `draw`, not the registry. The registry is for `MapView` overlays specifically.

---

## Primitive 2: Dev-console namespace

### Why

The console (`Console.java`, `GameUI.java:2308-2410` `cmdmap` block) is already the de-facto developer escape hatch:

- `afk` (`:2310`) — toggle AFK
- `act` (`:2316`) — fire an action menu path
- `belt` (`:2323`) — manipulate the belt UI
- `proto` (`:2355`) — proto inspector subcommands (stats/pause/record/bookmark)
- `gob inspect <id>` (`:2389`) — dump a gob

So the mechanism exists. What's missing is a convention: every feature should have its console handle, and they should be discoverable.

### What

A naming convention:

```
dev.<feature>.<verb> [args...]
```

- `<feature>` is a single short token (`milk`, `snap`, `pol`, `path`).
- `<verb>` is the action: `dump`, `fire`, `snapshot`, `clear`, `set`, `list`. (Not enforced; just a starter set.)
- `dev.` prefix keeps these out of the way of feature commands like `gob` or `belt` and makes them easy to grep.

Plus a tiny helper to register a feature's commands together and discover them:

```java
// src/haven/dev/DevCmd.java
public final class DevCmd {
    private static final Map<String, Map<String, Console.Command>> byFeature = new TreeMap<>();

    public static void register(String feature, String verb, Console.Command cmd) {
        Console.setscmd("dev." + feature + "." + verb, cmd);
        byFeature.computeIfAbsent(feature, f -> new TreeMap<>()).put(verb, cmd);
    }

    static {
        Console.setscmd("dev", (cons, args) -> {
            if(args.length < 2) {
                cons.out.println("registered dev features: " + String.join(", ", byFeature.keySet()));
                return;
            }
            Map<String, Console.Command> verbs = byFeature.get(args[1]);
            if(verbs == null) { cons.out.println("no dev." + args[1] + ".* commands registered"); return; }
            cons.out.println("dev." + args[1] + ".*: " + String.join(", ", verbs.keySet()));
        });
    }
}
```

`Console.setscmd` (`Console.java:54`) is the static-global registration API; commands registered there are visible in any `Console.Host`, including `GameUI` (which already aggregates static + instance + directory commands at `:66-81`). That means dev commands work pre-character-select if needed, and don't have to live in `GameUI.cmdmap`.

A feature's registration is one block. From `MilkingAssistDebug.java`:

```java
static {
    DebugDraw.Registry.register(INSTANCE);
    DevCmd.register("milk", "dump",     MilkingAssistDebug::cmdDump);
    DevCmd.register("milk", "fire",     MilkingAssistDebug::cmdFire);     // dev.milk.fire <uid> -- stage a UID without right-clicking
    DevCmd.register("milk", "clear",    MilkingAssistDebug::cmdClear);
    DevCmd.register("milk", "snapshot", MilkingAssistDebug::cmdSnapshot);
    DebugReplay.register("milk", MilkingAssistDebug::replay);
}
```

`dev` with no args lists registered features. `dev milk` lists `milk`'s verbs. The user can then type `dev.milk.dump` directly. Existing console autocomplete (if any — check `Chatwindow` / wherever console input is handled) keeps working since these are real registered commands.

### Where

- `src/haven/dev/DevCmd.java` — registration helper + the `dev` meta-command.
- Per-feature: register at static init from the same `*Debug.java` that owns the `DebugDraw` painter.

### Effort

- Helper: ~50 lines, landed in this commit.
- Per-feature: 5-15 lines per command body, plus whatever the command does. The four `dev.milk.*` commands together are ~40 lines.

### What it doesn't do

- Auto-completion of `dev.<feature>.<verb>` paths. The console treats them as opaque names; if you want tab-complete, that's a separate task in the input widget.
- Argument parsing. Each command parses its own args. (Most are zero or one arg; over-engineering an arg parser doesn't pay off.)
- Permissioning or "release builds disable dev". Not needed — a player who opens the console and types `dev.milk.dump` deserves to see the output.

---

## Primitive 3: `DebugSnapshot` capture + replay

### Why

The hardest debugging is "this only happens in *that* spot in the world, with *those* gobs around, at *that* zoom level." Recreating the scene by hand is the slowest part of the loop, often slower than the launch. The proto retro-recordings (`play/proto-recordings/retro-*.jsonl`, written from `RetroCapture.java:124-159`) already solved this for the network layer: capture a rolling window of events, dump on demand, post-mortem inspect.

The same idea applied to feature state: when something interesting happens (or the user runs `dev.<feature>.snapshot`), serialize the inputs the feature's logic operates on, write a JSONL file, and provide a `main()` driver that loads the file and re-runs the feature's pure layer headlessly. Iteration time becomes the JVM startup cost, not the launch cost.

### What

Three pieces:

**1. A capture API.** A thin static helper that writes to a per-feature directory, mirroring the proto pattern:

```java
// src/haven/dev/DebugSnapshot.java
public final class DebugSnapshot {
    public static Path write(String feature, JSONObject body) throws IOException {
        Path dir = Utils.path(System.getProperty("user.dir", "."))
            .resolve("dev-snapshots").resolve(feature);
        Files.createDirectories(dir);
        String name = String.format("%tY%<tm%<td-%<tH%<tM%<tS-%<tL.jsonl", new Date());
        Path file = dir.resolve(name);
        try(Writer w = Files.newBufferedWriter(file, CREATE, TRUNCATE_EXISTING)) {
            JSONObject header = new JSONObject()
                .put("type", "header")
                .put("feature", feature)
                .put("generated_at_ms", System.currentTimeMillis());
            w.write(header.toString()); w.write('\n');
            w.write(body.toString());   w.write('\n');
        }
        return file;
    }
}
```

Each feature decides what its snapshot body contains. For milking-assist (`MilkingAssistDebug.cmdSnapshot`): the toggle state, whether a `RosterWindow` exists, the current pending UID + deadline + retry count, and the full roster (per cattle: id, name, marked, lactating). Header + one body line keeps the format readable; if a feature wants per-frame multi-event captures, additional event lines follow the proto recording convention.

**2. A replay driver.** A single class with `main(String[] args)` that loads the JSONL, dispatches by `feature` field to a registered handler, and exits with a status:

```java
// src/haven/dev/DebugReplay.java
public final class DebugReplay {
    public interface Handler {
        void replay(JSONObject body, PrintStream out) throws Exception;
    }

    private static final Map<String, Handler> handlers = new HashMap<>();
    public static void register(String feature, Handler h) { handlers.put(feature, h); }

    public static void main(String[] args) throws Exception {
        if(args.length < 1) { System.err.println("usage: DebugReplay <snapshot.jsonl>"); System.exit(2); }
        List<String> lines = Files.readAllLines(Paths.get(args[0]));
        JSONObject header = new JSONObject(lines.get(0));
        String feature = header.getString("feature");
        Handler h = handlers.get(feature);
        if(h == null) { System.err.println("no handler for feature: " + feature); System.exit(3); }
        h.replay(new JSONObject(lines.get(1)), System.out);
    }
}
```

Handlers register themselves in static init. Feature handlers operate on the feature's *pure* layer — that's the design pressure: if a feature can be replayed, its logic is testable.

---

## Primitive 4: `Feature` interface

The three primitives above are sufficient but ceremonial — wiring up a new feature means writing five separate `register` calls plus an ad-hoc state-capture struct that the painter, dump, and snapshot all read. The `Feature` interface absorbs that ceremony.

```java
// src/haven/dev/Feature.java
public interface Feature {
    String name();
    CFG<Boolean> toggle();
    JSONObject capture();                          // shared by dump + snapshot (and optionally the painter)
    void paint(GOut g, MapView mv);
    void replay(JSONObject body, PrintStream out) throws Exception;
    default Map<String, Console.Command> extraVerbs() { return Map.of(); }
}
```

A single `DevFeature.register(this)` call wires everything. From `MilkingAssistDebug.java`:

```java
public final class MilkingAssistDebug implements Feature {
    static { DevFeature.register(new MilkingAssistDebug()); }

    public String name()             { return "milk"; }
    public CFG<Boolean> toggle()     { return CFG.DEBUG_MILKING_ASSIST; }
    public JSONObject capture()      { /* build JSON: pending, retries, roster, ... */ }
    public void paint(GOut g, MapView mv) { /* read capture(), draw 3 lines */ }
    public void replay(JSONObject body, PrintStream out) { /* decision tree */ }

    public Map<String, Console.Command> extraVerbs() {
        Map<String, Console.Command> m = new LinkedHashMap<>();
        m.put("fire",  (cons, args) -> { /* stage pending */ });
        m.put("clear", (cons, args) -> { /* clear pending */ });
        return m;
    }
}
```

`DevFeature.register` then:

- registers a `DebugDraw` that gates `paint` on `toggle()`;
- registers `dev.<name>.dump`  → prints `capture().toString(2)` (pretty JSON);
- registers `dev.<name>.snapshot` → calls `DebugSnapshot.write(name, capture())`;
- registers each `extraVerbs()` entry as `dev.<name>.<verb>`;
- registers the `replay` method as the `DebugReplay` handler keyed by `name`.

Two non-obvious benefits:

1. **No drift between dump and snapshot.** Both come from the same `capture()`; you can't update the snapshot schema without the live dump reflecting it the next frame.
2. **No parallel state struct.** The old design had a `State` POJO read by painter and dump and converted to JSON for snapshot. Now the JSON *is* the projection — the painter reads it back via `optBoolean`/`optString`/`optJSONArray`. Slightly more verbose at the read site, but eliminates a whole layer.

Use the lower primitives directly when `Feature` doesn't fit:

- A snapshot-only utility (no painter, no console verbs) wires `DebugSnapshot.write` directly.
- A debug overlay with no need for replay implements `DebugDraw` directly.
- A console-only debugger (no overlay, no snapshot) calls `DevCmd.register` directly.

**3. The pure-layer pressure.** Replay forces the feature to expose a pure-input entry point. Milking-assist already has `thunder.roster.RosterLogic.shouldRestoreMark` (a 4-arg pure function) and `thunder.InventoryActionObserver` (decoupled from any UI). The replay handler reproduces `MilkingAssist.tryResolve`'s decision tree (toggle on? roster present? pending expired? UID in roster?) over JSON-encoded inputs — that decision tree was previously buried inside a method that needed a live `UI`/`Glob`/`Pending` to exercise. Plob-snap, the likely next adopter, has `PlobSnap.java` already (`abutAgainst`/`alignEdgeWith`/`jacobianInvert`) and `PlobSnapTest.java` extending it; the next step there is the picker (`findGobByFootprint`) — see `snap-debug-cycle.md` Option 4.

For features without a pure layer yet, the act of writing a snapshot/replay pair forces one to emerge. This is the largest non-obvious benefit of primitive 3: replay support is, in practice, an architectural lever.

### Format choice: JSONL

JSON Lines for two reasons:

1. **Mirrors `proto-recordings/`.** Same write conventions (`RetroCapture.writeDump` at `:132`), same `org.json` dependency on the classpath, same `play/<dir>/` placement. The same tools (`grep`, `jq`, an editor) work on both.
2. **One header line + one or more body lines.** Header carries `feature`, `generated_at_ms`, optional `client_version`; body lines carry the feature-specific payload. Multi-event captures (e.g. "ten frames of cursor movement during a snap") fit naturally as additional lines.

Snapshots live in `play/dev-snapshots/<feature>/<timestamp>.jsonl` (mirroring `play/proto-recordings/`). The whole `play/` directory is already gitignored, so no `.gitignore` change is needed.

### Where

- `src/haven/dev/DebugSnapshot.java` — write API.
- `src/haven/dev/DebugReplay.java` — replay driver, handler registry, `main`.
- Per-feature: a `snapshot()` method that builds the JSONObject body, called from `dev.<feature>.snapshot`. A handler registered via `DebugReplay.register(feature, ...)` that operates on the feature's pure layer.

### Effort

- Framework: ~140 lines, landed in this commit.
- First feature (milking-assist): ~150 lines for the capture serializer + replay handler combined, including the shared `State` capture struct used by the painter, dump, and snapshot.

### What it doesn't do

- Capture rendering / GL state. Snapshots cover inputs to feature logic, not the camera, not shader state, not the framebuffer. Visual regressions are out of scope; functional regressions are in.
- Capture across server boundaries. The protocol layer has its own recordings (`proto-recordings/`); they are separate streams, not unified. A feature that needs both ("did the snap math get the right answer for what the server actually sent?") cross-references by timestamp.
- Live mutation of replays. You can edit the JSONL by hand to test variants, but there's no GUI for "scrub through the snapshot and see the result interactively." If that becomes useful, it's a follow-up.

---

## Putting it together: milking-assist end-to-end

With all three primitives in place, the milking-assist iteration loop is:

1. **In-game (visualization).** Toggle `CFG.DEBUG_MILKING_ASSIST` (key `debug.milking_assist`). Three lines appear top-left of the MapView:
   - `milk: ON` or `milk: off (no roster window)`
   - `pending: uid=<hex> ttl=<ms> [in-roster | NOT in roster]` or `pending: none`
   - `retries: N  roster: M entries (X marked, Y lactating)`

   Most "why didn't this cattle deselect?" questions are answered without alt-tabbing to the log: the pending state, the TTL window, and roster membership of the pending UID are all visible per-frame.

2. **In-game (scripted firing).** `dev.milk.dump` prints the same state to the console. `dev.milk.fire <uid>` stages a pending UID without right-clicking a cattle gob — useful when the bug is "the resolve loop loses the match", because you can stage a known-good UID and watch the next item update. `dev.milk.clear` resets pending.

3. **Post-game (replay).** When a deselect didn't happen and you don't know why, `dev.milk.snapshot` writes `play/dev-snapshots/milk/<timestamp>.jsonl`. Then `java -cp hafen.jar haven.dev.DebugReplay play/dev-snapshots/milk/<timestamp>.jsonl` re-runs the resolve decision tree headlessly. Output looks like:

   ```
   [replay] feature=milk file=...
   captured: toggle=ON roster_window=true retries=0
   pending: uid=3039 deadline=... ttl=3000ms
   roster: 2 entries (match)
   decision: WOULD DESELECT cattle uid=3039 name=Bessie (currently marked=true, lactating=true)
   ```

   Or, for a stuck case:

   ```
   decision: NO RESOLVE -- pending UID not in roster -- tryResolve loops over entries and finds no match, leaves pending alone.
   ```

   Bug reports become attached files; the replay tells you which branch of `tryResolve`'s decision tree the live code is taking.

4. **CI (pure-layer tests).** The same decisions exercised by the replay handler are exercised by `RosterLogicTest` (for `shouldRestoreMark`) and `InventoryActionObserverTest` (for the pending/retry plumbing) — caught at `ant test` speed.

The same template applies to the next feature: a `DebugDraw` painter, a handful of `dev.<feature>.<verb>` commands, a `snapshot()` method + replay handler, and (as a side effect of the replay) a pure layer that gets unit tests for free.

## Adoption roadmap

Build order, smallest first; each is independently shippable. Items 1-7 are landed in this commit using milking-assist as the first user.

1. **`DebugDraw` registry + `DebugBoot`.** Interface, registry, MapView hook. Zero painters registered initially.
2. **First painter (milking-assist).** Validates the registry; the painter shows pending UID/TTL, retry count, roster summary.
3. **`DevCmd` helper + `dev` discovery command.** Helper plus the meta-command for listing features and verbs.
4. **First console commands (milking-assist).** `dev.milk.dump`, `dev.milk.fire <uid>`, `dev.milk.clear`, `dev.milk.snapshot`.
5. **`DebugSnapshot` write API.** JSONL writer mirroring `RetroCapture`.
6. **Snapshot serializer (milking-assist).** Captures pending + roster contents.
7. **`DebugReplay` driver + handler (milking-assist).** Pure decision tree over captured state.
8. **Next adopter.** Plob-snap (`docs/snap-debug-cycle.md`) is the natural second user; cattle highlighting / radius overlays are also candidates. Each new feature: ~150-200 lines in a single `*Debug.java`, plus one line in `DebugBoot.init`.

After that, the per-feature cost is dominated by the feature's own logic, not by debug tooling.

## File-path reference

### Existing precedents (cited above)

- `src/haven/MapView.java` — `draw(GOut g)` at line ~1855; `partydraw`/`poldraw` are the screen-space painter precedents. `DebugDraw.Registry.paintAll(g, this)` runs immediately after `partydraw(g)`.
- `src/haven/Hitbox.java:30-117` — 3D per-gob `Rendered` overlay; *not* the precedent for `DebugDraw` (different layer).
- `src/haven/Console.java:43-91` — `Command`, `setscmd`, `findcmd`, `run` dispatch.
- `src/haven/GameUI.java:2308-2410` — `cmdmap` block; existing console commands. Dev commands use `setscmd` instead, so they don't have to live here.
- `src/haven/proto/RetroCapture.java:124-159` — JSONL writer + path layout precedent for `DebugSnapshot`.
- `src/thunder/roster/RosterLogic.java:28-35` — pure-logic layer for the milking decision (`shouldRestoreMark`); replay handler reproduces the same shape over captured state.
- `src/test/java/thunder/InventoryActionObserverTest.java` — JUnit pattern over the pending/retry observer; extended in this commit with a `retryCount()` test.
- `src/haven/CFG.java` — namespacing pattern for `debug.<feature>` toggles; `DEBUG_MILKING_ASSIST` is the first one for this toolkit.
- `play/proto-recordings/retro-*.jsonl` — JSONL conventions mirrored by `play/dev-snapshots/<feature>/`.

### Toolkit files

- `src/haven/dev/Feature.java` — recommended entry point for a new feature.
- `src/haven/dev/DevFeature.java` — `register(Feature)` helper that wires painter + dump + snapshot + replay + extra verbs.
- `src/haven/dev/DebugDraw.java` — screen-space painter interface + registry.
- `src/haven/dev/DebugBoot.java` — force-loads feature debug classes; one-line addition per feature.
- `src/haven/dev/DevCmd.java` — `dev.<feature>.<verb>` registration helper + `dev` meta-command.
- `src/haven/dev/DebugSnapshot.java` — JSONL write API.
- `src/haven/dev/DebugReplay.java` — replay driver + handler registry + `main(String[])`.

### Worked example (milking-assist)

- `src/thunder/MilkingAssist.java` — feature implementation; debug accessors are package-private at the bottom.
- `src/thunder/InventoryActionObserver.java` — added `retryCount()` for the painter/dump.
- `src/thunder/MilkingAssistDebug.java` — owns the painter, the four `dev.milk.*` console commands, the snapshot serializer, and the replay handler.

### Related docs

- `docs/snap-debug-cycle.md` — feature-specific analysis for plob-snap; its six options map onto these primitives. Plob-snap is the natural next adopter once a snap bug warrants the build-out.
- `docs/cattle-roster-feature-batch.md` — design doc for milking-assist (the worked-example feature).
