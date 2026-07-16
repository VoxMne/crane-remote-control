package com.vukotic.crane.core.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Smoke tests for the shared M0 contract; the real safety tests arrive with M1. */
class ContractSmokeTest {

    @Test
    void demoProfileHasFiveDistinctAxes() {
        CraneProfile profile = CraneProfiles.demoKnuckleBoom();
        assertEquals(List.of("slew", "boom", "jib", "extension", "winch"), profile.axisIds());
        assertTrue(profile.axisById("slew").isPresent());
        assertTrue(profile.axisById("nonsense").isEmpty());
    }

    @Test
    void profileRejectsDuplicateAxisIds() {
        AxisSpec a = new AxisSpec("slew", "Slew", "deg", -180, 180, 15, 2);
        assertThrows(IllegalArgumentException.class,
                () -> new CraneProfile("bad", "Bad", List.of(a, a)));
    }

    @Test
    void axisSpecRejectsInvertedLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> new AxisSpec("boom", "Boom", "deg", 75, -5, 8, 2));
    }

    @Test
    void neutralCommandIsAllZeroAndDeadmanReleased() {
        CraneCommand neutral = CraneCommand.neutral(CraneProfiles.demoKnuckleBoom());
        assertTrue(neutral.allNeutral());
        assertFalse(neutral.deadmanHeld());
        assertEquals(0.0, neutral.demand("winch"));
        assertEquals(0.0, neutral.demand("unknown-axis"));
    }

    @Test
    void initialStateIsParkedInsideLimits() {
        CraneProfile profile = CraneProfiles.demoKnuckleBoom();
        CraneState state = CraneState.initial(profile);
        assertFalse(state.estopLatched());
        assertFalse(state.watchdogTripped());
        for (AxisSpec axis : profile.axes()) {
            double pos = state.position(axis.id());
            assertTrue(pos >= axis.minPosition() && pos <= axis.maxPosition(),
                    "axis " + axis.id() + " parked out of limits: " + pos);
            assertEquals(0.0, state.velocity(axis.id()));
        }
    }

    @Test
    void commandDemandsAreImmutable() {
        CraneCommand neutral = CraneCommand.neutral(CraneProfiles.demoKnuckleBoom());
        Map<String, Double> demands = neutral.axisDemands();
        assertThrows(UnsupportedOperationException.class, () -> demands.put("slew", 1.0));
    }
}
