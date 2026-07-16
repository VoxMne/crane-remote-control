package com.vukotic.crane.core.safety;

import com.vukotic.crane.core.model.AxisSpec;
import com.vukotic.crane.core.model.CraneCommand;
import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.model.CraneProfiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One focused test (at least) per safety rule of docs/ARCHITECTURE.md &sect;Safety
 * semantics. All time is explicit — no threads, no sleeps.
 */
class SafetyControllerTest {

    private static final double EPS = 1e-9;
    private static final double DT = 0.02;        // one 50 Hz tick
    private static final long T0 = 1_000_000;     // arbitrary wall-clock origin

    private CraneProfile profile;
    private SafetyController safety;
    private Map<String, Double> parked;

    @BeforeEach
    void setUp() {
        profile = CraneProfiles.demoKnuckleBoom();
        safety = new SafetyController(profile);
        // Positions well inside every axis' limits so rule 6 stays out of the way.
        parked = new LinkedHashMap<>();
        parked.put("slew", 0.0);
        parked.put("boom", 30.0);
        parked.put("jib", 40.0);
        parked.put("extension", 2.0);
        parked.put("winch", 5.0);
    }

    // ---- helpers -----------------------------------------------------------

    private CraneCommand command(long timestamp, boolean deadman, boolean estop,
                                 boolean reset, Map<String, Double> demands) {
        Map<String, Double> all = new LinkedHashMap<>();
        profile.axes().forEach(axis -> all.put(axis.id(), 0.0));
        all.putAll(demands);
        return new CraneCommand(timestamp, all, deadman, estop, reset);
    }

    private CraneCommand drive(long timestamp, Map<String, Double> demands) {
        return command(timestamp, true, false, false, demands);
    }

    /** Ramps the controller up until the slew output reaches the given value. */
    private SafetyOutput rampSlewUpTo(double demand, long now) {
        SafetyOutput out = null;
        for (int i = 0; i < 1000; i++) {
            out = safety.filter(drive(now, Map.of("slew", demand)), parked, DT, now);
            if (Math.abs(out.demand("slew") - demand) < EPS) {
                return out;
            }
        }
        throw new AssertionError("slew never reached " + demand + ", last: " + out.demand("slew"));
    }

    // ---- rule 1: E-STOP latches, demands zeroed immediately ------------------

    @Nested
    class Rule1Estop {

        @Test
        void estopZeroesAllDemandsInstantlyEvenMidMotion() {
            rampSlewUpTo(1.0, T0);

            SafetyOutput out = safety.filter(
                    command(T0, true, true, false, Map.of("slew", 1.0)), parked, DT, T0);

            assertTrue(out.estopLatched());
            for (String axisId : profile.axisIds()) {
                assertEquals(0.0, out.demand(axisId), EPS,
                        axisId + " must be zeroed instantly on E-STOP, not ramped");
            }
            assertTrue(out.activeAlarms().contains(SafetyController.ESTOP_ALARM));
        }

        @Test
        void estopStaysLatchedAfterButtonReleased() {
            safety.filter(command(T0, true, true, false, Map.of()), parked, DT, T0);

            // estopRequested no longer set, operator still trying to drive.
            SafetyOutput out = safety.filter(drive(T0, Map.of("boom", 0.8)), parked, DT, T0);

            assertTrue(out.estopLatched(), "E-STOP must latch, not follow the button");
            assertEquals(0.0, out.demand("boom"), EPS);
        }

        @Test
        void estopLatchesEvenFromStaleCommand() {
            long staleTimestamp = T0 - 10_000;
            SafetyOutput out = safety.filter(
                    command(staleTimestamp, false, true, false, Map.of()), parked, DT, T0);
            assertTrue(out.estopLatched());
        }
    }

    // ---- rule 2: reset only when neutral and deadman released ----------------

    @Nested
    class Rule2Reset {

        @BeforeEach
        void latchEstop() {
            safety.filter(command(T0, true, true, false, Map.of()), parked, DT, T0);
            assertTrue(safety.isEstopLatched());
        }

        @Test
        void resetRefusedWhileAnyDemandNonZero() {
            SafetyOutput out = safety.filter(
                    command(T0, false, false, true, Map.of("winch", 0.1)), parked, DT, T0);
            assertTrue(out.estopLatched(), "reset must be refused while a demand is non-neutral");
        }

