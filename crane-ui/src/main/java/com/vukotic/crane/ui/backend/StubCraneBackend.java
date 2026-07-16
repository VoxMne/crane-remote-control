package com.vukotic.crane.ui.backend;

import com.vukotic.crane.core.model.AxisSpec;
import com.vukotic.crane.core.model.CraneCommand;
import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.model.CraneState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * THROWAWAY stand-in backend so the HMI can be developed and demoed before the
 * real core exists — replaced by the real control loop at integration (M2b).
 *
 * <p>Runs a ~60 Hz daemon tick that integrates the latest command's demands
 * into positions at a fraction of each axis' {@link AxisSpec#maxVelocity()},
 * honoring position limits. Rough safety behavior for demo purposes only:
 * no motion unless the deadman is held, E-STOP latches until a reset arrives
 * with all controls neutral, stale commands (&gt;250 ms) trip the watchdog.
 * The authoritative implementations live in crane-core.
 */
public final class StubCraneBackend implements CraneBackend {

    private static final long TICK_MILLIS = 16;          // ~60 Hz
    private static final long WATCHDOG_MILLIS = 250;
    private static final double SPEED_FRACTION = 0.6;    // of AxisSpec.maxVelocity

    private final CraneProfile profile;
    private final AtomicReference<CraneCommand> latestCommand;
    private final AtomicReference<CraneState> latestState;

    // Tick-thread-only mutable state.
    private final Map<String, Double> positions = new LinkedHashMap<>();
    private boolean estopLatched;
    private long lastTickNanos;

    private ScheduledExecutorService executor;

    public StubCraneBackend(CraneProfile profile) {
        this.profile = profile;
        this.latestCommand = new AtomicReference<>(CraneCommand.neutral(profile));
        CraneState initial = CraneState.initial(profile);
        this.latestState = new AtomicReference<>(initial);
        positions.putAll(initial.axisPositions());
    }

    /** Starts the internal tick thread. */
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        lastTickNanos = System.nanoTime();
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "stub-crane-backend");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleAtFixedRate(this::tick, 0, TICK_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** Stops the internal tick thread. */
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public void submitCommand(CraneCommand command) {
        latestCommand.set(command);
    }

    @Override
    public CraneState latestState() {
        return latestState.get();
    }

    private void tick() {
        CraneCommand command = latestCommand.get();
        long now = System.currentTimeMillis();
        double dt = (System.nanoTime() - lastTickNanos) / 1e9;
        lastTickNanos = System.nanoTime();

        boolean watchdogTripped = now - command.timestampMillis() > WATCHDOG_MILLIS;
        if (command.estopRequested()) {
            estopLatched = true;
        } else if (estopLatched && command.resetRequested()
                && command.allNeutral() && !command.deadmanHeld()) {
            estopLatched = false;
        }

        boolean motionAllowed = !estopLatched && command.deadmanHeld() && !watchdogTripped;

        Map<String, Double> velocities = new LinkedHashMap<>();
        for (AxisSpec axis : profile.axes()) {
            double demand = motionAllowed ? Math.clamp(command.demand(axis.id()), -1.0, 1.0) : 0.0;
            double velocity = demand * axis.maxVelocity() * SPEED_FRACTION;
            double oldPosition = positions.get(axis.id());
            double newPosition = axis.clampPosition(oldPosition + velocity * dt);
            positions.put(axis.id(), newPosition);
            velocities.put(axis.id(), dt > 0 ? (newPosition - oldPosition) / dt : 0.0);
        }

        List<String> alarms = new ArrayList<>();
        if (estopLatched) {
            alarms.add("E-STOP latched — release controls and press RESET");
        }
        if (watchdogTripped) {
            alarms.add("Watchdog: no fresh command (stub)");
        }

        latestState.set(new CraneState(now, Map.copyOf(positions), velocities,
                estopLatched, command.deadmanHeld(), watchdogTripped, alarms));
    }
}
