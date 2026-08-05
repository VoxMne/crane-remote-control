# Crane Serial Protocol v1 (CSP/1)

> **Revision 1.1.** The `HI` handshake now carries each axis's declared travel (§3.2),
> and the host's telemetry gate is specified rather than left to the driver (§4). Both
> changes exist because CSP puts position-limit enforcement on the host, which only works
> if the host can (a) prove the loaded profile matches the machine and (b) tell when it
> has stopped being able to see it. Firmware written against 1.0 needs its `HI` line
> extended and its `T` lines to carry every axis; nothing else changes.

Line-based ASCII protocol between the host (crane-remote-control, module
`crane-driver-serial`) and a crane microcontroller ("the firmware"). Designed so that an
Arduino C implementation fits in roughly 200 lines: no binary framing, no escaping, no
retransmission — just checksummed text lines both ways over a UART.

Transport: serial port, **115200 baud, 8 data bits, no parity, 1 stop bit (8N1)**.

## 1. Framing

- One message per line. A line is terminated by LF (`\n`, 0x0A). The host tolerates an
  optional CR (`\r`, 0x0D) immediately before the LF, so firmware may use `Serial.println`.
- A line contains only printable ASCII (0x20–0x7E) before the terminator.
- Maximum line length: **240 characters** excluding CR/LF. Longer lines are invalid.
- Fields within a line are separated by a single space (0x20).
- Numbers always use `.` as the decimal separator (never `,`), no thousands separators,
  no exponent notation.
- Any line that fails framing, checksum, or field validation is **dropped in its
  entirety and counted** by the receiver. There is no NAK and no retransmission: the
  next periodic line supersedes the lost one.

## 2. Integrity: XOR checksum

Every line ends with `*XX` where `XX` is the two-hex-digit XOR of **every character
before the `*`** (NMEA-style, including the space that precedes the `*`):

```
checksum = 0
for each byte b before '*': checksum ^= b
XX = uppercase hex, zero-padded to 2 digits
```

Senders emit uppercase hex; receivers should accept either case. A missing `*`, a
malformed hex pair, or a mismatch invalidates the whole line.

Worked example, byte by byte, for the line `HELLO *62`:

```
'H' 0x48 -> 0x48
'E' 0x45 -> 0x0D
'L' 0x4C -> 0x41
'L' 0x4C -> 0x0D
'O' 0x4F -> 0x42
' ' 0x20 -> 0x62   => "*62"
```

## 3. Messages

### 3.1 `HELLO` — host → crane (session open)

```
HELLO *62
```

Sent by the host after opening the port. The firmware must reply with a `HI` line. The
host retries the `HELLO` a few times (with a ~1 s reply window each) before giving up;
firmware should treat repeated `HELLO`s as harmless (always re-reply).

### 3.2 `HI <name> <axisList>` — crane → host (session accept)

```
HI KB5 slew:-180:180,boom:-5:75,jib:0:150,extension:0:6,winch:0:20 *4A
```

- `<name>`: a single token (no spaces) identifying the crane, e.g. a model code.
- `<axisList>`: comma-separated, no spaces. Each entry is `<id>:<min>:<max>` — the axis
  id and the **travel the crane declares for it**, in that axis's physical unit, as
  decimals. `min` must be strictly less than `max`.

The host verifies this against the active `CraneProfile`:

1. **every profile axis must appear**; extra crane axes are fine (never commanded);
2. **every profile axis's `[minPosition, maxPosition]` must fit inside the declared
   travel.** A profile that reaches further than the crane is a connect error.

Both are connect errors — the host closes the port and reports why.

> **Why the crane must declare its travel.** CSP puts position-limit enforcement on the
> **host** (§3.3): the firmware's only mandatory safety duty is the watchdog. A handshake
> that only names axes therefore cannot tell the host whether the profile someone
> selected describes *this* machine. The bundled Demo and Heavy profiles expose exactly
> the same five axis ids and very different travel, so a name-only handshake would let
> the Heavy profile drive a small crane metres past its stops with every layer believing
> it was inside the limits.

**CSP/1.0 compatibility.** The older form `HI KB5 slew,boom,winch` (ids only, no travel)
still parses, and `CspCodec.Hi.declaresLimits()` reports `false` for it. The driver
**refuses to connect** to such a crane, for the reason above. Firmware written against
CSP/1.0 needs its `HI` line extended; nothing else changes.

### 3.3 `D <seq> <axis>:<demand> ...` — host → crane (demands, every control tick)

```
D 00042 slew:0.500 boom:-0.250 winch:0.000 *10
```

- Sent every host control tick (default 50 Hz).
- `<seq>`: exactly 5 decimal digits, zero-padded, incrementing per line, wrapping
  `99999 → 00000`. Firmware may use it to detect gaps; it must not reorder on it.
- Each `<axis>:<demand>` pair carries a **normalized demand in [-1.000, +1.000]** with
  exactly 3 decimals (e.g. `0.500`, `-0.250`, `0.000`). `0.000` = neutral/stop.
- A demand outside [-1, +1], a malformed number, or a malformed pair invalidates the
  **whole line** (drop and count, keep previous demands).
- Axes the firmware does not know: ignore the pair, keep the rest of the line.
- Axes missing from a `D` line: keep the previously commanded demand. The host always
  sends **every** profile axis on every line precisely because of this rule — an omitted
  axis would otherwise keep running on whatever it was last told.

