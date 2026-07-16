# Roadmap

- **M0 — Repo & skeleton** *(this commit)*
  Git repo, docs, Gradle multi-module build (crane-core / crane-sim / crane-ui), shared
  model contract in crane-core, minimal JavaFX window.

- **M1 — Core & simulator** *(branch `feature/core-sim`)*
  SafetyController (E-STOP latch, deadman, clamping, ramp limiting, watchdog), fixed-tick
  ControlLoop, SimulatedCraneDriver with first-order actuator dynamics. Safety logic unit-tested.

- **M2 — Operator UI** *(branch `feature/ui-shell`, then integration on `main`)*
  Control panel (per-axis controls, big E-STOP, hold-to-run deadman via Space), 2D schematic
  visualization (side view + top slew view) behind a renderer interface, status/alarm panel.
  Wired to the simulator through the control loop.

- **M3 — Universality & polish**
  JSON crane profiles (small 3-section vs large 6-section loader), alarms, telemetry logging,
  dark HMI theme, jpackage Windows installer.

- **M4 — Later**
  Gamepad input, `docs/PROTOCOL.md` + serial driver for real hardware (MCU firmware in C),
  3D view, auto-control: motion smoothing, anti-sway (sim load pendulum), fold-to-transport.
