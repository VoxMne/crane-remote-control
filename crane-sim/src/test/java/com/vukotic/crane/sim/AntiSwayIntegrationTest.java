package com.vukotic.crane.sim;

import com.vukotic.crane.core.assist.AntiSwayFilter;
import com.vukotic.crane.core.control.ControlLoop;
import com.vukotic.crane.core.control.DemandFilter;
import com.vukotic.crane.core.model.CraneCommand;
import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.model.CraneProfiles;
import com.vukotic.crane.core.safety.SafetyController;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Closed-loop proof that the anti-sway assist works: the same scripted maneuver
 * (spool out rope, slew hard, stop) settles dramatically faster with the filter
 * than without. Fully deterministic — direct tick() and step() calls.
 */
class AntiSwayIntegrationTest {

    private static final double DT = 0.02;

    @Test
    void antiSwaySettlesTheLoadFaster() {
        double without = settledSwayAmplitude(List.of());
        double with = settledSwayAmplitude(List.of(new AntiSwayFilter()));

        assertTrue(without > 0.5,
                "the maneuver must excite measurable sway to make this test meaningful, got "
                        + without);
        assertTrue(with < without * 0.5,
                "anti-sway should at least halve the settled sway: with=" + with
                        + " without=" + without);
    }

    /** Runs the scripted maneuver and returns the max |sway| in the settle window. */
    private static double settledSwayAmplitude(List<DemandFilter> filters) {
        CraneProfile profile = CraneProfiles.demoKnuckleBoom();
        SimulatedCraneDriver driver = SimulatedCraneDriver.manuallyStepped();
        ControlLoop loop = new ControlLoop(profile, driver, new SafetyController(profile));
        driver.connect(profile);
        loop.setDemandFilters(filters);

        double maxSettledSway = 0.0;
        long now = 1_000_000L;
        for (double t = 0.0; t < 10.0; t += DT) {
            // Phase 1 (0-3 s): spool out rope to a real pendulum length.
            // Phase 2 (3-5 s): full slew. Phase 3 (5-10 s): hands off, settle.
            double winch = t < 3.0 ? 1.0 : 0.0;
            double slew = t >= 3.0 && t < 5.0 ? 1.0 : 0.0;
            loop.submitCommand(command(profile, now, winch, slew));
            loop.tick(now, DT);
            driver.step(DT);
            now += (long) (DT * 1000);

            if (t >= 8.0) {
                double sway = driver.readState().axisPositions()
                        .get(SimulatedCraneDriver.LOAD_SWAY_KEY);
                maxSettledSway = Math.max(maxSettledSway, Math.abs(sway));
            }
        }
        return maxSettledSway;
    }

    private static CraneCommand command(CraneProfile profile, long now,
                                        double winch, double slew) {
        Map<String, Double> demands = new LinkedHashMap<>();
        profile.axes().forEach(a -> demands.put(a.id(), 0.0));
        demands.put("winch", winch);
        demands.put("slew", slew);
        return new CraneCommand(now, demands, true, false, false);
    }
}