        @Test
        void resetRefusedWhileDeadmanHeld() {
            SafetyOutput out = safety.filter(
                    command(T0, true, false, true, Map.of()), parked, DT, T0);
            assertTrue(out.estopLatched(), "reset must be refused while deadman is held");
        }

        @Test
        void resetRefusedFromStaleCommand() {
            long staleTimestamp = T0 - 10_000;
            SafetyOutput out = safety.filter(
                    command(staleTimestamp, false, false, true, Map.of()), parked, DT, T0);
            assertTrue(out.estopLatched(), "a stale command must not be able to reset E-STOP");
        }

        @Test
        void resetAcceptedWhenNeutralAndDeadmanReleased() {
            SafetyOutput out = safety.filter(
                    command(T0, false, false, true, Map.of()), parked, DT, T0);
            assertFalse(out.estopLatched());
            assertFalse(out.activeAlarms().contains(SafetyController.ESTOP_ALARM));
        }

        @Test
        void motionAfterResetRampsUpFromZero() {
            safety.filter(command(T0, false, false, true, Map.of()), parked, DT, T0);

            SafetyOutput out = safety.filter(drive(T0, Map.of("slew", 1.0)), parked, DT, T0);

            double maxFirstStep = profile.axisById("slew").orElseThrow().commandRampRate() * DT;
            assertEquals(maxFirstStep, out.demand("slew"), EPS,
                    "after reset, demand must ramp up from zero");
        }
    }

    // ---- rule 3: deadman released => fast controlled ramp to zero ------------

    @Nested
    class Rule3Deadman {

        @Test
        void deadmanReleaseRampsDownNotFreezesAndNotInstantZero() {
            rampSlewUpTo(1.0, T0);
            double rampPerTick = profile.axisById("slew").orElseThrow().commandRampRate() * DT;

            SafetyOutput out = safety.filter(
                    command(T0, false, false, false, Map.of("slew", 1.0)), parked, DT, T0);
            double first = out.demand("slew");
            assertEquals(1.0 - rampPerTick, first, EPS,
                    "release must start a controlled ramp down, not freeze at 1.0 or jump to 0");

            // Keep ticking: monotonically decreasing until exactly zero.
            double previous = first;
            boolean reachedZero = false;
            for (int i = 0; i < 1000 && !reachedZero; i++) {
                double d = safety.filter(
                                command(T0, false, false, false, Map.of("slew", 1.0)), parked, DT, T0)
                        .demand("slew");
                assertTrue(d <= previous + EPS, "ramp-down must be monotonic");
                previous = d;
                reachedZero = d == 0.0;
            }
            assertTrue(reachedZero, "demand must reach exactly 0 after deadman release");
        }

        @Test
        void deadmanReleasedFromStartKeepsOutputNeutral() {
            SafetyOutput out = safety.filter(
                    command(T0, false, false, false, Map.of("boom", 1.0)), parked, DT, T0);
            assertEquals(0.0, out.demand("boom"), EPS);
            assertFalse(out.deadmanEffective());
        }
    }

    // ---- rule 4: watchdog on stale commands ----------------------------------

    @Nested
    class Rule4Watchdog {

        @Test
        void staleCommandTripsWatchdogRaisesAlarmAndActsAsDeadmanReleased() {
            rampSlewUpTo(1.0, T0);
            double rampPerTick = profile.axisById("slew").orElseThrow().commandRampRate() * DT;

            long now = T0 + 300; // command is 300 ms old > 250 ms default timeout
            SafetyOutput out = safety.filter(drive(T0, Map.of("slew", 1.0)), parked, DT, now);

            assertTrue(out.watchdogTripped());
            assertFalse(out.deadmanEffective(), "watchdog trip must act as deadman released");
            assertTrue(out.activeAlarms().contains(SafetyController.WATCHDOG_ALARM));
            assertEquals(1.0 - rampPerTick, out.demand("slew"), EPS,
                    "stale command demand must ramp down like a deadman release");
        }

        @Test
        void freshCommandDoesNotTripWatchdog() {
            long now = T0 + 200; // 200 ms old <= 250 ms
            SafetyOutput out = safety.filter(drive(T0, Map.of()), parked, DT, now);
            assertFalse(out.watchdogTripped());
            assertTrue(out.deadmanEffective());
            assertFalse(out.activeAlarms().contains(SafetyController.WATCHDOG_ALARM));
        }

