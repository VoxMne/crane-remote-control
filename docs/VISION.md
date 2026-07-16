# Vision

## The spark
Watching a truck-mounted hydraulic loader crane (knuckle-boom, Palfinger/HIAB/Fassi class)
load a boat, operated entirely from a handheld radio remote by the driver. The remote is the
product: the crane maker buys the radio control system from a specialist vendor.

## What we build
**Universal crane control software** — the brains and the operator interface, hardware-agnostic:

1. **Now:** a Windows desktop application (Java/JavaFX) that is a complete operator HMI plus a
   realistic crane simulator. Anyone can drive a virtual crane with full safety semantics
   (E-STOP, deadman/hold-to-run, limits, watchdog).
2. **Later:** the same control core talks to real hardware through pluggable drivers
   (serial/CAN adapters, or a microcontroller running a small C firmware implementing our
   command/telemetry protocol). The desktop app becomes the HMI/commissioning tool.
3. **Business angle:** license the software stack (HMI + control core + protocol) to companies
   building crane radio remotes or retrofit kits; or ship it as the PC-side tool next to their
   hardware.

## Why "universal"
Every crane differs (axis count, ranges, speeds). We never hardcode a crane: a **crane profile**
(JSON) declares the axes and limits, and the whole stack — safety, control, UI — adapts to it.

## Safety position
Real crane control is safety-critical (EN 13849 / IEC 61508 territory; real remotes have
hardwired E-STOP circuits). This software is a development platform, simulator and HMI.
It must never drive a real crane without certified safety hardware in the loop. We still
implement software interlocks rigorously — that rigor is the credibility.

## Later ideas (roadmap M4+)
Gamepad input, 3D visualization, anti-sway assist, one-button "fold to transport position",
motion smoothing/auto-control experiments on the simulator.
