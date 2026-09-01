# Vision

## The spark
Watching a truck-mounted hydraulic loader crane (knuckle-boom, Palfinger/HIAB/Fassi class)
load a boat, operated entirely from a handheld radio remote by the driver. The remote is the
product: the crane maker buys the radio control system from a specialist vendor.

## What we build
**A free crane control simulator for training** — the operator console of a knuckle-boom
loader crane, with the same controls and the same discipline as the real machine, running on
any Windows PC.

This is software. There is no hardware product, and there is no plan for one: it is built and
maintained by one developer. The serial driver exists so that anyone who *does* have a machine
can connect it, and because designing the protocol forced the safety architecture to be
honest — not because a rig is coming.

1. **Now:** a Windows desktop application (Java/JavaFX) that is a complete operator HMI plus a
   realistic crane simulator. Anyone can drive a virtual crane with full safety semantics —
   latching E-STOP, deadman/hold-to-run, position limits, command watchdog, interference
   protection — and every session can be recorded, replayed and summarised.
2. **Available to anyone with hardware:** CSP/2 (docs/PROTOCOL.md) specifies how a
   microcontroller that enforces its own travel limits, behind a hardware emergency stop,
   would connect. Untested against a real machine; documented so it could be.
3. **Who it is for:** instructors teaching heavy equipment, mechatronics or crane operation
   who want a free teaching aid; students; and anyone who wants to see how a safety layer for
   a machine is put together. It is given away, not sold.

## Why it is worth using
A professional crane simulator costs tens of thousands. A trainee's first hours on a real
machine are expensive, weather-dependent and supervised one-to-one. This does not replace
either — it is far simpler than a commercial simulator and has no motion platform, no physics
of load dynamics beyond a pendulum. What it does have is the control discipline: the
sequence of habits an operator needs before they touch anything that moves, and a record of
whether they followed it.

What makes it teaching equipment rather than a toy is **assessment**. A recording carries its
own provenance — which crane, which trainee, when, in what units — and reduces to the numbers
an instructor marks on: emergency stops tripped, limits driven into, time actually moving, and
how smoothly the controls were handled. It reports; it does not grade. Where the pass line sits
is the instructor's judgement and varies by course, and a number that looked like a mark would
be used as one.

## Why the crane is still data
Every crane differs (axis count, ranges, speeds). We never hardcode one: a **crane profile**
(JSON) declares the axes and limits, and the whole stack — safety, control, UI — adapts to it.
This is what lets one program serve a three-axis machine and a five-axis one, and what lets
an instructor describe a specific crane without touching code.

## Safety position
Crane control is safety-critical territory (EN 13849 / IEC 61508; real remotes have hardwired
E-STOP circuits). Two things follow, and neither is negotiable:

- **The machine defends itself.** Travel limits and stopping distance belong on the
  microcontroller, which reads its own encoders every control period. The host's identical
  checks are a second opinion arriving up to 100 ms late. See docs/PROTOCOL.md §0.
- **The emergency stop is hardware.** A contactor or safety relay that removes motor power
  without asking any processor. It is deliberately not a protocol message and never will be.

For a *training* product this matters more than usual, not less: a rig that teaches operators
to trust a software emergency stop is teaching the wrong lesson.

Placing a machine on the market brings its own obligations — risk assessment, CE marking,
technical file. Those are a manufacturer's duties and this repository does not discharge them.

## What we are deliberately not
- Not certified safety equipment, and not a replacement for supervised time on a real machine.
- Not a bid to replace the control software inside a working crane. That software is embedded
  in a manufacturer's safety case, and displacing it is a decade-long conversation.
- Not a hardware company. No rig is being built.
- Not a children's toy. Age-graded training equipment is a different product and a different
  regulatory regime.

## Later ideas
Gamepad input, anti-sway assist tuning, one-button "fold to transport". None are scheduled.
The next real step is finding out whether any instructor uses this at all.
