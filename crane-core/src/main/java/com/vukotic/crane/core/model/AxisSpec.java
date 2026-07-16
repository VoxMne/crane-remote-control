package com.vukotic.crane.core.model;

/**
 * Static description of one controllable crane axis. Physical units (degrees, metres)
 * live here and only here; everywhere else in the stack an axis is driven by a
 * normalized demand in [-1.0, +1.0].
 *
 * @param id              stable identifier used as the key in commands and state (e.g. "slew")
 * @param label           human-readable name for the UI
 * @param unit            unit of position/velocity values (e.g. "deg", "m")
 * @param minPosition     lower position limit, in {@code unit}
 * @param maxPosition     upper position limit, in {@code unit}
 * @param maxVelocity     velocity reached at full demand (+1.0), in {@code unit}/s
 * @param commandRampRate maximum allowed demand change per second (1/s)
 */
public record AxisSpec(
        String id,
        String label,
        String unit,
        double minPosition,
        double maxPosition,
        double maxVelocity,
        double commandRampRate) {

    public AxisSpec {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("axis id must not be blank");
        }
        if (maxPosition <= minPosition) {
            throw new IllegalArgumentException(
                    "axis '%s': maxPosition (%s) must be greater than minPosition (%s)"
                            .formatted(id, maxPosition, minPosition));
        }
        if (maxVelocity <= 0) {
            throw new IllegalArgumentException("axis '%s': maxVelocity must be positive".formatted(id));
        }
        if (commandRampRate <= 0) {
            throw new IllegalArgumentException("axis '%s': commandRampRate must be positive".formatted(id));
        }
    }

    /** Clamps a physical position to this axis' limits. */
    public double clampPosition(double position) {
        return Math.clamp(position, minPosition, maxPosition);
    }
}
