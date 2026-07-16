# Crane Remote Control

Universal hydraulic crane control software — Java 21 + JavaFX operator HMI with a pluggable
crane back-end (v1: built-in simulator; later: real hardware via serial/CAN drivers).

> Safety note: this is a development platform, simulator and HMI. It must never drive a real
> crane without certified safety hardware (hardwired E-STOP, hold-to-run) in the loop.

## Quick start

```
./gradlew :crane-ui:run              # launch the desktop app
./gradlew build                      # compile + run all tests
./gradlew :crane-ui:jpackageImage    # self-contained Windows app (no Java needed)
```

Requires JDK 21 (the Gradle wrapper handles everything else).

**Universal by data:** every crane is a JSON profile (axes, limits, speeds). Two are
bundled; drop your own `*.json` into a `profiles/` folder next to the app and it appears
in the profile selector. Telemetry can be recorded to CSV with the REC toggle.

## Docs
- [Vision](docs/VISION.md) · [Architecture](docs/ARCHITECTURE.md) ·
  [Roadmap](docs/ROADMAP.md) · [Backlog / task board](docs/BACKLOG.md)
