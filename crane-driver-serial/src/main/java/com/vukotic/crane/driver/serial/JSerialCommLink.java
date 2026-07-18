package com.vukotic.crane.driver.serial;

import com.fazecast.jSerialComm.SerialPort;

import java.nio.charset.StandardCharsets;

/**
 * {@link SerialLink} over a real COM port via jSerialComm, at the CSP/1 transport
 * settings (115200 baud, 8N1). Kept deliberately thin — all protocol logic lives in
 * {@link CspCodec}/{@link SerialCraneDriver}, and tests replace this class with an
 * in-memory fake; it is only truly exercised with hardware attached.
 *
 * <p>Not thread-safe per method contract-wise beyond the driver's usage: one reader
 * thread calls {@link #readLine}, the control loop calls {@link #writeLine}.
 */
public final class JSerialCommLink implements SerialLink {

    private static final int BAUD_RATE = 115_200;
    private static final int POLL_SLICE_MILLIS = 50;
    private static final int MAX_BUFFERED_CHARS = 4096;

    private final String portName;
    private SerialPort port;
    private final StringBuilder rxBuffer = new StringBuilder();

    public JSerialCommLink(String portName) {
        this.portName = portName;
    }

    @Override
    public void open() {
        if (isOpen()) {
            return;
        }
        SerialPort candidate = SerialPort.getCommPort(portName);
        candidate.setComPortParameters(BAUD_RATE, 8,
                SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        candidate.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING,
                POLL_SLICE_MILLIS, 0);
        if (!candidate.openPort()) {
            throw new SerialLinkException("cannot open serial port " + portName);
        }
        port = candidate;
        rxBuffer.setLength(0);
    }

    @Override
    public void close() {
        if (port != null) {
            port.closePort();
            port = null;
        }
    }

    @Override
    public boolean isOpen() {
        return port != null && port.isOpen();
    }

    @Override
    public String readLine(long timeoutMillis) {
        requireOpen();
        long deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L;
        byte[] chunk = new byte[256];
        while (!Thread.currentThread().isInterrupted()) {
            String buffered = takeBufferedLine();
            if (buffered != null) {
                return buffered;
            }
            if (System.nanoTime() >= deadlineNanos) {
                return null;
            }
            int read = port.readBytes(chunk, chunk.length);
            if (read < 0) {
                throw new SerialLinkException("serial read failed on " + portName);
            }
            for (int i = 0; i < read; i++) {
                rxBuffer.append((char) (chunk[i] & 0xFF));
            }
            if (rxBuffer.length() > MAX_BUFFERED_CHARS) {
                rxBuffer.setLength(0); // garbage flood — resync at the next line break
            }
        }
        return null;
    }

    @Override
    public void writeLine(String line) {
        requireOpen();
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.US_ASCII);
        if (port.writeBytes(bytes, bytes.length) != bytes.length) {
            throw new SerialLinkException("serial write failed on " + portName);
        }
    }

    /** Returns the next complete buffered line without CR/LF, or null. */
    private String takeBufferedLine() {
        int newline = rxBuffer.indexOf("\n");
        if (newline < 0) {
            return null;
        }
        String line = rxBuffer.substring(0, newline);
        rxBuffer.delete(0, newline + 1);
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        return line;
    }

    private void requireOpen() {
        if (!isOpen()) {
            throw new SerialLinkException("serial port " + portName + " is not open");
        }
    }
}
