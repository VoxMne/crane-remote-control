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
        // Non-finite numbers slip past every comparison below: NaN makes each of
        // them false, so a NaN limit or ramp rate used to build a perfectly
        // "valid" axis whose limits nothing could ever be outside of.
        requireFinite(id, "minPosition", minPosition);
        requireFinite(id, "maxPosition", maxPosition);
        requireFinite(id, "maxVelocity", maxVelocity);
        requireFinite(id, "commandRampRate", commandRampRate);
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

    private static void requireFinite(String id, String field, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(
                    "axis '%s': %s must be a finite number, was %s".formatted(id, field, value));
        }
    }

    /**
     * Clamps a physical position to this axis' limits. A non-finite position is
     * not a position: it reads as the axis being at its lower limit, which is the
     * conservative answer for a limit check.
     */
    public double clampPosition(double position) {
        if (!Double.isFinite(position)) {
            return minPosition;
        }
        return Math.clamp(position, minPosition, maxPosition);
    }
}
