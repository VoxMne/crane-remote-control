package com.vukotic.crane.sim;

import com.vukotic.crane.core.driver.DriverState;
import com.vukotic.crane.core.model.AxisSpec;
import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.model.CraneProfiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic tests against the manually-stepped simulator: explicit
 * {@code step(dt)} calls, no wall clock, no sleeps.
 */
class SimulatedCraneDriverTest {

    private static final double EPS = 1e-9;
    private static final double DT = 0.02;

    private CraneProfile profile;
    private SimulatedCraneDriver sim;

    @BeforeEach
    void setUp() {
        profile = CraneProfiles.demoKnuckleBoom();
        sim = SimulatedCraneDriver.manuallyStepped();
        sim.connect(profile);
    }

    private Map<String, Double> demands(String axisId, double value) {
        Map<String, Double> map = new LinkedHashMap<>();
        profile.axes().forEach(axis -> map.put(axis.id(), 0.0));
        map.put(axisId, value);
        return map;
    }

    private void stepFor(double seconds) {
        int steps = (int) Math.round(seconds / DT);
        for (int i = 0; i < steps; i++) {
            sim.step(DT);
        }
    }

    @Test
    void connectsParkedInsideLimitsWithZeroVelocity() {
        DriverState state = sim.readState();
        for (AxisSpec axis : profile.axes()) {
            double position = state.axisPositions().get(axis.id());
            assertTrue(position >= axis.minPosition() && position <= axis.maxPosition());
            assertEquals(0.0, state.axisVelocities().get(axis.id()), EPS);
        }
    }

    @Test
    void velocityFollowsFirstOrderResponseTowardDemandTimesMaxVelocity() {
        AxisSpec slew = profile.axisById("slew").orElseThrow();
        sim.sendDemands(demands("slew", 1.0));

        sim.step(DT);
        double afterOneStep = sim.readState().axisVelocities().get("slew");
        double expectedFirstStep = slew.maxVelocity()
                * (1.0 - Math.exp(-DT / SimulatedCraneDriver.DEFAULT_TIME_CONSTANT_SECONDS));
        assertEquals(expectedFirstStep, afterOneStep, 1e-6,
                "one step must follow the exact first-order discretization");
        assertTrue(afterOneStep > 0 && afterOneStep < slew.maxVelocity(),
                "velocity must rise gradually, not jump to max");

        // After 2 s = 5 time constants the response has converged (>99%).
        stepFor(2.0);
        double converged = sim.readState().axisVelocities().get("slew");
        assertEquals(slew.maxVelocity(), converged, 0.01 * slew.maxVelocity());
    }

    @Test
    void halfDemandConvergesToHalfMaxVelocity() {
        AxisSpec boom = profile.axisById("boom").orElseThrow();
        sim.sendDemands(demands("boom", 0.5));
        stepFor(3.0);
        assertEquals(0.5 * boom.maxVelocity(),
                sim.readState().axisVelocities().get("boom"), 0.01 * boom.maxVelocity());
    }

    @Test
    void positionIntegratesVelocityOverTime() {
        sim.sendDemands(demands("boom", 1.0));
        stepFor(1.0);
        double posAfter1s = sim.readState().axisPositions().get("boom");
        assertTrue(posAfter1s > 0.0, "position must move once velocity builds up");

        stepFor(1.0);
        double posAfter2s = sim.readState().axisPositions().get("boom");
        assertTrue(posAfter2s > posAfter1s, "position must keep integrating velocity");
    }

    @Test
    void hardStopAtMaxLimitZeroesVelocity() {
        AxisSpec slew = profile.axisById("slew").orElseThrow();
        sim.sendDemands(demands("slew", 1.0));
        // 180 deg at 15 deg/s needs ~12.5 s incl. spin-up; 20 s is plenty.
        stepFor(20.0);

        DriverState state = sim.readState();
        assertEquals(slew.maxPosition(), state.axisPositions().get("slew"), EPS,
                "position must stop exactly at the max limit");
        assertEquals(0.0, state.axisVelocities().get("slew"), EPS,
                "velocity must be zeroed at the hard stop");

        // Still pushing outward: it must stay pinned.
        stepFor(1.0);
        assertEquals(slew.maxPosition(), sim.readState().axisPositions().get("slew"), EPS);
        assertEquals(0.0, sim.readState().axisVelocities().get("slew"), EPS);
    }

    @Test
    void hardStopAtMinLimitZeroesVelocity() {
        AxisSpec extension = profile.axisById("extension").orElseThrow();
        assertEquals(extension.minPosition(), sim.readState().axisPositions().get("extension"), EPS,
                "extension parks at its min limit");

        sim.sendDemands(demands("extension", -1.0));
        stepFor(1.0);

        DriverState state = sim.readState();
        assertEquals(extension.minPosition(), state.axisPositions().get("extension"), EPS);
        assertEquals(0.0, state.axisVelocities().get("extension"), EPS);
    }

    @Test
    void movingAwayFromALimitIsAllowed() {
        sim.sendDemands(demands("extension", 1.0)); // parked at min, driving inward
        stepFor(1.0);
        assertTrue(sim.readState().axisPositions().get("extension") > 0.0,
                "motion away from the limit must not be blocked");
    }

    @Test
    void neutralDemandDecaysVelocityBackToZero() {
        sim.sendDemands(demands("slew", 1.0));
        stepFor(1.0);
        assertTrue(sim.readState().axisVelocities().get("slew") > 1.0);

        sim.sendDemands(demands("slew", 0.0));
        stepFor(3.0);
        assertEquals(0.0, sim.readState().axisVelocities().get("slew"), 0.05,
                "velocity must decay toward zero on neutral demand");
    }

    @Test
    void sendDemandsClampsToUnitRange() {
        AxisSpec winch = profile.axisById("winch").orElseThrow();
        sim.sendDemands(demands("winch", 4.0)); // driver defends itself too
        stepFor(3.0);
        double velocity = sim.readState().axisVelocities().get("winch");
        assertTrue(velocity <= winch.maxVelocity() + EPS,
                "velocity must never exceed maxVelocity even for out-of-range demand");
    }

    @Test
    void stepIsDeterministic() {
        SimulatedCraneDriver other = SimulatedCraneDriver.manuallyStepped();
        other.connect(profile);

        sim.sendDemands(demands("jib", 0.7));
        other.sendDemands(demands("jib", 0.7));
        for (int i = 0; i < 100; i++) {
            sim.step(DT);
            other.step(DT);
        }
        assertEquals(sim.readState().axisPositions().get("jib"),
                other.readState().axisPositions().get("jib"), 0.0);
    }

    @Test
    void usageWhileDisconnectedIsRejected() {
        SimulatedCraneDriver fresh = SimulatedCraneDriver.manuallyStepped();
        assertFalse(fresh.isConnected());
        assertThrows(IllegalStateException.class, () -> fresh.sendDemands(Map.of()));
        assertThrows(IllegalStateException.class, fresh::readState);
        assertThrows(IllegalStateException.class, () -> fresh.step(DT));
        assertEquals("Simulator", fresh.name());
    }

    @Test
    void disconnectForgetsProfile() {
        sim.disconnect();
        assertFalse(sim.isConnected());
        assertThrows(IllegalStateException.class, sim::readState);
    }
}
