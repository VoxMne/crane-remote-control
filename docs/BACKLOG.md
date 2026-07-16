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
- [ ] TODO — Merge feature/core-sim + feature/ui-shell
- [ ] TODO — Wire UI → ControlLoop → SimulatedCraneDriver → CraneState → renderer
- [ ] TODO — Manual drive test (all axes, deadman, E-STOP, watchdog)

## M3 — Universality & polish
- [ ] TODO — JSON crane profiles + loader (Jackson); 2 bundled profiles
- [ ] TODO — Alarm framework (limit reached, watchdog trip, E-STOP events)
- [ ] TODO — Telemetry logging to CSV
- [ ] TODO — Dark HMI theme polish
- [ ] TODO — jpackage Windows installer

## M4 — Later (not scheduled)
- [ ] gamepad input · PROTOCOL.md + serial driver · 3D view · anti-sway · fold-to-transport
