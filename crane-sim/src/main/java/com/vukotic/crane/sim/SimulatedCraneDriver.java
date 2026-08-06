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
 *
 * <h2>Load sway</h2>
 * The hook load is modelled as a planar damped pendulum ({@link LoadSwayModel})
 * hanging from the boom tip on the winch rope. Because profiles carry no true
 * geometry, the pendulum pivot excitation is a documented heuristic: the
 * horizontal tip speed is approximated as
 *
 * <pre>
 *   tipSpeed &asymp; K_SLEW * slewVel(rad/s) * reach + K_EXT * extVel(m/s)
 *              + K_BOOM_ARM * boomVel(rad/s),   reach &asymp; 4 m + extension position
 * </pre>
 *
 * and differentiated numerically into the pivot acceleration. Axes a profile does
 * not declare (e.g. no jib/extension on a compact crane) simply read as 0. The
 * rope length is the {@code winch} axis position clamped to at least
 * {@value #MIN_ROPE_LENGTH_METERS} m, or a fixed
 * {@value #DEFAULT_ROPE_LENGTH_METERS} m when the profile has no {@code winch}
 * axis. The constants are tuned so a full-speed slew start on the demo
 * knuckle-boom profile produces a clearly visible sway of roughly 5&ndash;15&deg;.
 *
 * <p>The sway is published <em>without any contract change</em> as extra entries
 * {@value #LOAD_SWAY_KEY} (deg) / {@value #LOAD_SWAY_VEL_KEY} (deg/s) in the
 * positions/velocities maps of {@link #readState()}; every consumer tolerates
 * unknown keys (state lookups have getOrDefault semantics, UI panels iterate
 * profile axes only).
 */
public final class SimulatedCraneDriver implements CraneDriver {

    /** Extra {@link DriverState#axisPositions()} key: load sway angle, degrees. */
    public static final String LOAD_SWAY_KEY = "loadSway";

    /** Extra {@link DriverState#axisVelocities()} key: load sway angular velocity, deg/s. */
    public static final String LOAD_SWAY_VEL_KEY = "loadSwayVel";

    /** Default first-order actuator time constant, in seconds. */
    public static final double DEFAULT_TIME_CONSTANT_SECONDS = 0.4;

    /** Rope length used when the profile declares no {@code winch} axis, metres. */
    public static final double DEFAULT_ROPE_LENGTH_METERS = 5.0;

    /** Lower clamp on the rope length so the pendulum math never explodes, metres. */
    public static final double MIN_ROPE_LENGTH_METERS = 0.5;

    /** Wind drag per (m/s)² as an acceleration on the load, m/s² — see windAccelAlongBoom(). */
    private static final double WIND_DRAG_COEFFICIENT = 0.0046;

    /** Upper bound on one wall-clock auto-step, guards against scheduling gaps. */
    private static final double MAX_AUTO_STEP_SECONDS = 0.25;

    // Heuristic tip-speed constants (see class Javadoc). Tuned so a full-speed
    // slew start on the demo knuckle-boom profile peaks around 10 deg of sway.
    /** Weight of slew tangential speed (slewVel[rad/s] * reach[m]) in the tip speed. */
    private static final double K_SLEW = 0.6;
    /** Weight of the extension axis velocity (m/s) in the tip speed. */
    private static final double K_EXT = 0.6;
    /** Effective lever arm (m) turning boom angular velocity (rad/s) into tip speed. */
    private static final double K_BOOM_ARM_METERS = 2.0;
    /** Base horizontal reach at zero extension, metres. */
    private static final double BASE_REACH_METERS = 4.0;

    private final double timeConstantSeconds;
    private final boolean wallClockStepping;

    private CraneProfile profile; // null while disconnected
    private final Map<String, Double> demands = new LinkedHashMap<>();
    private final Map<String, Double> positions = new LinkedHashMap<>();
    private final Map<String, Double> velocities = new LinkedHashMap<>();
    private final LoadSwayModel sway = new LoadSwayModel();
    private boolean hasWinchAxis;
    private volatile double windSpeedMps;
    private volatile double windFromDeg;   // meteorological: direction it blows FROM
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
        if (!Double.isFinite(timeConstantSeconds) || timeConstantSeconds <= 0) {
            throw new IllegalArgumentException(
                    "timeConstantSeconds must be a positive finite number, was "
                            + timeConstantSeconds);
        }
        this.timeConstantSeconds = timeConstantSeconds;
        this.wallClockStepping = wallClockStepping;
    }

    /**
     * Sets the ambient wind, which pushes the hanging load off vertical and
     * feeds the sway model.
     *
     * @param speedMps wind speed, m/s (0 = still air)
     * @param fromDeg  meteorological direction the wind blows <em>from</em>,
     *                 degrees clockwise from north (0 = northerly, 90 = easterly)
     */
    public void setWind(double speedMps, double fromDeg) {
        // Non-finite weather is no weather. A NaN wind speed propagates through the
        // pendulum into the published load-sway angle, and from there into the
        // anti-sway filter's correction — one bad number turning an assist into a
        // demand generator.
        this.windSpeedMps = Double.isFinite(speedMps) ? Math.max(0, speedMps) : 0.0;
        this.windFromDeg = Double.isFinite(fromDeg) ? fromDeg : 0.0;
    }

    public double windSpeedMps() {
        return windSpeedMps;
    }

    public double windFromDeg() {
        return windFromDeg;
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
        hasWinchAxis = profile.axisById("winch").isPresent();
        sway.reset();
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

    /**
     * {@inheritDoc}
     *
     * <p>Besides the profile axes, the returned maps carry the extra entries
     * {@value #LOAD_SWAY_KEY} (sway angle, deg) in the positions and
     * {@value #LOAD_SWAY_VEL_KEY} (deg/s) in the velocities — consumers that
     * iterate profile axes only are unaffected.
     */
    @Override
    public synchronized DriverState readState() {
        requireConnected();
        Map<String, Double> outPositions = new LinkedHashMap<>(positions);
        Map<String, Double> outVelocities = new LinkedHashMap<>(velocities);
        outPositions.put(LOAD_SWAY_KEY, sway.angleDegrees());
        outVelocities.put(LOAD_SWAY_VEL_KEY, sway.angularVelocityDegreesPerSecond());
        return new DriverState(outPositions, outVelocities);
    }

    /**
     * Advances the simulation by {@code dtSeconds}: first-order velocity response
     * toward {@code demand * maxVelocity}, position integration, hard stop at the
     * profile position limits (velocity zeroed at the stop), then one load-sway
     * pendulum step excited by the resulting axis motion (see class Javadoc).
     *
     * <p>Tests call this directly for deterministic time; production stepping goes
     * through {@link #sendDemands(Map)} in wall-clock mode. Both paths therefore
     * include the sway dynamics.
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
        stepLoadSway(dtSeconds);
    }

    /**
     * One pendulum step from the heuristic tip motion. Axes the profile does not
     * declare read as 0 through getOrDefault, so compact profiles just work.
     */
    private void stepLoadSway(double dtSeconds) {
        double slewVelRad = Math.toRadians(velocities.getOrDefault("slew", 0.0));
        double boomVelRad = Math.toRadians(velocities.getOrDefault("boom", 0.0));
        double extensionVel = velocities.getOrDefault("extension", 0.0);
        double reachMeters = BASE_REACH_METERS + positions.getOrDefault("extension", 0.0);

        double tipSpeed = K_SLEW * slewVelRad * reachMeters
                + K_EXT * extensionVel
                + K_BOOM_ARM_METERS * boomVelRad;

        double ropeLength = hasWinchAxis
                ? Math.max(MIN_ROPE_LENGTH_METERS, positions.getOrDefault("winch", 0.0))
                : DEFAULT_ROPE_LENGTH_METERS;

        sway.step(dtSeconds, ropeLength, tipSpeed, windAccelAlongBoom());
    }

    /**
     * Component of the wind's push on the load along the boom's radial direction,
     * which is the plane the sway model swings in. Drag goes with the square of
     * wind speed; {@value #WIND_DRAG_COEFFICIENT} is scaled so a stiff 15 m/s
     * breeze parks the load roughly 6° off vertical.
     *
     * <p>The truck's own heading is a visualization concern and is not modelled
     * here — crane work happens with the vehicle parked.
     */
    private double windAccelAlongBoom() {
        double speed = windSpeedMps;
        if (speed <= 0) {
            return 0;
        }
        double from = Math.toRadians(windFromDeg);
        // North is −Z, east is +X; the wind blows opposite its "from" bearing.
        double towardX = -Math.sin(from);
        double towardZ = Math.cos(from);

        double slew = Math.toRadians(positions.getOrDefault("slew", 0.0));
        double boomX = Math.cos(slew);
        double boomZ = -Math.sin(slew);

        double alignment = towardX * boomX + towardZ * boomZ;
        return WIND_DRAG_COEFFICIENT * speed * speed * alignment;
    }

    private void requireConnected() {
        if (profile == null) {
            throw new IllegalStateException("SimulatedCraneDriver is not connected to a profile");
        }
    }
}
