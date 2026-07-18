package com.vukotic.crane.core.assist;

import com.vukotic.crane.core.model.CraneCommand;
import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.model.CraneState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Automated "fold to transport position" sequencer. Drives one axis at a time,
 * in a fixed safety-motivated order, toward a conventional transport pose:
 *
 * <ol>
 *   <li>{@code extension} → fully retracted (min)</li>
 *   <li>{@code winch} → hook fully up (min rope out)</li>
 *   <li>{@code jib} → folded against the boom (max knuckle angle)</li>
 *   <li>{@code boom} → lowered (min)</li>
 *   <li>{@code slew} → centered (0, clamped into limits)</li>
 * </ol>
 *
 * Axes the profile does not declare are skipped. Targets are always clamped into
 * the profile's axis limits, so any profile folds to a valid pose.
 *
 * <p><b>Safety semantics.</b> {@link #next} only ever replaces the demand of the
 * currently active axis in the operator's command; {@code deadmanHeld},
 * {@code estopRequested} and {@code resetRequested} pass through untouched. The
 * sequence therefore only produces motion while the operator physically holds
 * the deadman — releasing it stops the crane via the normal safety layer, and
 * the sequence simply resumes when it is held again. Any manual axis input or an
 * E-STOP request cancels the sequence immediately.
 *
 * <p>Call pattern (UI frame loop): {@code command = sequencer.next(state, command)}
 * between input snapshot and submission. Owned by one thread; not thread-safe.
 */
public final class AutoSequencer {

    /** Fold order: retract, hook up, fold jib, lower boom, center slew. */
    public static final List<String> PHASE_ORDER =
            List.of("extension", "winch", "jib", "boom", "slew");

    /** A phase counts as reached within this distance of the target, in axis units. */
    public static final double DEFAULT_TOLERANCE = 0.5;

    /** Approach demand per unit of position error (saturates at full demand). */
    private static final double APPROACH_GAIN = 0.5;

    private record Phase(String axisId, double target) {
    }

    private final double tolerance;

    private List<Phase> phases = List.of();
    private int phaseIndex;
    private boolean active;
    private boolean complete;

    public AutoSequencer() {
        this(DEFAULT_TOLERANCE);
    }

    public AutoSequencer(double tolerance) {
        if (tolerance <= 0) {
            throw new IllegalArgumentException("tolerance must be positive");
        }
        this.tolerance = tolerance;
    }

    /** Starts (or restarts) the fold sequence for the given profile. */
    public void start(CraneProfile profile) {
        Objects.requireNonNull(profile, "profile");
        List<Phase> built = new ArrayList<>();
        for (String axisId : PHASE_ORDER) {
            profile.axisById(axisId).ifPresent(axis -> built.add(new Phase(axisId,
                    switch (axisId) {
                        case "jib" -> axis.maxPosition();
                        case "slew" -> axis.clampPosition(0.0);
                        default -> axis.minPosition(); // extension, winch, boom
                    })));
        }
        phases = List.copyOf(built);
        phaseIndex = 0;
        active = !phases.isEmpty();
        complete = phases.isEmpty();
    }

    public void cancel() {
        active = false;
    }

    /** True while the sequence is running (not cancelled, not complete). */
    public boolean isActive() {
        return active;
    }

    /** True once a started sequence reached the transport pose. */
    public boolean isComplete() {
        return complete;
    }

    /** Axis currently being driven, or empty string when inactive. */
    public String activeAxis() {
        return active && phaseIndex < phases.size() ? phases.get(phaseIndex).axisId() : "";
    }

    /**
     * Produces the command to submit this frame: the operator command with the
     * active phase's axis demand replaced by a proportional approach demand.
     * Inactive, cancelled or complete: returns the operator command unchanged.
     */
    public CraneCommand next(CraneState current, CraneCommand operator) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(operator, "operator");
        if (!active) {
            return operator;
        }
        // Operator intervention or E-STOP always wins over automation.
        if (operator.estopRequested() || !operator.allNeutral()) {
            active = false;
            return operator;
        }

        while (phaseIndex < phases.size() && reached(current, phases.get(phaseIndex))) {
            phaseIndex++;
        }
        if (phaseIndex >= phases.size()) {
            active = false;
            complete = true;
            return operator;
        }

        Phase phase = phases.get(phaseIndex);
        double error = phase.target() - current.position(phase.axisId());
        double demand = Math.clamp(error * APPROACH_GAIN, -1.0, 1.0);

        Map<String, Double> demands = new LinkedHashMap<>(operator.axisDemands());
        demands.put(phase.axisId(), demand);
        return new CraneCommand(operator.timestampMillis(), demands,
                operator.deadmanHeld(), operator.estopRequested(), operator.resetRequested());
    }

    private boolean reached(CraneState state, Phase phase) {
        return Math.abs(state.position(phase.axisId()) - phase.target()) <= tolerance;
    }
}
