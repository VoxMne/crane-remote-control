package com.vukotic.crane.sim;

import com.vukotic.crane.core.driver.CraneDriver;
import com.vukotic.crane.core.driver.DriverState;
import com.vukotic.crane.core.model.AxisSpec;
import com.vukotic.crane.core.model.CraneProfile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Simulated crane back-end with per-axis first-order actuator dynamics:
 * each axis velocity approaches {@code demand * maxVelocity} with time constant
 * {@code tau} (default {@value #DEFAULT_TIME_CONSTANT_SECONDS} s), the position
 * integrates the velocity, and the position hard-stops at the profile limits
 * (velocity zeroed at the stop).
 *
 * <h2>How simulated time advances</h2>
 * The sim only moves when it is stepped:
 * <ul>
 *   <li><b>Production (wall-clock mode, the default constructors):</b> every
 *       {@link #sendDemands(Map)} call — i.e. every control-loop tick — first
 *       advances the simulation by the real time elapsed since the previous
 *       {@code sendDemands}, then stores the new demands. The control loop drives
 *       the sim purely through the {@link CraneDriver} port; no extra thread and
 *       no sim-specific wiring in crane-core.</li>
 *   <li><b>Tests (manual mode, {@link #manuallyStepped()}):</b> wall-clock
 *       stepping is disabled and the test advances simulated time explicitly and
 *       deterministically with {@link #step(double)}.</li>
 * </ul>
 */
public final class SimulatedCraneDriver implements CraneDriver {

    /** Default first-order actuator time constant, in seconds. */
    public static final double DEFAULT_TIME_CONSTANT_SECONDS = 0.4;

    /** Upper bound on one wall-clock auto-step, guards against scheduling gaps. */
    private static final double MAX_AUTO_STEP_SECONDS = 0.25;

    private final double timeConstantSeconds;
    private final boolean wallClockStepping;

    private CraneProfile profile; // null while disconnected
    private final Map<String, Double> demands = new LinkedHashMap<>();
    private final Map<String, Double> positions = new LinkedHashMap<>();
    private final Map<String, Double> velocities = new LinkedHashMap<>();
    private long lastAutoStepNanos = -1;

    /** Wall-clock-stepped sim with the default time constant. */
    public SimulatedCraneDriver() {
        this(DEFAULT_TIME_CONSTANT_SECONDS, true);
    }

    /** Wall-clock-stepped sim with a custom actuator time constant (seconds). */
    public SimulatedCraneDriver(double timeConstantSeconds) {
        this(timeConstantSeconds, true);
    }

    private SimulatedCraneDriver(double timeConstantSeconds, boolean wallClockStepping) {
        if (timeConstantSeconds <= 0) {
            throw new IllegalArgumentException("timeConstantSeconds must be positive");
        }
        this.timeConstantSeconds = timeConstantSeconds;
        this.wallClockStepping = wallClockStepping;
    }

    /** Deterministic sim for tests: advances only via explicit {@link #step(double)}. */
    public static SimulatedCraneDriver manuallyStepped() {
        return new SimulatedCraneDriver(DEFAULT_TIME_CONSTANT_SECONDS, false);
    }

    /** Deterministic sim for tests, with a custom actuator time constant (seconds). */
    public static SimulatedCraneDriver manuallyStepped(double timeConstantSeconds) {
        return new SimulatedCraneDriver(timeConstantSeconds, false);
    }

    @Override
    public String name() {
        return "Simulator";
    }

    public double timeConstantSeconds() {
        return timeConstantSeconds;
    }

    @Override
    public synchronized void connect(CraneProfile profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
        demands.clear();
        positions.clear();
        velocities.clear();
        for (AxisSpec axis : profile.axes()) {
            demands.put(axis.id(), 0.0);
            positions.put(axis.id(), axis.clampPosition(0.0));
            velocities.put(axis.id(), 0.0);
        }
        lastAutoStepNanos = -1;
    }

    @Override
    public synchronized void disconnect() {
        profile = null;
        demands.clear();
        positions.clear();
        velocities.clear();
    }

    @Override
    public synchronized boolean isConnected() {
        return profile != null;
    }

    /**
     * Stores the (already safety-filtered) demands. In wall-clock mode this call
     * also advances the simulation by the real time elapsed since the previous
     * call, so the crane keeps moving on the old demands up to this instant and
     * the new demands take effect from now on.
     */
    @Override
    public synchronized void sendDemands(Map<String, Double> axisDemands) {
        requireConnected();
        Objects.requireNonNull(axisDemands, "axisDemands");
        if (wallClockStepping) {
            long nowNanos = System.nanoTime();
            if (lastAutoStepNanos >= 0) {
                double elapsed = (nowNanos - lastAutoStepNanos) / 1_000_000_000.0;
                step(Math.clamp(elapsed, 0.0, MAX_AUTO_STEP_SECONDS));
            }
            lastAutoStepNanos = nowNanos;
        }
        for (AxisSpec axis : profile.axes()) {
            double demand = axisDemands.getOrDefault(axis.id(), 0.0);
            demands.put(axis.id(), Math.clamp(demand, -1.0, 1.0));
        }
    }

    @Override
    public synchronized DriverState readState() {
        requireConnected();
        return new DriverState(new LinkedHashMap<>(positions), new LinkedHashMap<>(velocities));
    }

    /**
     * Advances the simulation by {@code dtSeconds}: first-order velocity response
     * toward {@code demand * maxVelocity}, position integration, hard stop at the
     * profile position limits (velocity zeroed at the stop).
     *
     * <p>Tests call this directly for deterministic time; production stepping goes
     * through {@link #sendDemands(Map)} in wall-clock mode.
     */
    public synchronized void step(double dtSeconds) {
        requireConnected();
        if (dtSeconds < 0) {
            throw new IllegalArgumentException("dtSeconds must not be negative");
        }
        if (dtSeconds == 0) {
            return;
        }
        // Exact discretization of dv/dt = (target - v) / tau: stable for any dt.
        double alpha = 1.0 - Math.exp(-dtSeconds / timeConstantSeconds);
        for (AxisSpec axis : profile.axes()) {
            String id = axis.id();
            double targetVelocity = demands.get(id) * axis.maxVelocity();
            double velocity = velocities.get(id) + (targetVelocity - velocities.get(id)) * alpha;
            double position = positions.get(id) + velocity * dtSeconds;

            if (position >= axis.maxPosition()) {
                position = axis.maxPosition();
                if (velocity > 0) {
                    velocity = 0.0;
                }
            } else if (position <= axis.minPosition()) {
                position = axis.minPosition();
                if (velocity < 0) {
                    velocity = 0.0;
                }
            }
            velocities.put(id, velocity);
            positions.put(id, position);
        }
    }

    private void requireConnected() {
        if (profile == null) {
            throw new IllegalStateException("SimulatedCraneDriver is not connected to a profile");
        }
    }
}
