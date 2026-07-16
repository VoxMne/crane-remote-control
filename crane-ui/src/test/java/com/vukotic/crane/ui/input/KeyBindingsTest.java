package com.vukotic.crane.ui.input;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-Java tests — no JavaFX toolkit required. */
class KeyBindingsTest {

    private final KeyBindings bindings = KeyBindings.defaults();

    @Test
    void defaultMapCoversAllFiveAxesInBothDirections() {
        assertAxisAction("Q", "slew", +1.0);
        assertAxisAction("A", "slew", -1.0);
        assertAxisAction("W", "boom", +1.0);
        assertAxisAction("S", "boom", -1.0);
        assertAxisAction("E", "jib", +1.0);
        assertAxisAction("D", "jib", -1.0);
        assertAxisAction("R", "extension", +1.0);
        assertAxisAction("F", "extension", -1.0);
        assertAxisAction("T", "winch", +1.0);
        assertAxisAction("G", "winch", -1.0);
    }

    private void assertAxisAction(String key, String expectedAxis, double expectedDirection) {
        KeyBindings.AxisAction action = bindings.axisActionFor(key).orElseThrow(
                () -> new AssertionError("key %s should be bound".formatted(key)));
        assertEquals(expectedAxis, action.axisId(), "axis for key " + key);
        assertEquals(expectedDirection, action.direction(), "direction for key " + key);
    }

    @Test
    void spaceIsDeadmanAndEscapeIsEstop() {
        assertTrue(bindings.isDeadmanKey("SPACE"));
        assertTrue(bindings.isEstopKey("ESCAPE"));
        assertFalse(bindings.isDeadmanKey("Q"));
        assertFalse(bindings.isEstopKey("SPACE"));
        assertTrue(bindings.axisActionFor("SPACE").isEmpty());
        assertTrue(bindings.axisActionFor("ESCAPE").isEmpty());
    }

    @Test
    void unboundKeysAreIgnored() {
        assertTrue(bindings.axisActionFor("Z").isEmpty());
        assertFalse(bindings.isBound("Z"));
        assertTrue(bindings.isBound("Q"));
        assertTrue(bindings.isBound("SPACE"));
        assertTrue(bindings.isBound("ESCAPE"));
    }

    @Test
    void singleKeyGivesFullDemand() {
        Map<String, Double> demands = bindings.demandsFor(Set.of("Q"));
        assertEquals(Map.of("slew", 1.0), demands);
    }

    @Test
    void opposingKeysOnSameAxisCancelOut() {
        Map<String, Double> demands = bindings.demandsFor(Set.of("Q", "A"));
        assertEquals(0.0, demands.get("slew"));
    }

    @Test
    void multipleAxesCombineIndependently() {
        Map<String, Double> demands = bindings.demandsFor(Set.of("Q", "S", "T"));
        assertEquals(+1.0, demands.get("slew"));
        assertEquals(-1.0, demands.get("boom"));
        assertEquals(+1.0, demands.get("winch"));
        assertFalse(demands.containsKey("jib"), "untouched axes are absent");
    }

    @Test
    void nonAxisKeysContributeNoDemand() {
        Map<String, Double> demands = bindings.demandsFor(Set.of("SPACE", "Z"));
        assertTrue(demands.isEmpty());
    }
}
