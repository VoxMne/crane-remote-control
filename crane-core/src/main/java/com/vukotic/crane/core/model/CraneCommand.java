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

    /**
     * All axes neutral, deadman released — the safe default, stamped with the
     * monotonic clock the watchdog measures against.
     *
     * <p>This used to stamp {@link System#currentTimeMillis()}, which the watchdog
     * does not use: a neutral command therefore looked arbitrarily stale or
     * arbitrarily fresh depending on the offset between the two clocks.
     */
    public static CraneCommand neutral(CraneProfile profile) {
        return neutralAt(profile, com.vukotic.crane.core.MonotonicClock.millis());
    }

    /** All axes neutral, deadman released, at an explicit timestamp. */
    public static CraneCommand neutralAt(CraneProfile profile, long timestampMillis) {
        return new CraneCommand(timestampMillis, zeros(profile), false, false, false);
    }

    /**
     * This command with every axis demand zeroed and the deadman released, keeping
     * only {@code estopRequested}. Used wherever motion has to be suppressed while
     * an emergency stop must still get through: a gated driver link, a UI that has
     * stopped proving it is alive, driver mode, a recording on screen.
     *
     * <p>{@code resetRequested} is deliberately dropped. A reset is a deliberate
     * two-handed act by an operator looking at the live machine; it must never ride
     * along inside a command that was synthesised because something was wrong.
     */
    public CraneCommand withMotionSuppressed(CraneProfile profile) {
        return new CraneCommand(timestampMillis, zeros(profile), false, estopRequested, false);
    }

    private static Map<String, Double> zeros(CraneProfile profile) {
        Map<String, Double> zeros = new LinkedHashMap<>();
        profile.axes().forEach(axis -> zeros.put(axis.id(), 0.0));
        return zeros;
    }

    /** Demand for one axis; unknown axes read as neutral. */
    public double demand(String axisId) {
        return axisDemands.getOrDefault(axisId, 0.0);
    }

    /**
     * True when every axis demand is exactly neutral. A non-finite demand is not
     * neutral: it must never be able to satisfy the reset precondition.
     */
    public boolean allNeutral() {
        return axisDemands.values().stream().allMatch(d -> d != null && d == 0.0);
    }
}
