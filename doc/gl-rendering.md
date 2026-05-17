# GL rendering — architecture and invariants

A map of the GL rendering layer (`src/haven/render/gl/`) as it stands
**after reverting the thunder GL refactor** in response to upstream
feedback (loftar PR 22 — see "PR 22 history" at the bottom).

Thunder now sits on loftar's stock GL code plus a single one-line fix
loftar landed in `2c183d2fd` (merged into thunder via `220949a48`).
File references are inline; line numbers drift — treat them as anchors.

## Contents

- [Top-level shape](#top-level-shape) — `GLEnvironment` / `GLRender` / `GLDrawList`, the `BGL` buffered-GL indirection
- [Frame lifecycle](#frame-lifecycle) — prep → submitted → disposeall ordering in `process()`
- [Core primitives](#core-primitives) — `BGL`, `GLObject` (rc/disp/dispseq), `Sequence`
- [Disposal and the seq ring](#disposal-and-the-seq-ring) — the seqhead/seqtail ring and why one stuck Sequence blocks all downstream tail advancement
- [VAO / EBO binding](#vao--ebo-binding) — caching model, the historical crash bug, loftar's tracker fix
- [STREAM buffers](#stream-buffers) — `StreamBuffer`/`Pool`/`Fill`, publication order
- [State application: Applier and GLState](#state-application-applier-and-glstate) — `Applier`, `curstate`, state diffing
- [Thread model](#thread-model) — submit call-site table, shared-state protection
- [Caches and their lifetime keys](#caches-and-their-lifetime-keys) — key types and ref-types
- [PR 22 history](#pr-22-history) — what we tried, what loftar rejected, what stuck
- [Pointers](#pointers)

## Top-level shape

Three collaborator classes own almost everything:

- **`GLEnvironment`** (`GLEnvironment.java`) — per-GL-context singleton.
  Owns the prep render, the submitted queue, the dispose ring, the
  STREAM-buffer pool, the program/VAO caches, and the `process()` loop
  that drains queued work onto the real GL.
- **`GLRender`** (`GLRender.java`) — a recorder for one batch of GL
  commands. Client code calls `env.render()` to get one, records draws
  into it, and calls `env.submit(r)` to hand it off. Each `GLRender`
  owns a `BufferBGL` (its command list) and a `Sequence` (see "Disposal
  and the seq ring" below).
- **`GLDrawList`** (`GLDrawList.java`) — a persistent, sorted list of
  draw slots that replays per frame with minimal state churn. Each
  `DrawSlot` holds a per-slot `BufferBGL` (`main`) and an array of
  refcounted `Setting` objects (VAO binding, FBO config, pipe state,
  uniforms).

Recording is decoupled from execution by the **`BGL`** abstraction
(`BGL.java`) — a buffered GL command queue. `BufferBGL` records into
a growable `Command[]`; `BGL.run(GL)` replays against a real JOGL `GL`.
Every command is a lambda capturing its operands, so the recording
thread need not be the executing thread.

## Frame lifecycle

One frame, one call to `GLEnvironment.process(GL)` on the renderer
thread (`JOGLPanel.java:174`, `GLPanel.java` similar).

```
Client threads                    Renderer thread
(game, AWT, workers)
---------------                    ---------------
env.prepare(...)      ────→        this.prep (single GLRender)
env.submit(render)    ────→          submitted queue
                                           │
                                           ▼
                                   env.process(gl):
                                     copy = drain(submitted)
                                     prep = this.prep; this.prep = null
                                     if prep: prep.gl.run(gl); prep.dispose()
                                     for c in copy: c.gl.run(gl); c.dispose()
                                     checkqueries(gl)
                                     disposeall().run(gl)
                                     clean()
```

### Prep vs submitted

- **Prep** is one shared `GLRender` held on `GLEnvironment.prep`. The
  three `prepare(...)` overloads (`GLEnvironment.java:470-489`) lazily
  create it under `synchronized(prepmon)` and record setup work into
  it — typically data-store uploads (`glBufferData`, `glTexImage2D`,
  etc.). `process()` claims the current prep render under `prepmon`,
  resets `this.prep = null`, and runs it first inside `drawmon`
  (`GLEnvironment.java:328-347`).
- **Submitted** renders are the caller-visible draw batches handed in
  via `env.submit(render)` (`GLEnvironment.java:386-408`). They run
  after prep inside the same `drawmon` block
  (`GLEnvironment.java:348-360`).
- **`disposeall()`** runs last (`GLEnvironment.java:362`), actually
  calling `glDelete*` on objects that hit `rc == 0` and whose
  `dispseq` is now older than `seqtail`.

### Drain ordering invariant

`process()` snapshots **submitted first, then prep**
(`GLEnvironment.java:321-331`). The comment in the code spells out
why: it's important to drain submitted before prep so that additional
renders submitted during processing aren't drained without their
matching prep work.

The three `prepare(...)` overloads are all `synchronized(prepmon)`, so
writes to `this.prep` are serialized — there's only ever one prep
writer at a time. (This was a thunder confusion in PR 22; see
[PR 22 history](#pr-22-history).)

## Core primitives

### `BGL` — buffered GL

`BGL.java` defines the GL-like interface. `BufferBGL` records commands
(`BufferBGL.java`). Every record is a lambda capturing its args; the
command array grows as needed. `trim()` returns a minimal copy for
long-lived storage (used by `GLDrawList` settings).

### `GLObject` — refcounted GL handles

`GLObject.java` is the base for every wrapped GL name: `GLBuffer`,
`GLTexture2D`, `GLVertexArray`, `GLProgram`, `GLShader`, `GLSampler`,
`GLFrameBuffer`, `GLRenderBuffer`, `GLQuery`.

- **`rc`** — reference count. `get()` increments, `put()` decrements.
  When `rc == 0` and `dispose()` has been called, `dispose0()` stages
  the actual delete.
- **`disp`** flag — latches when `dispose()` is called; prevents double
  dispose and cooperates with `rc` so a resource still being used
  (`rc > 0`) doesn't get deleted underneath a live render.
- **`dispseq`** — the `seqhead` value at the time of `dispose0()`.
  Determines when the object's `glDelete*` may run (see next section).
- **`glid()`** throws `UseAfterFreeException` if called post-delete —
  a Java-level use-after-free surfaces as an exception, not a
  driver-side NULL deref.

### `Sequence` — lifetime tracking

`GLEnvironment.Sequence` is a small `Disposable` that registers a
monotonic sequence number in the `sequse[]` ring via `seqreg()` and
unregisters via `sequnreg()`.

One `Sequence` per `GLRender` — there are no other owners in the
codebase.

## Disposal and the seq ring

This is the subtlest mechanism and the one that keeps the deferred
delete honest.

### `dispseq` and `disposeall()`

When a `GLObject` is Java-level disposed (`rc == 0 && disp == true`),
`dispose0()` does **not** call `glDelete*` — instead it stamps
`dispseq = env.dispseq()` (the current `seqhead`) and pushes the
object onto `env.disposed`.

Each frame, `GLEnvironment.disposeall()` (`GLEnvironment.java:417-440`)
walks `env.disposed` and deletes any object whose `dispseq - seqtail <= 0`.

### Why the deferral

Between Java-level dispose and the actual `glDelete*`, there may be
queued renders that still reference the object — their `BufferBGL`
captured a Java reference but the GL command only needs the numeric
name at replay time. If we deleted the name as soon as `rc == 0`, a
queued command could be replayed against a dead name.

The `Sequence` tied to each `GLRender` keeps `seqtail` pinned at or
below the oldest still-in-flight render's `dispseq`, so disposal waits
until every command list that could reference the object has been
processed and disposed.

### The ring and seqtail advancement

`sequse[]` is a fixed-size ring of booleans indexed by sequence number
mod ring size. `seqreg()` advances `seqhead` and marks the slot true;
`sequnreg()` marks false and, *if the freed slot is exactly at
`seqtail`*, advances `seqtail` forward until it hits the next
still-in-flight slot.

**Key consequence:** a single never-disposed Sequence at the tail
blocks *all* subsequent tail advancement, even if thousands of
younger Sequences have been properly freed. `seqhead - seqtail` grows
without bound, and `disposeall()` backs up because no `GLObject`'s
`dispseq` ever falls below the stuck tail. Loftar's code grows the
ring as needed.

## VAO / EBO binding

Full crash walkthrough lives in [`doc/gl-crash-analysis.md`](gl-crash-analysis.md).
Short version:

### VAO caching

`GLVertexArray.ProgIndex.get` caches one VAO per `(Model,
program.attribs[])`. The VAO is `init`'d once with whichever
`GL_ELEMENT_ARRAY_BUFFER` was current at creation time and never
re-init'd. `GLDrawList` separately caches a `VaoSetting` per
`(vao, ebo)` pair, so each `(vao, ebo)` combination gets a distinct
`VaoSetting` instance — but a given `vao` reference only ever pairs
with one `ebo` in practice (VAOs are immutable by convention, not
by enforcement — `GLVertexArray` holds only the VAO name, not Java
references to the EBO or attribute buffers).

`VaoBindState` keeps `DO_GL_EBO_FIXUP` permanently true: on every
`apply()`, the EBO is explicitly rebound after `glBindVertexArray`,
because some drivers don't reliably track the EBO with the VAO
(contrary to the GL spec).

### The historical bug and loftar's fix

The crash was a state-tracker desync inside
`GLDrawList.SlotRender.draw`. The compiled per-slot `bglCallList`s
internally rebind VAOs to suit each slot's program/attribs, so when
the loop ends the actual GL VAO is whichever the last slot bound —
but the `GLRender`'s `g.state` tracker had only seen
`assume(last.bk.state())`, which doesn't touch the VAO slot. Tracker
says "VAO V0 is bound"; real GL has V_last. The next draw's
`Applier.apply` sees no VAO transition (V0 → V0) and emits no rebind.
`glDrawElements` runs against V_last, whose internal EBO slot is
stale, the driver falls back to "indices is a client pointer", and
`indices=0` becomes a NULL deref.

Loftar's `2c183d2fd` (merged into thunder via `220949a48`) — one line
in `GLDrawList.SlotRender.draw`, after the existing `assume(...)`:

```java
g.state.apply(null, VaoState.slot, ((VaoSetting)last.settings[idx_vao]).st);
```

`apply(null, ...)` updates only the tracker (no GL emit). After the
list ends the tracker matches reality, so the next draw's `applyto`
correctly sees a VAO transition and rebinds.

Thunder briefly carried a draw-site defense in `4140e547` (per-draw
`glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo)` plus a
same-VAO/different-EBO branch in `VaoBindState.applyto`). The per-draw
bind masked the symptom but left the *wrong* VAO bound — vertex
attribute bindings could still be off. The `applyto` branch was dead
code (VAOs immutable by convention). Both were retired in `ab8253c47`
once loftar's tracker fix landed.

## STREAM buffers

STREAM-class data buffers (`DataBuffer.Usage.STREAM`) are uploaded to
a recyclable `GLBuffer` owned by a `StreamBuffer`. The flow lives in
`GLEnvironment.prepare(Model.Indices)` and the equivalent for vertex
arrays (`GLEnvironment.java:491-548`).

- `StreamBuffer` wraps a `GLBuffer` (`rbuf`) plus a `Pool` of
  recyclable transfer `ByteBuffer`s.
- On first prepare (or after `buf.ro` is invalidated), a new
  `StreamBuffer` is created. The Filler's `fill(buf, env)` runs
  inline; if it returns a `StreamBuffer.Fill`, `Vao0State.apply` +
  `glBufferData` is enqueued on the prep render.
- `buf.ro` is assigned **before** the prep is enqueued — i.e. the
  publication order is `ro` first, then prep. This is loftar's
  ordering. Thunder briefly reversed it (commit `33a4b26d6` on the
  reverted branch) in service of a separate refactor; that reversal
  was rolled back along with the rest.
- `StreamBuffer.Pool` is synchronized internally; the data-store
  upload happens on the submit thread, not deferred to renderer.

There is a known race here that loftar acknowledged but has not
landed a fix for: if two threads concurrently initialize a STREAM
`buf.ro` for the same `DataBuffer`, both may race to allocate a
`StreamBuffer`. Loftar's preferred fix is `synchronized(buf)` around
the init path. Thunder does not currently apply it.

## State application: Applier and GLState

`Applier` (`Applier.java`) is the state-diff machinery. A `Pipe`
(logical state soup) gets compiled into a `Pipe` of `GLState`
instances, one per slot (`VboState`, `VaoBindState`, `FboState`,
`Vao0State`, per-pipe states, uniforms). `Applier.apply(gl, that)`
walks slots and calls `this.states[i].applyto(gl, that.states[i])`,
which emits only the GL calls needed to transition from current state
to target state.

`curstate` on `GLEnvironment` persists the last applied state across
renders *within a process() call* — recording and carrying state from
one render into the next so `applyto` can minimize work. It's read
and written only under `synchronized(drawmon)` inside `process()`, so
it's renderer-thread-confined.

## Thread model

| Actor | What it does |
| --- | --- |
| Renderer / GL context thread | Runs `GLEnvironment.process()`, disposal, queries. The only thread that ever touches the real `GL` object. |
| Game / AWT / worker threads | Call `env.render()`, record commands into the returned `GLRender`, call `env.submit(...)`. Also call `env.prepare(...)` (indirectly, via model/texture upload paths). |
| Finalizer | `GLObject.LEAK_CHECK` leak tracer; finalizer-based safety net for leaked `Sequence`s. |

Submit call sites:

| Site | Thread | Shape |
| --- | --- | --- |
| `GLPanel.java` (`Loop.run`) | Renderer/GL | Fire-and-forget |
| `MapView.java` | Game/UI | Pre-built `GLRender` |
| `Fightsess.java` | Game/UI | Pre-built, one-shot fence |
| `rs/DrawBuffer.java` | Game/UI | Pre-built |
| `JOGLPanel.java` | GL context | Direct `process()` |

Shared-state protection:

- `submitted` queue — `synchronized(submitted)`.
- `this.prep` — `synchronized(prepmon)`. All three `prepare(...)`
  overloads hold this around their writes.
- `curstate` — `synchronized(drawmon)` inside `process()`.
- `disposed` list — `synchronized(disposed)`.
- `sequse`, `seqhead`, `seqtail` — `synchronized(seqmon)`.
- `StreamBuffer.Pool` — internally synchronized.
- `buf.ro` on `Model.Indices` / vertex arrays — `synchronized(buf)`.

## Caches and their lifetime keys

| Cache | Key | Ref type | Notes |
| --- | --- | --- | --- |
| `GLEnvironment.progcache` | shader hash + shader list | — | Program linking |
| `GLVertexArray.ProgIndex` (per Model) | `program.attribs[]` | — | VAO init once |
| `GLDrawList.vaos` | `(GLVertexArray, GLBuffer)` | WEAK | Per (vao, ebo) `VaoSetting` |
| `GLDrawList.settings` | `SettingKey` (program, vid, depid) | — | Setting refcounting |
| `StreamBuffer.Pool` | — | strong | Transfer-buffer recycling |

## PR 22 history

[dolda2000/hafen-client PR 22](https://github.com/dolda2000/hafen-client/pull/22)
was a thunder-side patch series proposing a substantial refactor of
the GL renderer in service of fixing the crash and improving
testability. **The PR was closed unmerged**; loftar wrote his own
one-line fix (`2c183d2fd`) and declined the rest. Subsequently the
entire refactor was reverted on thunder so we sit on stock loftar GL
code plus the merged tracker fix.

What was in PR 22:

| Thunder commit | Description | Outcome |
| --- | --- | --- |
| `beaff5f39` | Eliminate shared `GLEnvironment.prep`; each prepare gets its own private `GLRender` enqueued via `prepq` | Reverted — premise wrong, `this.prep` writes are already serialized via `prepmon` |
| `33a4b26d6` | Decouple STREAM prepare from `buf.ro` publication order | Reverted — only needed because the prep refactor introduced a CCE |
| `5b35ca266` | Extract `StreamFiller.runWithPreallocated` to pin the CCE regression | Reverted with the rest |
| `93c8e3e8b` | `StreamBuffer.Fill` lifecycle tests via test-only ctor | Reverted with the rest |
| `50e1859b6` | Extract `RenderQueue` from `GLEnvironment` | Reverted with the rest |
| `3d37a8937` | `GLRender.update` routes STREAM uploads through `runStreamFill` | Reverted with the rest |
| `ea26a4f8a` | Size dispose ring to observed steady-state (32k) | Reverted — loftar's grow-as-needed is fine |
| `4140e547` | VAO/EBO defensive draw-site rebind | Already retired in `ab8253c47` once loftar's tracker fix landed |
| Sequence-leak fixes + ring instrumentation | `env.submit` disposes empty renders, three `prepare(...)` overloads dispose on any throw, `process()` periodically logs `seq-ring: span=N alive=M` | Reverted — the underlying paths exist but no observed leak in production |

What stuck:

- Loftar's `2c183d2fd` tracker fix is merged via `220949a48`.
- The `gl-crash-analysis.md` walkthrough — loftar acknowledged the
  RDX=0 / EBO-unbound diagnosis as correct, even though arrived at
  independently of his AMD-driver reproduction.
- The known STREAM-init race on `buf.ro` — loftar agrees it's real,
  prefers `synchronized(buf)`. Not currently applied on either tree.

Lessons:

- The prep-race hazard the refactor was built on **did not exist** —
  `prepare(...)` overloads are all `synchronized(prepmon)`. Always
  re-verify the threading premise before building on it.
- Thunder's `4140e547` "prevented the crash" by per-draw EBO rebind
  but left the wrong VAO bound — masked the symptom while leaving
  vertex attribute bindings potentially off. The same-VAO/different-EBO
  `applyto` branch was provably dead (VAOs immutable by convention).
- The crash analysis methodology in `gl-crash-analysis.md` —
  "RDX=0 has only one mechanism in the `glDrawElements` path" — is
  worth keeping as a model for future crash hunts even when the
  surrounding refactor is gone.

## Pointers

- Crash analysis: [`doc/gl-crash-analysis.md`](gl-crash-analysis.md).
- GPU profiling notes: `doc/gpu-profiling/`.
- PR 22 thread: https://github.com/dolda2000/hafen-client/pull/22.
- Loftar's tracker fix: https://github.com/dolda2000/hafen-client/commit/2c183d2fd.
