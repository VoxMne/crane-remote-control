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

    /** Assist chain applied to raw demands before safety; swapped atomically at runtime. */
    private volatile List<DemandFilter> demandFilters = List.of();

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

    /**
     * Replaces the assist filter chain, applied in list order to the raw demands
     * of every subsequent tick, before the safety layer. Thread-safe; pass an
     * empty list to disable all assists.
     */
    public void setDemandFilters(List<DemandFilter> filters) {
        this.demandFilters = List.copyOf(Objects.requireNonNull(filters, "filters"));
    }

    /** Registers a listener invoked on the loop thread after every published state. */
    public void addStateListener(Consumer<CraneState> listener) {
        stateListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeStateListener(Consumer<CraneState> listener) {
        stateListeners.remove(listener);
    }

    /** Replaces null and non-finite demands with neutral, leaving the rest alone. */
    private static Map<String, Double> sanitised(Map<String, Double> demands) {
        Map<String, Double> clean = new LinkedHashMap<>();
        demands.forEach((id, value) ->
                clean.put(id, value != null && Double.isFinite(value) ? value : 0.0));
        return clean;
    }

    /** Scheduled-thread entry point: derives real time, then delegates to tick(). */
    private void scheduledTick() {
        try {
            long nowNanos = System.nanoTime();
            double dtSeconds = lastTickNanos < 0
                    ? 1.0 / tickHz
                    : Math.clamp((nowNanos - lastTickNanos) / 1_000_000_000.0, 0.0, MAX_DT_SECONDS);
            lastTickNanos = nowNanos;
            // Monotonic: command freshness must not depend on the wall clock.
            tick(com.vukotic.crane.core.MonotonicClock.millis(), dtSeconds);
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
        CraneCommand rawCommand = latestCommand.get();
        CraneCommand command = rawCommand;

        // Positions are read BEFORE the safety pass, not after it. Reading them
        // afterwards meant the first tick following a telemetry gap was filtered
        // against the cached (or placeholder) positions from before the gap: if the
        // recovered frame said an axis was already at its limit, one outward
        // command went out before that limit was known.
        Map<String, Double> positions = driver.isConnected()
                ? driver.readState().axisPositions()
                : lastKnownPositions;
        if (positions == null) {
            positions = latestState.get().axisPositions();
        }
        lastKnownPositions = positions;

        // A driver that cannot carry motion right now (a serial link with stale or
        // unverifiable telemetry) suppresses it HERE, before safety, and the ramp
        // limiter is FORCED to zero rather than allowed to ramp down towards it.
        // Ramping down still left 0.96 after one closed 20 ms tick from full
        // demand, so a brief dropout — shorter than the ramp — recovered from wire
        // zero to nearly full demand in a single tick. A gated link is not a
        // controlled stop; it is no link, and the remembered output must go with it.
        if (!driver.acceptsMotion()) {
            command = command.withMotionSuppressed(profile);
            // The reset goes too, but the raw DEMANDS stay: neutrality is still
            // judged on what the operator's controls are really doing, while the
            // reset itself is refused because nobody can confirm the machine's
            // state through a link that is not talking. Two separate rules, and
            // they have to compose — dropping the reset via withMotionSuppressed
            // alone stopped working the moment eligibility began reading the raw
            // command, which is exactly what the regression test caught.
            rawCommand = rawCommand.withResetSuppressed();
            safety.forceOutputToZero();
        }

        // Assist chain (smoothing, anti-sway, ...) reshapes raw demands first;
        // the safety layer below remains the final authority on what goes out.
        // A throwing filter must not be able to swallow the tick: E-STOP and the
        // deadman ride on this same command, so a broken assist falls back to the
        // unshaped command rather than skipping safety altogether.
        List<DemandFilter> filters = demandFilters;
        if (!filters.isEmpty()) {
            CraneState last = latestState.get();
            // Sanitised going IN as well as coming out. The safety layer maps a
            // non-finite demand to zero, but an assist handed an infinity can
            // legitimately clamp it to a finite +1 — turning a nonsense input into
            // full-speed motion that safety then sees as perfectly valid.
            Map<String, Double> shaped = sanitised(command.axisDemands());
            try {
                for (DemandFilter filter : filters) {
                    shaped = filter.apply(shaped, last, dtSeconds);
                }
                command = new CraneCommand(command.timestampMillis(), sanitised(shaped),
                        command.deadmanHeld(), command.estopRequested(),
                        command.resetRequested());
            } catch (RuntimeException e) {
                System.err.println("[control-loop] assist filter failed, "
                        + "falling back to unshaped demands: " + e);
            }
        }

        // Reset eligibility is judged on the RAW operator command, never on what
        // the assists made of it. A collision guard zeroing a held control would
        // otherwise let RESET through with the operator's lever still displaced,
        // and anti-sway injecting a correction would refuse a genuinely neutral
        // one. "Every control at neutral" is a statement about the operator.
        SafetyOutput safetyOutput =
                safety.filter(command, rawCommand, positions, dtSeconds, nowMillis);
        driver.sendDemands(safetyOutput.filteredDemands());
        DriverState driverState = driver.readState();
        lastKnownPositions = driverState.axisPositions();

        // A driver fault belongs in the operator's alarm list, not on stderr.
        List<String> alarms = safetyOutput.activeAlarms();
        java.util.Optional<String> fault = driver.fault();
        if (fault.isPresent()) {
            alarms = new java.util.ArrayList<>(alarms);
            alarms.add(driver.name() + ": " + fault.get());
        }

        CraneState state = new CraneState(
                nowMillis,
                driverState.axisPositions(),
                driverState.axisVelocities(),
                safetyOutput.estopLatched(),
                safetyOutput.deadmanEffective(),
                safetyOutput.watchdogTripped(),
                alarms);
        latestState.set(state);
        for (Consumer<CraneState> listener : stateListeners) {
            listener.accept(state);
        }
        return state;
    }
}
