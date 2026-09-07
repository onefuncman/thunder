# Eating Helper

`:eat` console command, wired via `GameUI.cmdmap`. Opens `thunder.cookbook.EatingHelperWnd`
(despite the package name, this isn't tied to the Cookbook feature -- it lives there because
that's where the food-domain helper code already is).

## What it does

Scans every open inventory/container (main inventory, tables, cabinets, pouches -- see
"Where it scans" below) for food items, simulates an eating order via `simulatePlan()`
(see "Multi-step plan" below), and shows the plan as grouped rows ("3x Egg & Bacon") plus a
small numbered badge on each item in your inventory matching its row number. "Auto-Eat" (see
below) will run that plan for you, one level at a time, indefinitely, until stopped.

## Auto-Eat

**Logging**: every stage of every bite writes a line to `cookbook-satiation-debug.log` (same
file as the satiation capture) -- `AUTOEAT-START`/`AUTOEAT-STOP`, `ROUND-START`/
`ROUND-RESULT`, `BITE-SENT`/`BITE-MENU-OK`/`BITE-MENU-NO-EAT-OPTION`/`BITE-CONFIRMED`/
`BITE-TIMEOUT`. Added directly at the user's request, specifically to check for silent
failures before trusting the fast per-bite pacing on a large batch: `BITE-MENU-NO-EAT-OPTION`
means `forceChoose("Eat")` found no matching option (an item that never actually got eaten,
with zero other visible symptom), and `BITE-TIMEOUT` means no confirming server update ever
arrived for that bite within `BITE_TIMEOUT_MS`. Grep the log for either after a run to check
whether anything didn't land as expected; `BITE-CONFIRMED ... rtt=Nms` lines show the real
round-trip time auto-eat is actually seeing, useful for judging how low the "Min. gap" field
can safely go.

**This logging crashed the client on its very first real test** -- the first version read
`FlowerMenu.opts` inside the `Reactor.FLOWER` subscriber to build the `BITE-MENU-OK`/
`BITE-MENU-NO-EAT-OPTION` line, which is null at that point (`Reactor.FLOWER.onNext(this)`
fires from inside `FlowerMenu`'s own constructor, before `opts` gets assigned) -- an instant,
uncaught NPE straight through `UI$CommandQueue`, confirmed via a real stack trace from the
user's IntelliJ console. Fixed by moving that same check to `Reactor.FLOWER_CHOICE.first()`
instead, which only fires once a choice is actually made (menu fully built by then). See
`docs/bot-automation-api.md`'s Eating section for the general lesson.

Button next to the stat dropdown, plus "Auto-eat limit (items/level)" and "Min. gap between
bites (ms)" fields. In Feast mode, the planner considers food in every open inventory and
Auto-Eat uses each item's normal left-click path so the table bonus is retained.
Outside Feast mode it runs the plan with right-click + force-choose "Eat" on each
queued item (same fire-and-forget
`Reactor.FLOWER.first().subscribe(m -> m.forceChoose("Eat")); item.rclick();` pattern
`haven.bot.AutoEat` already uses -- `tick()` is the main game loop and can't block waiting for
a menu widget, so this can't be a blocking poll loop like `auto.MiningMaterials`'
Bot-thread version).

**Both per-bite pacing and the settle check are event-driven, not fixed timers.** Raised
directly by the user wanting to go as fast as the connection allows (300 items in seconds,
not minutes) without outrunning the server -- a fixed delay picked safe for a good connection
wastes time on it, and one fast enough for a good connection risks checking stale state on a
bad one. `BAttrWnd.uimsg()` calls `SatiationCapture.markUpdated()` whenever a real
`food`/`glut`/`const` server message arrives (a plain `volatile long` timestamp, no extra file
I/O on the hot path); auto-eat waits for that timestamp to advance past the moment of the
relevant bite before proceeding, confirming the server actually processed it instead of
guessing a "safe" number:

- **Per bite**: after eating, waits for a confirmed update AND the "Min. gap" floor (default
  50ms) to both be satisfied before the next bite -- so it goes exactly as fast as the
  connection allows, never faster than the floor. `BITE_TIMEOUT_MS` (2s) is a fallback if one
  specific bite never gets a confirmation for some reason (so it doesn't hang on one item).
- **Settle check** (after the last bite in a round, before comparing attributes for a
  level-up): same idea, `SETTLE_TIMEOUT` (5s) fallback.

Two sends go out per bite either way -- `rclick()` (`wdgmsg("iact",...)`) immediately, then
`choose("Eat")` (`wdgmsg("cl",...)`) once the FlowerMenu's response to that rclick actually
arrives (gated on the real round trip already, not our clock) -- so the true floor on speed is
whichever is slower: the connection's round-trip time, or the configured min-gap.

- **Leveled**: reset the per-level item counter, snapshot the new attributes, run a fresh
  query for the NEXT point, repeat.
- **Didn't level**: this is the "mild issue" the user described directly -- every so often the
  plan is a sliver short and a small extra item is needed to actually finish the level. Just
  runs a fresh query again (which naturally proposes whatever small top-off is still needed)
  and keeps going, WITHOUT resetting the per-level item counter -- both rounds count toward
  the same level for limit-checking purposes.
- **Limiter is one strict check, applied identically to every round** -- `(items already
  eaten this level) + (this round's plan size)` against the limit field, whether that round
  is a fresh level start or a top-off retry, no exception either way. Went through two wrong
  turns before landing here, both corrected directly by the user:
  1. First version applied this same check every round, but the user pointed out it could
     fire mid-level (having already spent several items) rather than cleanly between levels.
  2. Tried exempting top-off rounds from the check entirely ("finish no matter how many
     top-offs it takes") -- the user immediately caught that this has no ceiling of its own,
     so a slow-converging level (satiation degrading available food faster than expected,
     etc.) could eat indefinitely past the limit.
  3. Landed here after the user clarified the actual expectation: a top-off should only ever
     happen once per level, so there's no real scenario where a round legitimately NEEDS to
     exceed the limit -- it's a hard ceiling on the level's total, full stop, no exceptions.
  Defaults to 10.
- **Target stat is locked for the whole run** (`autoTargetStat`), separate from `selectedStat`
  itself. `runQuery()`'s dropdown-preservation logic falls back to "whatever's first in the
  list" once the previously-selected stat runs out of food giving it -- fine for manual use,
  but for auto-eat that meant silently grinding a DIFFERENT stat than the one actually picked,
  with no warning at all. Reported directly by the user after exactly that happened
  (Constitution silently became Perception mid-run). `startAutoRound()` now compares
  `selectedStat` against the locked `autoTargetStat` after every `runQuery()` and stops
  cleanly, with a clear message, the moment they diverge -- instead of drifting.

**Scope decision on "make sure Feast! is activated on the correct table"**: this does NOT
auto-click any "Feast!" button. `Window.lastFeastTable`/`lastFeastBonus` (see below) are only
ever read, never driven -- with multiple tables potentially open, there's no reliable way to
infer which one the user actually wants to feast from, and clicking one has side effects
(salting, cutlery locking) the user should decide on, not the tool. Each round's fresh
`runQuery()` call re-reads whatever feasting state is ACTUALLY active at that moment (same as
a manual run), so if the user clicks Feast! partway through an auto-eat run, the very next
round picks that up automatically -- it just won't click it for them.

No per-bite verification that the "Eat" choice actually landed (matching `haven.bot.AutoEat`'s
own level of rigor) -- items that vanished between being queued and being eaten (someone else
grabbed it, e.g.) are silently skipped via `WItem.disposed()`, and the post-round attribute
comparison is the real correctness check, not the per-bite click.

## Why it doesn't reimplement the FEP math

The game's real FEP-gain formula is already implemented in our own client, in
`haven/resutil/FoodInfo.java`'s `fepnum(WItem)` method -- the exact same code that
generates the FEP number shown when hovering food. Confirmed directly against
[ringofbrodgar.com/wiki/FEP](https://ringofbrodgar.com/wiki/FEP) and
[.../wiki/Food_Satiations](https://ringofbrodgar.com/wiki/Food_Satiations) (both fetched
directly, since `WebFetch` gets a 403 from that wiki but plain `curl` with a browser
User-Agent works fine):

```
FEP Gained = Food FEP Value * Hunger Modifier * Satiation Modifier
Satiation Modifier = 100% - Satiation Penalty
```

matches `effmod = glutmeter.gmod * effective * bonusmul` in `fepnum()` exactly (`effective`
is the satiation modifier, `glutmeter.gmod` the hunger modifier, `bonusmul` the
subscription/verified account bonus). The variety-bonus cap-reduction formula
(`2 * MaxStat * sqrt(1/10)` per unique food type eaten this session, per a Loftar forum
post the wiki quotes) matches the `while(fepnext > curcap) ...sqrt(maxattr*2*gmod/5/n)`
loop in the same method. Since the client's own formula is already verified correct, using
it directly is strictly better than re-deriving it independently.

Added `FoodInfo.breakdown(boolean feast, int tableFepPercent)`, a sibling to `fepnum()`
that reuses the same private cached state (`glutmeter`, `feps`, `attr`, `constipation`,
populated by `getcw()` from `ui.gui.chrwdg.battr` -- so the Character Sheet's Base
Attributes/Food tab must have been opened at least once this session for `battr` to be
non-null) but returns the **per-attribute** split `fepnum()` only sums, and takes the table
bonus as an explicit parameter instead of reading the ambient `FoodInfo.tablefep` static
(see below for why).

## Table bonus: which table, specifically

`Window.java`'s `CheckForDinnerTable()` already parses a table window's "Food event
bonus: N%" label and "Feast!" button on every render, but sets a single shared
`FoodInfo.tablefep` static -- with two table windows open, whichever renders most recently
wins, not necessarily the one you're actually feasting from. Confirmed as the wrong
behavior directly by the user ("the only one that matters is the one we're clicking
Feast! from").

Fixed by hooking the "Feast!" button's own click action (already hooked once for a BGM
trigger) to record `Window.lastFeastTable` (the specific `Window` instance) and
`Window.lastFeastBonus` (that window's own `myTableFep`, a new per-instance field --
distinct from the shared static, so it's always this table's own last-parsed value, not
whichever table rendered most recently). `EatingHelperWnd` reads only these two, and only
scans `Window.lastFeastTable`'s own `Inventory` children for extra food -- never any other
open table.

## History: snapshot-only v1, and why it wasn't enough

Satiation increases fast but decays slowly (roughly 2-4+ days per the user; the wiki cites
a 2021 patch note "satiations now increase and decrease 3x slower"), so within one short
eating session it's effectively monotonically increasing, not something eating item #1 can
be assumed not to affect item #5's ranking. v1 had no public formula for exactly how much a
bite raises its category's satiation penalty (the client only ever receives the post-eating
value from the server via `Constipations.update(ResData, double)`, never a delta or rate),
so it just ranked the CURRENT state and asked the user to re-run the query between bites.

That undersold the tool: sorting 335 items by current-state FEP puts every instance of the
same dish back-to-back (six "Egg & Bacon" in a row, say) -- individually the highest values
at that instant, but eating six in a row is exactly what tanks that category's satiation.
Reported directly by the user with a screenshot after actually trying to work through a
335-item list top to bottom.

## Deriving the missing satiation formula empirically

No published formula existed, so this was measured directly from two real eating sessions
via passive capture (`SatiationCapture`/`EatCorrelationCapture`, both hooked into
`BAttrWnd.uimsg`/`WItem.draw`), the same "no offline source, observe it live" approach as
`RecipeCapture`. Findings, in order of discovery:

1. The raw satiation value's per-bite decrease is NOT constant -- it shrinks each bite
   (e.g. -0.0432, -0.0377, -0.0335, -0.0298 across four consecutive bites of the same dish).
2. Transforming to "odds" (`x = (1-a)/a`) makes the per-bite increase far more stable within
   one clean run (varying ~2x instead of ~9x) -- this is the right lens to look through.
3. Cross-session comparison surfaced that several distinct satiation categories can share
   the exact same display tooltip (four separate entries all labeled "Meat" seen in one
   sync) -- fixed by logging `ResData.hashCode()` (resource + content-based `MessageBuf`
   payload) as a stable `id` alongside the name, so different tracks can't be conflated.
4. A user-caught correlation nailed the last major factor: two bites of the *identical* item
   gave different results (Δx 0.0308 vs 0.0221) purely because current hunger modifier
   (`gmod`) had dropped from 1.63 to 1.49 between them -- current fullness modulates the
   satiation-increase rate too, not just the item eaten. `gmod`/`glut` are now logged
   directly on every `CONST` line for exact correlation instead of nearest-timestamp
   matching.

Current best fit, used by `EatingHelperWnd.satiationDelta()`:

```
x = (1-a)/a                      // satiation as "odds" instead of raw value
Δx ≈ 0.008 × item's total raw FEP  // per bite, held constant at live gmod for the session
new_a = 1 / (1 + x + Δx)
```

Explicitly an empirical fit from limited data, not a dev-confirmed constant -- flagged as
such in the window's own status text and code comments, not presented as exact.

## Multi-step plan (`simulatePlan()`)

Greedily builds an eating order: at each step, evaluate every remaining item under the
CURRENTLY-SIMULATED satiation/effmod state (starts from the live state, then updated by
`satiationDelta()` after each simulated bite -- not the live state itself, which only
changes when you actually eat), pick whichever gives the most useful target progress per
unit of hunger,
"eat" it (advance the simulated FEP-bar total and the satiation for its categories), repeat.
Stops once the simulated bar would cross the cap (one attribute point) or after
`MAX_SIM_STEPS` (60), whichever comes first. Consecutive picks of the same resource are
collapsed into one grouped row ("3x Egg & Bacon") for display, and the badge on each
physical inventory item matches its row number.

The FEP-bar cap's variety-bonus reduction (a newly-eaten distinct resource lowers the cap,
same formula as `fepnum()`'s own cap-reduction loop) IS re-simulated step to step, continuing
from the reduction count already baked into the live cap (derived by replaying `fepnum()`'s
search backwards). An earlier version held the cap fixed for the whole plan, which
overestimated how much total FEP was needed and recommended one extra item past what was
actually necessary -- reported directly by the user (plan said 2 items were needed, 1 turned
out to be enough).

Candidate scoring credits target FEP plus the cap reduction from a genuinely new food,
divides that useful progress by hunger cost, and caps credited progress at the remaining
gap so overfill is not rewarded. While any uneaten variety containing the selected stat is
available, repeated copies are ineligible; repeats become available only after those distinct
target-bearing choices are exhausted.
Auto-Eat remembers the resources it consumed across top-off replans within the current
attribute level and clears that memory after a level-up. Foods with no FEP for the selected
target remain ineligible.

FEP event variants are combined by base attribute for planning: `Strength +1` and
`Strength +2`, for example, both contribute to the single `Strength` target. The same
normalization applies to every attribute in the dropdown and its calculated target total.

Food variety is identified by the preparation's inventory resource together with the
server-provided satiation-category identities. This matters because species such as bear
and fox can share the generic `gfx/invobjs/meat` resource, while roast and spitroast are
different preparations. Quality alone does not create a new variety.

Known simplification still in place, worth revisiting if plans look off: `gmod` (hunger
modifier) is held constant for the whole plan -- it drifts slowly in practice (~5% over 15
real bites in the capture data), so this is a minor effect for a short plan, but not exactly
zero.

**Variety bonus, confirmed against the wiki's own page for it**
([ringofbrodgar.com/wiki/Variety_Bonus](https://ringofbrodgar.com/wiki/Variety_Bonus), found
by the user): their worked example gives `0.894 * sqrt(maxstat)` as the per-unique-food
reduction at "Famished" (200% food efficiency). Our formula -- already in `fepnum()`, now
also in `simulatePlan()` -- is `sqrt(maxattr * 2 * gmod / 5)` for the first unique food. At
gmod=2.0: `sqrt(2*2/5) = sqrt(0.8) = 0.894`. Exact match, independent confirmation the
formula (reverse-engineered from `fepnum()`, not from this wiki page) is right.

**Hunger-efficient finishing**: the original greedy loop picked the highest-target-FEP item,
including near the end of the bar. The current score caps useful progress at the remaining
gap before dividing by hunger, so FEP that would overfill the bar provides no advantage.
This naturally favors a low-hunger finisher that supplies enough useful progress and saves
larger dishes for later levels.

## Files

- `haven/resutil/FoodInfo.java` -- `Breakdown` class + `breakdown(boolean, int)` method
- `haven/Window.java` -- `lastFeastTable`/`lastFeastBonus` statics, `myTableFep` per-window
  field, both set from the existing "Feast!" button click hook
- `haven/BAttrWnd.java` -- `uimsg()` hooks logging every `food`/`glut`/`const`/`lvl`/`ftrig`
  message via `SatiationCapture`, and calling `SatiationCapture.markUpdated()` on
  `food`/`glut`/`const` for auto-eat's event-driven settle check
- `haven/WItem.java` -- `EatCorrelationCapture.consider(item)` hook (item-stat snapshots for
  satiation-delta correlation)
- `haven/GameUI.java` -- `eatingHelperWnd` field, `:eat` command
- `thunder/cookbook/EatingHelperWnd.java` -- the window itself, including `simulatePlan()`
- `thunder/cookbook/SatiationCapture.java` -- raw timestamped server-message log, plus
  `markUpdated()`/`lastUpdate()` (a plain volatile timestamp auto-eat's settle check polls)
- `thunder/cookbook/EatCorrelationCapture.java` -- per-item stat snapshots for correlating a
  specific bite with its satiation effect
