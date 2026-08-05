package com.vukotic.crane.core.driver;

import com.vukotic.crane.core.model.CraneProfile;

import java.util.Map;

/**
 * The port to an actual crane back-end (simulator now; serial/CAN/MCU links later).
 *
 * <p>Drivers receive demands that the core safety layer has already filtered
 * (clamped, ramp-limited, zeroed on E-STOP/deadman/watchdog). Drivers must not
 * contain safety logic beyond protecting their own physical limits.
 */
public interface CraneDriver {

    /** Short human-readable name, e.g. "Simulator". */
    String name();

    void connect(CraneProfile profile);

    void disconnect();

    boolean isConnected();

    /**
     * Applies safety-filtered demands, normalized to [-1.0, +1.0] per axis id.
     * Called once per control-loop tick.
     */
    void sendDemands(Map<String, Double> axisDemands);

    /** Latest physical state of the crane, in axis units. */
    DriverState readState();

    /**
     * Whether this driver can currently carry motion safely.
     *
     * <p>A driver that answers {@code false} is not merely going to ignore the
     * demands — the control loop suppresses motion <em>before</em> the safety
     * layer, so the ramp limiter stays parked at zero for as long as the link is
     * gated. Without that, a driver which quietly substituted zeros on the wire
     * let the safety layer keep ramping behind its back: an operator holding full
     * demand through a telemetry dropout would have the output wound up to 1.0,
     * and the first good frame would step the machine from standstill to full
     * demand in one tick instead of ramping.
     *
     * <p>Default {@code true}: a driver that is connected and has nothing to say
     * about liveness is treated as ready, which is what the simulator wants.
     */
    default boolean acceptsMotion() {
        return true;
    }

    /**
     * A fault the operator needs to see, or empty when the driver is healthy.
     * Serial links fail at runtime — a pulled cable, a dead port — and that must
     * reach the alarm list rather than a stack trace on stderr.
     */
    default java.util.Optional<String> fault() {
        return java.util.Optional.empty();
    }
}
