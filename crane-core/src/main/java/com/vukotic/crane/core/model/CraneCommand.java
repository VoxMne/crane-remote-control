package com.vukotic.crane.core.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One operator input sample, as produced by a UI (or later a physical remote).
 * This is the raw, unfiltered request — the safety layer in crane-core decides
 * what actually reaches the driver.
 *
 * @param timestampMillis when the command was produced (watchdog freshness check)
 * @param axisDemands     normalized demand per axis id, each in [-1.0, +1.0]; 0 = neutral
 * @param deadmanHeld     hold-to-run control currently held by the operator
 * @param estopRequested  operator pressed E-STOP (latched by the safety layer)
 * @param resetRequested  operator requested E-STOP reset (honored only when safe)
 */
public record CraneCommand(
        long timestampMillis,
        Map<String, Double> axisDemands,
        boolean deadmanHeld,
        boolean estopRequested,
        boolean resetRequested) {

    public CraneCommand {
        axisDemands = Map.copyOf(axisDemands);
    }

    /** All axes neutral, deadman released — the safe default. */
    public static CraneCommand neutral(CraneProfile profile) {
        Map<String, Double> zeros = new LinkedHashMap<>();
        profile.axes().forEach(axis -> zeros.put(axis.id(), 0.0));
        return new CraneCommand(System.currentTimeMillis(), zeros, false, false, false);
    }

    /** Demand for one axis; unknown axes read as neutral. */
    public double demand(String axisId) {
        return axisDemands.getOrDefault(axisId, 0.0);
    }

    /** True when every axis demand is exactly neutral. */
    public boolean allNeutral() {
        return axisDemands.values().stream().allMatch(d -> d == 0.0);
    }
}
