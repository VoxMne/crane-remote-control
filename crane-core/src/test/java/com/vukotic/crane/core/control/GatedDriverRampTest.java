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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A driver that cannot carry motion must stop the safety ramp winding up behind
 * its back.
 *
 * <p>The serial driver substituted zeros on the wire while telemetry was stale,
 * but the safety layer above it kept ramping and remembering the demand it
 * thought it had sent. An operator holding full demand through a dropout had the
 * internal output at 1.0 by the time the link recovered, and the first good frame
 * put all of it on the machine in one tick — a standstill-to-full-speed step
 * exactly when the crane had just proven the link was unreliable.
 */
class GatedDriverRampTest {

    private final CraneProfile profile = CraneProfiles.demoKnuckleBoom();

    /** A driver whose readiness and last-received demands the test controls. */
    private static final class GateableDriver implements CraneDriver {
        private boolean ready;
        private Map<String, Double> lastDemands = Map.of();

        @Override
        public String name() {
            return "Gateable";
        }

        @Override
        public void connect(CraneProfile profile) {
        }

        @Override
        public void disconnect() {
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public boolean acceptsMotion() {
            return ready;
        }

        @Override
        public void sendDemands(Map<String, Double> axisDemands) {
            lastDemands = Map.copyOf(axisDemands);
        }

        @Override
        public DriverState readState() {
            Map<String, Double> zeros = new LinkedHashMap<>();
            zeros.put("slew", 0.0);
            zeros.put("boom", 0.0);
            zeros.put("jib", 0.0);
            zeros.put("extension", 0.0);
            zeros.put("winch", 0.0);
            return new DriverState(zeros, new LinkedHashMap<>(zeros));
        }
    }

    private CraneCommand fullSlew(long timestampMillis) {
        Map<String, Double> demands = new LinkedHashMap<>();
        demands.put("slew", 1.0);
        return new CraneCommand(timestampMillis, demands, true, false, false);
    }

    @Test
    void aGatedDriverKeepsTheRampParkedAndRecoveryStillRamps() {
        GateableDriver driver = new GateableDriver();
        ControlLoop loop = new ControlLoop(profile, driver, new SafetyController(profile));

        // Two seconds of full demand while the driver refuses motion. The slew ramp
        // rate is 2.0/s, so an unguarded ramp would be saturated long before this.
        long now = 1_000;
        for (int i = 0; i < 100; i++) {
            loop.submitCommand(fullSlew(now));
            loop.tick(now, 0.02);
            now += 20;
        }
        assertEquals(0.0, driver.lastDemands.get("slew"),
                "a gated driver must receive nothing but zero");

        // The link comes back. The very next tick must be one ramp step, not 1.0.
        driver.ready = true;
        loop.submitCommand(fullSlew(now));
        CraneState state = loop.tick(now, 0.02);
        double first = driver.lastDemands.get("slew");

        assertTrue(first > 0.0, "motion is allowed again: " + first);
        assertTrue(first <= 2.0 * 0.02 + 1e-9,
                "recovery must ramp from zero, not step to full demand: " + first);
        assertTrue(state.deadmanHeld());
    }

    /**
     * The case the first version of this test missed: gating from a <b>saturated</b>
     * output, not from rest.
     *
     * <p>Suppressing the command only made safety ramp its remembered output down.
     * From 1.0, one closed 20 ms tick at a ramp rate of 2.0/s still left 0.96 — so a
     * dropout shorter than the ramp transmitted zero on the wire and then almost
     * full demand the instant telemetry returned. A gated link is not a controlled
     * stop; it is no link, and the remembered output has to go with it.
     */
    @Test
    void aBriefDropoutFromFullDemandStillRecoversThroughTheRamp() {
        GateableDriver driver = new GateableDriver();
        driver.ready = true;
        ControlLoop loop = new ControlLoop(profile, driver, new SafetyController(profile));

        // Wind all the way up to full demand with the gate open.
        long now = 1_000;
        for (int i = 0; i < 100; i++) {
            loop.submitCommand(fullSlew(now));
            loop.tick(now, 0.02);
            now += 20;
        }
        assertEquals(1.0, driver.lastDemands.get("slew"), 1e-9, "should be saturated");

        // One single gated tick — a dropout far shorter than the ramp-down time.
        driver.ready = false;
        loop.submitCommand(fullSlew(now));
        loop.tick(now, 0.02);
        now += 20;
        assertEquals(0.0, driver.lastDemands.get("slew"), "gated: nothing on the wire");

        // Telemetry returns. This must be one ramp step from zero, not 0.96.
        driver.ready = true;
        loop.submitCommand(fullSlew(now));
        loop.tick(now, 0.02);
        double recovered = driver.lastDemands.get("slew");

        assertTrue(recovered <= 2.0 * 0.02 + 1e-9,
                "recovery from a brief dropout must ramp from zero, was " + recovered);
    }

    @Test
    void aGatedDriverStillLetsAnEmergencyStopThrough() {
        GateableDriver driver = new GateableDriver();
        SafetyController safety = new SafetyController(profile);
        ControlLoop loop = new ControlLoop(profile, driver, safety);

        Map<String, Double> demands = new LinkedHashMap<>();
        demands.put("slew", 1.0);
        loop.submitCommand(new CraneCommand(1_000, demands, true, true, false));
        loop.tick(1_000, 0.02);

        assertTrue(safety.isEstopLatched(),
                "suppressing motion must never suppress the emergency stop with it");
    }

    @Test
    void aGatedDriverDropsAResetRidingAlongWithIt() {
        GateableDriver driver = new GateableDriver();
        SafetyController safety = new SafetyController(profile);
        ControlLoop loop = new ControlLoop(profile, driver, safety);

        loop.submitCommand(new CraneCommand(1_000, Map.of("slew", 0.0), false, true, false));
        loop.tick(1_000, 0.02);
        assertTrue(safety.isEstopLatched());

        // A reset arriving while the link cannot be trusted must not clear a latch:
        // nobody can confirm the machine's state through a link that is not talking.
        loop.submitCommand(new CraneCommand(1_020, Map.of("slew", 0.0), false, false, true));
        loop.tick(1_020, 0.02);
        assertTrue(safety.isEstopLatched(),
                "a reset must not be honoured through a driver that cannot carry motion");
    }
}
