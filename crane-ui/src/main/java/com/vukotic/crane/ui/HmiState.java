package com.vukotic.crane.ui;

import com.vukotic.crane.core.model.CraneState;

/**
 * What the cockpit should be saying, decided without touching a scene graph.
 *
 * <p>Every HMI defect found in three external audits lived in {@code CraneRemoteApp}
 * — a lamp reading a recording instead of the machine, a green RUN ENABLED beside a
 * latched emergency stop, a reset warning on every successful reset. None was caught
 * by the test suite, because that class needs a JavaFX toolkit to instantiate and so
 * has never had a single direct test.
 *
 * <p>The decisions are moved here, where they are plain functions of plain values.
 * {@code CraneRemoteApp} keeps the wiring; this keeps the judgement, and the
 * judgement is what has repeatedly been wrong.
 *
 * @param live     the machine's own state — never a recording's
 * @param replaying whether a recording is currently on screen
 * @param demoRunning whether the guided demo is driving
 * @param driverMode whether the crane is locked out for driving the truck
 * @param blocked  whether interference protection is holding an axis
 */
public record HmiState(CraneState live, boolean replaying, boolean demoRunning,
                       boolean driverMode, boolean blocked) {

    /** The header pill: text and the CSS style class that colours it. */
    public record Pill(String text, String styleClass) {
    }

    /**
     * The header pill.
     *
     * <p>The latch is checked before everything, replay included: a latched machine
     * is the most important thing this header can say, and while a recording plays
     * the recording's flags are not the machine's.
     */
    public Pill pill() {
        if (live.estopLatched()) {
            return new Pill("E-STOP LATCHED", "pill-alarm");
        }
        if (replaying) {
            return new Pill("REPLAY — RECORDED", "pill-warn");
        }
        if (demoRunning) {
            return new Pill("DEMO RUNNING", "pill-warn");
        }
        if (driverMode) {
            return new Pill("DRIVER MODE", "pill-warn");
        }
        if (blocked) {
            return new Pill("BLOCKED — OBSTACLE", "pill-warn");
        }
        if (live.deadmanHeld()) {
            return new Pill("RUNNING", "pill-ok");
        }
        return new Pill("READY — HOLD SPACE", "pill-idle");
    }

    /**
     * Whether the crane will actually move right now.
     *
     * <p>Not "is a key down". The deadman indicator used to read the raw key state,
     * so holding the deadman against a latched emergency stop lit a green RUN
     * ENABLED right next to the latch — the machine going nowhere and the panel
     * saying otherwise.
     */
    public boolean running() {
        return live.deadmanHeld() && !live.estopLatched();
    }

    /** The big label under the E-STOP: what the machine is waiting for. */
    public String deadmanText() {
        if (live.estopLatched()) {
            return "E-STOP LATCHED — PRESS RESET";
        }
        return running() ? "RUN ENABLED" : "HOLD SPACE TO RUN";
    }

    /** Caption under the view toggle: which view an operator should believe. */
    public static String viewNote(boolean use3d) {
        return use3d
                ? "3D is experimental — for presentation. Parts can still overlap. "
                        + "Use 2D for anything you are measuring or marking."
                : "2D schematic — dimensionally accurate, drawn from the axis positions.";
    }

    /** What the safety panel says about interference protection for this crane. */
    public static String interferenceNote(boolean available) {
        return available
                ? "Interference protection active"
                : "NO interference protection — this crane has no verified geometry";
    }
}
