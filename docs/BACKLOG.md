# Backlog — the agentic task board

No Jira/Notion: this file is the single source of truth for task status.
Statuses: `TODO` / `DOING` / `DONE`. Agents: set DOING when you start, DONE when finished
(same commit as the work). Add newly discovered tasks under the right milestone.

## M0 — Repo & skeleton
- [x] DONE — git repo, docs, .gitignore, CLAUDE.md
- [x] DONE — Gradle multi-module build + wrapper, JDK 21 toolchain
- [x] DONE — shared model contract in crane-core (+ smoke tests)
- [x] DONE — minimal JavaFX window in crane-ui

## M1 — Core & simulator (branch `feature/core-sim`)
- [x] DONE — SafetyController: E-STOP latch + reset rules, deadman, clamp, per-axis ramp
      limiting, watchdog (250 ms default) — semantics in docs/ARCHITECTURE.md §Safety
- [x] DONE — ControlLoop: fixed-tick (50 Hz) scheduler; samples latest CraneCommand,
      runs safety, calls driver, publishes CraneState
- [x] DONE — SimulatedCraneDriver (crane-sim): per-axis first-order velocity response
      (time constant per axis), integrate positions, hard stop at limits
- [x] DONE — JUnit tests: every rule in ARCHITECTURE §Safety gets at least one test;
      sim test: step demand → position converges, respects limits

## M2 — Operator UI (branch `feature/ui-shell`)
- [x] DONE — HMI layout: left control panel, center visualization canvas, right status panel
- [x] DONE — Per-axis control widgets (slider springs back to 0 on release) + keyboard map
      (Q/A slew, W/S boom, E/D jib, R/F extension, T/G winch), Space = deadman hold-to-run
- [x] DONE — Big E-STOP button (latching) + Reset button
- [x] DONE — CraneRenderer interface + 2D schematic renderer (side view + top slew view)
- [x] DONE — Status panel: positions, safety flags, alarms; driver + profile display
- [x] DONE — Runs against a stub state supplier (integration replaces it):
      CraneBackend seam + throwaway StubCraneBackend in com.vukotic.crane.ui.backend

## M2b — Integration (on `main`, after merging both branches)
- [x] DONE — Merge feature/core-sim + feature/ui-shell
- [x] DONE — Wire UI → ControlLoop → SimulatedCraneDriver → CraneState → renderer
      (ControlLoopBackend adapter; throwaway StubCraneBackend deleted)
- [x] DONE — Manual drive test (all axes, deadman, E-STOP, watchdog) — operator confirmed

## M3 — Universality & polish
- [x] DONE — JSON crane profiles + CraneProfileLoader (Jackson, strict validation);
      2 bundled profiles (compact-3, heavy-5) + user profiles from ./profiles/*.json;
      runtime profile switching rebuilds the whole cockpit (ProfileCatalog + ComboBox)
- [x] DONE — Alarms: active list + timestamped history (last 100) in status panel
- [x] DONE — TelemetryCsvLogger (crane-core) + REC toggle in UI → telemetry/*.csv
- [x] DONE — Dark HMI theme via hmi.css (-fx-base derived controls, amber accent)
- [x] DONE — jpackage app-image: `gradlew :crane-ui:jpackageImage` →
      build/jpackage/CraneRemoteControl/ (56 MB, no Java needed). MSI installer
      deferred: requires WiX Toolset on the build machine

## M4a — Physics & assist (branch `feature/physics-ai`)
- [ ] TODO — Load-sway pendulum in crane-sim: hook swings on the winch rope, excited by
      axis motion (simplified planar model, heuristic excitation — no geometry contract
      change). Sway published as extra state entries "loadSway" (deg) / "loadSwayVel"
- [ ] TODO — DemandFilter chain hook in ControlLoop (pre-safety, thread-safe toggling)
- [ ] TODO — MotionSmoothingFilter: jerk-limited (S-curve) demand shaping
- [ ] TODO — AntiSwayFilter: damps pendulum via slew/boom demand correction
- [ ] TODO — AutoSequencer "fold to transport": phased pose approach (retract extension →
      raise hook → fold jib → lower boom → center slew), only while deadman held,
      cancelled by E-STOP or manual input
- [ ] TODO — Tests: sway decay/excitation, anti-sway settling improvement, smoothing
      bounds acceleration, sequencer phase order + completion

## M4b — Protocol & serial driver (branch `feature/protocol-serial`)
- [ ] TODO — docs/PROTOCOL.md: line-based ASCII protocol (demands out / telemetry in),
      checksums, sequence numbers, watchdog semantics on both ends
- [ ] TODO — New module crane-driver-serial: SerialCraneDriver implements CraneDriver
      over jSerialComm behind a SerialLink abstraction (FakeLink for tests)
- [ ] TODO — Tests: encode/decode roundtrip, checksum rejection, stale-telemetry
      handling, full driver conversation against a scripted fake crane

## M4c — 3D visualization (branch `feature/render3d`)
- [x] DONE — CraneSceneView abstraction; Schematic2DView wraps the canvas renderer;
      2D/3D toggle overlays the center pane, choice survives profile switches
- [x] DONE — Crane3DView: articulated JavaFX 3D crane (transform-only per-frame
      updates), ground grid, truck, orbit camera (drag) + zoom (scroll), E-STOP banner
- [x] DONE — Load sway deflects the rope in BOTH views via optional "loadSway" entry

## M4d — MSI installer (on main)
- [x] DONE — WiX 3.14 binaries under tools/ (gitignored), jpackage MSI
      (CraneRemoteControl-1.0.0.msi, ~42 MB, per-user install with Start-menu
      shortcut; appVersion 1.x because MSI rejects a leading 0), README instructions

## M4e — Integration (on main, after merges)
- [ ] TODO — UI: ASSIST panel (smoothing + anti-sway toggles), FOLD TO TRANSPORT button
- [ ] TODO — Wire DemandFilters + AutoSequencer through ControlLoopBackend
- [ ] TODO — Full build, manual drive test, update ARCHITECTURE if needed

## Skipped for now (no equipment)
- gamepad/joystick input · Arduino/MCU firmware in C
