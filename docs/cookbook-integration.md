# Cookbook integration: what we learned about civ.hearthworld.com

In-game reimplementation of the "Haven & Hearth Automap" cookbook
(https://civ.hearthworld.com/cookbook/), reached via Xtended -> Cookbook.
Code: `src/thunder/cookbook/`. Wiring: `Action.OPEN_COOKBOOK` ->
`GameUI.toggleCookbook()` -> `CookbookWnd.toggle`; paginae button at
`resources/src/local/paginae/add/cookbook.res` (parent `paginae/act/add` =
"Xtended", per `MenuGrid.initCustomPaginae`).

All of this was reverse-engineered from the site's public HTML and its
Angular bundle (`/cookbook/index_files/main-es2015.*.js`, fetched while
logged in) — there is no public API documentation for this site. Re-check
this doc against the live bundle if the site changes and something breaks.

## Meal plan / bill of materials

`CookbookPlanWnd` (opened via the "Meal Plan (N)" button in `CookbookWnd`,
or the Plan column on each row of the browse list) is a from-scratch
feature, not something civ.hearthworld.com has — it was scoped from a
Discord thread with the client's users (Flame/raine), who wanted something
like their own hand-built meal-planning spreadsheet: pick dishes, say how
many of each, get a shopping list of raw ingredients.

We inspected that actual spreadsheet (`.xlsx`, unzipped and parsed directly
rather than trusting a rendered web view — Google Sheets doesn't expose
formulas or other tabs through a simple fetch). Its ingredient matrix
(`MealIngredients`) is a mix of two unit systems: ~68% of nonzero cells are
≤1.0, matching our existing ingredient-percentage data 1:1 (verified
directly: "Pan-seared Fish" has `Salt: 100%` in our dataset everywhere it's
used, and the spreadsheet's Salt row shows exactly `1.0` there), but ~32%
are round numbers like 3, 10, 50, 150 that don't fit that scale — likely a
different, hand-tracked unit for some ingredients (e.g. literal bulk counts
for curing salt) that we have no data source for.

Per explicit direction, `CookbookPlanWnd` treats **everything** as the
percentage scale we already have — `CookbookItem.ingredients[].percentage`
— rather than modeling that second unit system. So its output is a
relative/proportional measure (`percentage/100 * quantity`, summed across
every planned dish), not literal real-game item counts. This is called out
in the window itself (the note under "Bill of Materials"). If real batch
sizes ever become available (e.g. from the game's own crafting resource
data — untested, see `CraftDBWnd` for how Thunder already browses real
craft recipes), the aggregation in `CookbookPlanWnd.recomputeMaterials()`
is the one place that would need to change.

The live working plan (what's currently in `CookbookWnd.plan`) is **not**
auto-persisted -- closing the window drops it. Instead, `CookbookPlanWnd`
has explicit "Save Meal" (name it, `CookbookWnd.saveMeal`) and "Load Meal"
(`CookbookLoadMealWnd` picker, left-click to load / right-click to delete,
`CookbookWnd.loadMeal`/`deleteMeal`) buttons -- multiple named save slots in
one file (`cookbook-meals.json`, `{mealName: {itemKey: qty}}`), not a
single auto-saved snapshot. Loading a meal *replaces* the current working
plan, it doesn't merge.

Both the live plan and every saved meal are keyed by `CookbookItem.key()`
(resourceName + sorted ingredient list), not Java object identity or list
index -- the food dataset is re-fetched and re-parsed fresh every session,
so identity/index aren't stable, but the same dish+ingredient-variant
combination reliably produces the same key run to run. A saved meal
referencing a dish that's no longer in the current dataset is silently
dropped on load rather than erroring.

## Base-recipe ingredients (RecipeCapture)

The FEP-percentage ingredients from civ.hearthworld.com (e.g. "Osier: 100%")
are only the *flavor* input to a recipe -- they don't include the base
filler (e.g. Smoked Badger Botillo also needs 1 Intestines + 2 Raw Badger,
which never shows up in that percentage data at all). We looked for a
static/offline source for real recipe ingredient+quantity data and there
isn't one anywhere in the game: confirmed by reading `Makewindow.java` and
`CraftDBWnd.java` directly (see agent research in this session) -- a
recipe's real ingredient list (the `"inpop"`/`"opop"` widget messages) is
sent by the server only for a live crafting window you have open right now,
never cached to any resource file. `CraftDBWnd` itself opens a real live
`Makewindow` per recipe rather than reading from a local cache, which is
the tell that no such cache exists to read from.

So `RecipeCapture` (`src/thunder/cookbook/RecipeCapture.java`) does the only
thing that's actually possible: passively record the real ingredients
(resource + exact quantity, keyed by the crafted item's resource name) every
time a `Makewindow` shows both inputs and outputs, hooked from two call
sites in `Makewindow.uimsg` (`"inpop"`/`"opop"`). Saved to
`cookbook-recipes.json`. This means coverage starts at zero and only grows
as dishes actually get crafted/opened in-game by someone running this
client -- there's no way to backfill it in bulk.

`CookbookPlanWnd.recomputeMaterials()` prefers `RecipeCapture.recipeFor(dish)`
over the percentage estimate whenever it's available, which also gives real
icons for those ingredient rows (the percentage-estimate fallback has no
resource reference, so those rows render without an icon). A quick web
search (per the user, e.g. a 2020-era H&H forum thread on extracting icons)
turned up no name-to-resource-id database either -- only ad-hoc techniques
(grep the client's disk cache, or fetch `havenandhearth.com/mt/r/<respath>`
once you already know the path) that don't help without a path in hand
first, and we tested a few guessed `gfx/invobjs/<slug>` paths against that
endpoint directly: hit rate was low and inconsistent enough (2/9 on a quick
sample) that guessing risks showing a *wrong* icon, not just a missing one
-- worse than the current no-icon fallback. RecipeCapture's resource
references are exact, since they come straight from the live Makewindow.

### Missing Recipes checklist (`MissingRecipesWnd`)

Since RecipeCapture only learns a dish's real ingredients once its crafting
window has actually been opened, coverage grows organically and there was no
way to see the gap except noticing "Estimates" instead of "Real recipe
amounts" in the Meal Plan one dish at a time. Considered using
`paginae/craft/*.res` (the game's crafting-menu buttons) as an offline
source to close that gap directly -- decompiled a real one
(`paginae/craft/wurst_badgerbotillo.res`) to check, and its action layer is
only `pr` (parent menu, e.g. `paginae/craft/sausages`), `name`, and the
`ad`/`hk` click target -- no ingredients, confirming (again) there's no
static source for that data anywhere. So `MissingRecipesWnd` (button next to
"Meal Plan" in the Cookbook window) just makes the existing gap visible
instead of closing it: every dish from `/food-info.json` NOT in
`RecipeCapture.capturedNames()`, sorted alphabetically with its icon, so
there's a concrete list of what to go craft next instead of discovering
gaps by accident.

### IngredientIconResolver (Ring of Brodgar's resource index)

The user later pointed at https://brodgar.io/res/ -- a community-run,
publicly browsable mirror of the game's *entire* resource tree, with a
`?search=<query>` JSON endpoint (`[{name, relDir, versions:[...]}]`,
substring match against `.res` filenames). This is a real filename index,
not a guess, so it's a meaningfully better source than the path-guessing we
ruled out above. `IngredientIconResolver`
(`src/thunder/cookbook/IngredientIconResolver.java`) uses it as a fallback
icon source for ingredient rows RecipeCapture hasn't covered: query by
ingredient name, and only trust a **exact** filename-slug match that lands
in `gfx/invobjs` or `gfx/terobjs/items` (the real inventory-item-icon
directories).

That directory restriction is load-bearing, not defensive-programming
paranoia -- verified directly by querying brodgar.io for real ingredient
names before locking this in: a plain "Chicken"/"Osier"/"Blackpine"/"Yew"
query's top exact-filename hit was the *live animal sprite*
(`gfx/kritter/chicken/chicken.res`) or a *minimap tree marker*
(`gfx/terobjs/mm/trees/osier.res`) — genuinely wrong icons for a food
ingredient, not just imprecise ones. Restricting to invobjs/items directories
drops those false positives (and drops coverage for wood/herb flavor
ingredients specifically, which mostly don't have their own invobjs icon
the way meats/salt do) rather than risk showing something misleading.
Net effect: this mainly helps fill in icons for base-recipe filler
ingredients (meat, organs, salt, common goods) that RecipeCapture hasn't
captured yet, since those are the ones that tend to have real invobjs
entries.

The user then pointed out (correctly, verified directly): a smoking wood
like "Osier" isn't held/used as an item literally named "osier" — it's a
"block of osier" (`wblock-<name>`), and a raw meat like "Chicken" is
`meat-<name>`, not `chicken`. Two more bugs came out of verifying that: (1)
the search endpoint does a plain substring match against filenames, which
never contain spaces, so querying a two-word ingredient name as typed
(`"Stinging Nettle"`) returned zero results -- fixed by querying on the
slug (`"stingingnettle"`) instead; (2) the item-directory check required an
exact `relDir == "gfx/invobjs"`, missing real subfolders like
`gfx/invobjs/herbs/` -- broadened to a prefix check. `pickBest` now tries
`ROLE_PREFIXES = {"", "meat-", "wblock-"}` against the slug, in that order,
before giving up. Re-verified against a 12-name sample after the fix: 10/12
now resolve (previously ~1/12), the two still-unresolved being fruit/berry
names ("Juniper Berries", "Dog Rose Hips") whose real filenames don't
appear to follow either convention -- fine to leave as no-icon rather than
add more unverified guesses.

Brodgar.io is only ever used as a **name -> path lookup** — the actual icon
image is always loaded through our own game session
(`Resource.remote()`), same as every other icon in this client;
brodgar.io's own served image bytes are never used. Resolved paths (and
confirmed non-matches, so we don't requery every draw) are cached to
`cookbook-ingredient-paths.json`. It's a community site, not an official
H&H service — if it goes away or reshapes its search response, this
degrades gracefully to "no icon for uncaptured ingredients," not a crash
(all failures are caught in `IngredientIconResolver.query`).

### Closing the gap: swept every real ingredient name, user-confirmed the rest

Rather than keep testing a handful of examples, we pulled every distinct
ingredient name out of the live civ.hearthworld.com dataset (127 of them)
and ran the resolver against all of them for a real coverage number, not a
guess from a sample. `ROLE_PREFIXES` alone got 100/127. The remaining ones
mostly needed real domain knowledge no string transform could derive —
irregular plurals (`Chantrelles` -> `chantrelle`), word-order flips
(`Green Apple` -> `applegreen`), a leading rather than trailing descriptor
(`Wild Beef` -> `meat-beef`), or a name with no string relationship to the
filename at all (`Wild Onion` -> `preonion`, `Wildkale Leaf` ->
`leaf-brassica`) — so the user supplied the real item names directly, each
one checked against brodgar.io before adding it. Those went into
`MANUAL_OVERRIDES`, checked first, ahead of any automatic search. Two more
patterns turned out generalizable and got added properly: a `fish-`
prefix (covers any `Fish-<name>` item automatically, not just the two we
hit) and a `leaf-`/`leaves-` descriptor alongside the existing `egg-`/
`venison-` one. Final count: **126 of 127** ingredient names resolve to a
real icon; the lone holdout is plain "Venison" with no qualifier, which
turns out not to exist as its own item in the game at all (only
`meat-reindeer` and `meat-roedeer`, both already covered as "Reindeer
Venison"/"Roe Venison") — correctly left with no icon rather than guessed.
Two entries are flagged in the code as the user's best guess rather than a
fully confirmed exact item ("Portobello Mushroom" -> the largest of three
champignon sizes, and "Radish" -> the only `invobjs` hit, an oddly-named
`-tex` resource) — still better than nothing, but worth a second look if
either ever looks visibly wrong in the Meal Plan.

### Visual spot-check: `:restest`

`IngredientIconTestWnd` (`src/thunder/cookbook/IngredientIconTestWnd.java`)
is a debug window, opened by typing `:restest` in chat (wired via
`GameUI.cmdmap`, same mechanism as `:macro`) -- a scrollable grid of every
distinct ingredient name in the dataset with whatever icon it resolved to
next to it (an unresolved one shows a red box instead), so a wrong icon is
visible at a glance instead of having to check the Meal Plan one ingredient
at a time. Pulls its name list from `CookbookService.lastGood()` (fetching
once if empty) and resolves icons through the exact same
`IngredientIconResolver` path as the Meal Plan, so what you see here is
what you'd see there.

A "Show All Invobjs" button switches it to a second mode: every item
resource under `gfx/invobjs` on brodgar.io (~2000, `food`/`herbs`/`small`/
etc. subfolders included), for browsing the game's whole item-icon set by
eye and hovering to read a cell's real resource path -- meant for finding
the right name to add to `IngredientIconResolver.MANUAL_OVERRIDES` by
looking rather than guessing search terms. `BrodgarInvobjsIndex`
(`src/thunder/cookbook/BrodgarInvobjsIndex.java`) crawls brodgar.io's
plain server-rendered directory HTML once (regex over `href="/res/..."`,
recursing into subfolders) and caches the ~2000-entry list to
`cookbook-invobjs-index.json` so it isn't re-crawled every time the window
opens.

### Missing meat/fish backdrop (ItemIconUtil)

The user noticed raw-meat and common-fish ingredient icons were showing as
just a tiny species badge with no meat/fillet shape behind it. Confirmed by
downloading the actual `.res` files from brodgar.io and decompiling them
locally (`LayerUtil.jar -d`) rather than guessing: e.g. `meat-badger.res`
is genuinely only an ~800-byte badger-face badge on a transparent
background -- there's no missing code on our end, that really is the
resource's *entire* image layer. A few meat resources (`meat-chicken`,
`meat-testis`) happen to bake a full chunk shape into their one layer, but
most don't, including -- surprising at first -- the common fish: "Asp",
"Perch", "Salmon" etc. resolve to `meat-<name>` (not the `fish-<name>`
whole-creature illustrations used for rare cave fish), and those are
badge-only too, same as the land animals.

Per the earlier Makewindow/GSprite research, the real in-game compositing
for these happens via server-published custom sprite code we can't run
outside a live session -- so there's no way to ask the engine to do this
for us. `gfx/invobjs/meat-raw` (tooltip literally `"Raw %"`, a format
template) is the shared backdrop image; confirmed by rendering it locally
(`java.awt.Graphics2D`, centered composite) behind both a badge-only item
and an already-complete one before trusting it: badge-only items get the
meat-chunk shape back, already-complete items are unaffected since their
own shape already fully covers the backdrop. `ItemIconUtil`
(`src/thunder/cookbook/ItemIconUtil.java`) applies this to every
`meat-<name>` resource load (all three windows now go through it, replacing
their previous direct `Resource.remote()...layer(Resource.imgc).img` calls)
-- deliberately scoped to just `meat-*`, not `wblock-*` or `fish-*` (the
latter's whole-creature illustrations don't need or want a meat-chunk
backdrop behind them).

**Regression, fixed same session**: the first version re-threw `Loading`
from the backdrop fetch too (`catch(Loading l) { throw l; }`), so the
badge-and-backdrop composite would only get cached once *both* resources
were ready. Every caller caches its icon() result permanently once
non-Loading, so if the backdrop resource never actually settles on the
live server the way it does on brodgar.io's mirror (unconfirmed, but fits
the symptom exactly: meat icons went permanently blank, not just briefly),
nothing ever gets cached and *no* meat icon shows at all -- worse than
before this feature existed. Fixed by catching everything (Loading
included) around the backdrop attempt and always falling back to the bare
badge immediately rather than blocking on it; the backdrop is one shared
resource, so once the first meat icon's load kicks off its fetch, later
ones will likely already find it resolved. Lesson: a best-effort visual
enhancement should never be allowed to block the thing it's enhancing from
appearing at all -- always have a path that shows *something* fast.

## Login

Plain ASP.NET Core cookie-auth form, not a JS-driven login:

1. `GET /Auth` — sets a `.AspNetCore.Antiforgery.*` cookie and renders a
   hidden `<input name="__RequestVerificationToken" value="...">`.
2. `POST /Auth/Login` (`application/x-www-form-urlencoded`), with that
   antiforgery cookie attached, body: `username`, `password`,
   `persistent` (`true`/`false` both work fine as a value even though the
   real `<input>` has no `value` attribute), `__RequestVerificationToken`.
3. On success: `302` with `Set-Cookie: .AspNetCore.Cookies=...` (14-day
   expiry observed when `persistent=true`). On bad credentials: `200`
   (re-renders the login page).

Implemented in `CookbookAuth.doLogin`. We don't follow the redirect — we
only need the `Set-Cookie`. The cookie is persisted locally
(`Config.saveFile`, see `cookbook-session.json` in the client's config dir)
so login survives client restarts, and replayed as a `Cookie` header on
later requests. `CookbookAuth.logout()` just drops it locally; there's no
server-side logout call.

**Not implemented:** the "Login with Haven" OAuth button
(`/Auth/LoginWithHaven`). That flow needs a browser redirect round-trip we
have no way to capture a cookie from inside this client, so our login
window only offers the username/password path.

## Data source

`GET /food-info.json` — a **public, unauthenticated** static JSON array (no
login required, confirmed by comparing anonymous vs. authenticated fetches
byte-for-byte). ~930 entries, one per food+ingredient-combo, shape:

```json
{
  "itemName": "Smoked Running Rabbit Sausage",
  "resourceName": "gfx/invobjs/wurst-s-runningrabbit",
  "genus": "",
  "version": 0,
  "energy": 700,
  "hunger": 1.76,
  "ingredients": [{"name": "Osier", "percentage": 100}],
  "feps": [{"name": "Agility +1", "value": 10.2}, {"name": "Intelligence +1", "value": 3}]
}
```

`resourceName` is a real in-game resource path, so icons are pulled from
the game's own resource system (`Resource.remote().load(name).get()`) —
never from the website. `energy` and `hunger` are already display-ready
percentages (append `%`).

The site itself has this whole array loaded client-side once and does all
filtering/sorting in the browser (see `processData()`/`filterPredicate` in
the Angular bundle) — there is no per-keystroke search API. Our client
does the same: `CookbookService` fetches once per Refresh click and
`CookbookWnd` filters the in-memory list live as you type.

Even though the underlying data turned out to be public, the login flow
was still built as asked (matching the site's own UX and leaving room for
any future personalized/gated data).

## Derived fields (from the Angular bundle's `processData`)

Given a food's `feps` list, mapped through `fepmap` (`"Strength +1" ->
"str"`, `"Strength +2" -> "str2"`, ... same for agi/int/con/per/cha/dex/wil/psy):

- `total_fep` = sum of all `feps[].value` (this is the site's **"Total
  FEP"** column).
- `fepPer[attr]` = `value / total_fep * 100` — this is the per-attribute
  share used to size the colored **"Fep bar"** segments.
- `hungerfep` = `total_fep / hunger * 10` — this is the site's
  **"FEP/Hunger"** column. (Matches the `FEPPerHunger()` naming already
  used elsewhere in this codebase for the same concept, e.g. `ItemData.java`.)

Reimplemented in `CookbookItem` (constructor computes `totalFep`,
`fepPercent`, `fepPerHunger` from the raw `feps` map).

## Filter syntax (from the site's own "How-to" popup + `filterPredicate`)

Conditions are separated by `;` and are AND'ed together:

- `name:text` — name contains `text` (case-insensitive). `name:"text"` —
  exact match.
- `from:text` / `from:"text"` — same, over ingredient names.
- `-` prefix on either of the above negates it (`-from:troll`).
- `attr[2]><=N` or `attr[2]><=N%` — FEP filter, where `attr` is one of
  `str/agi/int/con/per/cha/dex/wil/psy` (optionally suffixed `2` for the
  "+2" tier). No `%` compares the raw FEP value (`t.feps`); with `%`
  compares the per-attribute share (`t.fepPer`). `N == 0` is a no-op.
- Bare text with no `name:`/`from:` prefix matches everything (the site
  doesn't do implicit free-text search — you have to say `name:` or
  `from:`).

Reimplemented as a small state-machine parser in `CookbookQuery`, matching
the original char-by-char logic (including the `0`-is-a-no-op and
negation-only-applies-to-name/from quirks) rather than a regex, since the
original isn't regex-based either and edge cases (quoting, `-` placement)
are easiest to get right by mirroring it directly.

**Not implemented:** the site's personal exclude-list feature (checkboxes
next to each food/ingredient, persisted to the *browser's* localStorage,
independent of the filter text). Could be added later as a client-local
favourites-style list (see `CraftDBWnd`'s `favourites.json` pattern) if
wanted.

## Threading gotcha: keep AWT text/texture rendering off background threads

`CookbookService.refreshAsync` and `CookbookAuth.loginAsync` run their
callback on a `Defer` background worker thread (see `haven/Defer.java`).
Early versions of `CookbookWnd`/`CookbookLoginWnd` called `Label.settext`,
`RichText.render(...).tex()`, and `new Button(...)` directly from inside
those callbacks (even wrapped in `synchronized(ui)`). All three do AWT/Java2D
font rendering synchronously, which is not safe to call concurrently with
the main/render thread's own text rendering — worst case a silent native
crash (Java2D's Windows text pipeline is exactly the kind of thing that
produces an `EXCEPTION_ACCESS_VIOLATION` with no catchable Java exception),
best case just a data race.

Fix: background callbacks only ever write plain (`volatile`) fields; the
actual widget/text work happens in `tick()`, which runs on the main thread
alongside `draw()`. See the `pending*` fields and `tick()` overrides in
`CookbookWnd` and `CookbookLoginWnd` for the pattern. `synchronized(ui)` was
already the established convention for background->main handoffs elsewhere
in this codebase (e.g. `KamiOptPanels.purgecache`) — but note that pattern's
own examples only ever queue a message (`ui.msg(...)`) or set plain fields,
never construct widgets or render text directly; that distinction is easy
to miss when copying the pattern.

## Paginae resource gotcha

Local resource text files (`meta`, `action_0.data`, `image_0.data`) must
use **CRLF line endings with no trailing blank line** — `haven.Utils.rstr`
chokes on LF-only files with a null-split NPE. Easiest way to check: `xxd`
a working file (e.g. `craftdb.res/meta`) and diff the byte pattern rather
than trusting an editor's default line endings. Verify with
`java -jar LayerUtil.jar -e <path-to-.res-dir>` before running the full
`ant bin`.
