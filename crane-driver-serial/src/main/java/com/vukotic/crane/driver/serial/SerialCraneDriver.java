package com.vukotic.crane.driver.serial;

import com.vukotic.crane.core.driver.CraneDriver;
import com.vukotic.crane.core.driver.DriverState;
import com.vukotic.crane.core.model.AxisSpec;
import com.vukotic.crane.core.model.CraneProfile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link CraneDriver} speaking the Crane Serial Protocol v1 (docs/PROTOCOL.md) over a
 * {@link SerialLink}. {@code connect} performs the {@code HELLO}/{@code HI} handshake
 * and verifies the crane covers every profile axis; a background reader thread then
 * parses {@code T} telemetry lines into the latest {@link DriverState} while
 * {@link #sendDemands} writes one checksummed {@code D} line per control tick.
 *
 * <p>Robustness rules straight from the protocol: corrupted lines are dropped and
 * counted, telemetry axes missing from a line keep their previous values, axes the
 * profile does not declare are ignored. Telemetry loss never blocks the demand path —
 * {@link #readState()} simply keeps returning the last known state while
 * {@link #millisSinceLastTelemetry()} grows, so upper layers can alarm on staleness.
 */
public final class SerialCraneDriver implements CraneDriver {

    /** Reply window for one HELLO attempt, ms. */
    public static final long HELLO_TIMEOUT_MILLIS = 1_000;

    /** Number of HELLO attempts before connect gives up. */
    public static final int HELLO_ATTEMPTS = 3;

    private static final long READ_SLICE_MILLIS = 200;

    private final SerialLink link;
    private final String label;

    private volatile CraneProfile profile;
    private volatile DriverState latest;
    private final AtomicLong lastTelemetryNanos = new AtomicLong(-1);
    private final AtomicLong droppedLines = new AtomicLong();
    private final AtomicInteger sequence = new AtomicInteger();
    private Thread reader;

    /** Production constructor: a real COM port, e.g. {@code new SerialCraneDriver("COM4")}. */
    public SerialCraneDriver(String portName) {
        this(new JSerialCommLink(portName), "Serial (" + portName + ")");
    }

    /** Seam constructor: any transport (tests use an in-memory fake). */
    public SerialCraneDriver(SerialLink link, String label) {
        this.link = Objects.requireNonNull(link, "link");
        this.label = Objects.requireNonNull(label, "label");
    }

    @Override
    public String name() {
        return label;
    }

    @Override
    public synchronized void connect(CraneProfile newProfile) {
        Objects.requireNonNull(newProfile, "profile");
        if (isConnected()) {
            return;
        }
        link.open();
        try {
            CspCodec.Hi hi = handshake();
            List<String> missing = newProfile.axisIds().stream()
                    .filter(id -> !hi.axisIds().contains(id))
                    .toList();
            if (!missing.isEmpty()) {
                throw new SerialLinkException(
                        "crane '%s' does not provide required axes %s (offers %s)"
                                .formatted(hi.craneName(), missing, hi.axisIds()));
            }
        } catch (RuntimeException e) {
            link.close();
            throw e;
        }

        Map<String, Double> zeros = new LinkedHashMap<>();
        for (AxisSpec axis : newProfile.axes()) {
            zeros.put(axis.id(), axis.clampPosition(0.0));
        }
        latest = new DriverState(zeros, zeroVelocities(newProfile));
        lastTelemetryNanos.set(-1);
        droppedLines.set(0);
        profile = newProfile;

        reader = new Thread(this::readLoop, "csp-reader");
        reader.setDaemon(true);
        reader.start();
    }

    @Override
    public synchronized void disconnect() {
        profile = null;
        if (reader != null) {
            reader.interrupt();
            try {
                reader.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            reader = null;
        }
        link.close();
    }

    @Override
    public boolean isConnected() {
        return profile != null && link.isOpen();
    }

    @Override
    public void sendDemands(Map<String, Double> axisDemands) {
        requireConnected();
        link.writeLine(CspCodec.encodeDemands(sequence.getAndIncrement(), axisDemands));
    }

    @Override
    public DriverState readState() {
        requireConnected();
        return latest;
    }

    /** Milliseconds since the last valid telemetry line; {@link Long#MAX_VALUE} if none yet. */
    public long millisSinceLastTelemetry() {
        long stamp = lastTelemetryNanos.get();
        return stamp < 0 ? Long.MAX_VALUE : (System.nanoTime() - stamp) / 1_000_000L;
    }

    /** Lines that failed framing/checksum/validation since connect (diagnostics). */
    public long droppedLineCount() {
        return droppedLines.get();
    }

    /** Package-private for tests: the telemetry reader thread, or null. */
    Thread readerThread() {
        return reader;
    }

    // ------------------------------------------------------------------------ internals

    private CspCodec.Hi handshake() {
        for (int attempt = 0; attempt < HELLO_ATTEMPTS; attempt++) {
            link.writeLine(CspCodec.encodeHello());
            long deadline = System.nanoTime() + HELLO_TIMEOUT_MILLIS * 1_000_000L;
            while (System.nanoTime() < deadline) {
                String line = link.readLine(READ_SLICE_MILLIS);
                if (line == null) {
                    continue;
                }
                Optional<CspCodec.Hi> hi = CspCodec.parseHi(line);
                if (hi.isPresent()) {
                    return hi.get();
                }
                // Pre-session telemetry or noise: not an error, just not a HI.
            }
        }
        throw new SerialLinkException(
                "no HI reply after %d HELLO attempts on %s".formatted(HELLO_ATTEMPTS, label));
    }

    private void readLoop() {
        while (!Thread.currentThread().isInterrupted() && link.isOpen()) {
            String line;
            try {
                line = link.readLine(READ_SLICE_MILLIS);
            } catch (SerialLinkException e) {
                return; // port died or was closed underneath us; disconnect() cleans up
            }
            if (line == null || line.isEmpty()) {
                continue;
            }
            Optional<CspCodec.Telemetry> telemetry = CspCodec.parseTelemetry(line);
            if (telemetry.isPresent()) {
                applyTelemetry(telemetry.get());
                lastTelemetryNanos.set(System.nanoTime());
            } else if (CspCodec.parseHi(line).isEmpty()) {
                droppedLines.incrementAndGet(); // corrupt/unknown; repeated HIs are benign
            }
        }
    }

    /** Merges one telemetry sample: missing axes keep previous, unknown axes ignored. */
    private void applyTelemetry(CspCodec.Telemetry telemetry) {
        CraneProfile activeProfile = profile;
        DriverState previous = latest;
        if (activeProfile == null || previous == null) {
            return;
        }
        Map<String, Double> positions = new LinkedHashMap<>(previous.axisPositions());
        Map<String, Double> velocities = new LinkedHashMap<>(previous.axisVelocities());
        for (Map.Entry<String, CspCodec.AxisTelemetry> entry : telemetry.axes().entrySet()) {
            if (activeProfile.axisById(entry.getKey()).isPresent()) {
                positions.put(entry.getKey(), entry.getValue().position());
                velocities.put(entry.getKey(), entry.getValue().velocity());
            }
        }
        latest = new DriverState(positions, velocities);
    }

    private static Map<String, Double> zeroVelocities(CraneProfile profile) {
        Map<String, Double> zeros = new LinkedHashMap<>();
        profile.axes().forEach(axis -> zeros.put(axis.id(), 0.0));
        return zeros;
    }

    private void requireConnected() {
        if (!isConnected()) {
            throw new IllegalStateException(label + " is not connected");
        }
    }
}
