package com.vukotic.crane.core.safety;

import java.util.List;
import java.util.Map;

/**
 * Result of one {@link SafetyController#filter} pass: the demands that may actually
 * reach the driver, plus the safety status the control loop publishes in
 * {@code CraneState}.
 *
 * @param filteredDemands  safety-filtered demand per axis id, each in [-1.0, +1.0];
 *                         contains an entry for every axis of the profile
 * @param estopLatched     E-STOP latch is currently engaged
 * @param deadmanEffective the deadman as seen by the safety layer: raw deadman held
 *                         AND the watchdog has not tripped
 * @param watchdogTripped  the last command was older than the watchdog timeout
 * @param activeAlarms     human-readable alarm strings active this tick
 */
public record SafetyOutput(
        Map<String, Double> filteredDemands,
        boolean estopLatched,
        boolean deadmanEffective,
        boolean watchdogTripped,
        List<String> activeAlarms) {

    public SafetyOutput {
        filteredDemands = Map.copyOf(filteredDemands);
        activeAlarms = List.copyOf(activeAlarms);
    }

    /** Filtered demand for one axis; unknown axes read as neutral. */
    public double demand(String axisId) {
        return filteredDemands.getOrDefault(axisId, 0.0);
    }
}
