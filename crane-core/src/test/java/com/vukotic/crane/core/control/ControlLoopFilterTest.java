package com.vukotic.crane.core.control;

import com.vukotic.crane.core.driver.CraneDriver;
import com.vukotic.crane.core.driver.DriverState;
import com.vukotic.crane.core.model.CraneCommand;
import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.model.CraneProfiles;
import com.vukotic.crane.core.model.CraneState;
import com.vukotic.crane.core.safety.SafetyController;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The assist filter chain must never weaken the safety layer. */
class ControlLoopFilterTest {

    private static final double DT = 0.02;

    /** Minimal driver capturing the demands the loop sends. */
    private static final class CapturingDriver implements CraneDriver {
        private CraneProfile profile;
        Map<String, Double> lastDemands = Map.of();

        @Override
        public String name() {
            return "capture";
        }

        @Override
        public void connect(CraneProfile profile) {
            this.profile = profile;
        }

        @Override
        public void disconnect() {
            profile = null;
        }

        @Override
        public boolean isConnected() {
            return profile != null;
        }

        @Override
        public void sendDemands(Map<String, Double> axisDemands) {
            lastDemands = Map.copyOf(axisDemands);
        }

        @Override
        public DriverState readState() {
            Map<String, Double> zeros = new LinkedHashMap<>();
            profile.axes().forEach(a -> zeros.put(a.id(), a.clampPosition(0.0)));
            return new DriverState(zeros, zeros);
        }
    }

    private static CraneCommand freshHeldNeutral(CraneProfile profile, long now) {
        Map<String, Double> zeros = new LinkedHashMap<>();
        profile.axes().forEach(a -> zeros.put(a.id(), 0.0));
        return new CraneCommand(now, zeros, true, false, false);
    }

    @Test
    void filterOutputStillGoesThroughSafetyClampAndRamp() {
        CraneProfile profile = CraneProfiles.demoKnuckleBoom();
        CapturingDriver driver = new CapturingDriver();
        ControlLoop loop = new ControlLoop(profile, driver, new SafetyController(profile));
        driver.connect(profile);

        // A rogue filter demanding far out of range on every tick.
        loop.setDemandFilters(List.of((demands, state, dt) -> {
            Map<String, Double> out = new LinkedHashMap<>(demands);
            out.put("slew", 5.0);
            return out;
        }));

        long now = 1_000L;
        loop.submitCommand(freshHeldNeutral(profile, now));
        loop.tick(now, DT);

        double sent = driver.lastDemands.get("slew");
        double rampLimit = profile.axisById("slew").orElseThrow().commandRampRate() * DT;
        assertEquals(rampLimit, sent, 1e-9,
                "first tick from neutral is ramp-limited even for a rogue filter");
        assertTrue(sent <= 1.0);
    }

    @Test
    void estopZeroesEverythingDespiteFilters() {
        CraneProfile profile = CraneProfiles.demoKnuckleBoom();
        CapturingDriver driver = new CapturingDriver();
        ControlLoop loop = new ControlLoop(profile, driver, new SafetyController(profile));
        driver.connect(profile);

        loop.setDemandFilters(List.of((demands, state, dt) -> {
            Map<String, Double> out = new LinkedHashMap<>(demands);
            out.put("slew", 1.0);
            return out;
        }));

        long now = 1_000L;
        Map<String, Double> zeros = new LinkedHashMap<>();
        profile.axes().forEach(a -> zeros.put(a.id(), 0.0));
        loop.submitCommand(new CraneCommand(now, zeros, true, true, false));
        CraneState state = loop.tick(now, DT);

        assertTrue(state.estopLatched());
        driver.lastDemands.values().forEach(d -> assertEquals(0.0, d, 1e-12));
    }

    @Test
    void emptyChainIsPassthrough() {
        CraneProfile profile = CraneProfiles.demoKnuckleBoom();
        CapturingDriver driver = new CapturingDriver();
        ControlLoop loop = new ControlLoop(profile, driver, new SafetyController(profile));
        driver.connect(profile);

        loop.setDemandFilters(List.of());
        long now = 1_000L;
        loop.submitCommand(freshHeldNeutral(profile, now));
        loop.tick(now, DT);
        driver.lastDemands.values().forEach(d -> assertEquals(0.0, d, 1e-12));
    }
}
