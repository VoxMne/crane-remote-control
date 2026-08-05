package com.vukotic.crane.ui.backend;

import com.vukotic.crane.core.control.ControlLoop;
import com.vukotic.crane.core.driver.CraneDriver;
import com.vukotic.crane.core.model.CraneCommand;
import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.model.CraneState;
import com.vukotic.crane.core.assist.AntiSwayFilter;
import com.vukotic.crane.core.assist.CollisionGuardFilter;
import com.vukotic.crane.core.assist.MotionSmoothingFilter;
import com.vukotic.crane.core.control.DemandFilter;
import com.vukotic.crane.core.geometry.Aabb;
import com.vukotic.crane.core.geometry.CraneGeometry;
import com.vukotic.crane.core.safety.SafetyController;

import java.util.List;

import java.util.ArrayList;
import java.util.List;
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
    private final SafetyController safety;
    /** Null when no verified geometry exists for this crane — see {@link #hasInterferenceProtection()}. */
    private final CollisionGuardFilter collisionGuard;

    public ControlLoopBackend(CraneProfile profile, CraneDriver driver) {
        this.driver = Objects.requireNonNull(driver, "driver");
        this.safety = new SafetyController(profile);
        this.loop = new ControlLoop(profile, driver, safety);
        // Interference protection needs to know the machine's actual dimensions.
        // Every backend used to install the standard loader geometry regardless of
        // profile, so a crane with different reach was guarded against a shape it
        // does not have — and the HMI still claimed the protection was active.
        this.collisionGuard = CraneGeometry.forProfile(profile)
                .map(geometry -> new CollisionGuardFilter(profile, geometry))
                .orElse(null);
    }

    /**
     * Whether interference protection is real for this crane. False when the
     * profile has no verified geometry: the guard is then absent rather than
     * guessing, and the UI must say so instead of showing a protection that is
     * not watching anything.
     */
    public boolean hasInterferenceProtection() {
        return collisionGuard != null;
    }

    /** Re-asserts a latched emergency stop onto this session's fresh controller. */
    public void engageEstopLatch() {
        safety.engageEstopLatch();
    }

    /** True while this session's safety controller holds the E-STOP latch. */
    public boolean isEstopLatched() {
        return safety.isEstopLatched();
    }

    /**
     * Tells the collision guard where loads are standing, in the crane frame, so
     * the arm will not sweep through them.
     */
    public void setLoadObstacles(List<Aabb> loads) {
        if (collisionGuard != null) {
            collisionGuard.setLoadObstacles(loads);
        }
    }

    /** True while interference protection is holding an axis back. */
    public boolean isCollisionBlocking() {
        return collisionGuard != null && collisionGuard.isBlocking();
    }

    /** Shown in the status panel, e.g. "Simulator". */
    public String driverName() {
        return driver.name();
    }

    /** Connects the driver (via the loop) and starts ticking. Idempotent. */
    public void start() {
        loop.start();
    }

    /**
     * Stops the loop (which zeroes demands) and disconnects the driver.
     *
     * <p>The disconnect runs in a {@code finally}: if the final zero-demand write
     * fails — exactly what happens when a serial cable has already been pulled —
     * the port must still be released rather than leaked.
     */
    public void stop() {
        try {
            loop.stop();
        } finally {
            driver.disconnect();
        }
    }

    /**
     * Enables/disables the assist filters. Smoothing runs first (shapes operator
     * input), anti-sway second so its damping correction is never delayed by the
     * smoother. Fresh filter instances each call — no stale per-axis state.
     */
    public void configureAssists(boolean smoothing, boolean antiSway) {
        List<DemandFilter> chain = new ArrayList<>();
        if (smoothing) {
            chain.add(new MotionSmoothingFilter());
        }
        if (antiSway) {
            chain.add(new AntiSwayFilter());
        }
        // Interference protection is not optional and runs last, so it vetoes
        // whatever the assists asked for. The safety layer still follows it.
        // Absent only when this crane has no verified geometry to guard against.
        if (collisionGuard != null) {
            chain.add(collisionGuard);
        }
        loop.setDemandFilters(chain);
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
