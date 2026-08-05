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

## Safety invariants at the UI boundary (V3.3)
The safety layer is only as good as what the UI feeds it. These rules are not
optional and are covered by tests:

1. **The demo drives the simulator only.** It synthesises operator input including
   the deadman, so `startDemo()` refuses to run unless the active driver is the
   simulator. A sales demo must never be able to move a real machine.
2. **No program clears an E-STOP latch.** Nothing in the codebase may call
   `requestReset()` on the operator's behalf — a latch is cleared by a person
   pressing RESET with the controls neutral. The latch is also re-asserted when a
   profile or driver switch builds a fresh `SafetyController`, so it survives
   backend replacement.
3. **Cached input expires.** The window neutralises every held key on focus loss
   and on minimise, because key releases only reach the focused window. The
   command thread additionally requires a UI heartbeat newer than 400 ms; if the
   JavaFX thread stalls, commands go out neutral instead of replaying stale
   intent with a fresh timestamp.
4. **Interlocks are `volatile`.** `driverMode` is written on the FX thread and read
   on the command thread; without it the lockout could be invisible to the sender.
5. **Elapsed safety time is monotonic.** Command freshness and the watchdog use
   `MonotonicClock`, never the wall clock, so an NTP step or a manual clock change
   cannot extend a stale command's validity.
6. **Serial fails closed.** `SerialCraneDriver` transmits zeros unless telemetry is
   fresher than 250 ms — before the first `T` line, or after the link goes quiet.
   Without position feedback the position limits would be guarding fiction.
7. **Non-finite demands are neutral.** NaN and infinity survive clamping, so the
   safety layer maps them to zero rather than letting them poison the ramp
   limiter and everything downstream.
8. **Replay cannot drive anything (V3.4).** While a recording is on screen the
   command thread sends neutral, exactly as it does for a stalled UI: what the
   operator sees is the past, so their inputs must not reach the present machine.
   The status pill says `REPLAY — RECORDED` rather than reporting the recording's
   own flags as if they were live.
9. **Nothing safety-relevant is persisted (V3.4).** `UiSettings` restores the
   window, crane, back-end, view and weather. Driver mode, the E-STOP latch and
   the deadman always start from their safe state — a machine that came back up in
   the mode you left it in is a surprise, and surprises are how people get hurt.

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

## 2D pro view (M5)
`SchematicRenderer2D` owns an explicit viewport (world centre + scale). Untouched it
auto-fits to the canvas exactly as before (the snapshot probe depends on that); the
view drives `zoomAt` / `panByScreenDelta` / `resetViewport` from scroll, drag and
double-click. Measurement overlays: dashed reach arcs (max tip envelope + current
radius) around the pillar pivot, an adaptive height tick scale, a live
outreach/height readout beside the hook, and a zoom-adaptive scale bar. The top-view
slew inset keeps its own fixed layout, unaffected by zoom/pan.

## 3D view (M4)
The center visualization sits behind `CraneSceneView` (`node()` + per-frame `update()`):
`Schematic2DView` wraps the existing canvas renderer; `Crane3DView` is a JavaFX 3D
SubScene — articulated boom/jib/extension/rope built once, per-frame updates only mutate
transforms. Orbit camera (drag = azimuth/elevation, scroll = zoom). A 2D/3D toggle
overlays the center pane; the choice survives profile switches, views are recreated per
profile. Both views deflect the rope by the optional `"loadSway"` state entry (0 when
absent) and show the E-STOP banner.

## Interference protection (V3.1)
`com.vukotic.crane.core.geometry` holds the machine's **physical model** as data:
`CraneGeometry` gives the dimensions and computes where the boom tip, jib tip and hook
are for a set of axis positions; `Aabb`/`Vec3` provide the collision volumes and tests.
Frame: **Y up**, metres, origin on the ground under the slew axis — the renderers
convert (JavaFX points Y down).

