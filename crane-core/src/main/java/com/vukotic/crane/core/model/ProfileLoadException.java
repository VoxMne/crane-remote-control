package com.vukotic.crane.core.model;

/** A crane profile JSON could not be read or failed validation. */
public class ProfileLoadException extends RuntimeException {

    public ProfileLoadException(String message) {
        super(message);
    }

    public ProfileLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
