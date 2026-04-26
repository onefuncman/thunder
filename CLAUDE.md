# Thunder Client

Haven & Hearth modified client forked from Kami.

## Build

```
ant bin
```

## Run

```
java -Dsun.java2d.uiScale.enabled=false -Dsun.java2d.win.uiScaleX=1.0 -Dsun.java2d.win.uiScaleY=1.0 -Xss8m -Xms1024m -Xmx4096m --add-exports java.base/java.lang=ALL-UNNAMED --add-exports java.desktop/sun.awt=ALL-UNNAMED --add-exports java.desktop/sun.java2d=ALL-UNNAMED -DrunningThroughSteam=true -jar hafen.jar
```

## Steam Workshop Upload

```
cd bin && java -cp hafen.jar haven.SteamWorkshop upload .
```

Optionally pass a changelog message as the last argument. Reads `workshop-client.properties` from the target directory.

## Resource Files

- **Decompile**: `cd resources && java -jar LayerUtil.jar -d path/to/file.res` → output in `dout/`
- **Compile**: `cd resources && java -jar LayerUtil.jar -e path/to/dir.res` → output in `dres/`
- **Batch decode**: `java -jar LayerUtil.jar -rd [IN_DIR] [OUT_DIR]`
- **Batch encode**: `java -jar LayerUtil.jar -re [IN_DIR] [OUT_DIR]`
- Local custom resources live in `resources/src/local/`
- Pre-compiled resources in `resources/pre-compiled/`
- `@FromResource(name, version)` annotation overrides server-sent resource code — use sparingly, blocks server updates

### Paginae resource structure (`resources/src/local/paginae/`)

Each action has a `.res/` directory containing:
- `meta` — version info
- `action/action_0.data` — parent path, name, hotkey
- `image/image_0.data` + `image/image_0.png` — icon (scaled 4.0x from source)
- `pagina/pagina_0.data` — tooltip text

### Resource image data format

```
#IMAGE LAYER FOR RES ...
#int16 z
0
#int16 subz
0
#Byte nooff
0
#int16 id
-1
#Coord o
0
0
scale
4.0
```

## Docs

Per-feature design notes. Check the relevant file before reading source for an existing feature.

- [docs/cattle-roster-feature-batch.md](docs/cattle-roster-feature-batch.md) — original roster feature batch (sort, recolor, milking-assist scaffolding)
- [docs/changelog-popup.md](docs/changelog-popup.md) — login-screen Changelog window
- [docs/dev-iteration-toolkit.md](docs/dev-iteration-toolkit.md) — `DevFeature` / `DebugSnapshot` / `DebugReplay` / `FeatureCapture` infrastructure
- [docs/game-protocol.md](docs/game-protocol.md) — general protocol notes
- [docs/milking-assistant.md](docs/milking-assistant.md) — sfx-driven cattle deselect after milking; movement-probe for no-milk rejection
- [docs/plob-snap-design.md](docs/plob-snap-design.md) — placeable-object snap geometry
- [docs/tile-quality-tracker.md](docs/tile-quality-tracker.md) — per-tile quality observations for mining/digging/water
- [docs/upstream-sync.md](docs/upstream-sync.md) — log of kami merges (which commits were no-op duplicates vs intentionally skipped)

## Remotes & Upstream Strategy

Lineage: `loftar` → `ender` → `kami` → **thunder**

### Remotes

- `origin` — our fork (onefuncman/thunder)
- `loftar` — game author (dolda2000/hafen-client)
- `ender` — EnderWiggin/hafen-client (Kami's upstream)
- `upstream` — DerKamii/KamiClient (our direct parent, inactive since Jan 2026)
- `hurricane` — Nightdawg/Hurricane (unrelated fork, reference only)

### Sync strategy

**loftar** — Merge `loftar/master` directly to stay current with the game author. Loftar is the canonical upstream for engine and protocol changes. Expect conflicts in areas where Thunder has its own features (OptWnd, Gob overlays, custom resources). After merging, do `post-merge fixes` as a follow-up commit for any adjustments needed.

**ender** — Secondary upstream. Merge `ender/master` periodically for Ender's own additions on top of loftar. Since we now merge loftar directly, ender merges will mostly contribute ender-specific changes.

**upstream (kami)** — Our direct parent. Periodically merge `upstream/master` to mark sync state in history; usually `-s ours` since we've diverged past most of it, but check for anything worth integrating before deciding. Log each sync in [docs/upstream-sync.md](docs/upstream-sync.md).

**hurricane** — Cherry-pick only. Never merge. Browse their commits for feature ideas and cherry-pick individual changes as needed. They have a completely different architecture for many features.
