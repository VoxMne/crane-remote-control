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
- [x] DONE — Load-sway pendulum in crane-sim (LoadSwayModel, semi-implicit Euler,
      substeps; heuristic tip excitation). Published as "loadSway"/"loadSwayVel"
- [x] DONE — DemandFilter chain hook in ControlLoop (pre-safety, volatile swap)
- [x] DONE — MotionSmoothingFilter: trapezoidal S-curve, braking cap, terminal snap
- [x] DONE — AntiSwayFilter: slew correction -kP*sway -kD*swayVel, neutral deadband,
      passthrough without sway data
- [x] DONE — AutoSequencer "fold to transport": phased, deadman passthrough,
      cancel on manual input / E-STOP
- [x] DONE — Tests (17 new): sway decay/excitation/rope-period, closed-loop anti-sway
      halves settled sway, smoothing accel bound + no overshoot, sequencer order,
      rogue-filter still clamped + E-STOP wins

## M4b — Protocol & serial driver (branch `feature/protocol-serial`)
- [x] DONE — docs/PROTOCOL.md: CSP/1 — NMEA-style XOR checksums, 5-digit sequences,
      HELLO/HI handshake, D/T lines, firmware watchdog rules + implementer checklist
- [x] DONE — Module crane-driver-serial: CspCodec (total parser, never throws on wire
      data), SerialLink seam, JSerialCommLink (115200 8N1), SerialCraneDriver with
      reader thread, droppedLineCount + millisSinceLastTelemetry diagnostics
- [x] DONE — 23 tests: documented byte examples verified exactly, roundtrips, corrupt/
      overlong/malformed rejection, handshake success + missing-axis failure, telemetry
      merge semantics, sequenced writes, clean reader shutdown

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
- [x] DONE — UI: ASSIST panel (SMOOTHING + ANTI-SWAY toggles, states survive profile
      switches), FOLD TO TRANSPORT toggle + live status line
- [x] DONE — ControlLoopBackend.configureAssists (smoothing before anti-sway);
      frame loop routes commands through AutoSequencer while active
- [x] DONE — Full build green: 121 tests / 0 failures across all four modules
- [x] DONE — Manual drive test (sway, assists, fold, 2D/3D, profiles)

## V1.0 release
- [x] DONE — DRIVER selector in the HMI: Simulator + detected COM ports
      (SerialPorts helper); failed serial handshake logs an event and falls back
      to the simulator
- [x] DONE — Dev snapshot probe (-Dcrane.devSnapshotDir): scripted self-test
      capturing 2D/3D/E-STOP PNGs through the real input path
- [x] DONE — Visual verification via probe: 2D articulation, 3D articulation
      (camera reframed: orbit centre +2.5 m, distance 34 m), E-STOP banner,
      driver/profile selectors, assist panel — all confirmed from screenshots
- [x] DONE — 2D/3D toggle moved top-left (was covering the top-view inset)
- [x] DONE — Version 1.0.0, refreshed app-image + MSI, git tag v1.0.0

## M5a — 3D world overhaul (branch `feature/world3d`) — V2 items 1,2,3,4
- [x] DONE — MeshFactory (TriangleMesh, dual-winding faces): tapered chamfered beams
      for boom/extension/jib, boat hull; HydraulicRam boom + jib cylinders animated
      from the joint angles; truck with windows, chassis rail, wheel rims
- [x] DONE — Environment: gradient sky, sun sphere + key light, blob shadows (truck,
      pillar, hook-tracking), quay with bollards, translucent water, bobbing boat
- [x] DONE — CargoType NONE/PALLET/CONTAINER/BOAT with hang → set down → pick up
      state machine, sway inherited
- [x] DONE — CameraMode ORBIT/CAB/HOOK/FOLLOW via a single rig parametrization with
      interpolated switches; frozen API setCameraMode/cameraMode/setCargo/cargo
- [x] DONE — Scope guard respected: render package only

## M5b — 2D pro view (branch `feature/render2d-pro`) — V2 item 5
- [x] DONE — Explicit viewport in the renderer: zoom-at-cursor (scroll), pan (drag),
      double-click resets to auto-fit; untouched viewport keeps auto-fitting
- [x] DONE — Dashed reach arcs (max + current), adaptive height tick scale, live
      "out / h" readout beside the hook, zoom-adaptive scale bar
- [ ] TODO — Capacity chart overlay deferred to the LMI feature (V2 item 8, later)
- [ ] TODO — Scope guard: render package ONLY; CraneRemoteApp untouched

## M5c — HMI theme 2.0 + sound (branch `feature/hmi2-sound`) — V2 items 6,7
- [x] DONE — Gauges: canvas radial slew dial (0° up, CW, red end stops) + per-axis
      ProgressBar position meters, numeric readouts kept alongside
- [x] DONE — SplitPane shell with draggable dividers + min widths, F11 fullscreen,
      touch sizing in hmi.css (34 px controls, fatter slider thumbs)
- [x] DONE — SoundEngine (javax.sound.sampled, all synthesized): hydraulic hum
      tracking total demand, motion beeper, alarm buzzer; deadman-released feeds
      neutral demands so the pump idles; graceful no-device fallback; MUTE toggle
- [x] DONE — Snapshot probe, assists, fold, driver/profile wiring all intact

## M5d — V2 integration (on main, after merges)
- [x] DONE — Camera + Load selectors in the status panel (choices survive profile
      switches); snapshot probe extended to 7 shots incl. cargo and camera modes
- [x] DONE — Visual verification + fixes found only by looking at the renders:
      · panels overflowed → status panel scrolls, safety controls pinned bottom
      · CAB camera looked into the pillar → eye moved to the cab side window
      · apron covered the harbour → ground now ends exactly at the quay
      · hook readout clipped at the canvas edge → flips side near the border
      · boat moored within crane reach instead of off-frame
- [x] DONE — Version 2.0.0, refreshed app-image + MSI, git tag v2.0.0

## Saved for later (V2 items 8-24)
- LMI + capacity charts · outriggers/tipping · hydraulic realism · wind · 2-axis sway
- point-and-lift · geofencing · training mode · replay
- gamepad · Arduino/ESP32 firmware · tablet HMI
- profile editor · CI · logging/crash bundles · localization · code signing/licensing

## Skipped for now (no equipment)
- gamepad/joystick input · Arduino/MCU firmware in C
