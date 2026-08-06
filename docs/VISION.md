# Vision

## The spark
Watching a truck-mounted hydraulic loader crane (knuckle-boom, Palfinger/HIAB/Fassi class)
load a boat, operated entirely from a handheld radio remote by the driver. The remote is the
product: the crane maker buys the radio control system from a specialist vendor.

## What we build
**A desktop crane operator training system** — a bench-top crane and the operator console that
drives it, with the same controls and the same discipline as the real machine.

1. **Now:** a Windows desktop application (Java/JavaFX) that is a complete operator HMI plus a
   realistic crane simulator. Anyone can drive a virtual crane with full safety semantics —
   latching E-STOP, deadman/hold-to-run, position limits, command watchdog, interference
   protection — and every session can be recorded, replayed and summarised.
2. **Next:** a small desktop crane driven over CSP/2 (docs/PROTOCOL.md) by a microcontroller
   that enforces its own travel limits, behind a hardware emergency stop. The desktop app is
   the trainee's console and the instructor's review tool.
3. **Who buys it:** vocational and technical schools teaching heavy equipment or mechatronics,
   crane-operator training centres, and manufacturers' own training departments. The purchase
   is teaching equipment on a department budget, not a software licence.

## Why it is worth buying
A real crane simulator costs tens of thousands. A trainee's first hours on a real machine are
expensive, weather-dependent and supervised one-to-one. This sits in that gap: cheap enough for
a department to buy, real enough that the habits transfer.

What makes it teaching equipment rather than a toy is **assessment**. A recording carries its
own provenance — which crane, which trainee, when, in what units — and reduces to the numbers
an instructor marks on: emergency stops tripped, limits driven into, time actually moving, and
how smoothly the controls were handled. It reports; it does not grade. Where the pass line sits
is the instructor's judgement and varies by course, and a number that looked like a mark would
be used as one.

## Why the crane is still data
Every crane differs (axis count, ranges, speeds). We never hardcode one: a **crane profile**
(JSON) declares the axes and limits, and the whole stack — safety, control, UI — adapts to it.
For a training product this is what lets one program serve a three-axis bench rig and a
five-axis one, and what lets a school describe its own machine without our involvement.

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
  in a manufacturer's safety case; displacing it is a decade-long conversation, and the
  training department is a far shorter one.
- Not a children's toy. Age-graded training equipment is a different product and a different
  regulatory regime.

## Later ideas
Gamepad input, anti-sway assist tuning, one-button "fold to transport", multi-seat classroom
licensing if an instructor ever asks for it. None of these are scheduled; the next real step is
five conversations with instructors.
