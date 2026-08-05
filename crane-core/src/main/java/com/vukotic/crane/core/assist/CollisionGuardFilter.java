package com.vukotic.crane.core.assist;

import com.vukotic.crane.core.control.DemandFilter;
import com.vukotic.crane.core.geometry.Aabb;
import com.vukotic.crane.core.geometry.CraneGeometry;
import com.vukotic.crane.core.geometry.Vec3;
import com.vukotic.crane.core.model.AxisSpec;
import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.model.CraneState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Interference protection: stops an axis before it drives the arm into the
 * machine itself, into the ground, or into a load standing on the deck or the
 * apron. Real cranes carry the same idea under names like interference zone or
 * anti-collision protection.
 *
 * <p>How it works: each tick the filter looks {@value #LOOKAHEAD_SECONDS} s
 * ahead for every axis that is being commanded, rebuilds the arm's shape at that
 * predicted position, and zeroes the demand if the arm would then be within
 * {@value #CLEARANCE_METRES} of an obstacle. Axes are tested one at a time, so
 * a blocked slew never freezes the winch.
 *
 * <p>It only ever <em>removes</em> motion, and it runs before the safety layer,
 * which keeps the final word. Motion that increases clearance is always allowed,
 * so the operator can always drive back out of a tight spot — a guard that
 * latched you into a corner would be worse than no guard at all.
 */
public final class CollisionGuardFilter implements DemandFilter {

    /** How far ahead the guard predicts, in seconds. */
    public static final double LOOKAHEAD_SECONDS = 0.35;

    /** Required clearance between the arm and anything solid, in metres. */
    public static final double CLEARANCE_METRES = 0.15;

    private final CraneProfile profile;
    private final CraneGeometry geometry;
    /**
     * Loads standing in the world, registered by whatever is tracking them.
     *
     * <p>Swapped as one immutable list, not mutated in place. It used to be a
     * {@code CopyOnWriteArrayList} updated with {@code clear()} then
     * {@code addAll()}, so the control thread — reading it 50 times a second —
     * could catch the empty gap between the two and let a tick through with
     * nothing to avoid.
     */
    private volatile List<Aabb> loadObstacles = List.of();

    private volatile boolean blocking;

    public CollisionGuardFilter(CraneProfile profile, CraneGeometry geometry) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.geometry = Objects.requireNonNull(geometry, "geometry");
    }

    /**
     * Replaces the set of loads the arm must avoid, in the crane frame. The
     * caller owns load tracking; the guard only needs their boxes.
     */
    public void setLoadObstacles(List<Aabb> loads) {
        loadObstacles = List.copyOf(loads);
    }

    /** True when the guard is currently holding at least one axis back. */
    public boolean isBlocking() {
        return blocking;
    }

    @Override
    public Map<String, Double> apply(Map<String, Double> demands, CraneState lastState,
                                     double dtSeconds) {
        if (lastState == null) {
            return demands;
        }
        double currentClearance = clearanceAt(lastState, Map.of());
        Map<String, Double> guarded = new LinkedHashMap<>(demands);
        boolean blocked = false;

        // The COMBINED move is checked first. Predicting each axis on its own let a
        // boom+jib command through whose individual predictions both cleared the
        // 0.15 m margin while the pose they produce together left 0.1289 m — the
        // arm arriving somewhere neither axis was asked to take it. Where they
        // conspire, every contributing axis is stopped; nothing else would be
        // sound, since the guard cannot know which one the operator would rather
        // keep.
        Map<String, Double> combined = predictedPositions(demands, lastState);
        if (combined.size() > 1) {
            double together = clearanceAt(lastState, combined);
            if (together < CLEARANCE_METRES && together < currentClearance) {
                combined.keySet().forEach(id -> guarded.put(id, 0.0));
                blocking = true;
                return guarded;
            }
        }

        // Then each axis alone, which still catches a single axis driving in.
        for (Map.Entry<String, Double> entry : demands.entrySet()) {
            double demand = entry.getValue();
            if (demand == 0) {
                continue;
            }
            AxisSpec axis = profile.axisById(entry.getKey()).orElse(null);
            if (axis == null) {
                continue;
            }
            double travel = demand * axis.maxVelocity() * LOOKAHEAD_SECONDS;
            double predicted = axis.clampPosition(lastState.position(axis.id()) + travel);
            double clearance = clearanceAt(lastState, Map.of(axis.id(), predicted));

            // Block only if this move would breach the margin AND makes things
            // worse — otherwise the operator could never back out of a breach.
            if (clearance < CLEARANCE_METRES && clearance < currentClearance) {
                guarded.put(entry.getKey(), 0.0);
                blocked = true;
            }
        }
        blocking = blocked;
        return guarded;
    }

    /**
     * The axes that move the arm through space, and so the only ones that can
     * combine into a breach. The winch pays out rope; it cannot swing the boom
     * into the cab, and stopping it because two other axes conspired would freeze
     * a load in mid-air for no reason.
     */
    private static final java.util.Set<String> ARM_AXES =
            java.util.Set.of("slew", "boom", "jib", "extension");

    /** Where every commanded arm axis would be together, one lookahead from now. */
    private Map<String, Double> predictedPositions(Map<String, Double> demands,
                                                   CraneState lastState) {
        Map<String, Double> predicted = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : demands.entrySet()) {
            double demand = entry.getValue();
            if (demand == 0 || !ARM_AXES.contains(entry.getKey())) {
                continue;
            }
            AxisSpec axis = profile.axisById(entry.getKey()).orElse(null);
            if (axis == null) {
                continue;
            }
            double travel = demand * axis.maxVelocity() * LOOKAHEAD_SECONDS;
            predicted.put(axis.id(),
                    axis.clampPosition(lastState.position(axis.id()) + travel));
        }
        return predicted;
    }

    /**
     * Smallest distance between the arm and anything solid, for the given state
     * with some axes overridden by predicted positions.
     */
    private double clearanceAt(CraneState state, Map<String, Double> overrides) {
        double slew = position(state, overrides, "slew");
        double boom = position(state, overrides, "boom");
        double jib = position(state, overrides, "jib");
        double extension = position(state, overrides, "extension");

        Vec3 pivot = geometry.boomPivot();
        Vec3 boomTip = geometry.boomTip(slew, boom, extension);
        Vec3 jibTip = geometry.jibTip(slew, boom, jib, extension);

        List<Aabb> obstacles = new ArrayList<>(geometry.structureObstacles());
        obstacles.addAll(loadObstacles);

        // Only the arm's structure is guarded. The rope and hook are deliberately
        // excluded: lowering a load onto the deck or the ground is the job, not a
        // collision, and guarding them would make the crane refuse to work.
        double clearance = Double.MAX_VALUE;
        for (Aabb obstacle : obstacles) {
            clearance = Math.min(clearance,
                    obstacle.distanceToSegment(pivot, boomTip) - geometry.boomRadius());
            clearance = Math.min(clearance,
                    obstacle.distanceToSegment(boomTip, jibTip) - geometry.jibRadius());
        }
        // The ground is an obstacle too, and a cheap one to test.
        clearance = Math.min(clearance, boomTip.y() - geometry.boomRadius());
        clearance = Math.min(clearance, jibTip.y() - geometry.jibRadius());
        return clearance;
    }

    private static double position(CraneState state, Map<String, Double> overrides,
                                   String axisId) {
        Double override = overrides.get(axisId);
        return override != null ? override : state.position(axisId);
    }
}
