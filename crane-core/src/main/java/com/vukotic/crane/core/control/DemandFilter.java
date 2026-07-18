package com.vukotic.crane.core.control;

import com.vukotic.crane.core.model.CraneState;

import java.util.Map;

/**
 * An assist stage that reshapes raw operator demands before they reach the
 * safety layer. Filters may smooth, damp, or correct motion — but the
 * {@code SafetyController} always runs after them, so no filter can ever
 * bypass E-STOP, deadman, clamping, ramp limits, or position limits.
 *
 * <p>Filters run on the control-loop thread only; implementations may keep
 * per-axis state and need not be thread-safe.
 */
public interface DemandFilter {

    /**
     * @param demands   demand per axis id, normalized [-1, +1] (raw, unfiltered)
     * @param lastState the most recently published state (carries extra entries
     *                  like {@code "loadSway"} through its maps); never null
     * @param dtSeconds time since the previous tick
     * @return the reshaped demands; implementations return a new or updated map,
     *         never null
     */
    Map<String, Double> apply(Map<String, Double> demands, CraneState lastState, double dtSeconds);
}
