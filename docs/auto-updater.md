# Auto-updater (standalone releases)

`updater.jar` is a self-updater run by the release launchers (`Thunder.bat` /
`thunder.sh`) before the client starts. It checks the latest GitHub release of
`onefuncman/thunder` and updates the install in place. Modeled on Nightdawg's
[Hurricane-Updater](https://github.com/Nightdawg/Hurricane-Updater), adapted
from its raw-manifest scheme to GitHub release assets.

## Flow

1. Launcher swaps in any `updater.jar.new` staged by a previous run, then runs
   `updater.jar`.
2. Updater reads the local `VERSION` file and calls
   `api.github.com/.../releases/latest`. Tag matches → exits immediately
   (one HTTP request).
3. On mismatch it downloads `Thunder-cross-platform.zip` (both platforms use
   this asset — it is the Windows zip minus the bundled JRE), verifies the
   GitHub-published SHA-256 `digest` and size, extracts to a temp stage dir,
   and skips files whose bytes already match (`Files.mismatch`).
4. Changed files are applied one by one: existing file moved to
   `.updater-backup/<timestamp>-<oldver>/`, staged file moved in. Any failure
   rolls everything back. The 2 newest backup dirs are kept.
5. `VERSION` is written with the new tag.
6. Launcher then starts the client.

## Locked-file rules

- A running JVM locks its own jar and `cmd.exe` re-reads a running `.bat` by
  byte offset, so `updater.jar`, `Thunder.bat`, and `thunder.sh` are never
  replaced in place. Changed versions are written as `<name>.new`; the
  launchers swap them in at the top of the next run (and restart themselves
  if the launcher itself changed — the bat's post-update section lives in
  parenthesized blocks, which cmd parses fully before executing, making the
  in-flight rewrite safe).
- `jre/` is never touched (it is running the updater on Windows). A bundled
  JRE upgrade requires a manual re-download of the Windows zip.
- `README.txt` is skipped (platform-specific; the cross-platform zip carries
  the wrong one for Windows installs).

## Failure policy

Fail open, always: network errors, HTTP errors, hash mismatches, and apply
failures all print a `[updater]` message and exit 0 so the launcher still
starts the currently-installed version. `THUNDER_NO_UPDATE=1` skips the check
entirely.

## Build & release integration

- Source: `updater/UpdaterMain.java` (own tree, not under `src/` — it needs
  `java.net.http`, so it compiles at `release="17"` via the `updater-jar` ant
  target, while the client tree compiles at release 8). No dependencies; JSON
  is parsed by a built-in minimal parser.
- `ant bin` builds `bin/updater.jar`, so the release workflow's `cp -r bin/.`
  ships it in both zips automatically.
- `release.yml` writes the release tag into `stage/*/Thunder/VERSION`.
- Installs older than the first updater-carrying release have no `VERSION`
  (treated as unknown → one full update) and no `updater.jar` (users must
  re-download once; the launcher tolerates its absence).
