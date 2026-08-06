package com.vukotic.crane.core.telemetry;

import com.vukotic.crane.core.model.CraneState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a training run looked like, reduced to the things an instructor grades on.
 *
 * <p>A recording is the assessment artifact for a training rig — the point is not
 * that the trainee moved the crane, it is <em>how</em>. This turns a few thousand
 * telemetry rows into the handful of numbers a marker actually reads: did they trip
 * the emergency stop, did they drive into the limits, how much of the run was spent
 * actually moving, and how smoothly.
 *
 * <p>Deliberately descriptive, not a grade. It counts events and reports rates; it
 * does not decide pass or fail, because where that line sits is the instructor's
 * judgement and varies by course. Handing back a number that looks like a mark
 * would invite it to be used as one.
 */
public record SessionSummary(
        long durationMillis,
        int frames,
        int estopTrips,
        int watchdogTrips,
        int limitHits,
        long movingMillis,
        double smoothnessIndex,
        Map<String, Integer> limitHitsByAxis,
        List<String> distinctAlarms) {

    public SessionSummary {
        limitHitsByAxis = Map.copyOf(limitHitsByAxis);
        distinctAlarms = List.copyOf(distinctAlarms);
    }

    /** Fraction of the run in which at least one axis was moving, 0..1. */
    public double dutyCycle() {
        return durationMillis <= 0 ? 0 : (double) movingMillis / durationMillis;
    }

    /**
     * Summarises a recording.
     *
     * <p>Counts <em>transitions</em>, not frames: an emergency stop held for ten
     * seconds is one trip, not five hundred. Counting frames would have made a
     * trainee who latched once and thought about it look far worse than one who
     * latched repeatedly and carried straight on.
     */
    public static SessionSummary of(TelemetryCsvReader.Recording recording) {
        Objects.requireNonNull(recording, "recording");
        List<CraneState> frames = recording.frames();
        if (frames.isEmpty()) {
            return new SessionSummary(0, 0, 0, 0, 0, 0, 0, Map.of(), List.of());
        }

        int estopTrips = 0;
        int watchdogTrips = 0;
        int limitHits = 0;
        long movingMillis = 0;
        double velocityChange = 0;
        int velocitySamples = 0;

        Map<String, Integer> byAxis = new LinkedHashMap<>();
        java.util.Set<String> alarms = new java.util.LinkedHashSet<>();
        java.util.Set<String> previousLimitAlarms = java.util.Set.of();
        boolean wasEstopped = false;
        boolean wasWatchdogged = false;
        Map<String, Double> previousVelocities = Map.of();

        for (int i = 0; i < frames.size(); i++) {
            CraneState frame = frames.get(i);

            if (frame.estopLatched() && !wasEstopped) {
                estopTrips++;
            }
            wasEstopped = frame.estopLatched();

            if (frame.watchdogTripped() && !wasWatchdogged) {
                watchdogTrips++;
            }
            wasWatchdogged = frame.watchdogTripped();

            java.util.Set<String> limitAlarms = new java.util.LinkedHashSet<>();
            for (String alarm : frame.activeAlarms()) {
                alarms.add(alarm);
                if (alarm.endsWith(" at limit")) {
                    limitAlarms.add(alarm);
                    if (!previousLimitAlarms.contains(alarm)) {
                        limitHits++;
                        String axis = alarm.substring(0, alarm.length() - " at limit".length());
                        byAxis.merge(axis, 1, Integer::sum);
                    }
                }
            }
            previousLimitAlarms = limitAlarms;

            boolean moving = frame.axisVelocities().values().stream()
                    .anyMatch(v -> v != null && Double.isFinite(v) && Math.abs(v) > 1e-3);
            if (moving && i > 0) {
                movingMillis += frame.timestampMillis() - frames.get(i - 1).timestampMillis();
            }

            // Smoothness: mean absolute change in velocity between frames. A
            // trainee stabbing at the controls produces a large number; one easing
            // the axes in and out produces a small one.
            if (!previousVelocities.isEmpty()) {
                for (Map.Entry<String, Double> entry : frame.axisVelocities().entrySet()) {
                    Double before = previousVelocities.get(entry.getKey());
                    Double now = entry.getValue();
                    if (before != null && now != null
                            && Double.isFinite(before) && Double.isFinite(now)) {
                        velocityChange += Math.abs(now - before);
                        velocitySamples++;
                    }
                }
            }
            previousVelocities = frame.axisVelocities();
        }

        double smoothness = velocitySamples == 0 ? 0 : velocityChange / velocitySamples;
        return new SessionSummary(recording.durationMillis(), frames.size(),
                estopTrips, watchdogTrips, limitHits, movingMillis, smoothness,
                byAxis, List.copyOf(alarms));
    }

    /** A short human-readable block, for the panel and for a printed sheet. */
    public String describe() {
        StringBuilder text = new StringBuilder();
        text.append("Duration      %.1f s%n".formatted(durationMillis / 1000.0));
        text.append("Time moving   %.0f%%%n".formatted(dutyCycle() * 100));
        text.append("E-STOP trips  %d%n".formatted(estopTrips));
        text.append("Limit hits    %d%n".formatted(limitHits));
        if (!limitHitsByAxis.isEmpty()) {
            text.append("  by axis     %s%n".formatted(limitHitsByAxis));
        }
        text.append("Watchdog      %d%n".formatted(watchdogTrips));
        text.append("Control index %.3f  (lower is smoother)".formatted(smoothnessIndex));
        return text.toString();
    }
}
