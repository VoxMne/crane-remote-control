package com.vukotic.crane.sim;

import com.vukotic.crane.core.driver.DriverState;
import com.vukotic.crane.core.model.AxisSpec;
import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.model.CraneProfiles;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulatedCraneDriverSwayTest {

    private static final double DT = 0.02;

    @Test
    void statePublishesSwayEntries() {
        SimulatedCraneDriver driver = SimulatedCraneDriver.manuallyStepped();
        driver.connect(CraneProfiles.demoKnuckleBoom());
        DriverState state = driver.readState();
        assertTrue(state.axisPositions().containsKey(SimulatedCraneDriver.LOAD_SWAY_KEY));
        assertTrue(state.axisVelocities().containsKey(SimulatedCraneDriver.LOAD_SWAY_VEL_KEY));
    }

    @Test
    void fullSlewExcitesVisibleSwayWhichThenDecays() {
        SimulatedCraneDriver driver = SimulatedCraneDriver.manuallyStepped();
        driver.connect(CraneProfiles.demoKnuckleBoom());

        driver.sendDemands(Map.of("slew", 1.0));
        double peak = 0.0;
        for (double t = 0; t < 2.0; t += DT) {
            driver.step(DT);
            peak = Math.max(peak, Math.abs(sway(driver)));
        }
        assertTrue(peak > 2.0, "full-speed slew should visibly excite the load, peak " + peak);
        assertTrue(peak < 60.0, "sway magnitude should stay physical, peak " + peak);

        driver.sendDemands(Map.of("slew", 0.0));
        for (double t = 0; t < 30.0; t += DT) {
            driver.step(DT);
        }
        assertTrue(Math.abs(sway(driver)) < 1.5,
                "sway should decay at rest, still " + sway(driver));
    }

    @Test
    void profileWithoutWinchUsesFallbackRopeAndDoesNotBlowUp() {
        CraneProfile slewOnly = new CraneProfile("slew-only", "Slew only", List.of(
                new AxisSpec("slew", "Slew", "deg", -180, 180, 15, 2.0)));
        SimulatedCraneDriver driver = SimulatedCraneDriver.manuallyStepped();
        driver.connect(slewOnly);

        driver.sendDemands(Map.of("slew", 1.0));
        for (double t = 0; t < 5.0; t += DT) {
            driver.step(DT);
        }
        double sway = sway(driver);
        assertTrue(Double.isFinite(sway) && Math.abs(sway) < 60.0,
                "fallback rope must keep the model sane, sway=" + sway);
    }

    @Test
    void steadyWindHoldsTheLoadOffVertical() {
        SimulatedCraneDriver driver = SimulatedCraneDriver.manuallyStepped();
        driver.connect(CraneProfiles.demoKnuckleBoom());
        driver.sendDemands(Map.of("winch", 1.0));      // pay out some rope
        for (double t = 0; t < 6.0; t += DT) {
            driver.step(DT);
        }
        driver.sendDemands(Map.of());
        for (double t = 0; t < 20.0; t += DT) {        // let it settle in still air
            driver.step(DT);
        }
        double still = sway(driver);

        // At slew 0 the boom points along +X (east); an easterly blows back
        // along it, so the load should hang measurably off vertical.
        driver.setWind(15.0, 90.0);
        for (double t = 0; t < 40.0; t += DT) {
            driver.step(DT);
        }
        double windy = sway(driver);

        assertTrue(Math.abs(still) < 1.0, "should be near vertical in still air: " + still);
        assertTrue(Math.abs(windy) > 3.0,
                "15 m/s wind should hold the load clearly off vertical, got " + windy);
        assertTrue(Math.abs(windy) < 20.0, "…but not blow it horizontal: " + windy);
    }

    private static double sway(SimulatedCraneDriver driver) {
        return driver.readState().axisPositions().get(SimulatedCraneDriver.LOAD_SWAY_KEY);
    }
}
