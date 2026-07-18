package com.vukotic.crane.core.assist;

import com.vukotic.crane.core.model.CraneState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AntiSwayFilterTest {

    private static CraneState stateWithSway(double swayDeg, double swayVelDegPerSec) {
        return new CraneState(0L,
                Map.of("slew", 0.0, "loadSway", swayDeg),
                Map.of("slew", 0.0, "loadSwayVel", swayVelDegPerSec),
                false, false, false, List.of());
    }

    private static CraneState stateWithoutSwayData() {
        return new CraneState(0L, Map.of("slew", 0.0), Map.of("slew", 0.0),
                false, false, false, List.of());
    }

    @Test
    void correctsDemandAgainstSway() {
        AntiSwayFilter filter = new AntiSwayFilter();
        double out = filter.apply(Map.of("slew", 0.5), stateWithSway(10.0, 0.0), 0.02).get("slew");
        assertEquals(0.5 - AntiSwayFilter.DEFAULT_KP * 10.0, out, 1e-9);
    }

    @Test
    void passesThroughWithoutSwayData() {
        AntiSwayFilter filter = new AntiSwayFilter();
        Map<String, Double> out = filter.apply(Map.of("slew", 0.7), stateWithoutSwayData(), 0.02);
        assertEquals(0.7, out.get("slew"), 1e-12);
    }

    @Test
    void neutralOperatorWithTinySwayStaysStill() {
        AntiSwayFilter filter = new AntiSwayFilter();
        double out = filter.apply(Map.of("slew", 0.0), stateWithSway(0.3, 0.4), 0.02).get("slew");
        assertEquals(0.0, out, "residual micro-sway must not cause a perpetual crawl");
    }

    @Test
    void neutralOperatorWithRealSwayGetsDamped() {
        AntiSwayFilter filter = new AntiSwayFilter();
        double out = filter.apply(Map.of("slew", 0.0), stateWithSway(8.0, 0.0), 0.02).get("slew");
        assertTrue(out < 0.0, "positive sway must produce a negative damping demand, got " + out);
    }

    @Test
    void correctionIsClamped() {
        AntiSwayFilter filter = new AntiSwayFilter();
        double out = filter.apply(Map.of("slew", -0.5), stateWithSway(90.0, 200.0), 0.02).get("slew");
        assertTrue(out >= -1.0 && out <= 1.0);
    }

    @Test
    void untouchedAxesPassThrough() {
        AntiSwayFilter filter = new AntiSwayFilter();
        Map<String, Double> out = filter.apply(
                Map.of("slew", 0.2, "boom", 0.9), stateWithSway(10.0, 0.0), 0.02);
        assertEquals(0.9, out.get("boom"), 1e-12);
    }
}
