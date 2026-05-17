# GL crash analysis: NULL EBO in `glDrawElements`

Working from `hs_err_pid14712.log` (Hurricane fork, NVIDIA, 2026-04-11). Same
crash signature as the recurring AMD/NVIDIA crashes on this branch; using
the Hurricane log because it's a clean reproduction against vanilla loftar
GL code, with none of Thunder's prep/STREAM refactor in the picture.

## Crash signature

```
EXCEPTION_ACCESS_VIOLATION (0xc0000005) at pc=0x00007fff4cc88e2d
JRE: Temurin-17.0.16+8, JOGL via nv_dispig.inf_amd64.../nvoglv64.dll
Problematic frame: C  [nvoglv64.dll+0x1568e2d]
siginfo: reading address 0x0000000000000000

Java frames:
  jogamp.opengl.gl4.GL4bcImpl.dispatch_glDrawElements1(IIIJJ)V
  jogamp.opengl.gl4.GL4bcImpl.glDrawElements(IIIJ)V
  haven.render.jogl.JOGLWrap.glDrawElements(IIIJ)V
  haven.render.gl.BGL$51.run(haven/render/gl/GL)V
  haven.render.gl.BGL$5.run(haven/render/gl/GL)V    [BufferBGL.run]
  haven.render.gl.GLEnvironment.process(haven/render/gl/GL)V
  jogamp.opengl.GLDrawableHelper.invokeGLImpl(...)
  ...AWT-EventQueue-0
```

`BGL$51` is the `Command` lambda for `glDrawElements` (it's the 51st `add(...)`
call site in `BufferBGL`). `BGL$5` is `BufferBGL.run`. The crash is inside
`process()` draining a `BufferBGL`, executing one of the recorded
`glDrawElements` commands.

## What the registers say

```
RDX = 0x0000000000000000      <- NULL, used as memory operand
RIP = 0x00007fff4cc88e2d      nvoglv64.dll
Crashing instruction (at RIP): c5 fe 6f 02
                                = vmovdqu ymm0, [rdx]    ; AVX 32-byte load
Following bytes:               c4 a1 7e 6f 6c 02 e0
                                = vmovdqu ymm5, [rdx + r8*8 - 0x20]
```

So we're inside an AVX-vectorized memcpy/load loop in the driver. RDX is the
*source pointer*. Reading 32 bytes from address 0 - classic NULL deref
inside what's clearly a bulk index-data fetch.

Call-site backtrace inside the driver (preceding bytes at PC-0x20 and earlier):
- size dispatcher comparing R8 against `0x20` / `0x2000` / `0x180000` and
  branching to size-class-specific copy paths
- the path we crashed in is "small chunk" (R8 < 0x2000)

This is the well-known shape of NVIDIA's "client-side index pointer" code
path: `glDrawElements(mode, count, type, indices)` is called with an
`indices` value that the driver interprets as a *client memory address*
because no `GL_ELEMENT_ARRAY_BUFFER` is bound to the active VAO. The
expected value is an offset into a bound EBO; with `indices=0` and no
EBO, the driver bulk-loads from address 0 and segfaults.

## Why this happens (root cause)

**`GLDrawList.draw()` runs compiled per-slot command lists via
`bglCallList(cur.compiled)` but does not update the `GLRender`'s state
tracker for the VAO it leaves bound.**

Sequence:

1. `GLDrawList.draw(g)` enters with tracker `g.state` reflecting whatever
   the previous draw left. Applies `first.bk.state()` and the first slot's
   `VaoBindState{vao=V0, ebo=E0}`. Walks slots, replaying each
   `cur.compiled` BGL. Each replayed list internally rebinds VAOs to suit
   that slot's program/attribs. Last slot leaves actual GL with
   `VAO=V_last, EBO=whatever_V_last_internally_holds` bound.
2. After the loop:
   ```
   g.state.assume(last.bk.state());
   ```
   `assume` updates non-VAO slots in the tracker but not the VAO slot.
   Tracker still claims `VaoBindState{vao=V0, ebo=E0}`. Real GL has V_last.