**Safety on the wire.** Demands are already safety-filtered by the host core (E-STOP
latch, deadman, watchdog, clamping, ramp limiting all run host-side). The protocol
therefore carries **no deadman or E-STOP flags in v1 — zero demands ARE the stop**.
The firmware needs exactly one safety behavior of its own, see §4.

### 3.4 `T <seq> <axis>:<position>,<velocity> ...` — crane → host (telemetry)

```
T 00042 slew:12.50,1.20 boom:45.00,0.00 *64
```

- Sent periodically (recommended 10–50 Hz) and/or in reply to each `D` line.
- `<seq>`: 5 decimal digits. Echo the sequence number of the most recent valid `D` line
  received (`00000` before any). **The host checks this and it must advance** — a stream
  repeating one sequence forever looks alive but says nothing about where the crane is,
  and is rejected as not-feedback.
- Each `<axis>:<position>,<velocity>` pair is in the **physical units declared by the
  profile's `AxisSpec`** (e.g. degrees and deg/s for a slew axis). Any decimal precision
  is accepted; two decimals are plenty.
- Unknown axis ids are ignored, and extra axes do not invalidate a line.
- **A `T` line that omits a profile axis is not accepted as position feedback.** It is
  dropped and counted, and the previous positions stand. Earlier revisions let missing
  axes keep their old values while still refreshing the freshness timer, which meant
  motion was permitted with the host's position limits guarding numbers that had stopped
  being measured. Send every axis the profile declares, every line.

## 4. Watchdogs, staleness, session end

- **Firmware watchdog (mandatory):** if no valid `D` line has arrived within **250 ms**,
  the firmware must force all its outputs to zero (stop all motion) until valid `D`
  lines resume. This covers unplugged cables, host crashes, and wedged UARTs. It is the
  only safety logic required firmware-side.
- **Host gate (mandatory, host-side):** the host transmits nonzero demands **only** while
  it holds usable position feedback — a complete, sequence-advancing `T` line newer than
  **250 ms**. Otherwise every `D` line carries zeros. Without feedback the host cannot
  know where the crane is, so its position limits would be guarding fiction, and a
  one-way link must not be able to keep a machine moving.
  - The host keeps sending `D` lines (all zeros) rather than going silent, so the
    firmware watchdog is not tripped by the host's own caution.
  - This is visible above the driver too: `CraneDriver.acceptsMotion()` reports it, and
    the control loop suppresses motion **before** the safety layer so the ramp limiter
    stays parked at zero. Otherwise the ramp would wind up to full behind the closed
    gate and the first good `T` line would step the machine from standstill to full
    demand in one tick.
  - `SerialCraneDriver.millisSinceLastTelemetry()` still reports the age for alarms.
- There is no goodbye message. Either side simply stops talking; the watchdogs do the
  rest. Closing and reopening the port starts a fresh `HELLO`/`HI` handshake.

## 5. Byte-level session example

All checksums below are real (computed, not illustrative):

```
host  → HELLO *62
crane → HI KB5 slew:-180.00:180.00,boom:-5.00:75.00,jib:0.00:150.00,extension:0.00:6.00,winch:0.00:20.00 *7B
crane → T 00000 slew:0.00,0.00 boom:0.00,0.00 jib:0.00,0.00 extension:0.00,0.00 winch:0.00,0.00 *33
host  → D 00042 slew:0.500 boom:-0.250 jib:0.000 extension:0.000 winch:0.000 *08
crane → T 00042 slew:12.50,1.20 boom:45.00,0.00 jib:0.00,0.00 extension:0.00,0.00 winch:0.00,0.00 *31
host  → D 00043 slew:0.000 boom:0.000 jib:0.000 extension:0.000 winch:0.000 *26
```

Note that both `D` and `T` lines carry **every** profile axis — see §3.3 and §3.4 for
why a partial line is unsafe in each direction.

## 6. Firmware implementer's checklist

1. UART at 115200 8N1; read bytes into a buffer until `\n`; strip an optional `\r`.
2. Discard (and count) any line longer than 240 chars or containing non-printable bytes.
3. Verify `*XX`: XOR every byte before the `*`, compare case-insensitively. Bad → drop
   line, count it, keep previous demands.
4. On `HELLO`: reply `HI <name> <id>:<min>:<max>,...` — every time, even mid-session.
   Declare the real mechanical travel of each axis: the host enforces the position
   limits and refuses to connect to a crane that will not say what they are.
5. On `D`: validate the 5-digit sequence and every pair; any demand outside
   [-1.000, +1.000] or malformed number → drop the whole line. Otherwise store demands
   (unknown axes ignored), remember the sequence number, and pet the watchdog.
6. Watchdog: if >250 ms since the last valid `D`, drive all outputs to zero.
7. Send `T <lastDSeq> <axis>:<pos>,<vel> ...` at 10–50 Hz, physical units, `.` decimal
   point, and append the checksum (`sprintf("*%02X", x)`). Include **every** axis the
   host's profile declares, and make sure `<lastDSeq>` advances — the host rejects
   partial frames and repeated sequences as not being position feedback, and holds all
   demands at zero until it gets some.
8. Never block on TX; if the UART is busy, skip a telemetry frame rather than stall the
   control path.
9. Map demand → actuator strictly proportionally around 0 = stop; the host has already
   done clamping, ramping, and all safety filtering.
