package com.vukotic.crane.core.control;

import com.vukotic.crane.core.driver.CraneDriver;
import com.vukotic.crane.core.driver.DriverState;
import com.vukotic.crane.core.model.CraneCommand;
import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.model.CraneProfiles;
import com.vukotic.crane.core.model.CraneState;
import com.vukotic.crane.core.safety.SafetyController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ControlLoop pipeline tests: command in &rarr; safety &rarr; driver &rarr; state
 * out. All tests drive {@link ControlLoop#tick(long, double)} directly with
 * explicit time — no scheduler threads, no sleeps.
 */
class ControlLoopTest {

    private static final double EPS = 1e-9;
    private static final double DT = 0.02;
    private static final long T0 = 5_000_000;

    private CraneProfile profile;
    private FakeDriver driver;
    private ControlLoop loop;

    @BeforeEach
    void setUp() {
        profile = CraneProfiles.demoKnuckleBoom();
        driver = new FakeDriver(profile);
        driver.connect(profile);
        loop = new ControlLoop(profile, driver, new SafetyController(profile));
    }

    private CraneCommand command(long timestamp, boolean deadman, boolean estop,
                                 boolean reset, Map<String, Double> demands) {
        Map<String, Double> all = new LinkedHashMap<>();
        profile.axes().forEach(axis -> all.put(axis.id(), 0.0));
        all.putAll(demands);
        return new CraneCommand(timestamp, all, deadman, estop, reset);
    }

    @Test
    void tickPipesCommandThroughSafetyToDriverAndPublishesState() {
        driver.positions.put("boom", 12.5);
        driver.velocities.put("boom", 1.5);
        loop.submitCommand(command(T0, true, false, false, Map.of("slew", 1.0)));

        CraneState state = loop.tick(T0, DT);

        // Driver received the safety-filtered (ramp-limited) demand, not the raw 1.0.
        double expected = profile.axisById("slew").orElseThrow().commandRampRate() * DT;
        assertEquals(expected, driver.lastDemands.get("slew"), EPS);
        assertEquals(1, driver.sendCount);

        // Published state mirrors driver feedback + safety flags.
        assertEquals(T0, state.timestampMillis());
        assertEquals(12.5, state.position("boom"), EPS);
        assertEquals(1.5, state.velocity("boom"), EPS);
        assertTrue(state.deadmanHeld());
        assertFalse(state.estopLatched());
        assertFalse(state.watchdogTripped());
        assertTrue(state.activeAlarms().isEmpty());
        assertSame(state, loop.latestState());
    }

    @Test
    void loopSamplesTheLatestSubmittedCommandOnly() {
        loop.submitCommand(command(T0, true, false, false, Map.of("slew", 1.0)));
        loop.submitCommand(command(T0, true, false, false, Map.of("slew", 0.0)));

        loop.tick(T0, DT);

        assertEquals(0.0, driver.lastDemands.get("slew"), EPS,
                "the loop must sample only the newest command");
    }

    @Test
    void estopCommandLatchesAndZeroesDriverDemands() {
        loop.submitCommand(command(T0, true, false, false, Map.of("winch", 1.0)));
        loop.tick(T0, DT);
        loop.submitCommand(command(T0, true, true, false, Map.of("winch", 1.0)));

        CraneState state = loop.tick(T0, DT);

        assertTrue(state.estopLatched());
        assertTrue(state.activeAlarms().contains(SafetyController.ESTOP_ALARM));
        for (String axisId : profile.axisIds()) {
            assertEquals(0.0, driver.lastDemands.get(axisId), EPS);
        }
    }

    @Test
    void staleCommandSurfacesWatchdogTripInPublishedState() {
        loop.submitCommand(command(T0, true, false, false, Map.of("jib", 0.5)));
        loop.tick(T0, DT);

        // No new command arrives; 400 ms later the sampled command is stale.
        CraneState state = loop.tick(T0 + 400, DT);

        assertTrue(state.watchdogTripped());
        assertFalse(state.deadmanHeld(), "watchdog trip must read as deadman released");
        assertTrue(state.activeAlarms().contains(SafetyController.WATCHDOG_ALARM));
    }

    @Test
    void limitAlarmUsesDriverReportedPositions() {
        driver.positions.put("slew", 180.0); // at max limit
        loop.tick(T0, DT); // first tick learns positions from the driver
        loop.submitCommand(command(T0, true, false, false, Map.of("slew", 1.0)));

        CraneState state = loop.tick(T0, DT);

        assertEquals(0.0, driver.lastDemands.get("slew"), EPS);
        assertTrue(state.activeAlarms().contains("slew at limit"));
    }

    @Test
    void stateListenerReceivesEveryPublishedState() {
        List<CraneState> seen = new ArrayList<>();
        loop.addStateListener(seen::add);

        CraneState first = loop.tick(T0, DT);
        CraneState second = loop.tick(T0 + 20, DT);

        assertEquals(List.of(first, second), seen);
    }

    @Test
    void latestStateIsNeverNullBeforeFirstTick() {
        assertNotNull(loop.latestState());
        assertFalse(loop.latestState().estopLatched());
    }

    @Test
    void startStopLifecycleIsIdempotentAndStopSendsZeroDemands() {
        loop.start();
        loop.start(); // idempotent
        assertTrue(loop.isRunning());

        loop.stop();
        loop.stop(); // idempotent
        assertFalse(loop.isRunning());

        assertNotNull(driver.lastDemands, "stop must send a final all-zero demand set");
        for (String axisId : profile.axisIds()) {
            assertEquals(0.0, driver.lastDemands.get(axisId), EPS);
        }
    }

    /** Minimal scripted driver: records demands, returns settable state. */
    private static final class FakeDriver implements CraneDriver {

        final Map<String, Double> positions = new LinkedHashMap<>();
        final Map<String, Double> velocities = new LinkedHashMap<>();
        Map<String, Double> lastDemands;
        int sendCount;
        private boolean connected;

        FakeDriver(CraneProfile profile) {
            profile.axes().forEach(axis -> {
                positions.put(axis.id(), axis.clampPosition(0.0));
                velocities.put(axis.id(), 0.0);
            });
        }

        @Override
        public String name() {
            return "Fake";
        }

        @Override
        public void connect(CraneProfile profile) {
            connected = true;
        }

        @Override
        public void disconnect() {
            connected = false;
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public void sendDemands(Map<String, Double> axisDemands) {
            lastDemands = new LinkedHashMap<>(axisDemands);
            sendCount++;
        }

        @Override
        public DriverState readState() {
            return new DriverState(new LinkedHashMap<>(positions), new LinkedHashMap<>(velocities));
        }
    }
}
