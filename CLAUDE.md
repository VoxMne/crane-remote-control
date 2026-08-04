# crane-remote-control

Universal hydraulic crane control software: Java 21 + JavaFX desktop HMI driving a pluggable
crane back-end. No hardware in v1 — cranes are reached only through the `CraneDriver`
interface; v1 ships a simulator. Product vision: docs/VISION.md. Design: docs/ARCHITECTURE.md.

## Build & test
- `./gradlew build` — compile everything + run all tests
- `./gradlew test` — tests only
- `./gradlew :crane-ui:run` — launch the desktop app

## Modules
- `crane-core` — domain model, safety layer, control loop, `CraneDriver` port.
  MUST NOT depend on JavaFX or any UI/hardware library.
- `crane-sim` — simulated crane implementing `CraneDriver`. Depends only on crane-core.
- `crane-ui` — JavaFX HMI. Depends on crane-core + crane-sim.

## Hard rules (safety semantics are non-negotiable)
- NEVER call `requestReset()` programmatically — only a human clears an E-STOP latch.
- The guided demo may drive the simulator only, never a real driver.
- Elapsed safety time comes from `MonotonicClock`, never `System.currentTimeMillis()`.
- A driver with no fresh position feedback must send zero demands.
- See docs/ARCHITECTURE.md "Safety invariants at the UI boundary" for the full list.
- E-STOP latches: once tripped, all motion demands are forced to 0 and stay 0 until an
  explicit reset while all controls are neutral.
- Deadman released ⇒ every axis demand goes to 0 (ramped down fast, not instantly frozen).
- Watchdog: if no fresh command arrives within the timeout, treat as deadman released.
- Every demand is clamped to [-1, +1], rate-limited by the axis `commandRampRate`, and
  motion stops at profile position limits.
- Axis demands are normalized doubles in [-1.0, +1.0]; 0 = neutral. Physical units live
  only in `AxisSpec` (positions/velocities) — never hardcode crane geometry outside profiles.

## Conventions
- Package root `com.vukotic.crane.*`. Standard Java style. UI text in English.
- JUnit 5. All safety logic must be tested.
- Task board: docs/BACKLOG.md — set your task to DOING when starting, DONE when finished.
- If you change the design, update docs/ARCHITECTURE.md in the same commit.
