package com.vukotic.crane.core.safety;

import com.vukotic.crane.core.model.AxisSpec;
import com.vukotic.crane.core.model.CraneCommand;
import com.vukotic.crane.core.model.CraneProfile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The safety layer between raw operator commands and the driver. Implements the six
 * rules of docs/ARCHITECTURE.md &sect;Safety semantics:
 *
 * <ol>
 *   <li>{@code estopRequested} latches; while latched every outgoing demand is 0
 *       immediately (no ramp).</li>
 *   <li>{@code resetRequested} clears the latch only if all raw demands are neutral
 *       AND the deadman is released. As an extra precaution a reset carried by a
 *       stale command (watchdog tripped) is ignored.</li>
 *   <li>Deadman not held &rArr; demands ramp to 0 at the axis
 *       {@code commandRampRate} — a controlled stop, not a frozen output.</li>
 *   <li>Watchdog: a command older than the timeout (default
 *       {@value #DEFAULT_WATCHDOG_TIMEOUT_MILLIS} ms) is treated as deadman
 *       released and raises the {@value #WATCHDOG_ALARM} alarm.</li>
 *   <li>Every demand is clamped to [-1, +1] and its change per tick is limited to
 *       {@code commandRampRate * dt} per axis.</li>
 *   <li>An axis at/over a position limit has any demand pushing further past the
 *       limit zeroed instantly (alarm "&lt;axis&gt; at limit"); demand moving back
 *       inside the envelope is allowed.</li>
 * </ol>
 *
 * <p>The controller is stateful (E-STOP latch, previous filtered output for ramp
 * limiting) and is meant to be owned and called by a single control loop thread;
 * it is not thread-safe on its own.
 */
public final class SafetyController {

    /** Default watchdog timeout: commands older than this are considered stale. */
    public static final long DEFAULT_WATCHDOG_TIMEOUT_MILLIS = 250;

    /** Alarm raised while the E-STOP latch is engaged. */
    public static final String ESTOP_ALARM = "E-STOP latched";

    /** Alarm raised while the watchdog considers the incoming command stale. */
    public static final String WATCHDOG_ALARM = "watchdog tripped: no fresh command";

    private final CraneProfile profile;
    private final long watchdogTimeoutMillis;

    /** Filtered output of the previous tick, per axis id — the ramp-limiter memory. */
    private final Map<String, Double> lastOutput = new LinkedHashMap<>();

    private boolean estopLatched;

    public SafetyController(CraneProfile profile) {
        this(profile, DEFAULT_WATCHDOG_TIMEOUT_MILLIS);
    }

    public SafetyController(CraneProfile profile, long watchdogTimeoutMillis) {
        this.profile = Objects.requireNonNull(profile, "profile");
        if (watchdogTimeoutMillis <= 0) {
            throw new IllegalArgumentException("watchdogTimeoutMillis must be positive");
        }
        this.watchdogTimeoutMillis = watchdogTimeoutMillis;
        profile.axes().forEach(axis -> lastOutput.put(axis.id(), 0.0));
    }

    /** True while the E-STOP latch is engaged. */
    public boolean isEstopLatched() {
        return estopLatched;
    }

    public long watchdogTimeoutMillis() {
        return watchdogTimeoutMillis;
    }

    /**
     * Runs one safety pass. Called once per control-loop tick.
     *
     * @param command       latest raw operator command (may be stale — the watchdog decides)
     * @param axisPositions current physical positions per axis id, in axis units
     * @param dtSeconds     time since the previous tick, in seconds
     * @param nowMillis     current wall-clock time, for command freshness
     * @return filtered demands (one entry per profile axis) plus safety status
     */
    public SafetyOutput filter(CraneCommand command, Map<String, Double> axisPositions,
                               double dtSeconds, long nowMillis) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(axisPositions, "axisPositions");
        if (dtSeconds < 0) {
            throw new IllegalArgumentException("dtSeconds must not be negative");
        }

        List<String> alarms = new ArrayList<>();

        // Rule 4 — watchdog: a stale command is treated exactly like deadman released.
        boolean watchdogTripped = nowMillis - command.timestampMillis() > watchdogTimeoutMillis;
        if (watchdogTripped) {
            alarms.add(WATCHDOG_ALARM);
        }
        boolean deadmanEffective = command.deadmanHeld() && !watchdogTripped;

        // Rule 1 — E-STOP latches; rule 2 — conditional reset. E-STOP always wins
        // (it latches even from a stale command), a reset is only honored from a
        // fresh command with all raw demands neutral and the deadman released.
        if (command.estopRequested()) {
            estopLatched = true;
        } else if (estopLatched && command.resetRequested()
                && !watchdogTripped && command.allNeutral() && !command.deadmanHeld()) {
            estopLatched = false;
        }

        if (estopLatched) {
            alarms.add(ESTOP_ALARM);
            // Immediate zero, bypassing the ramp limiter; also reset the limiter
            // memory so motion after a reset ramps up from zero.
            Map<String, Double> zeros = new LinkedHashMap<>();
            for (AxisSpec axis : profile.axes()) {
                zeros.put(axis.id(), 0.0);
                lastOutput.put(axis.id(), 0.0);
            }
            return new SafetyOutput(zeros, true, deadmanEffective, watchdogTripped, alarms);
        }

        Map<String, Double> filtered = new LinkedHashMap<>();
        for (AxisSpec axis : profile.axes()) {
            String id = axis.id();

            // Rule 5 — clamp the raw demand to [-1, +1]. A non-finite demand is
            // treated as neutral: NaN survives comparisons and clamping and would
            // otherwise poison the ramp limiter and the driver downstream.
            double requested = command.demand(id);
            double raw = Double.isFinite(requested) ? Math.clamp(requested, -1.0, 1.0) : 0.0;

            // Rules 3 + 4 — deadman released (or watchdog tripped): target neutral,
            // reached through the ramp limiter below = a fast controlled stop.
            double target = deadmanEffective ? raw : 0.0;

            // Rule 5 — slew-rate limit: at most commandRampRate * dt change per tick.
            double previous = lastOutput.getOrDefault(id, 0.0);
            double maxDelta = axis.commandRampRate() * dtSeconds;
            double demand = previous + Math.clamp(target - previous, -maxDelta, maxDelta);

            // Rule 6 — position limit stop: outward motion zeroed instantly,
            // inward motion allowed.
            Double position = axisPositions.get(id);
            if (position != null) {
                if (position >= axis.maxPosition() && demand > 0.0) {
                    demand = 0.0;
                    alarms.add(id + " at limit");
                } else if (position <= axis.minPosition() && demand < 0.0) {
                    demand = 0.0;
                    alarms.add(id + " at limit");
                }
            }

            filtered.put(id, demand);
            lastOutput.put(id, demand);
        }

        return new SafetyOutput(filtered, false, deadmanEffective, watchdogTripped, alarms);
    }
}
