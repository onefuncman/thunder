# Upstream sync history

Append a section here every time you sync `upstream/master` (DerKamii/KamiClient)
into thunder/master. The merge commit alone doesn't tell you *which* upstream
commits were intentionally skipped vs. brought in -- this file does.

For loftar/ender merges, the merge commit + standard commit history is enough
since those are content merges. Kami syncs are special: many kami commits are
duplicates of work onefuncman authored in both thunder and kami (separately,
under different SHAs), so a `-s ours` mark is usually appropriate, and we
want a record of what went through to avoid re-evaluating the same
duplicate-vs-novel question every sync.

---

## kami sync 2026-08-11 (merge commit 4d304ef9d)

~75 commits on `upstream/master` since the previous sync (kami reactivated in
May 2026). This was a **real content merge**, not `-s ours`, performed on
`sync/loftar-kami-20260810` immediately after merging `loftar/master`
9bba2bb9d directly (merge commit 226a276c0).

### Adopted from kami

- Marker flicker fix: MiniMap `redisplay()` reordered before the biome check,
  and `CustomMarker` equals/hashCode removed on purpose (value equality made
  MapFile marker lists swap markers for each other). Explicit
  `CustomMarker.equals(a, b)` is used where value comparison is wanted.
- `MenuSearch` now extends `WindowX` (was `Window`).
- FightWndEx Savelist `ReadLine.Owner.ui()` fix.
- `@FromResource(..., override = true)` restored on the four alch ingredient
  tooltip classes (load-bearing: `me.ender.alchemy.Effect` does instanceof/new
  on these exact classes).
- alchbook `EffectList` Loading fix (poke `ik.input.type.name()` before
  leaving the loading list).
- AWTToolkit alt-key consume refactored into `altgrab()`.
- GLEnvironment: dispose null-gl commands early instead of submitting.
- `UILoop.currentFps` made volatile (read off-thread by StatusWdg);
  `Config.getHomeDir` null-safe `localdir()` handling (dir name stays
  `ender-client`, NOT kami-client, to preserve existing user data).
- Resource-cache purge button in KamiOptPanels troubleshooting section.
- Combat distance tool actions (`COMBAT_DISTANCE_TOOL`/`_AUTO`) alongside
  Thunder's `FILL_CHEESE_TRAY`.
- Auto-merged without conflict: dynres preview fixes, autodrink fix,
  UTF-8 javac pin, ender catch-ups (shift-click transfer, crane aggro,
  wound treatment data, marker category dropdown).

### Kept Thunder over kami

