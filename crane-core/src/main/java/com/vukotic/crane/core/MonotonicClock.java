package com.vukotic.crane.core;

/**
 * The time source for everything safety-related.
 *
 * <p>Command freshness and the watchdog must be measured on a clock that only
 * moves forward. {@code System.currentTimeMillis()} does not qualify: an NTP
 * correction, a daylight-saving change or a user editing the system clock can
 * move it backwards or forwards, which would either extend a stale command's
 * apparent validity or trip the watchdog for no reason.
 *
 * <p>Both sides of every elapsed-time comparison must come from here — the
 * numbers are milliseconds since an arbitrary origin and are meaningless as
 * absolute times.
 */
public final class MonotonicClock {

    private MonotonicClock() {
    }

    /** Milliseconds from a fixed, arbitrary origin; never decreases. */
    public static long millis() {
        return System.nanoTime() / 1_000_000L;
    }
}
