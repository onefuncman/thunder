# Auto-Butcher

`src/auto/ButcherBot.java`, triggered from the "Auto-butcher" paginae button
(`paginae/act/automation` category, alongside Aggro All/Fill Cheese Tray/Mount
Horse — `resources/src/local/paginae/add/auto/auto_butcher.res`, wired up via
`Action.AUTO_BUTCHER` in `src/haven/Action.java` and one `makeLocal(...)` line
in `MenuGrid.initCustomPaginae()`).

Finds every dead animal corpse within 10 tiles, equips the player's best
cutting tool (from an equipped hand, the belt, or main inventory — restoring
the hand slot to exactly how it was found once done), then walks to and fully
processes each corpse in turn (skin/clean/butcher/collect bones, whichever of
those the corpse actually offers) before moving to the next.

## Corpse detection

Filter: `GobTag.ANIMAL && (GobTag.DEAD || GobTag.KO)`.

`GobTag.DEAD` (pose `/dead` or `/waterdead`, `src/haven/GobTag.java:394`) is
also set on dead *players* (`gfx/borka/body` carries `PLAYER`, not `ANIMAL`),
so corpse-finding always requires `GobTag.ANIMAL` too — `ANIMAL` is only set
for `gfx/kritter/...` gobs matching the game's wild/domestic animal lists
(`GobTag.java:234-263`).

`GobTag.DEAD` alone isn't enough, though: confirmed live, wild animals reduced
to 0 HP go into the `/knock` pose (`GobTag.KO`, `GobTag.java:391`)
*permanently* — there's apparently no separate `/dead` pose used for them at
all. A knocked bear's flower menu already offers Skin/Clean/Butcher, so `KO`
is included alongside `DEAD` for animals specifically. This isn't a guess:
`GobTag.java`'s own `AGGRO_TARGET` check already treats `KO` and `DEAD` as
equivalent "not a valid living target" states (`invalid = anyOf(tags, ME,
PARTY, IN_COMBAT, KO, DEAD)`), which is the same distinction this filter
needs. `ANIMAL` guards against ever matching a merely-knocked-down player.

`GobTag.MENU` (used by `ITarget.hasMenu()` / `BotUtil.selectFlower`) is **not**
set for corpses — only for `DOMESTIC`/`HERB`/`TREE`/`BUSH` gobs — so wild-game
corpses would silently be skipped by any code gated on it. `ButcherBot` right-
clicks corpses directly and polls for a real `FlowerMenu` widget instead
(`processCorpse`), the same pattern `MiningMaterials.eatViaFlowerMenu` already
uses for food.

## Tool list and flower-menu option names (verified, not guessed)

Per explicit instruction from the feature owner, nothing here was guessed —
where this repo's own source didn't already confirm a resource ID or option
name, it was cross-checked against the Ring of Brodgar wiki and two
independent, actively-maintained open-source Haven & Hearth clients
(`ReleMai/Moonflower`, which has its own working sharp-tool-swap feature, and
`Lanfir7/nurgling2`, which maintains a large item-resource catalog).

All 11 cutting-tool resource IDs (`ButcherBot.CUTTING_TOOL_TYPES`):

