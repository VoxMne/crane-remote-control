# Crane Serial Protocol v1 (CSP/1)

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

### 3.2 `HI <name> <axisIdList>` — crane → host (session accept)

```
HI KB5 slew,boom,jib,extension,winch *7C
```

- `<name>`: a single token (no spaces) identifying the crane, e.g. a model code.
- `<axisIdList>`: the axis ids this crane can drive, comma-separated, no spaces.

The host verifies the list against the active `CraneProfile`: **every profile axis must
appear in the list; extra crane axes are fine** (the host simply never commands them).
A missing axis is a connect error — the host closes the port.

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
- Axes missing from a `D` line: keep the previously commanded demand (the host normally
  always sends all profile axes).

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
  received (`00000` before any) — a crude round-trip liveness signal for the host.
- Each `<axis>:<position>,<velocity>` pair is in the **physical units declared by the
  profile's `AxisSpec`** (e.g. degrees and deg/s for a slew axis). Any decimal precision
  is accepted; two decimals are plenty.
- A `T` line need not carry every axis. Host behavior: axes missing from a line keep
  their previous values; unknown axis ids are ignored.

## 4. Watchdogs, staleness, session end

- **Firmware watchdog (mandatory):** if no valid `D` line has arrived within **250 ms**,
  the firmware must force all its outputs to zero (stop all motion) until valid `D`
  lines resume. This covers unplugged cables, host crashes, and wedged UARTs. It is the
  only safety logic required firmware-side.
- **Host staleness:** the host tracks the time since the last valid `T` line
  (`SerialCraneDriver.millisSinceLastTelemetry()`) so upper layers can raise an alarm on
  stale telemetry. Telemetry loss does not stop the host from sending `D` lines.
- There is no goodbye message. Either side simply stops talking; the watchdogs do the
  rest. Closing and reopening the port starts a fresh `HELLO`/`HI` handshake.

## 5. Byte-level session example

All checksums below are real (computed, not illustrative):

```
host  → HELLO *62
crane → HI KB5 slew,boom,jib,extension,winch *7C
crane → T 00000 slew:0.00,0.00 boom:0.00,0.00 jib:0.00,0.00 extension:0.00,0.00 winch:0.00,0.00 *33
host  → D 00042 slew:0.500 boom:-0.250 winch:0.000 *10
crane → T 00042 slew:12.50,1.20 boom:45.00,0.00 *64
host  → D 00043 slew:0.000 boom:0.000 winch:0.000 *3E
```

## 6. Firmware implementer's checklist

1. UART at 115200 8N1; read bytes into a buffer until `\n`; strip an optional `\r`.
2. Discard (and count) any line longer than 240 chars or containing non-printable bytes.
3. Verify `*XX`: XOR every byte before the `*`, compare case-insensitively. Bad → drop
   line, count it, keep previous demands.
4. On `HELLO`: reply `HI <name> <axis1,axis2,...>` — every time, even mid-session.
5. On `D`: validate the 5-digit sequence and every pair; any demand outside
   [-1.000, +1.000] or malformed number → drop the whole line. Otherwise store demands
   (unknown axes ignored), remember the sequence number, and pet the watchdog.
6. Watchdog: if >250 ms since the last valid `D`, drive all outputs to zero.
7. Send `T <lastDSeq> <axis>:<pos>,<vel> ...` at 10–50 Hz, physical units, `.` decimal
   point, and append the checksum (`sprintf("*%02X", x)`).
8. Never block on TX; if the UART is busy, skip a telemetry frame rather than stall the
   control path.
9. Map demand → actuator strictly proportionally around 0 = stop; the host has already
   done clamping, ramping, and all safety filtering.
