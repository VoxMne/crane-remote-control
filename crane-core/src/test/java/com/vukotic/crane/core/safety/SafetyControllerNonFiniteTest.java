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

    /**
     * Every axis sitting well inside its travel. These tests are about corrupt
     * <em>demands</em>; they used to pass an empty position map for convenience,
     * which now means "position unknown" and correctly inhibits motion by itself.
     */
    private static final Map<String, Double> MID_TRAVEL = Map.of(
            "slew", 0.0, "boom", 30.0, "jib", 40.0, "extension", 1.0, "winch", 5.0);

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
    void anAxisWithNoReportedPositionGetsNoMotion() {
        // The limit check cannot run without a position, and "cannot check" has to
        // mean "do not move". This used to fall straight through and command the
        // axis with no limit protection at all.
        SafetyController safety = new SafetyController(profile);
        SafetyOutput output = safety.filter(commandWith(1.0), Map.of(), 0.02, 1_000L);

        assertEquals(0.0, output.filteredDemands().get("slew"));
        assertTrue(output.activeAlarms().stream().anyMatch(a -> a.contains("position unknown")),
                "the operator has to be told why it will not move: " + output.activeAlarms());
    }

    @Test
    void anAxisWithANonFinitePositionGetsNoMotion() {
        SafetyController safety = new SafetyController(profile);
        Map<String, Double> broken = new LinkedHashMap<>(MID_TRAVEL);
        broken.put("slew", Double.NaN);

        SafetyOutput output = safety.filter(commandWith(1.0), broken, 0.02, 1_000L);

        assertEquals(0.0, output.filteredDemands().get("slew"),
                "a NaN position passes every limit comparison; it must not pass this one");
        assertTrue(output.filteredDemands().get("boom") == 0.0
                        || Double.isFinite(output.filteredDemands().get("boom")),
                "other axes stay well-formed");
    }

    @Test
    void nanDemandIsTreatedAsNeutral() {
        SafetyController safety = new SafetyController(profile);
        SafetyOutput output = safety.filter(commandWith(Double.NaN), MID_TRAVEL, 0.02, 1_000L);
        assertEquals(0.0, output.filteredDemands().get("slew"));
    }

    @Test
    void infiniteDemandIsTreatedAsNeutral() {
        SafetyController safety = new SafetyController(profile);
        SafetyOutput output =
                safety.filter(commandWith(Double.POSITIVE_INFINITY), MID_TRAVEL, 0.02, 1_000L);
        assertEquals(0.0, output.filteredDemands().get("slew"));
    }

    @Test
    void aPoisonedTickDoesNotContaminateLaterOnes() {
        SafetyController safety = new SafetyController(profile);
        safety.filter(commandWith(Double.NaN), MID_TRAVEL, 0.02, 1_000L);

        // A normal demand afterwards must ramp up as usual, not stay NaN.
        double demand = 0;
        for (int tick = 0; tick < 100; tick++) {
            long now = 1_000L + tick * 20L;   // command stays fresh at each tick
            demand = safety.filter(commandWith(1.0, now), MID_TRAVEL, 0.02, now)
                    .filteredDemands().get("slew");
        }
        assertTrue(Double.isFinite(demand) && demand > 0.9,
                "the ramp limiter should have recovered, got " + demand);
    }
}