`CollisionGuardFilter` is a `DemandFilter` in the assist chain, and it runs **last** so
it vetoes whatever the other assists asked for; the safety layer still follows it. Each
tick it predicts every commanded axis {@value CollisionGuardFilter#LOOKAHEAD_SECONDS} s
ahead and zeroes that axis if the arm would come within the clearance margin of the cab,
the deck, the ground or a load standing in the way. Two deliberate rules:

- **Only the arm is guarded, never the rope or hook.** Lowering a load onto the deck is
  the job, not a collision — guarding it would make the crane refuse to work.
- **Motion that increases clearance is always allowed**, so the operator can always back
  out of a tight spot. A guard that latched you into a corner would be worse than none.

The UI feeds the guard the box of any set-down load via `Crane3DView.loadObstacles()`.
(Geometry belongs in the profile eventually, so a JSON file fully describes its machine —
noted in docs/BACKLOG.md.)

## Vehicle, loads and driver mode (V3)
- **Layout.** The crane's slew axis is the *vehicle* origin. The cab sits ahead of it
  (−X) and the load bed runs behind it (+X), exactly like a real loader crane, so the
  crane can set a load down on its own deck. `Crane3DView` and `SchematicRenderer2D`
  share these constants and must be changed together.
- **Frames.** The truck is a rigid body: `vehicle = truck + superstructure`, moved by
  `vehicleTranslate`/`vehicleRotate`. Anything computed for the crane is produced in
  vehicle coordinates and lifted into the world with `vehicleToWorld`.
- **Loads rest on surfaces.** `supportHeight()` returns the deck when a load is over
  the bed footprint and the ground otherwise; a hanging load is never allowed below it,
  and `pushClearOfMast()` keeps it out of the mast, so cargo no longer passes through
  the crane. A released load falls under gravity onto that surface.
- **A load on the deck is stored in vehicle coordinates**, so it rides along when the
  truck is driven; a load on the ground stays in world coordinates.
- **Driver mode** is a hard interlock, not a mood: while it is on, the UI submits
  neutral axis demands every tick, so the crane cannot move no matter what the operator
  presses. The safety flags (deadman, E-STOP, reset) still pass through untouched.
  Driving itself is a simple bicycle model — steering only bites while rolling.

## What the winch is allowed to do (V3.4.1)
The rope used to be clamped against the ground and nothing else, so paying out over
the truck ran the rope and the hook through the deck, through the cab roof, and
through a container standing on the bed. Both renderers now clamp against the
surface genuinely under the hook, in this order: a set-down load → the cab roof →
the deck → the ground. `Crane3DView.surfaceHeightLocal` and `ropeToSurface` are the
testable halves; `HookClearanceTest` pins them.

Consequence worth knowing: the hook now parks just above a load instead of sinking
past it, which is *inside* the old proximity radius for picking a load back up. So
pick-up is armed rather than automatic — the hook has to be taken clear of the load
(1.6 m) before it will hook it again. Without that pairing, a load could be set down
and would instantly be snatched back up.

## Where a load may stand (V3.4.2)
Vertical clearance was only half of it. A load was still set down centred wherever
the hook happened to be, with nothing checking that it *fitted*: a boat lowered near
the back of the bed came to rest with its stern through the headboard and hanging in
the air past the end of the truck.

`keepClearOfTruck` now owns both rules. Above the headboard it only keeps the load
out of the mast, as before. Once the load is low enough to foul the deck furniture it
steers the footprint into the usable deck rectangle — clear of the mast at the front,
of the headboard at the rear, inside the side rails — so the load slides into place as
it comes down rather than intersecting the truck.

The footprint is the enclosing box of the *rotated* load, since a load is drawn yawed
by the slew angle: a 4.2 m boat lying across the truck takes up 1.5 m fore-and-aft,
and treating it as 4.2 would refuse placements that are perfectly fine.
`loadObstacles()` uses the same rotated extents, so the core's interference guard is
told about the volume the load actually occupies.

Still a simplification, and worth saying out loud: this steers loads, it does not
resolve contacts. A load whose *centre* is off the bed goes to the ground and may
still clip the deck edge on the way past.

## The demo paces itself to the crane (V3.4.3)
`RUN DEMO` synthesises operator input, and it used to hold each key for a hard-coded
number of seconds — which silently assumed the demo crane's axis speeds. Pick Heavy
Knuckle-Boom, whose boom moves at 5°/s instead of 8°/s, and six seconds of boom
reached 28.7° instead of ~57°: the jib tip stopped short of the deck, the load was
set on the ground behind the truck, and the truck then drove away while the caption
said "with the load aboard".

Hold times now come from the active profile (`maxVelocity`, `commandRampRate`) and
the steps are laid out on a running cursor rather than at fixed absolute times. A
crane with no `boom` or `winch` axis — one built in the profile editor — falls back
to the old fixed durations rather than failing.

Rule of thumb for anything else scripted against the crane: **drive to a position,
never for a duration.** Durations encode one machine's speeds, and the whole product
claim is that the machine is data.

## 3D view landmines (learned the hard way)
- **`SubScene.setFill(...)` must be a solid `Color`.** With a `LinearGradient` fill the
  SubScene's buffer is never cleared between frames, so moving geometry (boom, hook,
  shadows) accumulates into swept fans of stale pixels while static geometry looks
  perfectly fine. The sky gradient is therefore real geometry — a self-illuminated
  sky dome (`buildSkyDome()`), not a fill.
