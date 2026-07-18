package com.vukotic.crane.driver.serial;

/** Unrecoverable failure of the serial transport (open, read, or write). */
public class SerialLinkException extends RuntimeException {

    public SerialLinkException(String message) {
        super(message);
    }

    public SerialLinkException(String message, Throwable cause) {
        super(message, cause);
    }
}
