package com.vukotic.crane.ui.input;

import com.vukotic.crane.core.model.CraneCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-Java tests — no JavaFX toolkit required. */
class OperatorInputTest {

    private static final List<String> AXES = List.of("slew", "boom", "jib", "extension", "winch");

    private OperatorInput input;

    @BeforeEach
    void setUp() {
        input = new OperatorInput(KeyBindings.defaults(), AXES);
    }

    @Test
    void sliderValueAloneDrivesTheAxis() {
        input.setSliderDemand("boom", 0.4);
        assertEquals(0.4, input.currentDemands().get("boom"));
    }

    @Test
    void sliderValuesAreClampedToUnitRange() {
        input.setSliderDemand("boom", 3.7);
        input.setSliderDemand("jib", -2.0);
        assertEquals(+1.0, input.currentDemands().get("boom"));
        assertEquals(-1.0, input.currentDemands().get("jib"));
    }

    @Test
    void keyPressIsFullDemandAndReleaseFallsBackToSlider() {
        input.setSliderDemand("slew", 0.3);
        input.keyPressed("Q");
        assertEquals(1.0, input.currentDemands().get("slew"), "key (|1.0|) beats slider (|0.3|)");
        input.keyReleased("Q");
        assertEquals(0.3, input.currentDemands().get("slew"), "release falls back to slider");
    }

    @Test
    void largerAbsoluteValueWinsPerAxis() {
        // Opposing keys cancel to 0 on slew, so the slider (|0.5|) wins there.
        input.keyPressed("Q");
        input.keyPressed("A");
        input.setSliderDemand("slew", -0.5);
        // Boom has only a slider value; winch only a key.
        input.setSliderDemand("boom", -0.8);
        input.keyPressed("G");
        var demands = input.currentDemands();
        assertEquals(-0.5, demands.get("slew"));
        assertEquals(-0.8, demands.get("boom"));
        assertEquals(-1.0, demands.get("winch"));
    }

    @Test
    void keyboardWinsTiesOnEqualMagnitude() {
        input.setSliderDemand("winch", -1.0);
        input.keyPressed("T"); // winch +1.0, same magnitude
        assertEquals(+1.0, input.currentDemands().get("winch"));
    }

    @Test
    void demandsCoverExactlyTheProfileAxes() {
        var demands = input.currentDemands();
        assertEquals(AXES, List.copyOf(demands.keySet()));
        assertTrue(demands.values().stream().allMatch(d -> d == 0.0));
    }

    @Test
    void deadmanTracksSpaceKey() {
        assertFalse(input.deadmanHeld());
        input.keyPressed("SPACE");
        assertTrue(input.deadmanHeld());
        input.keyReleased("SPACE");
        assertFalse(input.deadmanHeld());
    }

    @Test
    void escapeLatchesEstopRequestUntilReset() {
        input.keyPressed("ESCAPE");
        assertTrue(input.estopRequested());
        input.keyReleased("ESCAPE");
        assertTrue(input.estopRequested(), "E-STOP request stays latched after key release");
        input.requestReset();
        assertFalse(input.estopRequested());
    }

    @Test
    void resetIsReportedExactlyOnce() {
        input.requestReset();
        CraneCommand first = input.snapshot(1000);
        CraneCommand second = input.snapshot(1016);
        assertTrue(first.resetRequested());
        assertFalse(second.resetRequested());
    }

    @Test
    void snapshotCarriesAllInputSourcesAndTimestamp() {
        input.setSliderDemand("extension", 0.25);
        input.keyPressed("W");
        input.keyPressed("SPACE");
        input.setEstopRequested(true);

        CraneCommand command = input.snapshot(123456L);

        assertEquals(123456L, command.timestampMillis());
        assertEquals(1.0, command.demand("boom"));
        assertEquals(0.25, command.demand("extension"));
        assertEquals(0.0, command.demand("slew"));
        assertTrue(command.deadmanHeld());
        assertTrue(command.estopRequested());
        assertFalse(command.resetRequested());
    }
}
