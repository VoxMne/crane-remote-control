# Crane Remote Control — User Guide

A complete beginner's guide: what this program is, how to start it, how to drive the
crane, and what every button does. No programming knowledge needed for chapters 1–9.

---

## 1. What this program is

It is the **software half of a crane remote control** — the same kind of handheld radio
box a truck driver uses to operate a hydraulic loader crane.

The software does three things:

1. **Reads what the operator wants** (keyboard, on-screen sliders).
2. **Applies safety rules** to those wishes — this is the important part.
3. **Sends the result to a crane** and draws what the crane is doing.

Out of the box, "a crane" means a built-in **simulator**: a physics model of a crane
running on your PC. No hardware needed. Later, the same software can talk to a real
crane over a serial cable instead — nothing else about the program changes.

> ⚠️ **Safety note.** This is a development platform, simulator and operator interface.
> It must never drive a real crane without certified safety hardware (a hardwired
> emergency-stop circuit and hold-to-run device) in the loop.

---

## 2. Starting the program

### Option A — Install it (easiest)

1. Go to `crane-ui\build\jpackage\`
2. Double-click **`CraneRemoteControl-3.8.0.msi`**
3. Click through the installer (no admin rights needed — it installs for you only)
4. Start it from the Start menu: **Crane Remote Control**

You do **not** need Java installed. The installer bundles everything.

### Option B — Portable (no install)

Copy the whole folder `crane-ui\build\jpackage\CraneRemoteControl\` anywhere — a USB
stick works — and double-click `CraneRemoteControl.exe`.

### Option C — From source (for development)

Open PowerShell in the project folder and run:

```
.\gradlew.bat :crane-ui:run
```

The first run downloads what it needs and takes a couple of minutes; later runs start in
seconds. Requires JDK 21.

---

## 3. The screen

The window has three parts. Drag the edges between them to resize; press **F11** for
fullscreen.

### Left — CONTROLS (what you command)

- One row per crane axis (slew, boom, jib, extension, winch), each with a slider and a
  number showing the demand you are giving, from `-1.00` to `+1.00` (0 = stop).
- **ASSIST**: SMOOTHING, ANTI-SWAY and FOLD TO TRANSPORT (chapter 6).
- **E-STOP** (big red), **RESET**, and the green **deadman** indicator. These are pinned
  to the bottom and never scroll away.

### Middle — the view

The crane itself, in **2D** (a technical side view) or **3D** (a harbour scene). Switch
with the small `2D`/`3D` buttons in the top-left corner (chapter 7).

### Right — STATUS (what the crane is actually doing)

- **PROFILE**: which crane you are driving.
- **DRIVER**: simulator or a real serial port (chapter 10).
- **3D VIEW**: camera and load selectors.
- **AXIS POSITIONS**: a round dial for slew, bars for the other axes, plus exact numbers
  in real units (degrees, metres).
- **SAFETY**: three lamps — E-STOP latched, deadman held, watchdog tripped.
- **TELEMETRY**: the REC button (chapter 8).
- **SOUND**: the MUTE button (chapter 9).
- **ACTIVE ALARMS** and a timestamped **ALARM HISTORY**.

---

## 4. Your first lift, step by step

1. **Click once inside the window** so it has keyboard focus.
2. **Press and hold the Space bar.** The green bar at the bottom-left changes from
   `HOLD SPACE TO RUN` to `RUN ENABLED`. This is the *deadman*: nothing moves unless you
   are holding it, exactly like the trigger on a real remote.
3. **While still holding Space**, press `W`. The main boom rises. Let go of `W` — it
   stops.
4. Try the rest (still holding Space):

   | Key | Movement | Key | Opposite |
   |-----|----------|-----|----------|
   | `Q` | slew left/right (rotate) | `A` | the other way |
   | `W` | boom up | `S` | boom down |
   | `E` | jib (the knuckle) | `D` | back |
   | `R` | extension out (telescope) | `F` | in |
   | `T` | winch — rope down | `G` | rope up |

5. **Let go of Space in the middle of a movement.** Everything stops smoothly. That is
   the safety layer, not a bug.
6. **Press `Esc`** (or click the big red button). A red **E-STOP** banner appears and the
   crane freezes. It stays frozen even if you hold Space and press keys.
7. **Click RESET.** Motion is possible again.

You can also drag the sliders with the mouse instead of using keys; they spring back to
zero when you release them. Keyboard and slider are combined — whichever asks for more
movement wins.

---

## 5. The safety system (the heart of the program)

Everything you command is only a *request*. Between your request and the crane sits the
safety layer, which applies six rules, in this order, 50 times per second:

1. **E-STOP latches.** Once tripped, every command is forced to zero *immediately*, and
   stays zero. Letting go of the button is not enough — it is a latch, like a real
   mushroom-head emergency button that you must twist to release.
2. **Reset is conditional.** RESET only clears the latch if all controls are at zero and
   the deadman is released. This prevents the classic accident: releasing an emergency
   stop while a lever is still pushed, and the machine leaps into motion.
3. **Deadman released ⇒ everything stops.** Not frozen instantly (that would shock the
   hydraulics and swing the load), but ramped to zero fast and under control.
4. **Watchdog.** If the software stops receiving fresh commands for 250 milliseconds —
   a crash, a frozen program, an unplugged cable — it assumes the worst and stops the
   crane, exactly as if you had let go of the deadman.
5. **Clamping and ramping.** Commands are limited to the ±1.0 range, and how *fast* a
   command may change is limited, so nothing can jerk.
6. **Position limits.** Each axis has a minimum and maximum from the crane profile. Push
   into a limit and the movement stops; move back the other way and it works normally.

You can watch rules 1, 3 and 4 on the three SAFETY lamps on the right.

> The alarm history often shows "watchdog tripped" entries right after startup. That is
> the watchdog working correctly before the first command arrives — not a fault.

---

## 6. The assists (the "smart" features)

These shape your commands *before* the safety layer sees them, so an assist can never
override E-STOP, the deadman or the limits.

- **SMOOTHING** — rounds off starts and stops (an S-curve instead of a step). The crane
  feels like an experienced operator is at the levers.
- **ANTI-SWAY** — the crane watches the load swinging on the rope and adds tiny slew
  corrections in the opposite direction to kill the swing. To see it: spool out a few
  metres of rope with `T`, slew hard with `Q`, then stop and watch the load swing. Turn
  ANTI-SWAY on and repeat — it settles roughly twice as fast.
- **FOLD TO TRANSPORT** — the automatic "pack up and go home" sequence. It moves one axis
  at a time in a safe order: retract the extension → hook up → fold the jib → lower the
  boom → centre the slew. **You must still hold Space** while it runs; releasing pauses
  it, and touching any control or hitting E-STOP cancels it. The status line under the
  button tells you which axis it is on.

---

## 7. The two views

### 2D — the technical view

A precise side view plus a top-down slew inset. Extras:

- **Scroll wheel** = zoom (at the mouse pointer), **drag** = pan, **double-click** =
  back to the default framing.
- Dashed **reach arcs** show maximum and current outreach.
- A **height scale** runs up the left side; a **scale bar** sits bottom-left.
- Next to the hook, a live readout: `out 9.0 m / h 2.4 m` — how far the hook is from the
  crane's centre of rotation, and how high above the ground.

### 3D — the harbour scene

The crane on its truck at a quay, with water, a moored boat and a sun. Choose from the
right panel:

- **Camera: Orbit** — free look. Drag to turn around the crane, scroll to zoom.
- **Camera: Cab** — from the driver's seat, looking at the load.
- **Camera: Hook** — straight down from above the hook. The best view for placing a load
  precisely.
- **Camera: Follow load** — behind and above the load, tracking it as you slew.

- **Load: None / Pallet / Container / Small boat** — hangs from the hook, swings with it,
  and if you lower it onto the ground it **stays there**. Bring the hook back down to it
  and it picks it back up. (This is decoration: the load has no weight in the physics.)

The load is drawn in **both** views — the 2D view shows it as a labelled silhouette under
the hook — so switching between 2D and 3D never changes what is on the hook.

Loads behave like objects, not decals: they rest on whatever is underneath (the truck
deck or the ground), they are pushed clear of the mast instead of passing through the
crane, and a released load falls under gravity.

## 7a. Loading the truck, and driving it

The crane is mounted **right behind the cab**, so the whole bed behind it is free to
carry a load — exactly how a real loader crane is arranged.

**To load the truck:** hook a load, raise the boom until the hook comes down over the
deck (with the boom low the crane reaches *past* the tail of the truck), then pay out
rope with `T` until it settles on the deck.

**RELEASE LOAD** unhooks the load where it hangs — in reality the ground crew does this,
the crane cannot drop anything by itself. The button greys out when nothing is hooked.

**DRIVER MODE** switches from operating the crane to driving the truck:

- The crane is **locked out completely** — every axis demand is forced to zero while
  the mode is on. You are either on the remote or behind the wheel, never both.
- Drive with the **arrow keys**: `↑` throttle, `↓` brake and reverse, `←`/`→` steer.
  Steering only works while the truck is rolling, like a real vehicle.
- Anything resting on the deck rides along with the truck.
- The camera follows the truck, and the **Cab** camera looks up the road instead of at
  the load.

---

## 7b. Wind, and why the crane sometimes refuses to move

**WEATHER** sets a wind speed (0–20 m/s) and the compass point it blows *from*. Wind
pushes the hanging load off vertical and keeps it moving, so it is the easiest way to see
what **ANTI-SWAY** is for: pick 12 m/s, swing a load, and try it with the assist off and
on. Because only the part of the wind blowing along the boom acts on the load, the effect
changes as you slew.

**Interference protection** stops an axis before the boom or jib would hit the truck's own
cab, the deck, the ground, or a load standing in the way — the same idea real cranes call
an interference or anti-collision zone. Two things worth knowing:

- It guards the **arm only**. The rope and hook are never blocked, because lowering a load
  onto the deck is the job, not a collision.
- It never traps you: any movement that increases clearance is always allowed, so you can
  always drive back out of a tight spot.

If an axis stops and the position is nowhere near its limit, this is usually why — check
what the boom is pointing at.

## 8. Recording telemetry

Press **REC** in the status panel. From then on, every control cycle (50 per second) is
written as a row into a CSV file:

```
telemetry\telemetry-<crane>-<date>-<time>.csv
```

(in the program's own folder). Columns: timestamp, each axis' position and speed, the
safety flags, and any active alarms. Open it in Excel to plot a movement, prove what
happened during a test, or — later — train an automatic control system.

Press REC again to stop and close the file.

---

## 9. Sound

The program synthesises its own sound (no sound files):

- a **hydraulic hum** that rises in pitch and volume with how much you are demanding,
- a **beeper** while any axis is moving,
- an **alarm buzzer** while E-STOP is latched or the watchdog has tripped.

**MUTE** silences it. If your PC has no audio device, the button reads `NO AUDIO DEVICE`
and the program simply runs silently.

---

## 10. Driving a different crane — profiles

This is what makes the software "universal": **a crane is a data file, not code.**

The PROFILE selector lists:

- *Demo Knuckle-Boom (5-axis)* — the built-in default,
- *Compact Loader (3-axis)* — a small crane with no jib and no extension (notice the
  `E`/`D` and `R`/`F` keys do nothing, and the panels have fewer rows),
- *Heavy Knuckle-Boom (5-axis)* — bigger, slower, longer reach,
- plus **any file you add yourself**.

To add your own crane, create a folder named `profiles` next to the program's `.exe`,
and put a `.json` file in it:

```json
{
  "id": "my-crane",
  "name": "My Crane",
  "axes": [
    { "id": "slew",  "label": "Slew (rotation)", "unit": "deg",
      "minPosition": -180, "maxPosition": 180,
      "maxVelocity": 12, "commandRampRate": 2.0 },
    { "id": "boom",  "label": "Main boom", "unit": "deg",
      "minPosition": 0, "maxPosition": 80,
      "maxVelocity": 7, "commandRampRate": 2.0 },
    { "id": "winch", "label": "Winch (rope out)", "unit": "m",
      "minPosition": 0, "maxPosition": 25,
      "maxVelocity": 1.0, "commandRampRate": 2.0 }
  ]
}
```

What the numbers mean:

| Field | Meaning |
|---|---|
| `id` | short internal name; use `slew`, `boom`, `jib`, `extension`, `winch` so the pictures know what to draw |
| `label` | what the operator sees |
| `unit` | `deg` for angles, `m` for lengths |
| `minPosition` / `maxPosition` | the axis' end stops, in that unit |
| `maxVelocity` | speed at full command, per second |
| `commandRampRate` | how fast the command may change — smaller = gentler machine |

Restart the program and your crane is in the list. If a file has a mistake, it is skipped
and the rest still work.

### The DRIVER selector

- **Simulator** — the built-in virtual crane (default).
- **Serial: COM3, COM4, …** — a real crane connected to that port, speaking the protocol
  in [PROTOCOL.md](PROTOCOL.md). If nothing answers, the program logs the failure in the
  alarm history and falls back to the simulator.

---

## 11. How it works inside (plain language)

Four layers, each ignorant of the ones around it. That is what makes the software
portable and sellable.

```
    YOU (keyboard, sliders)
          │  "I want the boom to go up"
          ▼
    ┌───────────────────────┐
    │  Operator interface   │   crane-ui   — draws everything, reads your input
    └───────────┬───────────┘
                │  a command: {boom: +1.0, deadman held}
                ▼
    ┌───────────────────────┐
    │  Assists (optional)   │   smoothing · anti-sway · auto-fold
    └───────────┬───────────┘
                ▼
    ┌───────────────────────┐
    │  SAFETY LAYER         │   crane-core — the six rules. Has the last word.
    └───────────┬───────────┘
                │  a safe command
                ▼
    ┌───────────────────────┐
    │  Driver (the "port")  │   simulator today · serial cable to real hardware later
    └───────────┬───────────┘
                │  positions and speeds coming back
                └──────────► back up to the screen
