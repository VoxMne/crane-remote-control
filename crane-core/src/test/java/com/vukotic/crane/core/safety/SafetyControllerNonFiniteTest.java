package com.vukotic.crane.core.safety;

import com.vukotic.crane.core.model.CraneCommand;
import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.model.CraneProfiles;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A corrupt number must not reach the machine. NaN survives comparison and
 * clamping, so without an explicit check it would flow through the ramp limiter
 * into the driver and poison every later tick.
 */
class SafetyControllerNonFiniteTest {

    private final CraneProfile profile = CraneProfiles.demoKnuckleBoom();

    private CraneCommand commandWith(double slewDemand) {
        return commandWith(slewDemand, 1_000L);
    }

    private CraneCommand commandWith(double slewDemand, long timestampMillis) {
        Map<String, Double> demands = new LinkedHashMap<>();
        profile.axes().forEach(axis -> demands.put(axis.id(), 0.0));
        demands.put("slew", slewDemand);
        return new CraneCommand(timestampMillis, demands, true, false, false);
    }

    @Test
    void nanDemandIsTreatedAsNeutral() {
        SafetyController safety = new SafetyController(profile);
        SafetyOutput output = safety.filter(commandWith(Double.NaN), Map.of(), 0.02, 1_000L);
        assertEquals(0.0, output.filteredDemands().get("slew"));
    }

    @Test
    void infiniteDemandIsTreatedAsNeutral() {
        SafetyController safety = new SafetyController(profile);
        SafetyOutput output =
                safety.filter(commandWith(Double.POSITIVE_INFINITY), Map.of(), 0.02, 1_000L);
        assertEquals(0.0, output.filteredDemands().get("slew"));
    }

    @Test
    void aPoisonedTickDoesNotContaminateLaterOnes() {
        SafetyController safety = new SafetyController(profile);
        safety.filter(commandWith(Double.NaN), Map.of(), 0.02, 1_000L);

        // A normal demand afterwards must ramp up as usual, not stay NaN.
        double demand = 0;
        for (int tick = 0; tick < 100; tick++) {
            long now = 1_000L + tick * 20L;   // command stays fresh at each tick
            demand = safety.filter(commandWith(1.0, now), Map.of(), 0.02, now)
                    .filteredDemands().get("slew");
        }
        assertTrue(Double.isFinite(demand) && demand > 0.9,
                "the ramp limiter should have recovered, got " + demand);
    }
}
