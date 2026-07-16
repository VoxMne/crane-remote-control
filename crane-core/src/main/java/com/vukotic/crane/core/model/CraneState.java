package com.vukotic.crane.core.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Snapshot of the whole system, published by the control loop every tick.
 * Positions/velocities are in physical units (see {@link AxisSpec#unit()}).
 */
public record CraneState(
        long timestampMillis,
        Map<String, Double> axisPositions,
        Map<String, Double> axisVelocities,
        boolean estopLatched,
        boolean deadmanHeld,
        boolean watchdogTripped,
        List<String> activeAlarms) {

    public CraneState {
        axisPositions = Map.copyOf(axisPositions);
        axisVelocities = Map.copyOf(axisVelocities);
        activeAlarms = List.copyOf(activeAlarms);
    }

    /** Parked state: every axis at rest at (0 clamped into its limits), no faults. */
    public static CraneState initial(CraneProfile profile) {
        Map<String, Double> positions = new LinkedHashMap<>();
        Map<String, Double> velocities = new LinkedHashMap<>();
        for (AxisSpec axis : profile.axes()) {
            positions.put(axis.id(), axis.clampPosition(0.0));
            velocities.put(axis.id(), 0.0);
        }
        return new CraneState(System.currentTimeMillis(), positions, velocities,
                false, false, false, List.of());
    }

    public double position(String axisId) {
        return axisPositions.getOrDefault(axisId, 0.0);
    }

    public double velocity(String axisId) {
        return axisVelocities.getOrDefault(axisId, 0.0);
    }
}
