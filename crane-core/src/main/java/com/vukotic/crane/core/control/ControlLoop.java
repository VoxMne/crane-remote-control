package com.vukotic.crane.core.control;

import com.vukotic.crane.core.driver.CraneDriver;
import com.vukotic.crane.core.driver.DriverState;
import com.vukotic.crane.core.model.CraneCommand;
import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.model.CraneState;
import com.vukotic.crane.core.safety.SafetyController;
import com.vukotic.crane.core.safety.SafetyOutput;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Fixed-tick control engine (default {@value #DEFAULT_TICK_HZ} Hz) running on its own
 * single scheduled thread. Each tick: sample the latest {@link CraneCommand} &rarr;
 * {@link SafetyController} &rarr; {@code driver.sendDemands(filtered)} &rarr;
 * {@code driver.readState()} &rarr; publish a {@link CraneState}.
 *
 * <p>Thread contract (see docs/ARCHITECTURE.md &sect;Threading):
 * <ul>
 *   <li>The UI hands commands over via {@link #submitCommand(CraneCommand)} from any
 *       thread; the loop samples the latest one each tick.</li>
 *   <li>The latest published state is readable from any thread via
 *       {@link #latestState()}; listeners registered with
 *       {@link #addStateListener(Consumer)} are invoked on the loop thread and must
 *       hop to their own thread themselves (e.g. {@code Platform.runLater}).</li>
 * </ul>
 *
 * <p>For deterministic tests the per-tick logic is exposed as
 * {@link #tick(long, double)} — tests call it directly with explicit time, no
 * threads or sleeps involved.
 */
public final class ControlLoop {

    /** Default tick rate in Hz. */
    public static final double DEFAULT_TICK_HZ = 50.0;

    /** Upper bound on the dt fed into a tick, guards against scheduler hiccups. */
    private static final double MAX_DT_SECONDS = 0.25;

    private final CraneProfile profile;
    private final CraneDriver driver;
    private final SafetyController safety;
    private final double tickHz;
    private final long tickPeriodNanos;

    private final AtomicReference<CraneCommand> latestCommand;
    private final AtomicReference<CraneState> latestState;
    private final List<Consumer<CraneState>> stateListeners = new CopyOnWriteArrayList<>();

    /** Positions fed to the safety layer; refreshed from the driver every tick. */
    private volatile Map<String, Double> lastKnownPositions;

    private ScheduledExecutorService executor; // guarded by "this"
    private long lastTickNanos = -1;           // loop thread only

    public ControlLoop(CraneProfile profile, CraneDriver driver, SafetyController safety) {
        this(profile, driver, safety, DEFAULT_TICK_HZ);
    }

    public ControlLoop(CraneProfile profile, CraneDriver driver, SafetyController safety,
                       double tickHz) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.driver = Objects.requireNonNull(driver, "driver");
        this.safety = Objects.requireNonNull(safety, "safety");
        if (tickHz <= 0) {
            throw new IllegalArgumentException("tickHz must be positive");
        }
        this.tickHz = tickHz;
        this.tickPeriodNanos = Math.round(1_000_000_000.0 / tickHz);
        this.latestCommand = new AtomicReference<>(CraneCommand.neutral(profile));
        this.latestState = new AtomicReference<>(CraneState.initial(profile));
    }

    public double tickHz() {
        return tickHz;
    }

    /**
     * Starts the loop on a dedicated daemon thread. Connects the driver to the
     * profile if it is not connected yet. Idempotent: calling start on a running
     * loop is a no-op.
     */
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        if (!driver.isConnected()) {
            driver.connect(profile);
        }
        lastTickNanos = -1;
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "control-loop");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleAtFixedRate(this::scheduledTick, 0, tickPeriodNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Stops the loop and, as a final safety action, sends all-zero demands to the
     * driver. Idempotent. The driver stays connected — disconnecting is the
     * caller's decision.
     */
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
        if (driver.isConnected()) {
            Map<String, Double> zeros = new LinkedHashMap<>();
            profile.axes().forEach(axis -> zeros.put(axis.id(), 0.0));
            driver.sendDemands(zeros);
        }
    }

    public synchronized boolean isRunning() {
        return executor != null;
    }

    /**
     * Hands the latest operator command to the loop. Thread-safe; may be called at
     * any rate — the loop samples whatever is newest at each tick, older unsampled
     * commands are simply superseded.
     */
    public void submitCommand(CraneCommand command) {
        latestCommand.set(Objects.requireNonNull(command, "command"));
    }

    /** Latest published state; never null (starts as the parked initial state). */
    public CraneState latestState() {
        return latestState.get();
    }

    /** Registers a listener invoked on the loop thread after every published state. */
    public void addStateListener(Consumer<CraneState> listener) {
        stateListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeStateListener(Consumer<CraneState> listener) {
        stateListeners.remove(listener);
    }

    /** Scheduled-thread entry point: derives real time, then delegates to tick(). */
    private void scheduledTick() {
        try {
            long nowNanos = System.nanoTime();
            double dtSeconds = lastTickNanos < 0
                    ? 1.0 / tickHz
                    : Math.clamp((nowNanos - lastTickNanos) / 1_000_000_000.0, 0.0, MAX_DT_SECONDS);
            lastTickNanos = nowNanos;
            tick(System.currentTimeMillis(), dtSeconds);
        } catch (Throwable t) {
            // Never let one bad tick kill the scheduled task (scheduleAtFixedRate
            // stops silently on an uncaught exception).
            System.err.println("[control-loop] tick failed: " + t);
            t.printStackTrace();
        }
    }

    /**
     * Runs exactly one control tick: sample command &rarr; safety &rarr; driver
     * &rarr; publish state. Exposed for deterministic testing — tests call this
     * directly with explicit time instead of starting the scheduler. In production
     * only the loop thread calls it.
     *
     * @param nowMillis wall-clock time of this tick
     * @param dtSeconds time since the previous tick, in seconds
     * @return the state published this tick
     */
    public CraneState tick(long nowMillis, double dtSeconds) {
        CraneCommand command = latestCommand.get();

        Map<String, Double> positions = lastKnownPositions;
        if (positions == null) {
            positions = driver.isConnected()
                    ? driver.readState().axisPositions()
                    : latestState.get().axisPositions();
        }

        SafetyOutput safetyOutput = safety.filter(command, positions, dtSeconds, nowMillis);
        driver.sendDemands(safetyOutput.filteredDemands());
        DriverState driverState = driver.readState();
        lastKnownPositions = driverState.axisPositions();

        CraneState state = new CraneState(
                nowMillis,
                driverState.axisPositions(),
                driverState.axisVelocities(),
                safetyOutput.estopLatched(),
                safetyOutput.deadmanEffective(),
                safetyOutput.watchdogTripped(),
                safetyOutput.activeAlarms());
        latestState.set(state);
        for (Consumer<CraneState> listener : stateListeners) {
            listener.accept(state);
        }
        return state;
    }
}