- Auth flow: AccountList token storage, `loginname` capture, Client `TITLE`,
  error-handler wiring (functionally identical to kami's), `crashed` flag
  (kami's 137-exit for launcher restart came along and works with it).
- `Makewindow.parsespecs` (handles legacy flat-spec wire format; kami has
  only the new-format parser).
- Marker draw path with `upscaleMarker`/`iconmult` (Thunder feature).
- OptWnd lazy Video/Audio panel buttons (both forks fixed the same bug
  independently; kept Thunder's `addLazyPanelButton` naming).
- HelpWnd Dismiss (same behavior as kami's `tryClose`, different layout),
  changelog.txt, build.xml `play` target, `.gitignore` (kami's two entries
  appended).

### Post-merge fixes (folded into the merge commit)

- `MenuGrid.pagseq` was silently dropped by the auto-merge (both sides have
  it); restored declaration + increment. Callers: MenuSearch, CraftDBWnd,
  ActWindow.
- Deduplicated `Client.crashed` and `LessTime`/`MoreTime` `image()`/`desc()`
  (kept Thunder's L10N variants).

### Skipped

- kami changelog entries (kami-release notes, not Thunder's).
- kami's own loftar merges (we merge loftar directly).
- `kami-client` homedir rename.

---

## kami sync 2026-05-03 (merge commit efafb5f29)

12 commits on `upstream/master` since the previous sync. Seven commits were
cherry-picked first to bring in real new content; the `-s ours` merge then
marks the rest as synced. (b9a025e78 was cherry-picked after the `-s ours`
merge once we re-evaluated it as live `@FromResource` override code, not a
mirror-only doc bump.)

### Cherry-picked (new content brought into thunder)

| kami SHA | thunder SHA | subject | notes |
|---|---|---|---|
| a32b2258b | 83c751a11 | Added rotation smoothing. Prevent NPE on left clicking cave icons. | Added `cam.rotation_smoothing_ms` (default 0 -- opt-in). MapWnd NPE guard already present from loftar follow-up; that hunk merged as no-op. |
| 4b44fbcf8 | 910426458 | Fixed Ortho cams | Companion to a32b2258b -- partially reverts the smoothing in `tick2()` because it broke ortho. Adopt these two together. |
| b83a97847 | 957903f98 | Added a way to disable warning rings while in combat with the animal. | New `ui.combat.hide_animal_warning_in_combat` CFG + `Gob.lastInCombat` timestamp. GobWarning conflict resolved by combining thunder's `!isSkull(gob)` guard with kami's combat-ring suppression. |
| a6e21bde9 | 64b1b824c | Don't attack tamed horses switch + stack/equipory bottom-left positioning. | MapView Click conflict resolved by keeping both thunder's MacroPicker/MacroRecorder hooks and kami's tamed-horse block (block runs first, then macro hooks). |
| 5ebcbd455 | 436bda816 | render numbers over the default player bars. | Self-contained IMeter change. |
| 9630f1f88 | f244bf4b4 | legacy bgm with different client based hooks. | New LegacyBGM/LegacyAudioPlayer + 8 ogg files in `resources/sfx/legacy/`. |
| d1b82ff43 | 04dfe3351 | Stripped logging. | Companion to 9630f1f88 -- removes debug prints from LegacyBGM/LegacyAudioPlayer. |
| b9a025e78 | d5e9e4978 | resources. | `@FromResource` override for `ui/realm` is live game code, not a mirror-only file. Pulls in `Utils.iv(args[1])` (replaces a `(Integer)args[1]` hard cast that crashes when the server sends the count as Long/Byte) and bumps the version pin from 31 to 34 (kami's pin) plus the `thingwall` subresource pin v1->v4. Conflict on the version line resolved by taking kami's v34. |

Per-cherry-pick `changelog.txt` conflicts were resolved by `checkout --ours`
(thunder's `changelog.txt` is a stale kami-inherited file we don't actively
maintain).

### Adopted as no-op (subject-equivalent to existing thunder commit)

1 of 12.

| kami SHA | thunder SHA | subject |
|---|---|---|
| 538eeca86 | aa62d8976 (+82e94004d, 18def7176) | "ender markers as extensions" rework | Thunder did the same Marker/PMarker/SMarker consolidation independently in the loftar-merge follow-up sequence. |

### Skipped (kami-only; not adopted into thunder)

4 of 12.

| kami SHA | subject | reason skipped |
|---|---|---|
| 5f06c9d22 | Merged loftar's changes as good as possibru. | kami's loftar merge; we merge loftar directly via our `loftar` remote |
| 2eef6e1c3 | Changelog. CFG default Changes. | kami changelog. The CFG default flip (smooth_strength 30->0, rotation_smoothing_ms 250->0) was applied to the cherry-picked rotation_smoothing line directly per user request; smooth_strength default stays at 30. |
| b6240b071 | cHaNgElOg... | kami changelog only |

### Not reviewed individually

None. All 12 inspected via diff/code review.

---

## kami sync 2026-04-26 (merge commit 2bc812e24)

Merged 37 commits from `upstream/master` (DerKamii/KamiClient) into thunder
via `git merge -s ours --no-ff upstream/master`. No file changes; mark only.

### Adopted as no-op (subject-equivalent to existing thunder commit)

22 of 37. These are work onefuncman authored in both repos (thunder and
kami) under different SHAs -- same intent / file changes, two separate
commits in two separate histories. The `-s ours` merge keeps thunder's
SHAs as canonical without taking kami's parallel commits as overrides.

| kami SHA | thunder SHA | subject |
|---|---|---|
| 605c5807c | 10df79be4 | Again changed some intrinsics of the smoothing function. because physics and game logic is.. special. |
| 271ef1465 | 7da742bdc | Changed smoothing algorithm and strength slider. |
| d67a13685 | 030796732 | Increased max jitter smoothing to 200. |
| 3babec65d | 0d53ef970 | Changelog. |
| 936ccaef6 | 4f2851b0c | Added in-game character portrait toggle |
| 20da26471 | 5aee926b3 | Added yulelights disable toggle |
| 3c9b19b93 | 6519709db | Added parallel scene tick toggle |
| 1b8f67849 | 2f2361d21 | Rate-limit GL disposes per frame |
| 0fcebee31 | 93a40ba0f | Capped DepInfo interner to fix stutter |
| 772ba2404 | d8d57d25a | Fixed StatusWdg texture churn |
| 98a916465 | cd6be2c7d | Fixed small UI texture churn |
| 25593f3c4 | 3efddf0fc | fixed tooltip positions. Added the ability to lock compact map wnd in place. |
| a6ea6ce15 | 6bc3002e3 | Fixed state updates for highlights and resizing of cupboards and walls. |
| 861ed81b5 | d0079d894 | Yoinked flat cave walls from Hurricane. Thanks ND. Less headache for me. |
| 02d381d37 | f40bb203d | Added some jitter smoothing to free camera. |
| 8ef9d0322 | 86af559b2 | Reducing cpu overhead even more. But that's pretty much it for tuning. I don't think there's more gained here. |
| 603752b0d | 00fab91ca | fixed near clipping on extended ortho zoom. added extended ortho view option. drops fps but it's nice for screenshots. |
| cfafde3c5 | 07d595008 | A couple more perf options. hide domestic animal will probably become its own toggle. |
| f434d6df1 | a486c7646 | A bunch of optimizations aimed to reduce cpu and increase fps on low performing systems. (FPS counter etc) |
| 88a711d64 | 17887e3dc | zoom out doesn't clip that early. |
| 0a60b7488 | 87c88c992 | mammoth skull shouldn't get an alert ring now. |
| 690c0f3bc | 1c52ecbb9 | Debugging Stuff for exports. Helped me debug problems with that one dude's map files. |

### Skipped (kami-only; not adopted into thunder)

15 of 37. Reviewed and intentionally not pulled because they're kami-side
housekeeping or already covered by thunder via different paths:

| kami SHA | subject | reason skipped |
|---|---|---|
| 22321c6cf | Use generational ZGC for IntelliJ run config | kami-only IntelliJ runner; thunder's launch script isn't IDE-bound |
| 394e39221 | Changelog. Different default for frmae skip. | kami changelog file; we don't ship that file |
| 6cdae6146 | Merged loftar changes. | kami's loftar merge; we merge loftar directly via our `loftar` remote |
| 56bb4d9e1 | I always forget the stupid changelog. damn it. | kami changelog |
| 07c5b1ff5 | Merged loftar's changes. Hope i didn't break something. | kami's loftar merge |
| 8479da42a | changelogs.. I always forget them. | kami changelog |
| 24d4d1a2c | changelog... | kami changelog |
| f1181743e | Added NPE check | kami-side fix; verify before adopting if a similar NPE shows up in thunder |
| 9e54fec18 | ignored misc.xml from now on. | kami `.gitignore` for IntelliJ; not needed for thunder workflow |
| feb5af38f | distilled down a bigger PR (mine crashes) "Credits go to onefuncman." | already in thunder under our own SHA |
| cfa386383 | added .idea configs to gitignore. | kami `.gitignore`; thunder ignores `.idea/` differently |
| da4c44508 | Removed all .bak files. | kami repo cleanup; we don't have those files |
| 81bfa8429 | Re-Added depricated stuff. Server side res are still using these. | revert in kami; thunder may have already adopted equivalent |
| 39346d943 | Big Ass Loftar & Eder merge. | kami-side merge; we merge loftar/ender directly |
| 27b8f8884 | updated run targets | kami IDE run targets |

### Not reviewed individually

None. All 37 inspected via subject-line classification, with the unique
ones cross-checked against likely thunder coverage. If any of the
"Skipped" entries turn out to need adoption, cherry-pick from kami and
note it in the next sync section here.

---

## How to add a new sync section

When you do the next kami merge:

```
git fetch upstream
git log --oneline upstream/master ^master      # see what's new
# Decide -s ours (mark only) vs. real merge (integrate)
git merge -s ours --no-ff upstream/master -m "Mark kami sync"
```

Then:

1. Run the classifier to get the duplicate / unique split:
   ```
   python -c "import subprocess; ..."   # see the script in tools/ if extracted, or
                                         # rerun the recipe used in the 2026-04-26 entry
   ```
2. Append a new `## kami sync <date> (merge commit <SHA>)` section here.
3. List adopted-as-no-op (subject matches a thunder commit) and skipped
   (kami-only with a reason).
4. Commit the doc update.

For loftar or ender merges, this file isn't needed -- a normal merge with
its own commit message is sufficient since those are real content merges,
not cross-fork cherry-pick reconciliation.
