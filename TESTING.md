# Thunder Client - Testing Strategy

## Current State

**Test coverage: effectively zero.** There are no JUnit, TestNG, or any standard test framework dependencies. The build (`ant bin`) compiles and packages but runs no tests.

### Existing Test Infrastructure

The repo has a custom, manual test harness in `src/haven/test/` (7 files):

| File | Purpose |
|------|---------|
| `BaseTest.java` | Abstract base — sets up a ThreadGroup, disables audio, hooks shutdown |
| `TestClient.java` | Headless client that connects to `localhost:1870`, runs a UI session without rendering |
| `Robot.java` | Interface for bots that react to widget creation, destruction, and UI messages |
| `DumpBot.java` | Robot that logs all widget/UI activity |
| `CharSelector.java` | Robot that auto-selects a character on login |
| `MultiClient.java` | Launches N TestClients with DumpBots against a local server |
| `RobotException.java` | Exception wrapper for robot failures |

There are also interactive render tests in `src/haven/render/jogl/Test.java` and `src/haven/render/lwjgl/Test.java` that launch a window for visual inspection.

**CI:** `.travis.yml` targets Oracle JDK 7 (defunct), runs `ant buildprocess` only. No test execution.

---

## Architecture & Testability Map

| Layer | Key Classes | Files | Testability | Notes |
|-------|------------|-------|-------------|-------|
| **Data types** | `Coord`, `Coord2d`, `Coord3f`, `Area` | 4 | **High** | Pure math, no dependencies |
| **Message protocol** | `Message`, `MessageBuf`, `MessageInputStream` | 3 | **High** | Serialization/deserialization, pure byte logic |
| **Protocol debug** | `proto/ProtoDecoder`, `ProtoEvent`, `ProtoBus` | 12 | **High** | Already decoupled for inspection |
| **Utilities** | `me/ender/*`, `Utils.java` | ~70 | **High** | Static helpers, string/math/collection utils |
| **Resource loading** | `Resource`, `res/*` | ~76 | **Medium** | Needs filesystem fixtures but no GPU |
| **Session/network** | `Session`, `Connection`, `Bootstrap` | ~10 | **Medium** | Needs server mock or recorded traffic |
| **Widget/UI framework** | `Widget`, `GameUI`, `OptWnd` | ~200 | **Low-Medium** | Deep widget tree, side effects |
| **Game objects** | `Gob`, `OCache`, `Glob` | ~30 | **Low-Medium** | Coupled to session and map state |
| **Rendering** | `render/*`, `render/sl/*`, `render/gl/*` | ~164 | **Low** | Requires OpenGL context or heavy mocking |
| **Integrations** | `integrations/mapv4`, `integrations/food` | 5 | **Medium** | HTTP calls, can mock |

---

## Proposed Strategy

### Phase 0: Infrastructure Setup

**Goal:** Get a test framework running in the build with zero production code changes.

1. **Add JUnit 5 dependency** — drop `junit-platform-console-standalone-*.jar` into `lib/` (keeps the Ant build simple, no Maven/Gradle migration needed).

2. **Add Ant test targets** to `build.xml`:
   ```xml
   <target name="compile-tests" depends="hafen-client">
       <mkdir dir="build/test-classes"/>
       <javac srcdir="src/test/java" destdir="build/test-classes"
              classpath="build/classes:lib/junit-platform-console-standalone.jar"
              includeantruntime="false" release="8"/>
   </target>

   <target name="test" depends="compile-tests">
       <java jar="lib/junit-platform-console-standalone.jar" fork="true" failonerror="true">
           <arg value="--class-path"/>
           <arg value="build/test-classes:build/classes:lib/*"/>
           <arg value="--scan-classpath"/>
           <arg value="build/test-classes"/>
       </java>
   </target>
   ```

3. **Create test source root**: `src/test/java/haven/` mirroring the main source tree.

4. **Replace Travis CI** with GitHub Actions:
   ```yaml
   # .github/workflows/build.yml
   name: Build & Test
   on: [push, pull_request]
   jobs:
     build:
       runs-on: ubuntu-latest
       steps:
         - uses: actions/checkout@v4
         - uses: actions/setup-java@v4
           with:
             distribution: temurin
             java-version: 17
         - run: ant bin
         - run: ant test
   ```

---

### Phase 1: Pure Logic Unit Tests (Week 1-2)

Start with classes that have **no UI, rendering, or network dependencies**. These give the highest confidence-per-effort.

#### 1a. Coordinate & Geometry Math

Target: `Coord`, `Coord2d`, `Coord3f`, `Area`

```
src/test/java/haven/CoordTest.java
src/test/java/haven/AreaTest.java
```

What to test:
- Arithmetic: `add`, `sub`, `mul`, `div`, `mod`, `inv`
- Distance: `dist`, `angle`, `manhattan`
- Area: `contains`, `intersection`, `union`, iteration
- Edge cases: zero coord, negative coords, overflow behavior
- Serialization round-trip (Coord implements Serializable)

#### 1b. Message Serialization

Target: `Message`, `MessageBuf`

```
src/test/java/haven/MessageBufTest.java
```

What to test:
- Write then read round-trips for every type constant (`T_INT`, `T_STR`, `T_COORD`, `T_COLOR`, etc.)
- Mixed-type message sequences
- `addbytes` / `getbytes` boundary conditions
- Underflow/overflow behavior
- `nil` message behavior
- List encoding (`tto()`) with nested types

