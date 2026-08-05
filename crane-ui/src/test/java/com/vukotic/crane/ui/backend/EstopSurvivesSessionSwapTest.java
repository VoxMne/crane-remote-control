package com.vukotic.crane.ui.backend;

import com.vukotic.crane.core.model.CraneCommand;
import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.model.CraneProfiles;
import com.vukotic.crane.sim.SimulatedCraneDriver;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A latched emergency stop has to survive being rebuilt.
 *
 * <p>Switching crane profile or driver tears the whole session down and builds a
 * fresh {@code SafetyController}. The UI used to carry the latch across by reading
 * its own E-STOP toggle — but that toggle is part of the panel being rebuilt, and
 * came back unselected. So the first switch left the machine latched under an
 * un-pressed button, and the second switch believed the button and built an
 * unlatched controller: an emergency stop cleared by changing a dropdown twice,
 * with nobody having pressed RESET.
 *
 * <p>The latch now travels from the outgoing controller, which is the authority.
 */
class EstopSurvivesSessionSwapTest {

    private final CraneProfile profile = CraneProfiles.demoKnuckleBoom();

    private ControlLoopBackend startedBackend() {
        ControlLoopBackend backend =
                new ControlLoopBackend(profile, new SimulatedCraneDriver());
        backend.configureAssists(false, false);
        backend.start();
        return backend;
    }

    /** What CraneRemoteApp.activateProfile() does, reduced to its safety-relevant core. */
    private ControlLoopBackend swapSession(ControlLoopBackend outgoing) {
        boolean latchWasEngaged = outgoing.isEstopLatched();
        outgoing.stop();
        ControlLoopBackend incoming = startedBackend();
        if (latchWasEngaged) {
            incoming.engageEstopLatch();
        }
        return incoming;
    }

    @Test
    void theLatchSurvivesTwoConsecutiveSwaps() {
        ControlLoopBackend backend = startedBackend();
        backend.submitCommand(new CraneCommand(
                com.vukotic.crane.core.MonotonicClock.millis(),
                Map.of("slew", 0.0), false, true, false));
        awaitLatched(backend);

        backend = swapSession(backend);
        assertTrue(backend.isEstopLatched(), "latch must survive the first swap");

        // The second swap is the one that used to clear it: by now the rebuilt
        // E-STOP toggle reads "not pressed", and it used to be believed.
        backend = swapSession(backend);
        assertTrue(backend.isEstopLatched(), "latch must survive the second swap too");
        backend.stop();
    }

    @Test
    void onlyAnOperatorResetClearsIt() {
        ControlLoopBackend backend = startedBackend();
        backend.submitCommand(new CraneCommand(
                com.vukotic.crane.core.MonotonicClock.millis(),
                Map.of("slew", 0.0), false, true, false));
        awaitLatched(backend);

        // Everything neutral, deadman released, reset requested: the one legal way.
        backend.submitCommand(new CraneCommand(
                com.vukotic.crane.core.MonotonicClock.millis(),
                Map.of("slew", 0.0), false, false, true));
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (backend.isEstopLatched() && System.nanoTime() < deadline) {
            sleep();
        }
        assertFalse(backend.isEstopLatched(), "a proper reset must still work");
        backend.stop();
    }

    private static void awaitLatched(ControlLoopBackend backend) {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (!backend.isEstopLatched() && System.nanoTime() < deadline) {
            sleep();
        }
        assertTrue(backend.isEstopLatched(), "E-STOP should have latched");
    }

    private static void sleep() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
