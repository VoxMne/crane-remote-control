package com.vukotic.crane.core.assist;

import com.vukotic.crane.core.control.DemandFilter;
import com.vukotic.crane.core.model.CraneState;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Damping feedback that corrects the {@code slew} demand against the measured
 * load sway: {@code corrected = requested - kP*sway - kD*swayVel}. The simulator
 * publishes sway as the extra state entries {@code "loadSway"} (deg, in the
 * positions map) and {@code "loadSwayVel"} (deg/s, in the velocities map); the
 * sim's sway sign convention is chosen so exactly this correction damps the
 * pendulum. On a state without those entries (a real crane with no sway sensor)
 * both read as 0 and the filter passes demands through unchanged.
 *
 * <p>A small deadband keeps the crane still when the operator is neutral and
 * only negligible residual sway remains — the assist must never turn into a
 * perpetual crawl.
 *
 * <p>Owned by the control-loop thread. The safety layer runs after this filter,
 * so the correction can never bypass E-STOP/deadman/limits.
 */
public final class AntiSwayFilter implements DemandFilter {

    /** State key for the sway angle (degrees) in the positions map. */
    public static final String SWAY_KEY = "loadSway";

    /** State key for the sway angular velocity (deg/s) in the velocities map. */
    public static final String SWAY_VEL_KEY = "loadSwayVel";

    /** Default proportional gain, demand per degree of sway. */
    public static final double DEFAULT_KP = 0.04;

    /** Default derivative gain, demand per deg/s of sway velocity. */
    public static final double DEFAULT_KD = 0.012;

    private static final String SLEW_AXIS = "slew";
    private static final double NEUTRAL_SWAY_DEADBAND_DEG = 0.5;
    private static final double NEUTRAL_SWAY_VEL_DEADBAND = 1.0;

    private final double kP;
    private final double kD;

    public AntiSwayFilter() {
        this(DEFAULT_KP, DEFAULT_KD);
    }

    public AntiSwayFilter(double kP, double kD) {
        if (kP < 0 || kD < 0) {
            throw new IllegalArgumentException("gains must not be negative");
        }
        this.kP = kP;
        this.kD = kD;
    }

    @Override
    public Map<String, Double> apply(Map<String, Double> demands, CraneState lastState,
                                     double dtSeconds) {
        if (lastState == null || !demands.containsKey(SLEW_AXIS)) {
            return demands;
        }
        double sway = lastState.position(SWAY_KEY);
        double swayVel = lastState.velocity(SWAY_VEL_KEY);
        if (sway == 0.0 && swayVel == 0.0) {
            return demands; // no sway data (or a perfectly still load): nothing to do
        }

        double requested = demands.getOrDefault(SLEW_AXIS, 0.0);
        double corrected;
        if (requested == 0.0
                && Math.abs(sway) < NEUTRAL_SWAY_DEADBAND_DEG
                && Math.abs(swayVel) < NEUTRAL_SWAY_VEL_DEADBAND) {
            corrected = 0.0;
        } else {
            corrected = Math.clamp(requested - kP * sway - kD * swayVel, -1.0, 1.0);
        }

        Map<String, Double> out = new LinkedHashMap<>(demands);
        out.put(SLEW_AXIS, corrected);
        return out;
    }
}