```

The middle box runs on its own clock, **50 times every second**, whether or not you touch
anything. Each cycle it: takes your latest command → runs the assists → runs the safety
rules → sends the result to the crane → reads back where the crane now is → hands that to
the screen to draw.

Because the crane is reached only through the "driver" box, swapping the simulator for
real hardware changes nothing else. Because the crane's shape and limits come from the
profile file, the same program drives any crane.

---

## 12. Troubleshooting

| Problem | Cause and fix |
|---|---|
| Nothing moves | You are not holding **Space** (the deadman). Look at the indicator at the bottom-left. |
| Still nothing, red banner | E-STOP is latched. Click **RESET** — with all controls at zero and Space released. |
| RESET does nothing | Same reason: let go of every key and Space first, then click RESET. |
| Keys do nothing at all | The window lost keyboard focus. Click once on the window. |
| Some keys do nothing | That crane has no such axis — the compact profile has no jib or extension. |
| Movement stops before I expect | The axis reached its limit; the alarm list says which one. |
| No sound | MUTE is on, or there is no audio device (the button says so). |
| "watchdog tripped" in history | Normal at startup, before the first command arrives. |
| The 3D view is slow | Switch to 2D; it is much lighter on old graphics chips. |

---

## 13. Where things are on disk

| What | Where |
|---|---|
| Installer | `crane-ui\build\jpackage\CraneRemoteControl-3.8.0.msi` |
| Portable app | `crane-ui\build\jpackage\CraneRemoteControl\` |
| Your own crane profiles | `%LOCALAPPDATA%\CraneRemoteControl\profiles\*.json` (or `profiles\` beside the program if that folder exists) |
| Telemetry recordings | `%LOCALAPPDATA%\CraneRemoteControl\telemetry\*.csv` (or `telemetry\` beside the program if that folder exists) |
| Source code | `crane-core`, `crane-sim`, `crane-driver-serial`, `crane-ui` |
| Design documents | `docs\` |

---

## 14. Where to read next

- [VISION.md](VISION.md) — what this product is for and who might buy it
- [ARCHITECTURE.md](ARCHITECTURE.md) — the technical design
- [PROTOCOL.md](PROTOCOL.md) — the wire protocol for real hardware
- [BACKLOG.md](BACKLOG.md) — what is done and what is planned