- **`Scene.snapshot()` cannot see this class of bug.** It renders fresh offscreen, so
  the snapshot probe showed a clean crane while the on-screen window smeared. Rendering
  regressions must be verified by capturing the actual window
  (`scripts/capture-window.ps1`), not by snapshots.
- **A diffuse map and a diffuse colour multiply.** `new PhongMaterial(colour)` plus
  `setDiffuseMap(...)` squares the colour and renders almost black; textured materials
  must keep `Color.WHITE` as the diffuse colour (see `texturedMaterial`).

## 3D world (M5)
Everything is procedural — `MeshFactory` builds `TriangleMesh` shapes (tapered,
chamfered beam sections; a stylised boat hull). Each triangle is emitted once, oriented
outward from a reference point inside the solid, with an explicit face normal
(`POINT_NORMAL_TEXCOORD`, flat shading) and `CullFace.NONE`. Emitting both windings
instead — the original shortcut — left coincident triangles that z-fought into stippled
garbage on some GPUs while the opposed normals averaged to zero. The scene adds animated hydraulic
rams (`HydraulicRam` spans two anchor points computed per frame from the joint angles),
a detailed truck, a gradient sky with sun + key light, blob shadows including one that
tracks the hook, and a dock with water and a moored, gently bobbing boat.

`CargoType` (NONE/PALLET/CONTAINER/BOAT) hangs below the hook, inherits sway, detaches
when it touches the ground and re-attaches when the hook returns within 0.7 m — a purely
visual state machine, no effect on the simulation.

`CameraMode` (ORBIT/CAB/HOOK/FOLLOW) is expressed through one parametrization — orbit
centre, azimuth, elevation, distance — so switching modes interpolates instead of
cutting; `rigFromEye` inverts the rig chain to place the camera at a desired eye point
(used by CAB and FOLLOW). Mouse orbit/zoom applies only in ORBIT.
Frozen public API for the UI: `setCameraMode`/`cameraMode`, `setCargo`/`cargo`.

## Profiles & telemetry (M3)
- `CraneProfileLoader` (core, Jackson): JSON → `CraneProfile` with strict validation
  (unknown fields rejected; record constructor rules surface as `ProfileLoadException`).
- `ProfileCatalog` (ui): built-in demo + bundled `/profiles/*.json` resources + any
  `profiles/*.json` directory next to the app — new cranes are data, never code.
  Switching profiles tears down the session (backend, telemetry) and rebuilds the cockpit.
- `TelemetryCsvLogger` (core): a control-loop state listener writing one CSV row per tick
  (Locale.ROOT numbers); UI REC toggle writes `telemetry/telemetry-<profile>-<stamp>.csv`.
- `TelemetryCsvReader` (core, V3.4): the inverse. The header names the axes, so a
  recording is self-describing and needs neither the profile it came from nor a running
  crane; malformed rows are skipped so a run truncated by a crash still plays.
  `Recording.frameAt(elapsedMillis)` is what the frame loop asks for.
- `CraneProfileWriter` (core, V3.4): `CraneProfile` → the same JSON the loader reads, so
  a machine built in the editor is a file you can email, diff or hand to a manufacturer.
  The id is sanitised into the file name; it can never escape the folder.
- `ProfileEditorDialog` (ui, V3.4): a name and a table of axes. Validation is by
  construction — the values go to `AxisSpec`/`CraneProfile`, whose constructors already
  reject inverted limits, duplicate ids and non-positive speeds, and the dialog reports
  their complaint rather than duplicating the rules. Saving reloads the catalog and
  activates the crane, so an edit is driveable without a restart.
- Replay (ui, V3.4): `displayState()` returns a recorded frame instead of the live one
  while a recording is loaded; see safety invariant 8. A CSV given on the command line
  (`crane-remote-control run.csv`) opens straight into it.