3. Next draw on the same `GLRender` constructs a target state with some
   `VaoBindState{vao=Vt, ebo=Et}`. `Applier.apply` walks slots, hits
   `VaoBindState.applyto`:
   ```java
   if(that.vao != this.vao) { glBindVertexArray(that.vao); ...EBO_FIXUP... }
   ```
   `this.vao == V0` per tracker. If `Vt == V0`, the branch is skipped
   entirely. No GL call is emitted. Real GL still has V_last bound.
4. Driver hits `glDrawElements`. The bound VAO is V_last, whose
   GL-internal EBO slot is whatever was current when V_last was first
   populated. If that EBO has since been deleted, the slot is 0, the
   driver falls back to client-pointer mode, and reads NULL.

The bug is age-old; what makes it surface intermittently is the matrix of
(VAO V_last's history) X (which EBOs have been deleted since). NVIDIA and
AMD trip it differently because their VAO->EBO tracking quirks differ -
which is exactly why `VaoBindState` already had `DO_GL_EBO_FIXUP` in place
to override the slot defensively on every `apply()`.

## The fix

### Tracker fix (loftar `2c183d2fd`, merged into thunder via `220949a48`)

Single line in `GLDrawList.SlotRender.draw`:

```java
g.state.assume(last.bk.state());
g.state.apply(null, VaoState.slot, ((VaoSetting)last.settings[idx_vao]).st);   // <-- new
```

`apply(null, ...)` updates the tracker only, no GL calls. After the list
ends, the tracker now matches actual GL's bound VAO. The next draw's
`applyto` correctly sees a transition and rebinds.

This addresses the cause, not the symptom.

### Earlier defensive layer (Thunder `4140e547`, retired in `ab8253c47`)

Before loftar's diagnosis Thunder carried two defensive layers:

- A per-draw `glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo)` injected
  into `SlotRender.draw`'s `BufferBGL` immediately before
  `glDrawElements`. Stopped the crash but left the wrong VAO bound,
  so vertex attribute bindings could still be off.
- A same-VAO/different-EBO branch in `VaoBindState.applyto`. VAOs
  in this codebase are immutable post-init (`GLVertexArray.init()`
  runs once via `ProgIndex.get`, captures `mod.ind`'s GLBuffer in
  the init lambda, never re-runs). The `GLDrawList.vaos` cache pairs
  each `vao` with one `ebo` in the steady state, so the branch only
  fired in already-broken states (e.g. a `StreamBuffer` rotated
  underneath a cached `Indexed` VAO whose vertex attrib bindings
  were also captured against the old buffers). Rebinding only the
  EBO in those states couldn't produce a correct draw.

Both layers were retired in `ab8253c47` once loftar's tracker fix
landed.

## What this log does and doesn't prove

The stack frames here (`BGL$51.run` / `BufferBGL.run` / `process()`) are
generic - they show "some glDrawElements ran inside a submitted
BufferBGL" but don't pin which upstream path enqueued the draw. So the
Hurricane log doesn't directly confirm loftar's specific GLDrawList
state-tracker desync over alternatives like a deleted-EBO race or a
VAO-init race.

What it *does* establish:

- The crash exists on a fork that had none of Thunder's now-reverted
  prep/STREAM refactor commits, so that refactor wasn't the cause.
- It exists with stock loftar GL + Hurricane's application code, so the
  bug lives in the shared `haven.render.gl.*` layer, not in any one
  fork's customizations.

Loftar's diagnosis stands on his own reproduction; this log only
eliminates Thunder as a suspect. Thunder's earlier per-draw EBO bind
masked the symptom while it was in tree, which is why our crashes
stopped while Hurricane's didn't.

## Outcome

[PR 22](https://github.com/dolda2000/hafen-client/pull/22) (thunder's
patch series) was closed unmerged. Loftar landed his own one-line
tracker fix (`2c183d2fd`) which thunder merged in via `220949a48`.
The thunder-side prep/STREAM/sequence-leak refactor was subsequently
reverted in full — see [`gl-rendering.md` § PR 22 history](gl-rendering.md#pr-22-history)
— so the crash is fixed by loftar's tracker fix alone, with no
thunder-side carry on top.

## Pointers

- Loftar's fix: https://github.com/dolda2000/hafen-client/commit/2c183d2fd
- Thunder's defensive layer (retired in `ab8253c47`): commit `4140e547`
- PR thread: https://github.com/dolda2000/hafen-client/pull/22
