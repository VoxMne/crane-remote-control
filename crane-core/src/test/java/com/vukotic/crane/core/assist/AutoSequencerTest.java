package com.vukotic.crane.core.assist;

import com.vukotic.crane.core.model.CraneCommand;
import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.model.CraneProfiles;
import com.vukotic.crane.core.model.CraneState;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoSequencerTest {

    private final CraneProfile profile = CraneProfiles.demoKnuckleBoom();

    /** A pose far from transport on every axis. */
    private CraneState unfoldedState() {
        return state(Map.of("slew", 30.0, "boom", 40.0, "jib", 10.0,
                "extension", 3.0, "winch", 5.0));
    }

    private CraneState state(Map<String, Double> positions) {
        Map<String, Double> velocities = new LinkedHashMap<>();
        positions.keySet().forEach(id -> velocities.put(id, 0.0));
        return new CraneState(0L, positions, velocities, false, false, false, List.of());
    }

    private CraneCommand heldNeutral() {
        Map<String, Double> zeros = new LinkedHashMap<>();
        profile.axes().forEach(a -> zeros.put(a.id(), 0.0));
        return new CraneCommand(0L, zeros, true, false, false);
    }

    @Test
    void runsPhasesInFoldOrder() {
        AutoSequencer seq = new AutoSequencer();
        seq.start(profile);

        // Phase 1: extension retracts (negative demand), nothing else driven.
        CraneCommand out = seq.next(unfoldedState(), heldNeutral());
        assertEquals("extension", seq.activeAxis());
        assertTrue(out.demand("extension") < 0.0);
        assertEquals(0.0, out.demand("winch"));
        assertEquals(0.0, out.demand("slew"));

        // Extension reached -> winch becomes active.
        seq.next(state(Map.of("slew", 30.0, "boom", 40.0, "jib", 10.0,
                "extension", 0.0, "winch", 5.0)), heldNeutral());
        assertEquals("winch", seq.activeAxis());

        // Winch reached -> jib folds toward its max.
        CraneCommand jibOut = seq.next(state(Map.of("slew", 30.0, "boom", 40.0,
                "jib", 10.0, "extension", 0.0, "winch", 0.0)), heldNeutral());
        assertEquals("jib", seq.activeAxis());
        assertTrue(jibOut.demand("jib") > 0.0, "jib folds toward max");

        // Jib + boom done -> slew centers with negative demand from +30 deg.
        CraneCommand slewOut = seq.next(state(Map.of("slew", 30.0, "boom", -5.0,
                "jib", 150.0, "extension", 0.0, "winch", 0.0)), heldNeutral());
        assertEquals("slew", seq.activeAxis());
        assertTrue(slewOut.demand("slew") < 0.0);

        // Everything at transport pose -> complete.
        seq.next(state(Map.of("slew", 0.0, "boom", -5.0, "jib", 150.0,
                "extension", 0.0, "winch", 0.0)), heldNeutral());
        assertTrue(seq.isComplete());
        assertFalse(seq.isActive());
        assertEquals("", seq.activeAxis());
    }

    @Test
    void manualInputCancels() {
        AutoSequencer seq = new AutoSequencer();
        seq.start(profile);
        Map<String, Double> demands = new LinkedHashMap<>();
        profile.axes().forEach(a -> demands.put(a.id(), 0.0));
        demands.put("boom", 0.4);
        CraneCommand manual = new CraneCommand(0L, demands, true, false, false);

        CraneCommand out = seq.next(unfoldedState(), manual);
        assertFalse(seq.isActive());
        assertEquals(manual.axisDemands(), out.axisDemands(), "operator command untouched");
    }

    @Test
    void estopCancels() {
        AutoSequencer seq = new AutoSequencer();
        seq.start(profile);
        Map<String, Double> zeros = new LinkedHashMap<>();
        profile.axes().forEach(a -> zeros.put(a.id(), 0.0));
        CraneCommand estop = new CraneCommand(0L, zeros, true, true, false);

        seq.next(unfoldedState(), estop);
        assertFalse(seq.isActive());
    }

    @Test
    void deadmanFlagPassesThroughUntouched() {
        AutoSequencer seq = new AutoSequencer();
        seq.start(profile);
        Map<String, Double> zeros = new LinkedHashMap<>();
        profile.axes().forEach(a -> zeros.put(a.id(), 0.0));
        CraneCommand released = new CraneCommand(0L, zeros, false, false, false);

        CraneCommand out = seq.next(unfoldedState(), released);
        assertFalse(out.deadmanHeld(), "sequencer must never fabricate a held deadman");
        assertTrue(seq.isActive(), "sequence stays armed; safety stops the motion");
    }

    @Test
    void worksOnProfilesWithFewerAxes() {
        AutoSequencer seq = new AutoSequencer();
        CraneProfile compact = new CraneProfile("c", "Compact", List.of(
                profile.axisById("slew").orElseThrow(),
                profile.axisById("boom").orElseThrow(),
                profile.axisById("winch").orElseThrow()));
        seq.start(compact);

        CraneCommand out = seq.next(
                state(Map.of("slew", 20.0, "boom", 30.0, "winch", 4.0)),
                new CraneCommand(0L, Map.of("slew", 0.0, "boom", 0.0, "winch", 0.0),
                        true, false, false));
        assertEquals("winch", seq.activeAxis(), "first present axis in fold order");
        assertTrue(out.demand("winch") < 0.0);
    }
}