| Item | Resource ID | Confirmed via |
|---|---|---|
| Stone Axe | `gfx/invobjs/stoneaxe` | this repo's i10n, nurgling2 |
| Woodsman's Axe | `gfx/invobjs/woodsmansaxe` | this repo's i10n, nurgling2 |
| Metal Axe | `gfx/invobjs/axe-m` | nurgling2 — **not** `metalaxe`, which is only the crafting-menu path (`paginae/craft/metalaxe`); the item's own resid follows the game's `-m`/`-t`/`-w` material-tier suffix convention (compare `Equip.SHOVEL`'s `/shovel-m`) |
| Tinker's Throwing Axe | `gfx/invobjs/tinkersthrowingaxe` | nurgling2 |
| Butcher's Cleaver | `gfx/invobjs/butcherscleaver` | this repo's i10n, Moonflower, nurgling2 |
| Ceramic Knife | `gfx/invobjs/ceramicknife` | Moonflower, nurgling2 |
| Flint Knife | `gfx/invobjs/flintknife` | Moonflower, nurgling2 |
| Obsidian Dagger | `gfx/invobjs/obsidiandagger` | nurgling2 (not in Ring of Brodgar's wiki category page, but a legitimate confirmed item) |
| Bronze Sword | `gfx/invobjs/bronzesword` | this repo's i10n + `Equip.SWORD`, Moonflower, nurgling2 |
| Fyrdsman's Sword | `gfx/invobjs/fyrdsword` | this repo's own `Equip.SWORD` (`src/auto/Equip.java:12`) + i10n — nurgling2's `fyrdssword` (double-s) looks like a typo, so this repo's own confirmed-working string wins |
| Hirdsman's Sword | `gfx/invobjs/hirdsword` | same reasoning (nurgling2's `hirdssword` is the outlier) |

Flower-menu option names (`ButcherBot.ACTION_PRIORITY`) come from Moonflower's
`SharpToolAutoManager.isProcessingOption` — an independently built, working
implementation of this same mechanic: `Skin`, `Flay`, `Clean`, `Gut`, `Pluck`,
`Scale`, `Butcher`, `Collect Bones`, `Gather Bones`, `Take Bones`. The Ring of
Brodgar wiki confirms the ordering rule this list encodes: skin before you
butcher, or the hide is ruined; butchering itself yields hide/meat/entrails/
bones together, and bones-collection is deliberately last so it's the final
interaction on the corpse.

**The confirmed mechanic** (direct, explicit confirmation, not inferred from
logs): each stage is selected from the flower menu **exactly once** per
animal. What happens after that single selection differs by stage:

- **Skin, Clean, Butcher**: the loading bar/hourglass fills automatically,
  over and over, once per individual unit of product the animal yields — one
  hide, then another; one batch of intestines, then another; one cut of meat,
  then the next — entirely without further input, until every unit of that
  stage's product has been collected. The *number of fills* varies by animal
  (how much it yields); the number of flower-menu *selections* never does.
- **Collect Bones**: no loading bar at all. Choosing it collects every bone
  at once, instantly.

`waitActionFinished` (Skin/Clean/Butcher) waits through however many fills a
given animal's stage actually needs before ever going back to the flower
menu: after each fill clears, it watches for `PROGRESS_QUIET_MS` with no new
fill starting before concluding the stage is genuinely done; if another fill
starts within that window (the animal has more of that product left), it
waits that one out too and checks again. It only pays the quiet-window cost
once, at the very end — it breaks out immediately the instant a real next
fill starts — so an animal needing many fills doesn't pay it many times, only
however long the true gaps between fills happen to be.

`PROGRESS_QUIET_MS` (75ms) is sized off the ArdClient fork's own
`Butcher.PBot` script rather than guessed independently: its
`PBotUtils.waitForHourglass` polls the "still active" check every 50ms and,
per direct confirmation, has never once left a stage incomplete. That only
makes sense if the real fill-to-fill gap is shorter than 50ms — a slower poll
would simply step over a brief gap and never observe the bar dip between two
consecutive fills, treating them as one continuous bar by accident rather
than by design. Our own poll runs every 10ms (fine enough to have actually
measured 6 distinct fills on one boar's Butcher stage, at 500ms it was
needlessly conservative by roughly 10x), so 75ms — just above their
proven-reliable 50ms — should give the same reliability without the
unnecessary tail latency. Under-guessing still degrades gracefully (the stage
just gets reselected once more, at the cost of one extra menu round-trip) if
the true gap ever turns out longer than this on some animal.

`waitBonesFinished` skips progress-bar waiting entirely for Collect Bones and
just polls for the corpse's own disposal for up to `BONES_TIMEOUT_MS` — there
is nothing else to wait on for that stage.

Getting here took a few wrong turns worth recording, since each one seemed
well-supported by the data available at the time:

1. First version used the shared `BotUtil.waitProgress` (returns as soon as
   `gui.prog` clears once) with no multi-fill awareness. On a bear, this
   picked "Clean" six times in a row before Collect Bones ever appeared —
   each individual fill misread as "the whole stage is done."
2. That looked like each stage should never be repeated, so a version
   tracking one-shot-per-stage (refusing to ever reselect) was tried — wrong
   in the other direction: it broke real animals whose Clean/Butcher stage
   still had fills left, since it refused to reselect even when the menu
   still (correctly) offered the same stage.
3. Backed off that to "just keep reselecting whatever's still offered" with a
   multi-fill-aware wait — conceptually correct, but then a fox and a boar
   both happened to need only one fill per stage, which was misread as
   evidence multi-fill doesn't exist at all, and the quiet-window logic got
   removed as unnecessary.
4. A second bear run under that simplified version showed Butcher reselected
   three times in a row — direct proof multi-fill is real and step 3's
   removal was the mistake, not step 3 itself. Confirmed explicitly by the
   person who knows the game's actual mechanics (see above), settling it.

`processCorpse` structurally re-picks the highest-priority option still on
offer each loop iteration regardless of stage — that's just loop plumbing,
not where the repeat-awareness lives. `MAX_STEPS_PER_CORPSE` (10) is a safety
net against a genuinely stuck loop, not a fill-count budget. The loop also
stops early if no known option matches what's currently offered, or the
corpse gob is disposed (fully consumed once bones are collected).

Option names are matched case/hyphen/whitespace-insensitively (`normalize()`)
rather than with exact-case `.equals()` — the same normalization Moonflower's
own `isProcessingOption` does — since the exact display casing of a given
option isn't guaranteed and a strict-case mismatch would silently skip a real
step (e.g. missing bones collection because the actual label capitalization
differed from the guessed list).

## Why restoration is a `try/finally`, not `Bot.cleanup()`

`Bot.call()` (`src/auto/Bot.java:50-88`) only reaches its `cleanup` action
list after the main action loop returns *normally*. `checkCancelled()`
throwing `InterruptedException` (Esc, `:macro cancel`, another bot starting)
— or any action throwing at all — skips straight past `cleanup` to a `finally`
block that only clears the "current bot" pointer, never runs user cleanup.
So `ButcherBot.run` is written as a single `Bot.execute` action containing a
real Java `try/finally` around the corpse-processing loop: `ToolSwap.restore`
in the `finally` is guaranteed to run on normal completion, an early return,
cancellation, or an exception, which `Bot.cleanup()` cannot guarantee.

The swap itself reuses `InvHelper.ContainedItem` (`InventoryItem`/`BeltItem`,
each already knowing how to `take()`/`putBack()` to their own origin) for the
tool. The one thing `InvHelper` doesn't already provide is parking a
*displaced* item that didn't come from a container — `ButcherBot` reserves a
free grid slot with `Inventory.findPlaceFor(size)` (belt first, then main
inventory — `findStashTarget`) *before* touching anything, specifically so a
"no room" failure can never leave an item stuck on the cursor.

A belt pouch's contents widget is a plain `Inventory` for an ordinary pouch,
but some special/premium belts use a fancier `ExtInventory` wrapper instead
— confirmed live with an "Exquisite Belt" (`contents=ExtInventory`). An
earlier version checked `contents instanceof Inventory`, which is false for
`ExtInventory`, so it always fell through to main inventory for that belt
regardless of actual free space. Fixed with `ExtInventory.inventory(Widget)`
(`beltInventory`/`findStashTarget`), an existing helper in this codebase
(already used the same way in `MiningMaterials.java`) that unwraps either
case to the real underlying `Inventory` grid.

`equipBestTool` also clears **both** hands of any cutting-tool-capable item
that isn't the one being equipped, not just the hand the tool goes into. This
is deliberate, not incidental: the game has no logic to prefer one hand's
tool over the other's when both qualify, so a worse tool left equipped in the
off hand (e.g. a quality-10 sword) could silently get used by the server
instead of the quality-15 axe just equipped in the other hand — there'd be no
error, just a worse result. A non-cutting item (a shield, say) is left alone
in whichever hand it's in, unless the tool's target hand happens to need it
moved out of the way, in which case it's parked and restored exactly like the
tool's own displaced item, in the same belt-first `findStashTarget`. Because
up to two items can need parking (one from each hand), the total free-slot
count (belt + inventory) is checked up front, before any hand is touched.

Room-checking is sized by each item's real grid footprint (`WItem.lsz`,
`src/haven/WItem.java:59,254`), not a flat 1 square per item — an earlier
version hardcoded `Coord.of(1,1)` for every `findPlaceFor` call, which is
wrong for anything bigger than 1x1 (a sword, say): it could return a
coordinate with only partial room, which the server would then have to
silently reject or relocate. `lsz` is derived from the item's sprite size
(`item.spr().sz()`), not from whatever container widget currently displays
it, so it's the same value whether read from the item sitting in a hand, a
belt, or a backpack — capturing it from the hand right before `take()` (the
original `WItem` is disposed once picked up, so it has to be read before
then) reliably predicts the room it'll need in either target container.

