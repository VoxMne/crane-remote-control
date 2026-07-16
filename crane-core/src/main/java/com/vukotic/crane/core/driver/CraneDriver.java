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
}
