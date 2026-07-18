package com.vukotic.crane.core.assist;

import com.vukotic.crane.core.control.DemandFilter;
import com.vukotic.crane.core.model.CraneState;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Jerk-limited (S-curve) demand shaping: the output follows the requested demand,
 * but the <em>acceleration</em> of the normalized demand is capped at
 * {@code maxDemandAccel} (1/s²). Combined with the safety layer's per-axis ramp
 * (velocity) limit this yields crane-operator-grade gentle starts and stops.
 *
 * <p>Stateful per axis; owned by the control-loop thread, not thread-safe.
 * Safety behavior is unaffected: the {@code SafetyController} runs after this
 * filter, so E-STOP still zeroes instantly and deadman semantics are untouched.
 */
public final class MotionSmoothingFilter implements DemandFilter {

    /** Default cap on demand acceleration, 1/s² (full stop → full speed S-curve ≈ 0.7 s). */
    public static final double DEFAULT_MAX_DEMAND_ACCEL = 8.0;

    private final double maxDemandAccel;

    /** Per axis id: [lastOutput, lastRate]. */
    private final Map<String, double[]> axisState = new HashMap<>();

    public MotionSmoothingFilter() {
        this(DEFAULT_MAX_DEMAND_ACCEL);
    }

    public MotionSmoothingFilter(double maxDemandAccel) {
        if (maxDemandAccel <= 0) {
            throw new IllegalArgumentException("maxDemandAccel must be positive");
        }
        this.maxDemandAccel = maxDemandAccel;
    }

    @Override
    public Map<String, Double> apply(Map<String, Double> demands, CraneState lastState,
                                     double dtSeconds) {
        if (dtSeconds <= 0) {
            return demands;
        }
        Map<String, Double> out = new LinkedHashMap<>(demands);
        for (Map.Entry<String, Double> entry : demands.entrySet()) {
            double[] state = axisState.computeIfAbsent(entry.getKey(), k -> new double[2]);
            double target = Math.clamp(entry.getValue(), -1.0, 1.0);

            // Trapezoidal profile: approach speed additionally capped by the
            // braking distance sqrt(2*A*|err|) so the output never overshoots.
            double error = target - state[0];
            double desiredRate = Math.signum(error) * Math.min(
                    Math.abs(error) / dtSeconds,
                    Math.sqrt(2.0 * maxDemandAccel * Math.abs(error)));
            double rate = Math.clamp(desiredRate,
                    state[1] - maxDemandAccel * dtSeconds,
                    state[1] + maxDemandAccel * dtSeconds);
            double output = Math.clamp(state[0] + rate * dtSeconds, -1.0, 1.0);
            // Terminal snap: a discrete step that would cross the target lands
            // exactly on it and kills the residual rate (prevents overshoot and
            // post-arrival drift; the safety ramp downstream stays smooth anyway).
            if (error * (target - output) < 0.0) {
                output = target;
                state[1] = 0.0;
            } else {
                state[1] = (output - state[0]) / dtSeconds;
            }
            state[0] = output;
            out.put(entry.getKey(), output);
        }
        return out;
    }
}
