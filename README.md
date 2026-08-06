# Crane Remote Control

**A desktop crane operator training system.** Java 21 + JavaFX operator HMI with the same
controls and the same safety discipline as a real knuckle-boom loader crane: latching
emergency stop, hold-to-run deadman, command watchdog, position limits and interference
protection.

Runs against a built-in simulator today, or a physical desktop crane over a serial link
speaking CSP/2 (`docs/PROTOCOL.md`). The machine is described by a JSON profile rather
than by code, so the same software teaches a three-axis rig or a five-axis one.

**Why it is worth using for training:** every session can be recorded and replayed, and a
recording carries its own provenance — which crane, which trainee, when, in what units —
plus a summary of what actually happened: emergency stops tripped, limits driven into,
time spent moving, and how smoothly the controls were handled. That is the artifact an
instructor marks.

> **What this is not.** Not certified safety equipment, and not a substitute for
> supervised time on a real machine. The emergency stop in this software is a software
> latch; on any physical rig the emergency stop must be a hardware circuit that removes
> motor power without asking a processor's permission — see `docs/PROTOCOL.md` §0.
>
> The serial path has been hardened against three external audits but **has never been
> connected to physical hardware**. See `docs/BACKLOG.md` §Known limits before pointing it
> at anything that can move.

## Quick start

```
./gradlew :crane-ui:run              # launch the desktop app
./gradlew build                      # compile + run all tests
./gradlew :crane-ui:jpackageImage    # self-contained Windows app (no Java needed)
./gradlew :crane-ui:jpackage         # Windows .msi installer (WiX on PATH, see below)
```

For the `.msi`, WiX 3.14 binaries must be on the PATH. This repo keeps them locally
(gitignored) — from PowerShell:

```powershell
$env:Path = "$pwd\tools\wix314;$env:Path"; .\gradlew.bat :crane-ui:jpackage
```

(If `tools/wix314` is missing, download `wix314-binaries.zip` from the official
wixtoolset/wix3 GitHub releases and extract it there.)

Requires JDK 21 (the Gradle wrapper handles everything else).

**Universal by data:** every crane is a JSON profile (axes, limits, speeds). Two are
bundled; drop your own `*.json` into a `profiles/` folder next to the app and it appears
in the profile selector. Telemetry can be recorded to CSV with the REC toggle.

## Driving it
Hold **Space** (deadman — nothing moves without it), then `Q/A` slew, `W/S` boom,
`E/D` jib, `R/F` extension, `T/G` winch. `Esc` or the big red button latches E-STOP.
`F11` toggles fullscreen; panel edges drag to resize.

**v2 additions:** switchable 2D schematic (zoom/pan, reach arcs, height scale) and a 3D
harbour scene with four camera modes (orbit / cab / hook / follow) and a selectable load
that can be set down and picked back up; radial + bar gauges; synthesized cockpit audio
(hydraulic hum, motion beeper, alarm buzzer) with a MUTE toggle.

**v3 additions:** the crane is mounted behind the cab with a free load bed behind it, so
you can pick a load off the ground and set it on the truck; loads rest on real surfaces
and no longer pass through the crane; RELEASE LOAD unhooks it; and DRIVER MODE locks the
crane out and lets you drive the truck away with the load aboard (arrow keys).

## Docs
- **[User Guide](docs/USER_GUIDE.md) — start here if you just want to drive the crane**
- [Vision](docs/VISION.md) · [Architecture](docs/ARCHITECTURE.md) ·
  [Protocol](docs/PROTOCOL.md) · [Roadmap](docs/ROADMAP.md) ·
  [Backlog / task board](docs/BACKLOG.md)
