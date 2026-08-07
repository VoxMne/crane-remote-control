package com.vukotic.crane.ui;

import com.vukotic.crane.core.model.CraneState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HMI defects found by three external audits, as tests.
 *
 * <p>Each of these describes a way the panel once lied about the machine. They are
 * cheap because the judgement now lives in a plain class — before, verifying any of
 * this meant launching the app and photographing the window.
 */
class HmiStateTest {

    private static CraneState state(boolean estop, boolean deadman, boolean watchdog) {
        Map<String, Double> zeros = Map.of("slew", 0.0, "boom", 0.0);
        return new CraneState(1_000, zeros, zeros, estop, deadman, watchdog,
                estop ? List.of("E-STOP latched") : List.of());
    }

    private static HmiState hmi(CraneState live) {
        return new HmiState(live, false, false, false, false);
    }

    @Test
    void aLatchedEstopIsNeverGreen() {
        // Audit 2: "The HMI can show E-STOP and green RUN ENABLED simultaneously."
        // The deadman is held AND the latch is on - the crane is going nowhere.
        HmiState state = hmi(state(true, true, false));

        assertFalse(state.running(), "a latched crane is not running");
        assertEquals("E-STOP LATCHED — PRESS RESET", state.deadmanText());
        assertEquals("E-STOP LATCHED", state.pill().text());
    }

    @Test
    void theLatchOutranksEveryOtherHeaderMessage() {
        CraneState latched = state(true, false, false);
        for (HmiState variant : List.of(
                new HmiState(latched, true, false, false, false),   // replaying
                new HmiState(latched, false, true, false, false),   // demo
                new HmiState(latched, false, false, true, false),   // driver mode
                new HmiState(latched, false, false, false, true))) { // blocked
            assertEquals("E-STOP LATCHED", variant.pill().text(),
                    "nothing may push the latch off the header");
        }
    }

    @Test
    void replayIsAnnouncedRatherThanReportedAsRunning() {
        // Audit 3: while a recording plays, its flags are not the machine's. The
        // pill is fed the LIVE state, so a calm machine under a busy recording
        // still reads as a replay and not as motion.
        HmiState replaying = new HmiState(state(false, false, false),
                true, false, false, false);
        assertEquals("REPLAY — RECORDED", replaying.pill().text());
        assertFalse(replaying.running());
    }

    @Test
    void deadmanHeldOnAHealthyCraneIsRunning() {
        HmiState state = hmi(state(false, true, false));
        assertTrue(state.running());
        assertEquals("RUN ENABLED", state.deadmanText());
        assertEquals("RUNNING", state.pill().text());
        assertEquals("pill-ok", state.pill().styleClass());
    }

    @Test
    void anIdleCraneAsksForTheDeadman() {
        HmiState state = hmi(state(false, false, false));
        assertEquals("HOLD SPACE TO RUN", state.deadmanText());
        assertEquals("READY — HOLD SPACE", state.pill().text());
    }

    @Test
    void interferenceProtectionIsDescribedHonestlyEitherWay() {
        // Audit 2: claiming a protection that is watching nothing is worse than
        // having none, so the absent case has to be stated, not implied.
        assertTrue(HmiState.interferenceNote(true).startsWith("Interference protection active"));
        assertTrue(HmiState.interferenceNote(false).contains("NO interference protection"));
    }

    @Test
    void theViewNoteSaysWhichViewToBelieve() {
        assertTrue(HmiState.viewNote(true).contains("experimental"));
        assertTrue(HmiState.viewNote(true).contains("2D"),
                "the 3D warning has to point somewhere better");
        assertTrue(HmiState.viewNote(false).contains("accurate"));
    }

    @Test
    void blockingAndDriverModeRankBelowTheLatchButAboveRunning() {
        HmiState blocked = new HmiState(state(false, true, false),
                false, false, false, true);
        assertEquals("BLOCKED — OBSTACLE", blocked.pill().text(),
                "an operator holding the deadman into an obstacle needs to know why");

        HmiState driving = new HmiState(state(false, false, false),
                false, false, true, false);
        assertEquals("DRIVER MODE", driving.pill().text());
    }
}
