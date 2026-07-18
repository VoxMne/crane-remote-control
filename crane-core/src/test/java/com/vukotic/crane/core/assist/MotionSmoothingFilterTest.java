package com.vukotic.crane.core.assist;

import com.vukotic.crane.core.model.CraneProfiles;
import com.vukotic.crane.core.model.CraneState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MotionSmoothingFilterTest {

    private static final double DT = 0.02;
    private static final double ACCEL = 8.0;

    private final CraneState state = CraneState.initial(CraneProfiles.demoKnuckleBoom());

    @Test
    void demandAccelerationIsBounded() {
        MotionSmoothingFilter filter = new MotionSmoothingFilter(ACCEL);
        List<Double> outputs = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            outputs.add(filter.apply(Map.of("slew", 1.0), state, DT).get("slew"));
        }
        double previousDelta = 0.0;
        for (int i = 0; i < outputs.size(); i++) {
            if (outputs.get(i) >= 1.0 - 1e-9) {
                break; // arrival tick may snap onto the target; bound applies en route
            }
            double delta = outputs.get(i) - (i == 0 ? 0.0 : outputs.get(i - 1));
            assertTrue(Math.abs(delta - previousDelta) <= ACCEL * DT * DT + 1e-9,
                    "demand acceleration exceeded at tick " + i);
            previousDelta = delta;
        }
    }

    @Test
    void reachesTargetWithoutOvershoot() {
        MotionSmoothingFilter filter = new MotionSmoothingFilter(ACCEL);
        double output = 0.0;
        double maxSeen = 0.0;
        for (int i = 0; i < 300; i++) {
            output = filter.apply(Map.of("slew", 0.6), state, DT).get("slew");
            maxSeen = Math.max(maxSeen, output);
        }
        assertEquals(0.6, output, 0.02, "should settle at the requested demand");
        assertTrue(maxSeen <= 0.6 + 1e-6, "must not overshoot, peaked at " + maxSeen);
    }

    @Test
    void clampsOutOfRangeInput() {
        MotionSmoothingFilter filter = new MotionSmoothingFilter(ACCEL);
        double output = 0.0;
        for (int i = 0; i < 500; i++) {
            output = filter.apply(Map.of("slew", 5.0), state, DT).get("slew");
        }
        assertEquals(1.0, output, 1e-6, "smoothed output saturates at +1");
    }

    @Test
    void zeroDtPassesThrough() {
        MotionSmoothingFilter filter = new MotionSmoothingFilter(ACCEL);
        assertEquals(1.0, filter.apply(Map.of("slew", 1.0), state, 0.0).get("slew"));
    }
}
