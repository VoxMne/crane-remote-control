# Crane Remote Control

Universal hydraulic crane control software — Java 21 + JavaFX operator HMI with a pluggable
crane back-end. Ships with a simulator and a serial driver speaking CSP/1.1
(`docs/PROTOCOL.md`); a crane is described by a JSON profile, not by code.

> **Safety note.** This is a development platform, simulator and HMI. It must never drive a
> real crane without certified safety hardware (hardwired E-STOP, hold-to-run) in the loop.
>
> The serial path has been hardened against two external audits but **has never been
> connected to a physical crane**, and interference protection is still bound to one
> modelled machine rather than to the loaded profile. See `docs/BACKLOG.md` §Known limits
> before pointing this at anything that can move.

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
