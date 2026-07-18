package com.vukotic.crane.driver.serial;

/**
 * Minimal line-oriented transport seam between {@link SerialCraneDriver} and the actual
 * serial port. Production uses {@link JSerialCommLink}; tests use an in-memory fake, so
 * no protocol or driver logic ever touches jSerialComm directly.
 */
public interface SerialLink {

    /**
     * Opens the transport.
     *
     * @throws SerialLinkException if the port cannot be opened
     */
    void open();

    /** Closes the transport. Safe to call when already closed. */
    void close();

    boolean isOpen();

    /**
     * Returns the next received line without its CR/LF terminators, or {@code null} if
     * no complete line arrived within {@code timeoutMillis}. Implementations must be
     * interrupt-friendly: when the calling thread is interrupted they return promptly
     * (with {@code null}, interrupt status preserved) instead of blocking on.
     *
     * @throws SerialLinkException on unrecoverable transport failure
     */
    String readLine(long timeoutMillis);

    /**
     * Writes one line; the implementation appends the LF terminator.
     *
     * @throws SerialLinkException on transport failure
     */
    void writeLine(String line);
}
