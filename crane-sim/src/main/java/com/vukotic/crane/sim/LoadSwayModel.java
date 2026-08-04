package com.vukotic.crane.sim;

/**
 * Planar damped pendulum modelling the hook load hanging from the boom tip on the
 * winch rope:
 *
 * <pre>
 *   theta'' = -(g/L)*sin(theta) - c*theta' - (aPivot/L)*cos(theta)
 * </pre>
 *
 * where {@code theta} is the rope angle from vertical (rad), {@code L} the rope
 * length (m), {@code c} a linear damping coefficient (1/s) and {@code aPivot} the
 * horizontal acceleration of the suspension point (boom tip, m/s&sup2;), obtained by
 * numerically differentiating the pivot speed handed to {@link #step}.
 *
 * <h2>Simplifications (deliberate — no geometry contract exists)</h2>
 * <ul>
 *   <li>Planar: one swing angle, no separate radial/tangential components.</li>
 *   <li>The pivot speed is a heuristic scalar supplied by the caller
 *       ({@link SimulatedCraneDriver} combines slew/boom/extension velocities);
 *       no true 3D tip kinematics.</li>
 *   <li>Rope length changes do not pump energy in or out (no Coriolis term from
 *       {@code L'}); L is simply re-read every step.</li>
 * </ul>
 *
 * <p><b>Sign convention:</b> the pendulum reference axis points <em>opposite</em>
 * the pivot velocity handed in, so a positive published sway angle means the load
 * is trailing behind the current tip motion. This orientation is chosen so the
 * classic damping correction {@code demand - kP*sway - kD*swayVel}
 * ({@code AntiSwayFilter} in crane-core) damps the pendulum instead of pumping it.
 * Concretely: {@link #step} negates the supplied pivot speed before
 * differentiating it into {@code aPivot}.
 *
 * <p>Integration is semi-implicit Euler with internal substeps capped at
 * {@value #MAX_SUBSTEP_SECONDS} s, stable for any caller {@code dt} and any rope
 * length &ge; the caller-enforced minimum.
 *
 * <p>Not thread-safe; owned and stepped by {@link SimulatedCraneDriver} under its
 * own lock.
 */
final class LoadSwayModel {

    /** Standard gravity, m/s^2. */
    static final double GRAVITY = 9.81;

    /** Default linear damping coefficient c, 1/s (lightly damped rigging). */
    static final double DEFAULT_DAMPING_PER_SECOND = 0.25;

    /** Internal integration substep cap, seconds. */
    private static final double MAX_SUBSTEP_SECONDS = 0.005;

    private final double dampingPerSecond;

    private double angleRad;
    private double angularVelocityRad;

    /** Previous (already sign-flipped) pivot speed, for numeric differentiation. */
    private double lastPivotSpeed;
    private boolean hasLastPivotSpeed;

    LoadSwayModel() {
        this(DEFAULT_DAMPING_PER_SECOND);
    }

    LoadSwayModel(double dampingPerSecond) {
        if (dampingPerSecond < 0) {
            throw new IllegalArgumentException("dampingPerSecond must not be negative");
        }
        this.dampingPerSecond = dampingPerSecond;
    }

    /** Sway angle from vertical, degrees (see class Javadoc for the sign convention). */
    double angleDegrees() {
        return Math.toDegrees(angleRad);
    }

    /** Sway angular velocity, degrees per second. */
    double angularVelocityDegreesPerSecond() {
        return Math.toDegrees(angularVelocityRad);
    }

    /** Injects an instantaneous angular displacement (e.g. a wind gust); used by tests. */
    void displace(double angleDegrees) {
        this.angleRad += Math.toRadians(angleDegrees);
    }

    /** Forgets all pendulum state (load at rest, no excitation memory). */
    void reset() {
        angleRad = 0.0;
        angularVelocityRad = 0.0;
        lastPivotSpeed = 0.0;
        hasLastPivotSpeed = false;
    }

    /**
     * Advances the pendulum by {@code dtSeconds}.
     *
     * @param dtSeconds         time step, seconds (&gt; 0)
     * @param ropeLengthMeters  current rope length L, metres (caller clamps to a
     *                          sane minimum so the math never explodes)
     * @param pivotSpeedMps     heuristic horizontal boom-tip speed, m/s; the model
     *                          differentiates it numerically into {@code aPivot}
     * @param windAccelMps2     horizontal wind acceleration on the load, m/s²,
     *                          positive blowing radially <em>outward</em> (away
     *                          from the mast). A steady wind therefore parks the
     *                          load at {@code atan(aWind / g)} off vertical.
     */
    void step(double dtSeconds, double ropeLengthMeters, double pivotSpeedMps,
              double windAccelMps2) {
        if (dtSeconds <= 0) {
            return;
        }
        // Sign convention (see class Javadoc): pendulum axis opposes tip motion.
        double pivotSpeed = -pivotSpeedMps;
        if (!hasLastPivotSpeed) {
            lastPivotSpeed = pivotSpeed; // first step: no acceleration spike
            hasLastPivotSpeed = true;
        }
        double aPivot = (pivotSpeed - lastPivotSpeed) / dtSeconds;
        lastPivotSpeed = pivotSpeed;

        int substeps = Math.max(1, (int) Math.ceil(dtSeconds / MAX_SUBSTEP_SECONDS));
        double h = dtSeconds / substeps;
        for (int i = 0; i < substeps; i++) {
            // Wind acts on the bob, which is equivalent to accelerating the pivot
            // the other way — hence the opposite sign to the aPivot term.
            double accel = -(GRAVITY / ropeLengthMeters) * Math.sin(angleRad)
                    - dampingPerSecond * angularVelocityRad
                    - (aPivot / ropeLengthMeters) * Math.cos(angleRad)
                    + (windAccelMps2 / ropeLengthMeters) * Math.cos(angleRad);
            // Semi-implicit Euler: update velocity first, then position with it.
            angularVelocityRad += accel * h;
            angleRad += angularVelocityRad * h;
        }
    }
}
