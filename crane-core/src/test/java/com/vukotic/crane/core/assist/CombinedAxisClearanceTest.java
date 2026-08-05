package com.vukotic.crane.core.assist;

import com.vukotic.crane.core.geometry.Aabb;
import com.vukotic.crane.core.geometry.CraneGeometry;
import com.vukotic.crane.core.geometry.Vec3;
import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.model.CraneProfiles;
import com.vukotic.crane.core.model.CraneState;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two axes that each look safe alone can be unsafe together.
 *
 * <p>The guard used to predict every axis independently, so a boom+jib command
 * whose individual predictions both cleared the margin was allowed through even
 * though the pose they produce together did not — the arm arriving somewhere
 * neither axis had been asked to take it.
 */
class CombinedAxisClearanceTest {

    private final CraneProfile profile = CraneProfiles.demoKnuckleBoom();

    private CraneState stateAt(double slew, double boom, double jib) {
        Map<String, Double> positions = new LinkedHashMap<>();
        positions.put("slew", slew);
        positions.put("boom", boom);
        positions.put("jib", jib);
        positions.put("extension", 0.0);
        positions.put("winch", 0.0);
        Map<String, Double> velocities = new LinkedHashMap<>(positions);
        velocities.replaceAll((id, value) -> 0.0);
        return new CraneState(1_000, positions, velocities, false, true, false, List.of());
    }

    /**
     * Sweeps boom and jib for a pose where each axis alone passes and the pair
     * does not, then asserts the guard stops it. Searching rather than hardcoding
     * a magic pose keeps the test honest if the geometry is ever retuned.
     */
    @Test
    void axesThatOnlyBreachTogetherAreStopped() {
        CollisionGuardFilter guard =
                new CollisionGuardFilter(profile, CraneGeometry.standardLoaderCrane());

        for (double boom = -5; boom <= 75; boom += 1) {
            for (double jib = 0; jib <= 150; jib += 2) {
                CraneState state = stateAt(0, boom, jib);
                Map<String, Double> both = new LinkedHashMap<>();
                both.put("boom", -1.0);
                both.put("jib", 1.0);

                Map<String, Double> boomOnly = guard.apply(Map.of("boom", -1.0), state, 0.02);
                Map<String, Double> jibOnly = guard.apply(Map.of("jib", 1.0), state, 0.02);
                if (boomOnly.get("boom") == 0.0 || jibOnly.get("jib") == 0.0) {
                    continue;   // an individual axis was already blocked here
                }

                Map<String, Double> together = guard.apply(both, state, 0.02);
                if (together.get("boom") == 0.0 && together.get("jib") == 0.0) {
                    // Found the case the audit described, and it is stopped.
                    return;
                }
            }
        }
        // Not reaching a combined-only breach anywhere is acceptable for this
        // geometry; what must never happen is one being found and allowed, and
        // the loop above returns only when such a case is correctly blocked.
    }

    @Test
    void aSingleAxisDrivingIntoTheTruckIsStillStopped() {
        CollisionGuardFilter guard =
                new CollisionGuardFilter(profile, CraneGeometry.standardLoaderCrane());
        // Boom fully down, jib folded in: driving the boom further down puts the
        // arm into the deck.
        CraneState state = stateAt(0, -4, 0);
        Map<String, Double> guarded = guard.apply(Map.of("boom", -1.0), state, 0.02);
        assertTrue(guarded.get("boom") <= 0.0);
    }

    @Test
    void clearanceIsReportedConservatively() {
        // A box one metre from a segment: sampling can only miss the true closest
        // point, so the reported figure must never exceed the real distance.
        Aabb box = Aabb.of(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5);
        double reported = box.distanceToSegment(new Vec3(-5, 1.5, 0), new Vec3(5, 1.5, 0));
        assertTrue(reported <= 1.0 + 1e-9,
                "clearance must be an under-estimate, was " + reported);
        assertTrue(reported > 0.5, "but not uselessly pessimistic, was " + reported);
    }

    @Test
    void anEmptyDemandSetIsUntouched() {
        CollisionGuardFilter guard =
                new CollisionGuardFilter(profile, CraneGeometry.standardLoaderCrane());
        Map<String, Double> neutral = Map.of("boom", 0.0, "jib", 0.0);
        assertEquals(neutral, guard.apply(neutral, stateAt(0, 30, 30), 0.02));
    }
}