## Status messages

Logging is kept to the minimum a player needs, surfaced as real in-game
messages (`gui.msg`/`bot.cancel`) rather than console/dev-log output: a start
notice when the button is pressed, a "no processable corpses detected"
warning if nothing was found, a "cannot be fully processed" warning if a
single corpse hits `MAX_STEPS_PER_CORPSE` without finishing, an "interrupted
before completion" notice if the run is cancelled partway through, and a
final "operation complete — N corpse(s) processed" summary. Earlier
development builds logged every menu open, option chosen, and fill timing to
track down the issues described above — useful during development, but not
appropriate for normal play, so it was stripped back down once the mechanics
above were confirmed.

## Rudimentary pathfinding

`processCorpse` has **no explicit pre-walk step** — it just right-clicks the
corpse (`corpse.rclick(0)`) and waits for a `FlowerMenu` to appear. This is a
deliberate change from an earlier version that called
`MapHelper.walkTo(gui, corpse.rc, timeoutMs, MapHelper.GOB_ARRIVE_RADIUS)`
first: a right-click on a gob already triggers the game's own native
walk-there-then-interact behavior (`Gob.rclick` → `MapView.click(Gob,...)`),
identical to what happens on a manual right-click, so it inherently accounts
for the real interaction range — whatever that range actually is for this
player, this mount, this corpse. A fixed `GOB_ARRIVE_RADIUS` distance check
does not: confirmed live, it wrongly timed out while the player was mounted,
because a horse's larger collision radius keeps the rider further from the
corpse's own center than that fixed threshold allowed, even though the corpse
was fully interactable (the flower menu opens fine on manual right-click from
that same distance). Letting the native click handle approach entirely
sidesteps needing to know or guess the correct interaction radius for every
possible mount/hitbox combination.

The first right-click on each corpse uses a much longer menu-appearance
timeout (`FIRST_MENU_TIMEOUT_MS`, 12s) than every subsequent re-click on the
same corpse (`MENU_TIMEOUT_MS`, 3s), since only the first one has to cover an
actual walk — once the menu has opened once, the player is already adjacent
for the rest of that corpse's steps.

The failure mode this implies: if a corpse is blocked by an obstacle the
native click-to-move can't route around, no flower menu ever appears within
`FIRST_MENU_TIMEOUT_MS`, that one corpse is skipped, and the bot moves on to
the next corpse rather than getting stuck. This is the "rudimentary"
tradeoff — correct and safe, not exhaustive; it inherits whatever the game's
own native pathing can and can't route through, same as manual play.
