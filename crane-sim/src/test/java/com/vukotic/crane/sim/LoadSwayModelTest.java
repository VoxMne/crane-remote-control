package com.vukotic.crane.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadSwayModelTest {

    private static final double DT = 0.005;

    @Test
    void displacedPendulumOscillatesAndDecays() {
        LoadSwayModel model = new LoadSwayModel();
        model.displace(10.0);

        int signChanges = 0;
        double previous = model.angleDegrees();
        for (double t = 0; t < 20.0; t += DT) {
            model.step(DT, 5.0, 0.0, 0.0);
            double angle = model.angleDegrees();
            if (Math.signum(angle) != Math.signum(previous) && Math.abs(angle) > 1e-6) {
                signChanges++;
            }
            previous = angle;
        }
        assertTrue(signChanges >= 4, "expected oscillation, saw " + signChanges + " sign changes");
        assertTrue(Math.abs(model.angleDegrees()) < 3.0,
                "expected decay from 10 deg, still at " + model.angleDegrees());
    }

    @Test
    void longerRopeSwingsSlower() {
        assertTrue(quarterPeriodSeconds(8.0) > 1.5 * quarterPeriodSeconds(2.0),
                "period must grow with rope length");
    }

    private static double quarterPeriodSeconds(double ropeLength) {
        LoadSwayModel model = new LoadSwayModel(0.0); // undamped for a clean period
        model.displace(10.0);
        for (double t = 0; t < 10.0; t += DT) {
            model.step(DT, ropeLength, 0.0, 0.0);
            if (model.angleDegrees() <= 0.0) {
                return t;
            }
        }
        throw new AssertionError("pendulum never crossed zero for L=" + ropeLength);
    }

    @Test
    void resetForgetsAllState() {
        LoadSwayModel model = new LoadSwayModel();
        model.displace(15.0);
        model.step(DT, 5.0, 2.0, 0.0);
        model.reset();
        assertEquals(0.0, model.angleDegrees());
        assertEquals(0.0, model.angularVelocityDegreesPerSecond());
    }
}
