# Architecture

Ports & adapters: the control core knows nothing about JavaFX or hardware.

```
crane-ui (JavaFX HMI)
   │  builds CraneCommand from operator input; renders CraneState
   ▼
crane-core (pure Java)
   ├── model:   CraneProfile, AxisSpec, CraneCommand, CraneState
   ├── safety:  SafetyController — E-STOP latch, deadman, clamp, ramp limit, watchdog
   ├── control: ControlLoop — fixed tick (default 50 Hz): input → safety → driver → state out
   └── driver:  CraneDriver (the port)
   ▲
crane-sim (adapter #1: SimulatedCraneDriver — kinematics + first-order actuator dynamics)
future adapters: serial link to MCU firmware, CAN, vendor radio bridges
```

## The shared contract (committed in M0, stable for parallel work)

All types in `com.vukotic.crane.core.model` / `...core.driver`:

- **`AxisSpec`** — `id`, `label`, `unit`, `minPosition`, `maxPosition`, `maxVelocity`
  (units/s at full demand), `commandRampRate` (max demand change per second).
  Physical units live here and only here.
- **`CraneProfile`** — `id`, `name`, ordered `List<AxisSpec>`, `axisById()`.
  `CraneProfiles.demoKnuckleBoom()` = 5 axes: `slew`, `boom`, `jib`, `extension`, `winch`.
- **`CraneCommand`** — `timestampMillis`, `Map<axisId, demand ∈ [-1,+1]>`,
  `deadmanHeld`, `estopRequested`, `resetRequested`. `CraneCommand.neutral(profile)` helper.
- **`CraneState`** — `timestampMillis`, `axisPositions`, `axisVelocities` (physical units),
  `estopLatched`, `deadmanHeld`, `watchdogTripped`, `activeAlarms` (List<String>).
- **`CraneDriver`** — `name()`, `connect(profile)`, `disconnect()`, `isConnected()`,
  `sendDemands(Map<String,Double>)`, `readState()` → positions/velocities.
  Drivers receive **already-safety-filtered** demands; safety lives in core, not in drivers.

## Safety semantics (tested in crane-core)
1. E-STOP request latches `estopLatched`; all outgoing demands forced to 0 immediately.
2. Reset only clears the latch if all operator demands are neutral and deadman is released.
3. Deadman not held ⇒ demands ramp to 0 quickly (controlled stop, not a frozen output).
4. Watchdog: no fresh CraneCommand within timeout (default 250 ms) ⇒ same as deadman released.
5. Demands clamped to [-1,+1] and slew-rate-limited per axis (`commandRampRate`).
6. Motion toward a violated position limit is zeroed; motion away from it is allowed.

## Assist pipeline (M4)
`ControlLoop` applies an optional chain of `DemandFilter`s to the raw demands each tick,
BEFORE the safety layer — assists shape motion, safety always has the final word:
- `MotionSmoothingFilter` — jerk-limited (S-curve) demand shaping, no overshoot.
- `AntiSwayFilter` — damps the load pendulum by correcting the slew demand with
  `-kP*sway -kD*swayVel`; passes through when no sway data is present.
- `AutoSequencer` (UI-side, not a filter) — "fold to transport": drives one axis at a
  time (extension → winch → jib → boom → slew) toward a conventional pose; only moves
  while the operator holds the deadman; any manual input or E-STOP cancels.

The simulator models the hook load as a planar damped pendulum (`LoadSwayModel`)
excited by a heuristic boom-tip speed, and publishes it as extra state map entries
`"loadSway"` (deg) / `"loadSwayVel"` (deg/s) — a contract-free virtual sensor channel
(consumers use getOrDefault semantics and iterate profile axes only).

## UI layer (crane-ui)
- `CraneRenderer` interface over a JavaFX Canvas; v1 implementation = 2D schematic
  (side view: boom/jib/extension/hook; top view: slew). 3D can slot in later.
- Control panel: per-axis slider/joystick widgets + keyboard bindings; Space = deadman
  (hold-to-run); big latching E-STOP button; reset button.
- Status panel: axis positions, safety flags, alarm list, driver/profile selectors.
- UI never mutates state directly — it only produces `CraneCommand`s for the ControlLoop
  and renders the `CraneState` the loop publishes.

## 3D view (M4)
The center visualization sits behind `CraneSceneView` (`node()` + per-frame `update()`):
`Schematic2DView` wraps the existing canvas renderer; `Crane3DView` is a JavaFX 3D
SubScene — articulated boom/jib/extension/rope built once, per-frame updates only mutate
transforms. Orbit camera (drag = azimuth/elevation, scroll = zoom). A 2D/3D toggle
overlays the center pane; the choice survives profile switches, views are recreated per
profile. Both views deflect the rope by the optional `"loadSway"` state entry (0 when
absent) and show the E-STOP banner.

## Profiles & telemetry (M3)
- `CraneProfileLoader` (core, Jackson): JSON → `CraneProfile` with strict validation
  (unknown fields rejected; record constructor rules surface as `ProfileLoadException`).
- `ProfileCatalog` (ui): built-in demo + bundled `/profiles/*.json` resources + any
  `profiles/*.json` directory next to the app — new cranes are data, never code.
  Switching profiles tears down the session (backend, telemetry) and rebuilds the cockpit.
- `TelemetryCsvLogger` (core): a control-loop state listener writing one CSV row per tick
  (Locale.ROOT numbers); UI REC toggle writes `telemetry/telemetry-<profile>-<stamp>.csv`.

## Packaging
`gradlew :crane-ui:jpackageImage` (badass-runtime plugin): jlink'ed JRE + app classpath
→ self-contained `build/jpackage/CraneRemoteControl/`. Main class is the non-Application
`Launcher` (classpath JavaFX rule). MSI installer needs WiX Toolset installed.

## Serial link (M4)
Module `crane-driver-serial`: `SerialCraneDriver` implements the `CraneDriver` port over
the Crane Serial Protocol v1 (docs/PROTOCOL.md) — checksummed ASCII lines at 115200 8N1,
`HELLO`/`HI` handshake with axis verification, `D` demand lines per tick, `T` telemetry
parsed on a reader thread. Corrupted lines are dropped and counted; stale telemetry is
exposed via `millisSinceLastTelemetry()` and never blocks the demand path. The transport
sits behind the `SerialLink` seam (`JSerialCommLink` for real COM ports, an in-memory
fake in tests), so all protocol logic is tested without hardware. The safety layer stays
host-side: the wire carries no E-STOP/deadman flags — zero demands are the stop, and the
firmware's only mandatory safety duty is its own 250 ms watchdog.

## Threading
ControlLoop runs on its own scheduled thread at fixed tick; UI reads the latest published
`CraneState` via `javafx.application.Platform.runLater` or an `AnimationTimer` polling an
`AtomicReference<CraneState>`. Commands flow UI → loop through a thread-safe holder the
loop samples each tick.