- `AppPaths` (ui, V3.4): profiles and recordings used to be relative paths, which resolve
  against the process working directory — the repo under Gradle, but something arbitrary
  and often unwritable for a copy started from the Start menu. Now: `./profiles` and
  `./telemetry` if they already exist (repo checkout, portable folder), otherwise
  `%LOCALAPPDATA%\CraneRemoteControl\` (or `~/.craneremotecontrol` off Windows).

## Packaging
`gradlew :crane-ui:jpackageImage` (badass-runtime plugin): jlink'ed JRE + app classpath
→ self-contained `build/jpackage/CraneRemoteControl/`. Main class is the non-Application
`Launcher` (classpath JavaFX rule). MSI installer needs WiX Toolset installed.

## Serial link (M4)
Module `crane-driver-serial`: `SerialCraneDriver` implements the `CraneDriver` port over
the Crane Serial Protocol v1 (docs/PROTOCOL.md) — checksummed ASCII lines at 115200 8N1,
`HELLO`/`HI` handshake with axis verification, `D` demand lines per tick, `T` telemetry
parsed on a reader thread. Corrupted lines are dropped and counted. Stale telemetry
**does** block the demand path: `acceptsMotion()` reports false, the control loop
suppresses motion before the safety layer and forces the ramp memory to zero, and the
wire carries zeros until a complete, finite, sequence-advancing frame returns. The transport
sits behind the `SerialLink` seam (`JSerialCommLink` for real COM ports, an in-memory
fake in tests), so all protocol logic is tested without hardware. The safety layer stays
host-side: the wire carries no E-STOP/deadman flags — zero demands are the stop, and the
firmware's only mandatory safety duty is its own 250 ms watchdog.

The HMI's DRIVER selector (status panel) lists the simulator plus every COM port found
by `SerialPorts.availablePortNames()`; picking a port reconnects the session through
`SerialCraneDriver`. A failed handshake logs an alarm-history event and falls back to
the simulator, so a wrong selection never bricks the cockpit. (The handshake blocks the
FX thread for up to ~3 s — acceptable for v1.)

## HMI 2.0 &amp; sound (M5)
- Shell is a `SplitPane` (controls / view / status) with draggable dividers and min
  widths; F11 toggles fullscreen. Touch-friendly sizing lives in `hmi.css`
  (34 px minimum control height, fatter slider thumbs).
- Gauges in the status panel: a canvas-drawn radial slew dial (0° up, positive
  clockwise, red end stops) plus per-axis `ProgressBar` position meters; the numeric
  readouts stay next to them.
- `SoundEngine` (`com.vukotic.crane.ui.sound`): fully synthesized cockpit audio on a
  daemon thread writing PCM to a `javax.sound.sampled` line — no asset files, no new
  dependencies. Layers: hydraulic hum (pitch/volume/filter follow total demand),
  motion beeper, E-STOP/watchdog alarm buzzer. The FX frame loop calls
  `update(command, state)` with neutral demands whenever the deadman is released, so
  the pump idles when the crane cannot move. Missing audio device = silently disabled.
  MUTE toggle in the status panel.

## Dev snapshot probe
`-Dcrane.devSnapshotDir=<dir>` (forwarded by `gradlew :crane-ui:run`) runs a scripted
self-test on launch: drives the crane through the real input path, saves PNG snapshots
of the 2D view, 3D view and E-STOP state into the directory, then exits. Used for
visual verification/regression; inert without the property.

## Threading
Three threads, deliberately separated:

1. **`control-loop`** (crane-core) — fixed 50 Hz: safety, driver I/O, publishes `CraneState`.
2. **`operator-command`** (crane-ui) — fixed 50 Hz: samples `OperatorInput`, runs the
   `AutoSequencer`, submits the command, feeds the `SoundEngine`.
3. **JavaFX application thread** — *drawing only*: reads the last command + latest state
   and repaints.

**Why the command thread exists.** It used to live in the `AnimationTimer`, so commands
were only produced as fast as the scene could be drawn. A 3D frame occasionally took
300–1600 ms, which is longer than the safety layer's 250 ms watchdog — the crane kept
stopping because the GPU was busy. Operator intent has nothing to do with render cost,
so command production was moved to its own fixed-rate thread. `OperatorInput` is
therefore thread-safe (concurrent collections + `AtomicBoolean` for the one-shot reset),
and `AutoSequencer`'s state fields are `volatile` (started/cancelled from the UI thread,
advanced on the command thread).

The 3D scene was also made cheaper (antialiasing off, coarser ground grid, transforms
reused instead of reallocated per frame) so frames stay well inside budget.
