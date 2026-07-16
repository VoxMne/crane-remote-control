package com.vukotic.crane.ui.backend;

import com.vukotic.crane.core.control.ControlLoop;
import com.vukotic.crane.core.driver.CraneDriver;
import com.vukotic.crane.core.model.CraneCommand;
import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.model.CraneState;
import com.vukotic.crane.core.safety.SafetyController;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * The real backend: adapts the crane-core {@link ControlLoop} (50 Hz on its own
 * thread, full safety layer) plus any {@link CraneDriver} to the UI's
 * {@link CraneBackend} seam. v1 wires it to the simulator; a hardware driver
 * plugs in here without any UI change.
 */
public final class ControlLoopBackend implements CraneBackend {

    private final CraneDriver driver;
    private final ControlLoop loop;

    public ControlLoopBackend(CraneProfile profile, CraneDriver driver) {
        this.driver = Objects.requireNonNull(driver, "driver");
        this.loop = new ControlLoop(profile, driver, new SafetyController(profile));
    }

    /** Shown in the status panel, e.g. "Simulator". */
    public String driverName() {
        return driver.name();
    }

    /** Connects the driver (via the loop) and starts ticking. Idempotent. */
    public void start() {
        loop.start();
    }

    /** Stops the loop (which zeroes demands) and disconnects the driver. */
    public void stop() {
        loop.stop();
        driver.disconnect();
    }

    /** Registers a listener called on the loop thread for every published state. */
    public void addStateListener(Consumer<CraneState> listener) {
        loop.addStateListener(listener);
    }

    public void removeStateListener(Consumer<CraneState> listener) {
        loop.removeStateListener(listener);
    }

    @Override
    public void submitCommand(CraneCommand command) {
        loop.submitCommand(command);
    }

    @Override
    public CraneState latestState() {
        return loop.latestState();
    }
}
