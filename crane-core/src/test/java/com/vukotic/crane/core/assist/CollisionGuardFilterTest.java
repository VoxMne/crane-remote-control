package com.vukotic.crane.core.assist;

import com.vukotic.crane.core.geometry.Aabb;
import com.vukotic.crane.core.geometry.CraneGeometry;
import com.vukotic.crane.core.geometry.Vec3;
import com.vukotic.crane.core.model.AxisSpec;
import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.model.CraneProfiles;
import com.vukotic.crane.core.model.CraneState;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollisionGuardFilterTest {

    private static final double DT = 0.02;

    private final CraneProfile profile = CraneProfiles.demoKnuckleBoom();
    private final CraneGeometry geometry = CraneGeometry.standardLoaderCrane();
    private final CollisionGuardFilter guard = new CollisionGuardFilter(profile, geometry);

    private CraneState stateAt(Map<String, Double> positions) {
        Map<String, Double> velocities = new LinkedHashMap<>();
        positions.keySet().forEach(id -> velocities.put(id, 0.0));
        return new CraneState(0L, positions, velocities, false, false, false, List.of());
    }

    /** Boom out over the bed, well clear of everything. */
    private CraneState clearState() {
        return stateAt(Map.of("slew", 0.0, "boom", 40.0, "jib", 0.0,
                "extension", 0.0, "winch", 0.0));
    }

    /**
     * Drives one axis at full demand through the guard for a few seconds and
     * reports where it ended up — the honest test of "does it actually stop
     * before hitting something", rather than a single-frame check.
     */
    private Map<String, Double> driveThroughGuard(Map<String, Double> start,
                                                  String axisId, double demand) {
        Map<String, Double> positions = new HashMap<>(start);
        AxisSpec axis = profile.axisById(axisId).orElseThrow();
        for (int tick = 0; tick < 400; tick++) {
            CraneState state = stateAt(positions);
            double allowed = guard.apply(Map.of(axisId, demand), state, DT).get(axisId);
            if (allowed == 0) {
                break;
            }
            positions.put(axisId, axis.clampPosition(
                    positions.get(axisId) + allowed * axis.maxVelocity() * DT));
        }
        return positions;
    }

    /** Positions at the point where folding the jib down is first refused. */
    private Map<String, Double> jibBlockedState() {
        return driveThroughGuard(new HashMap<>(Map.of("slew", 0.0, "boom", 0.0,
                "jib", 0.0, "extension", 0.0, "winch", 0.0)), "jib", +1.0);
    }

    private double jibTipHeight(Map<String, Double> positions, double jib) {
        return geometry.jibTip(positions.get("slew"), positions.get("boom"), jib,
                positions.get("extension")).y();
    }

    @Test
    void allowsMotionInClearAir() {
        Map<String, Double> demands = Map.of("slew", 1.0, "boom", 1.0, "winch", 1.0);
        assertEquals(demands, guard.apply(demands, clearState(), DT));
        assertFalse(guard.isBlocking());
    }

    @Test
    void stopsTheJibBeforeItReachesTheGround() {
        Map<String, Double> start = new HashMap<>(Map.of("slew", 0.0, "boom", 0.0,
                "jib", 0.0, "extension", 0.0, "winch", 0.0));
        double finalJib = driveThroughGuard(start, "jib", +1.0).get("jib");

        assertTrue(finalJib > 5, "the jib should still have folded a long way, got " + finalJib);
        assertTrue(finalJib < profile.axisById("jib").orElseThrow().maxPosition(),
                "it must stop short of its mechanical limit, got " + finalJib);
        assertTrue(jibTipHeight(start, finalJib) > 0,
                "the jib tip must never end up underground");
        assertTrue(guard.isBlocking());
    }

    @Test
    void stopsTheJibBeforeItReachesALoadStandingOnTheGround() {
        // A container on the apron, right under where the jib would swing down.
        Aabb container = Aabb.centred(new Vec3(7.5, 0.7, 0), 3.0, 1.4, 1.4);
        guard.setLoadObstacles(List.of(container));

        Map<String, Double> start = new HashMap<>(Map.of("slew", 0.0, "boom", 0.0,
                "jib", 0.0, "extension", 0.0, "winch", 0.0));
        double finalJib = driveThroughGuard(start, "jib", +1.0).get("jib");

        Vec3 boomTip = geometry.boomTip(0, 0, 0);
        Vec3 jibTip = geometry.jibTip(0, 0, finalJib, 0);
        assertTrue(container.distanceToSegment(boomTip, jibTip) >= 0,
                "the jib must never end up inside the load");
        assertTrue(guard.isBlocking());
    }

    @Test
    void stillAllowsBackingOutOfATightSpot() {
        // Jib folded down near the ground: unfolding it must stay possible.
        CraneState state = stateAt(Map.of("slew", 0.0, "boom", 0.0, "jib", 66.0,
                "extension", 0.0, "winch", 0.0));
        assertEquals(-1.0, guard.apply(Map.of("jib", -1.0), state, DT).get("jib"),
                "raising the jib away from the ground must stay allowed");
    }

    @Test
    void aLoadOutOfReachDoesNotBlockAnything() {
        guard.setLoadObstacles(List.of(Aabb.centred(new Vec3(0, 0.7, -40), 3, 1.4, 1.4)));
        Map<String, Double> demands = Map.of("slew", -1.0);
        assertEquals(demands, guard.apply(demands, clearState(), DT));
    }

    @Test
    void blockingOneAxisLeavesTheOthersAlone() {
        CraneState blocked = stateAt(jibBlockedState());
        Map<String, Double> guarded =
                guard.apply(Map.of("jib", 1.0, "winch", 1.0), blocked, DT);
        assertEquals(0.0, guarded.get("jib"), "the jib is heading into the ground");
        assertEquals(1.0, guarded.get("winch"), "a blocked jib must not freeze the winch");
    }

    @Test
    void nullStateIsPassedThroughUntouched() {
        Map<String, Double> demands = Map.of("slew", 1.0);
        assertEquals(demands, guard.apply(demands, null, DT));
    }
}