        @Test
        void watchdogTimeoutIsConfigurable() {
            SafetyController tight = new SafetyController(profile, 100);
            long now = T0 + 150;
            SafetyOutput out = tight.filter(drive(T0, Map.of()), parked, DT, now);
            assertTrue(out.watchdogTripped());
        }
    }

    // ---- rule 5: clamp + per-axis ramp limiting -------------------------------

    @Nested
    class Rule5ClampAndRamp {

        @Test
        void outOfRangeDemandsAreClampedToUnitRange() {
            // Huge dt so the ramp limiter cannot mask the clamp.
            SafetyOutput out = safety.filter(
                    drive(T0, Map.of("slew", 5.0, "boom", -7.0)), parked, 10.0, T0);
            assertEquals(1.0, out.demand("slew"), EPS);
            assertEquals(-1.0, out.demand("boom"), EPS);
        }

        @Test
        void demandChangePerTickIsLimitedToRampRateTimesDt() {
            AxisSpec slew = profile.axisById("slew").orElseThrow();
            double maxDelta = slew.commandRampRate() * DT;

            SafetyOutput first = safety.filter(drive(T0, Map.of("slew", 1.0)), parked, DT, T0);
            assertEquals(maxDelta, first.demand("slew"), EPS);

            SafetyOutput second = safety.filter(drive(T0, Map.of("slew", 1.0)), parked, DT, T0);
            assertEquals(2 * maxDelta, second.demand("slew"), EPS);
        }

        @Test
        void rampLimitAppliesToReversalsToo() {
            rampSlewUpTo(1.0, T0);
            double maxDelta = profile.axisById("slew").orElseThrow().commandRampRate() * DT;

            SafetyOutput out = safety.filter(drive(T0, Map.of("slew", -1.0)), parked, DT, T0);
            assertEquals(1.0 - maxDelta, out.demand("slew"), EPS,
                    "a full reversal must still move at most rampRate*dt per tick");
        }
    }

    // ---- rule 6: position limit stop ------------------------------------------

    @Nested
    class Rule6LimitStop {

        @Test
        void demandPushingPastMaxLimitIsZeroedWithAlarm() {
            Map<String, Double> atMax = new LinkedHashMap<>(parked);
            atMax.put("slew", 180.0); // slew max
            SafetyOutput out = safety.filter(drive(T0, Map.of("slew", 1.0)), atMax, DT, T0);
            assertEquals(0.0, out.demand("slew"), EPS);
            assertTrue(out.activeAlarms().contains("slew at limit"));
        }

        @Test
        void demandPushingPastMinLimitIsZeroedWithAlarm() {
            Map<String, Double> atMin = new LinkedHashMap<>(parked);
            atMin.put("extension", 0.0); // extension min
            SafetyOutput out = safety.filter(drive(T0, Map.of("extension", -1.0)), atMin, DT, T0);
            assertEquals(0.0, out.demand("extension"), EPS);
            assertTrue(out.activeAlarms().contains("extension at limit"));
        }

        @Test
        void positionBeyondLimitIsStillBlockedOutward() {
            Map<String, Double> overMax = new LinkedHashMap<>(parked);
            overMax.put("boom", 80.0); // boom max is 75
            SafetyOutput out = safety.filter(drive(T0, Map.of("boom", 0.5)), overMax, DT, T0);
            assertEquals(0.0, out.demand("boom"), EPS);
            assertTrue(out.activeAlarms().contains("boom at limit"));
        }

        @Test
        void demandMovingBackInsideIsAllowed() {
            Map<String, Double> atMax = new LinkedHashMap<>(parked);
            atMax.put("slew", 180.0);
            double maxDelta = profile.axisById("slew").orElseThrow().commandRampRate() * DT;

            SafetyOutput out = safety.filter(drive(T0, Map.of("slew", -1.0)), atMax, DT, T0);
            assertEquals(-maxDelta, out.demand("slew"), EPS,
                    "inward motion away from the limit must be allowed");
            assertFalse(out.activeAlarms().contains("slew at limit"));
        }
    }
}
