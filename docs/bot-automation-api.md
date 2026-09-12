# Bot automation: rules

Load this before writing a new `auto.*` bot. Each rule = trap + fix + code
pointer. Nothing here is a reusable class — go read the named method, copy the
pattern.

- **Open a container**: right-click (button 3) raw `wdgmsg("click", ...)`.
  NOT `Gob.itemact()` (= "use held item on gob," opens nothing). NOT
  `Gob.click()`/`MapView.click()` (button-1 path also calls `pathQueue.start`,
  re-triggers movement, breaks a just-settled state). → `MiningMaterials.openContainerWindow`

- **Container UIs differ**: crate = `Inventory` grid, shift-click
  (`wdgmsg("transfer",...)`). Stockpile = "Take" button, no grid, click once
  per unit. Same `GobTag.CONTAINER` tag for both — try grid, fall back to
  button. → `MiningMaterials.fetchFromZone`
  - Missing tag on a container gob? Add resid to `etc/containers.json5`
    (presence = tag, empty pose arrays OK). Diagnose via
    `MiningMaterials.logZoneContents` (resid + tags per gob), don't guess.

- **Nested buttons**: widget search must be recursive, not direct-children-only
  (Stockpile's "Take" was one level inside an `ISBox`). →
  `MiningBot.findButton` (recursive), `MiningBot.dumpWidgetTree` (structure
  dump when a search comes up empty)

- **"Take" click cap**: use `long` math. `need` can be `Integer.MAX_VALUE`;
  `int` math (`need - count + 5`) overflows negative → takes 0 silently.

- **Eating**: `WItem.itemact(0)` does nothing (verified: energy frozen across
  15+ calls) — same dead bug was in `haven/bot/AutoEat.java` for years. Real
  path: right-click item → wait for `FlowerMenu` → `menu.choose(Petal)` by
  name. Eat straight from the container inventory, never transfer to
  player inventory first. → `MiningMaterials.eatViaFlowerMenu`
  - `forceChoose(...)` only works if set *before* `attach()` — useless once
    the menu's already on screen (that's when you're finding it). Use
    `choose(Petal)` directly instead.
  - `FlowerMenu` attaches above `GameUI`, grabs mouse/keys globally. Search
    from **`gui.ui.root`**, not `gui` — searching `gui` finds nothing.
  - Main-loop code that can't block-and-search (e.g. `AutoEat.tick()`):
    subscribe first, click second —
    `Reactor.FLOWER.first().subscribe(m -> m.forceChoose("Eat")); item.rclick();`
  - **Never read `FlowerMenu.opts` inside that `Reactor.FLOWER` subscriber** --
    crashed the whole client instantly, real stack trace: `Reactor.FLOWER.onNext(this)`
    fires from *inside FlowerMenu's own constructor*, before `opts` is assigned, so
    reading it there is a guaranteed NPE ("Cannot read the array length because ...
    is null"), uncaught, straight through `UI$CommandQueue`. `forceChoose(...)` itself
    is safe (it re-checks options later, once the menu is actually built). To verify
    which option a menu actually got chosen (e.g. confirming "Eat" was really
    available), subscribe to `Reactor.FLOWER_CHOICE.first()` instead -- it only fires
    once a real choice is made, by which point the menu is fully constructed. →
    `thunder/cookbook/EatingHelperWnd.java` (`eatOne`)

- **Pacing a fast auto-loop (many fire-and-forget actions in a row)**: don't
  use a fixed sleep between actions. Wait for a real server-confirmed signal
  that the last one was processed (e.g. a capture hook's `lastUpdate()`
  timestamp advancing past the action's send time), with a short timeout
  fallback so one dropped/unconfirmed action can't hang the loop forever, plus
  a small minimum-gap floor enforced even when confirmation is instant (don't
  hammer the server on a fast connection). → `thunder/cookbook/EatingHelperWnd.java`
  (`tick()`, `eatOne`, `SatiationCapture.lastUpdate()`)

- **`tick()` state machine as an alternative to a `Bot`/`Defer` thread**: for a
  UI-window-driven repeat action (click, wait a beat, click the next one) that
  doesn't need to block step-to-step, running it off the widget's own `tick()`
  with explicit phase flags avoids needing a cancellable background thread at
  all — no `Defer.Future`, no starvation risk (see below). Use `auto.Bot`'s
  threaded style (`MiningBot`) when a step must block waiting on movement or
  placement; use a `tick()` state machine (`EatingHelperWnd`) when steps are
  just paced, fire-and-forget clicks. → `thunder/cookbook/EatingHelperWnd.java`
  (`tick()`, `autoRunning`/`autoSettling`/`autoWaitingBite`)

- **Lock in a multi-round loop's target at the start — don't re-read a mutable
  field**: a field that's also used for live UI display (e.g. a dropdown
  selection) can get silently reassigned by unrelated logic between rounds
  (a "keep selection, else fall back to first available" refresh). Snapshot
  the real target once when the loop starts, compare against the live field
  every round, stop cleanly on mismatch. Real bug this caught: auto-eat kept
  running after its target stat silently switched underneath it. →
  `thunder/cookbook/EatingHelperWnd.java` (`autoTargetStat` vs `selectedStat`,
  `startAutoRound`)

- **Walking to a gob** (not a tile): default arrival radius (`tilesz*0.6`) is
  tile-sized; a gob's collision keeps you further out, so `arrived` never
  fires. Use `MapHelper.GOB_ARRIVE_RADIUS` (`tilesz*1.5`) via the 4-arg
  `walkTo(gui, target, timeoutMs, arriveRadius)`.

- **Before any interact/placement after walking**: wait for `Moving` GAttrib
  to clear (`player.getattr(Moving.class) == null`) — server-authoritative,
  not `walkTo`'s local distance guess. Firing immediately after "arrived" can
  be silently dropped server-side mid-stride. → `MiningBot.waitForMovementSettled`
  (same idiom as `thunder.MilkingAssist`)

- **Energy scale**: `IMeter.meter(0)` is 0.0–1.0. The tooltip % is that value
  × 10000 (display quirk, not a different stat) — "20%" on the bar = `0.20`.
  "Eat at 2600%" = `0.26`. → `MiningBot.LOW_ENERGY_THRESHOLD`

- **Long-running bot task starves the engine**: `auto.Bot` runs a whole bot
  as one `Defer.Future`; that instance's worker doesn't free up until the
  task fully returns, which can starve unrelated work sharing the same
  `Defer` instance (e.g. `TexL.prepare()` texture finalization → placement
  hangs on "Finalizing texture in ..."). Fix: `Defer.Future.ensureExtraWorker()`
  on the specific stalled instance (`Defer`'s own auto-grow only triggers on
  a queue that was already non-empty, so one item landing on a fully-occupied
  pool never grows it). → `haven/Defer.java`; call sites in
  `haven/MapView.java` (`placingBlockerPoolStats`, `boostPlacementPriority`,
  `ensurePlacementBlockerWorker`)

- **New local paginae resource won't parse**: `.data`/`meta` text files
  under `resources/src/local/paginae/` must use **CRLF line endings with no
  trailing blank line** — LF-only trips a null-split NPE in
  `haven.Utils.rstr`. `diff`/`xxd` a known-working sibling resource rather
  than trusting an editor's default line endings; verify with
  `java -jar LayerUtil.jar -e <path-to-.res-dir>` before `ant bin`. →
  `docs/cookbook-integration.md` (paginae gotcha section)

- **No offline database of real crafting-recipe ingredients exists**: a
  recipe's actual required inputs (item + quantity, e.g. "1 Intestines + 2
  Raw Badger") only exist as live `"inpop"`/`"opop"` widget messages the
  server sends for whichever `Makewindow` you have open right now — nothing
  is cached to a resource file, and `CraftDBWnd` proves it by opening a real
  live `Makewindow` per recipe instead of reading a local cache. If you need
  this data, the only option is passive capture (record it when a player
  happens to open that recipe's crafting window) — there's no bulk source to
  pull from. → `thunder/cookbook/RecipeCapture.java`, hooked from
  `Makewindow.uimsg` (`"inpop"`/`"opop"`), `docs/cookbook-integration.md`

- **Inspecting a resource's real content without a live game session**:
  `brodgar.io/res/<respath>.res` serves the raw resource binary directly
  (confirmed: byte-for-byte the size reported by its own search API) —
  download it, then `java -jar resources/LayerUtil.jar -d <file>.res`
  decompiles it same as any local resource, dumping `image_N.png` etc. you
  can just view. Only works from inside `resources/` with a relative path —
  an absolute Windows path (backslashes) makes LayerUtil's internal path
  handling throw `IOException: filename... syntax is incorrect`; copy the
  file into `resources/` first. → used to confirm `meat-badger.res` really
  is just an ~800-byte badge with no meat shape, not a client bug —
  `docs/cookbook-integration.md` (ItemIconUtil section).

- **Resolving an item's resource path from just its display name** (no live
  session): `brodgar.io/res/?search=<query>` returns `[{name, relDir,
  versions}]` — a real filename index, not a guess. Query the name's slug
  (filenames have no spaces), require an **exact** filename match, and
  restrict to the directories that actually hold the icon type you want (e.g.
  `gfx/invobjs`/`gfx/terobjs/items` for item icons) — an unrestricted search
  can return a wrong-but-real resource (a live sprite, a minimap marker)
  instead of no result. → `thunder/cookbook/IngredientIconResolver.java`; to
  browse/cache a whole directory instead of one query, see
  `thunder/cookbook/BrodgarInvobjsIndex.java`.

- **`Label.settext()` doesn't wrap even if the Label was constructed with a
  width** — it always falls back to unwrapped rendering on update, so a status
  line that grows can run clean off the window. Override `settext` to call
  `f.renderwrap(...)` (same as the width-constructor's own initial render) for
  any label whose text updates and can get long. →
  `thunder/cookbook/EatingHelperWnd.java` (`WrappedLabel`)

- **A best-effort visual extra must never block the base render**: if you
  composite an optional second resource onto an icon (a backdrop, an
  overlay), don't re-throw `Loading` from that second fetch — every icon
  cache in this codebase caches its result permanently once non-Loading,
  so if the optional resource never settles (unconfirmed live-server vs.
  third-party-mirror resolution differs), the *whole* icon never gets
  cached and never shows, not just the extra. Catch everything around the
  optional part and fall back to the base image immediately. →
  `thunder/cookbook/ItemIconUtil.java`, `docs/cookbook-integration.md`
  ("Missing meat/fish backdrop" section, the regression + fix).

- **`Defer.later` background callback touching a `Label`/`Button`/`RichText`**:
  don't. `settext`, `new Button(...)`, `RichText.render(...).tex()` all do
  synchronous AWT/Java2D font rendering, which is not safe off the main
  thread — worst case a silent native crash, no catchable exception.
  `synchronized(ui)` does not fix this (it's a threading issue, not a data
  race). Fix: background callback writes plain `volatile` fields only; do
  the actual widget work in `tick()`, which runs on the main thread. →
  `thunder/cookbook/CookbookWnd.java` (`pending*` fields), `docs/cookbook-integration.md`

- **Diagnostics**: log to a file, not just console (no scrollback in-game).
  Put the log call *before* any early-return — a trivially-satisfied check
  that returns early before logging produces an empty file with no clue why.
  → `MiningBot`'s `openDiagLog`/`diag`/`dumpWorkerThreadStacks` (also dumps
  every `Defer.Worker` thread's live stack — how the starvation bug above was
  root-caused instead of inferred)
