# Haven Navigation Core

`HavenNavigationCore` is Thunder's renderer-independent local navigation
library. It keeps route planning and interaction-pose selection separate from
the live client so automation can share one deterministic, testable planner.

The module provides:

- occupancy-grid A* with exact edge and body collision checks;
- multi-goal local planning and partial-horizon results;
- legal interaction poses around exact object footprints;
- overlap escape and approach planning for tightly packed objects;
- land, swimming, cart, and boat terrain policies;
- confirmed-arrival state tracking for movement executors; and
- typed plan results and optional telemetry hooks.

The core targets Java 8 and depends only on the small `Coord` and `Coord2d`
compile-time stubs included in this directory. Thunder builds it as a separate
JAR and places that JAR on both the compile-time and runtime classpaths.

## Build and test

From the repository root:

```sh
ant navigation-core
ant navigation-core-tests
```

The regular `ant test`, `ant bin`, and `ant play` targets build and use the
same JAR automatically.

## Integration boundary

The library plans against caller-provided occupancy, terrain, geometry, and
movement observations. It does not read live game state or issue clicks.
Client adapters and bots remain responsible for observing the world,
executing returned routes, confirming arrival, and deciding what interaction
to perform next.
