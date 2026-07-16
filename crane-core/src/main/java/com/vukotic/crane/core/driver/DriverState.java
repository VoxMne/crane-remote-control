package com.vukotic.crane.core.driver;

import java.util.Map;

/**
 * Raw physical feedback from a crane back-end: positions and velocities per axis id,
 * in the units declared by the profile's {@code AxisSpec}. Safety flags are not part
 * of this — they belong to the core safety layer, not to drivers.
 */
public record DriverState(Map<String, Double> axisPositions, Map<String, Double> axisVelocities) {

    public DriverState {
        axisPositions = Map.copyOf(axisPositions);
        axisVelocities = Map.copyOf(axisVelocities);
    }
}
