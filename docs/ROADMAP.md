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

- **M4 — Done**
  `docs/PROTOCOL.md` (CSP/1.1) + `crane-driver-serial`, 3D view, auto-control: motion
  smoothing, anti-sway (sim load pendulum), fold-to-transport.

- **M5 / V2–V3 — Done**
  3D world, 2D pro view, HMI 2.0 + sound, truck and loads with driver mode, interference
  protection, wind, demo scenarios, telemetry replay, settings persistence, profile editor,
  and two safety-hardening passes against external audits (see `docs/BACKLOG.md`).

- **Still ahead**
  Gamepad input and MCU firmware in C — both waiting on hardware. A jMonkeyEngine
  visualiser running as a separate process, for PBR/shadows and real contact physics.
  Geometry carried inside `CraneProfile` so a profile fully describes its machine.

> **Not yet safe for real hardware.** Two audits have been worked through, but the serial
> path has never met a physical crane, and `CraneGeometry` is still bound to one modelled
> machine rather than to the profile. See `docs/BACKLOG.md` §Known limits.
