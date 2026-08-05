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

    /**
     * Telemetry older than this is treated as no telemetry at all. Matches the
     * firmware watchdog in docs/PROTOCOL.md, so both ends give up together.
     */
    public static final long TELEMETRY_TIMEOUT_MILLIS = 250;

    /** Alarm text while the link is gated for want of usable position feedback. */
    static final String TELEMETRY_FAULT =
            "no usable position feedback — demands held at zero";

    private final java.util.concurrent.atomic.AtomicBoolean telemetryLost =
            new java.util.concurrent.atomic.AtomicBoolean();

    private final SerialLink link;
    private final String label;

    private volatile CraneProfile profile;
    /**
     * Every axis the crane declared in its HI, profile axes first. Demands are
     * written for all of them so an axis this profile does not drive cannot sit
     * holding an old value — see {@link #sendDemands}.
     */
    private volatile List<String> craneAxisIds = List.of();
    /** Null until the crane has reported a complete position set — never fabricated. */
    private volatile DriverState latest;
    private final AtomicLong lastTelemetryNanos = new AtomicLong(-1);
    private final AtomicLong droppedLines = new AtomicLong();
    private final AtomicInteger sequence = new AtomicInteger();
    /** Last accepted telemetry sequence, for the round-trip liveness check. */
    private final AtomicInteger lastSequence = new AtomicInteger(-1);
    /** A runtime fault the operator must see, or null while healthy. */
    private final java.util.concurrent.atomic.AtomicReference<String> fault =
            new java.util.concurrent.atomic.AtomicReference<>();
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
        List<String> declaredAxes;
        try {
            CspCodec.Hi hi = handshake();
            verifyProfileAgainstCrane(newProfile, hi);
            // Profile axes first so the wire order stays familiar, then anything
            // else the crane offers, so every one of them gets an explicit zero.
            List<String> ordered = new java.util.ArrayList<>(newProfile.axisIds());
            hi.axisIds().stream().filter(id -> !ordered.contains(id)).forEach(ordered::add);
            declaredAxes = List.copyOf(ordered);
        } catch (RuntimeException e) {
            link.close();
            throw e;
        }
        craneAxisIds = declaredAxes;

        // No fabricated positions. Until the crane has told us where it is, there
        // is no state to read and no motion is permitted; pretending every axis is
        // at zero was a lie the position limits would have been enforced against.
        latest = null;
        lastTelemetryNanos.set(-1);
        lastSequence.set(-1);
        droppedLines.set(0);
        fault.set(null);
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

    /**
     * {@inheritDoc}
     *
     * <p><b>Fails closed.</b> Nonzero demands are only transmitted while fresh
     * telemetry is arriving. Before the first {@code T} line, or once telemetry
     * is older than {@value #TELEMETRY_TIMEOUT_MILLIS} ms, zeros go out instead:
     * without position feedback the host cannot know where the crane is, so the
     * safety layer's position limits would be operating on fiction. A one-way
     * link must not be able to keep a machine moving.
     */
    @Override
    public void sendDemands(Map<String, Double> axisDemands) {
        requireConnected();
        boolean fresh = isTelemetryFresh();
        telemetryLost.set(!fresh);

        // Every axis the CRANE declared, every line — not just the profile's.
        // CSP says an omitted axis keeps its previous demand, and the firmware
        // watchdog is petted by any valid D line, so a crane with axes this profile
        // does not drive would hold them at whatever they were last told for as
        // long as the session lasts. Switching from a five-axis profile to a
        // three-axis one could leave jib and extension running if the final zero
        // was the line that got corrupted.
        Map<String, Double> outgoing = new LinkedHashMap<>();
        for (String axisId : craneAxisIds) {
            double demand = axisDemands.getOrDefault(axisId, 0.0);
            outgoing.put(axisId, fresh && Double.isFinite(demand) ? demand : 0.0);
        }
        try {
            link.writeLine(CspCodec.encodeDemands(sequence.getAndIncrement(), outgoing));
            if (fresh) {
                fault.compareAndSet(TELEMETRY_FAULT, null);
            } else {
                fault.compareAndSet(null, TELEMETRY_FAULT);
            }
        } catch (SerialLinkException e) {
            // Recorded, NOT rethrown. Rethrowing aborted the tick before the loop
            // could publish the fault, so a dead port froze the HMI on its last
            // healthy frame — the operator watching a still picture of a crane
            // they had lost contact with. It also aborted profile switching during
            // teardown, stranding the session. The alarm list is the right channel;
            // the loop keeps running and keeps reporting.
            fault.set("link failed: " + e.getMessage());
        }
    }

    /** True while position feedback is recent enough to command motion against. */
    public boolean isTelemetryFresh() {
        return latest != null && millisSinceLastTelemetry() <= TELEMETRY_TIMEOUT_MILLIS;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Motion is permitted only with a complete, fresh, advancing telemetry
     * stream. Answering here rather than only substituting zeros on the wire is
     * what keeps the core's ramp limiter parked while the link is gated — see
     * {@link CraneDriver#acceptsMotion()}.
     */
    @Override
    public boolean acceptsMotion() {
        return isTelemetryFresh();
    }

    @Override
    public Optional<String> fault() {
        return Optional.ofNullable(fault.get());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Before the first complete telemetry frame there is no measured state to
     * report, so every axis reads as its profile-clamped zero <em>and</em>
     * {@link #acceptsMotion()} is false. The pair matters: the placeholder exists
     * so the UI has something to draw, never so that motion can be commanded
     * against it.
     */
    @Override
    public DriverState readState() {
        requireConnected();
        DriverState state = latest;
        if (state != null) {
            return state;
        }
        CraneProfile activeProfile = profile;
        Map<String, Double> unknown = new LinkedHashMap<>();
        for (AxisSpec axis : activeProfile.axes()) {
            unknown.put(axis.id(), axis.clampPosition(0.0));
        }
        return new DriverState(unknown, zeroVelocities(activeProfile));
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

    /**
     * Checks that the loaded profile actually describes the crane that answered.
     *
     * <p>Matching axis names is not enough. CSP makes the <em>host</em> responsible
     * for position limits, and the bundled Demo and Heavy profiles expose the same
     * five axis ids with very different travel — so a name-only handshake would let
     * the Heavy profile drive a small crane 12 m of extension past its stops. A
     * crane must therefore declare its travel (CSP/1.1) and the profile must fit
     * inside it.
     */
    private static void verifyProfileAgainstCrane(CraneProfile newProfile, CspCodec.Hi hi) {
        List<String> missing = newProfile.axisIds().stream()
                .filter(id -> !hi.axisIds().contains(id))
                .toList();
        if (!missing.isEmpty()) {
            throw new SerialLinkException(
                    "crane '%s' does not provide required axes %s (offers %s)"
                            .formatted(hi.craneName(), missing, hi.axisIds()));
        }
        if (!hi.declaresLimits()) {
            throw new SerialLinkException(
                    ("crane '%s' answered CSP/1.0 without declaring its travel. This host "
                            + "enforces the position limits, so it cannot verify that profile "
                            + "'%s' describes the machine on the other end — refusing to "
                            + "connect rather than command a crane it cannot measure.")
                            .formatted(hi.craneName(), newProfile.id()));
        }
        for (AxisSpec axis : newProfile.axes()) {
            CspCodec.AxisLimits declared = hi.limits().get(axis.id());
            if (declared == null || !declared.contains(axis.minPosition(), axis.maxPosition())) {
                throw new SerialLinkException(
                        ("profile '%s' axis '%s' spans %.2f..%.2f %s, outside the %s the crane "
                                + "'%s' declares — wrong profile for this machine")
                                .formatted(newProfile.id(), axis.id(),
                                        axis.minPosition(), axis.maxPosition(), axis.unit(),
                                        declared == null ? "(nothing)"
                                                : "%.2f..%.2f".formatted(
                                                        declared.minPosition(),
                                                        declared.maxPosition()),
                                        hi.craneName()));
            }
        }
    }

    private void readLoop() {
        while (!Thread.currentThread().isInterrupted() && link.isOpen()) {
            String line;
            try {
                line = link.readLine(READ_SLICE_MILLIS);
            } catch (SerialLinkException e) {
                // The port died or was pulled. This has to reach the operator: the
                // reader simply returning left the link silently dead, alarming only
                // as a telemetry timeout with no idea why.
                fault.set("link failed: " + e.getMessage());
                return;
            }
            if (line == null || line.isEmpty()) {
                continue;
            }
            Optional<CspCodec.Telemetry> telemetry = CspCodec.parseTelemetry(line);
            if (telemetry.isPresent()) {
                if (applyTelemetry(telemetry.get())) {
                    lastTelemetryNanos.set(System.nanoTime());
                } else {
                    droppedLines.incrementAndGet();
                }
            } else if (CspCodec.parseHi(line).isEmpty()) {
                droppedLines.incrementAndGet(); // corrupt/unknown; repeated HIs are benign
            }
        }
    }

    /**
     * Accepts one telemetry sample, or rejects it.
     *
     * <p>Freshness used to be a single timestamp refreshed by <em>any</em> parseable
     * {@code T} line — including one carrying a single axis, only axes the profile
     * has never heard of, no axes at all, or an old frame replayed forever. Motion
     * was then permitted while the position limits were being enforced against
     * positions that were stale or had never been measured.
     *
     * <p>A frame now counts only if it carries every profile axis with a finite
     * sample and its sequence has advanced. CSP defines the echoed sequence as the
     * round-trip liveness signal, and it was never being checked.
     *
     * @return true when the frame was accepted as fresh position feedback
     */
    private boolean applyTelemetry(CspCodec.Telemetry telemetry) {
        CraneProfile activeProfile = profile;
        if (activeProfile == null) {
            return false;
        }
        Map<String, Double> positions = new LinkedHashMap<>();
        Map<String, Double> velocities = new LinkedHashMap<>();
        for (AxisSpec axis : activeProfile.axes()) {
            CspCodec.AxisTelemetry sample = telemetry.axes().get(axis.id());
            if (sample == null
                    || !Double.isFinite(sample.position())
                    || !Double.isFinite(sample.velocity())) {
                return false;   // incomplete frame: not position feedback
            }
            positions.put(axis.id(), sample.position());
            velocities.put(axis.id(), sample.velocity());
        }

        // Sequence must advance. Equal or going backwards means a replayed or
        // stuck stream, which looks alive but says nothing about where the crane is.
        int previous = lastSequence.get();
        int current = telemetry.sequence();
        if (previous >= 0 && !advances(previous, current)) {
            return false;
        }
        lastSequence.set(current);
        latest = new DriverState(positions, velocities);
        return true;
    }

    /** Sequence advanced, allowing for the CSP wrap at 100000. */
    private static boolean advances(int previous, int current) {
        int forward = Math.floorMod(current - previous, CspCodec.SEQUENCE_MODULUS);
        return forward > 0 && forward < CspCodec.SEQUENCE_MODULUS / 2;
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
