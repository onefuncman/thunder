# LayerUtil Linux encode fix (2026-08-18)

## Incident

The v2026.08.16 GitHub release shipped a broken `client-res.jar`: the client
crashed at the login screen with

```
NullPointerException: Cannot invoke "haven.Resource$Image.tex()"
    because the return value of "haven.Resource.loadrimg(String)" is null
    at haven.LoginScreen.<clinit>(LoginScreen.java:40)
```

Inspection of the release jar showed `res/gfx/loginscr_thunder.res` — and every
other locally-encoded image resource — was an 18-byte stub (the `Haven Resource 1`
header plus version, zero layers). 10 resources were missing from the jar
entirely. Local Windows builds were fine; only the CI build on `ubuntu-latest`
was affected.

## Root cause

`resources/LayerUtil.jar` (inherited from Kami's toolchain, no source in the
repo) pairs `image_N.data` with `image_N.png` by their *position* in
`File.listFiles()`:

```java
for (j = 0; j < df.length - 1; ++j) {
    if (!df[j].getName().endsWith(".data") && !df[j+1].getName().endsWith(".png")) continue;
    layers.add(cons.newInstance(this, df[j++], df[j]));
}
```

`File.listFiles()` returns names in alphabetical order on Windows NTFS but in
arbitrary (hash) order on Linux ext4. On the CI runner the `.png` often came
back before the `.data`, so pairs were either silently skipped (→ header-only
stub resources) or mispaired, making LayerUtil parse a PNG as the text `.data`
file (`NumberFormatException: For input string: "�PNG"` → resource missing from
output). LayerUtil prints the exception but exits 0, and ant's `<exec>` ignores
the exit code anyway, so the build "succeeded".

## Fix

Two layers of defense:

1. **Patched `resources/LayerUtil.jar`**: `haven.Resource.loadfromdecode` now
   sorts both `listFiles()` results by filename before use, making encode order
   deterministic on every filesystem. The jar has no source in the repo, so the
   class was decompiled with CFR 0.152 (it was built with debug info), the two
   `Arrays.sort(..., comparing File::getName)` lines added, recompiled with
   `javac --release 8 -cp <extracted-jar>`, and the classes updated in place
   with `jar uf`. The patched source is kept at
   [resources/layerutil-patch/Resource.java](../resources/layerutil-patch/Resource.java)
   for future maintenance.

   Verified by re-encoding both `resources/src/local` (233 res) and
   `resources/src/remote` with the patched jar on Windows: output is
   byte-identical to the pre-patch output, i.e. the sort is a no-op where the
   listing was already ordered.

2. **Release-workflow guard**: `release.yml` now has a "Verify encoded
   resources" step after `ant bin` that fails the build if the number of `.res`
   entries in `bin/client-res.jar` is lower than the number of `*.res` source
   directories (for both `res/` and `res-preload/` sets), or if any encoded
   resource is an 18-byte header-only stub.

## Note on the broken release

Release assets built before this fix (v2026.08.16) must be rebuilt: re-run the
Release workflow via workflow_dispatch with the existing tag once the fix is on
master; `softprops/action-gh-release` replaces same-named assets on the
existing release.
