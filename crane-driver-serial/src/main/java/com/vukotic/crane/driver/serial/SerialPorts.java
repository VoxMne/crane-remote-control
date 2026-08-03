package com.vukotic.crane.driver.serial;

import com.fazecast.jSerialComm.SerialPort;

import java.util.Arrays;
import java.util.List;

/** COM-port discovery for UIs, keeping jSerialComm out of their dependencies. */
public final class SerialPorts {

    private SerialPorts() {
    }

    /**
     * System port names (e.g. "COM4"), best-effort: discovery failures (missing
     * native library, odd drivers) return an empty list and never propagate.
     */
    public static List<String> availablePortNames() {
        try {
            return Arrays.stream(SerialPort.getCommPorts())
                    .map(SerialPort::getSystemPortName)
                    .distinct()
                    .toList();
        } catch (Throwable t) {
            System.err.println("[serial] port discovery failed: " + t);
            return List.of();
        }
    }
}
