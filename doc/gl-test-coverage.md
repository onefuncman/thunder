# GLEnvironment test coverage

There is no unit-test coverage for `haven.render.gl.GLEnvironment` and
its collaborators today. The previously checked-in suite
(`StreamBufferPoolTest`, `StreamBufferFillTest`, `StreamFillerTest`,
`RenderQueueTest`) was removed when the thunder GL refactor it
exercised was reverted — see [`gl-rendering.md` § PR 22 history](gl-rendering.md#pr-22-history)
for context.

## Why the tests are gone

The tests covered extractions (`StreamBuffer.Pool`,
`StreamBuffer.Fill` via test-only ctor, `StreamFiller`, `RenderQueue`)
that were a means to test the prep refactor's CCE-fix path. Loftar
declined the refactor in PR 22 on the grounds that the multi-writer
race it was guarding against didn't exist — the three `prepare(...)`
overloads are all `synchronized(prepmon)`. The extractions were
reverted along with the refactor; keeping the tests pointing at
deleted code is worse than no tests.

## What remains testable cheaply

Without the extractions, the GL layer is essentially impossible to
unit-test without standing up a real GL context or building a
byte-capturing fake `GL`. The few things that *would* still be cheap
to cover:

- **`StreamBuffer.Pool` concurrency** — the Pool is a self-contained
  inner class in loftar's `StreamBuffer`. A targeted test (8 threads
  × 1000 get/put, no two `get()`s return the same `ByteBuffer`
  identity, allocations ≤ peak concurrency) is doable without
  extraction; you'd just need to construct a `StreamBuffer` against
  a fake `GLEnvironment`/`GLBuffer`, which is the awkward bit.
- **`sequse[]` ring invariants** — `seqreg`/`sequnreg` and
  `seqtail` advancement are pure data-structure logic. A test would
  need the same `GLEnvironment` construction workaround.

If the construction-cost workaround for those two ever becomes worth
paying, do it as an in-place test, not behind a refactor that loftar
will revert.

## What's not cheap

- End-to-end STREAM prepare → process → `glBufferData` byte
  verification.
- `dispose()` interleaved with in-flight prep across threads.
- Texture path caching by env identity.

These need a real GL or step-pause harness — worth attempting only
if a regression in this area actually bites.

## Pointers

- Architecture: [`gl-rendering.md`](gl-rendering.md).
- PR 22 history (what was tried, what was rejected): same doc,
  [PR 22 history section](gl-rendering.md#pr-22-history).
