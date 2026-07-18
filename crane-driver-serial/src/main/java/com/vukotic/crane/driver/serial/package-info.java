/**
 * Serial-port {@link com.vukotic.crane.core.driver.CraneDriver} adapter speaking the
 * Crane Serial Protocol v1 (CSP/1, see docs/PROTOCOL.md): line-based checksummed ASCII,
 * demands out ("D" lines) and telemetry in ("T" lines) over 115200 8N1.
 *
 * <p>All protocol logic lives behind the {@link com.vukotic.crane.driver.serial.SerialLink}
 * seam so the driver is fully testable without a physical port; jSerialComm appears only
 * in {@link com.vukotic.crane.driver.serial.JSerialCommLink}.
 */
package com.vukotic.crane.driver.serial;