#### 1c. Utility Functions

Target: `Utils.java`, `me/ender/*.java`

```
src/test/java/haven/UtilsTest.java
src/test/java/me/ender/*.java
```

What to test:
- String helpers, number formatting, color manipulation
- Collection utilities
- Any pure functions in `me/ender/`

**Expected outcome:** ~50-100 unit tests, all run in <5 seconds, no external dependencies.

---

### Phase 2: Protocol & Resource Tests (Week 3-4)

#### 2a. Protocol Decoding

Target: `proto/ProtoDecoder`, `proto/ProtoEvent`

What to test:
- Decode known byte sequences into correct ProtoEvent types
- Round-trip: encode an event, decode it, assert equality
- Malformed input handling

**Approach:** Capture real protocol traffic as byte arrays (using the existing `DumpBot`/`EnhancedRecorder`), store as test fixtures in `src/test/resources/protocol/`, replay through the decoder.

#### 2b. Resource Layer Parsing

Target: `Resource`, resource layer classes

What to test:
- Parse known `.res` files and assert correct layer extraction
- Image layer data format parsing
- Action layer parsing (paginae)
- Corrupt/truncated resource handling

**Approach:** Place small test `.res` files in `src/test/resources/fixtures/`. These can be crafted with `LayerUtil`.

---

### Phase 3: Component Integration Tests (Week 5-8)

#### 3a. Session Protocol (with mock server)

Create a lightweight mock server that speaks the H&H protocol (or replay recorded sessions):

```
src/test/java/haven/MockServer.java       -- minimal socket server
src/test/java/haven/SessionTest.java       -- connect, handshake, message exchange
```

What to test:
- Connection handshake sequence
- Message dispatch to correct handlers
- Session timeout and reconnection behavior
- Object cache updates from server messages

#### 3b. Widget Tree (headless)

The existing `TestClient` already creates a headless `UI`. Build on that pattern:

What to test:
- Widget creation from server messages
- Widget hierarchy (parent/child relationships)
- UI message routing (`uimsg`)
- Widget destruction cleanup

#### 3c. Game Object (Gob) State

What to test:
- Gob creation and attribute attachment
- Position updates and interpolation
- Overlay management
- OCache add/remove/update cycles

---

### Phase 4: Visual & Render Tests (Ongoing)

These are the hardest to automate and lowest priority.

#### 4a. Shader Compilation (no GPU needed)

Target: `render/sl/*` — the GLSL code generation layer

What to test:
- Shader source generation produces valid GLSL strings
- Uniform/varying declarations are correct
- Shader composition (combining vertex + fragment programs)

#### 4b. Snapshot Testing (optional, CI-unfriendly)

- Render a known scene to an offscreen framebuffer
- Compare against a reference image (pixel diff with tolerance)
- Requires GPU on CI runner — run separately or skip in CI

---

### Phase 5: Bot / End-to-End Tests (Stretch)

Leverage the existing `TestClient`/`Robot` framework:

- Stand up a local H&H server in a container
- Run `MultiClient` with scripted `Robot` implementations
- Assert game state after sequences of actions (move, pick up, craft)

This requires a server, so it's only viable if a test server image is available.

---

## What NOT to Test

- **Vendored libraries** (`com/jcraft/*`, `org/json/*`) — not our code
- **Trivial getters/setters** — no logic, no value
- **OpenGL driver behavior** — untestable without real GPU, and driver-specific
- **Server-side game logic** — we only control the client
- **UI layout pixel-perfection** — too brittle, changes constantly

---

## Test File Organization

```
src/
  test/
    java/
      haven/
        CoordTest.java
        Coord2dTest.java
        AreaTest.java
        MessageBufTest.java
        UtilsTest.java
        proto/
          ProtoDecoderTest.java
        res/
          ResourceParserTest.java
        SessionTest.java
        MockServer.java
        WidgetTreeTest.java
      me/
        ender/
          ...Test.java
    resources/
      protocol/          # captured protocol byte sequences
      fixtures/          # small .res files for parsing tests
```

---

## Priority Summary

| Priority | What | Why | Effort |
|----------|------|-----|--------|
| **P0** | Build infra (JUnit + Ant target + CI) | Everything else blocks on this | Small |
| **P1** | Coord/Area/Message unit tests | Pure logic, high bug surface, easy wins | Small |
| **P2** | Protocol decoding tests | Catches breakage from upstream merges | Medium |
| **P3** | Resource parsing tests | Catches broken custom resources | Medium |
| **P4** | Session mock + widget tests | Catches integration regressions | Large |
| **P5** | Shader compilation tests | Low churn area but complex | Medium |
| **P6** | E2E bot tests | Highest coverage but needs server | Large |

---

## Metrics & Goals

- **Short-term (1 month):** Phase 0 + Phase 1 complete. `ant test` runs in CI on every push. ~100 unit tests covering data types and protocol serialization.
- **Medium-term (3 months):** Phase 2 + Phase 3a. Protocol and resource parsing under test. Mock server enables session-level tests. ~300 tests.
- **Long-term (6 months):** Phase 3-4. Widget tree and shader tests. Coverage on the most bug-prone areas. ~500+ tests.

The goal is not 100% coverage — it's **catching regressions in the areas that break most often**: protocol changes from upstream merges, custom resource issues, and coordinate math bugs.
