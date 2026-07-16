package com.vukotic.crane.ui.input;

import com.vukotic.crane.core.model.CraneCommand;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merges the two operator input sources — on-screen sliders and the keyboard —
 * into a single demand map plus the safety flags (deadman, E-STOP, reset), and
 * snapshots them into {@link CraneCommand}s.
 *
 * <p>Combination rule: per axis, whichever source has the larger absolute value
 * wins (keyboard wins ties). Key press = full demand of +/-1.0, key release = 0.
 *
 * <p>Plain class, no JavaFX types: the UI feeds it key names
 * ({@code KeyCode.name()}) and slider values. Intended to be used from a single
 * thread (the FX application thread).
 */
public final class OperatorInput {

    private final KeyBindings bindings;
    private final List<String> axisIds;
    private final Map<String, Double> sliderDemands = new HashMap<>();
    private final Set<String> pressedKeys = new HashSet<>();
    private boolean estopRequested;
    private boolean resetRequested;

    public OperatorInput(KeyBindings bindings, List<String> axisIds) {
        this.bindings = bindings;
        this.axisIds = List.copyOf(axisIds);
    }

    /** Latest on-screen slider value for an axis, clamped to [-1.0, +1.0]. */
    public void setSliderDemand(String axisId, double value) {
        sliderDemands.put(axisId, Math.clamp(value, -1.0, 1.0));
    }

    /** Notify a key press ({@code KeyCode.name()} string). */
    public void keyPressed(String keyName) {
        if (bindings.isEstopKey(keyName)) {
            estopRequested = true;
        } else {
            pressedKeys.add(keyName);
        }
    }

    /** Notify a key release ({@code KeyCode.name()} string). */
    public void keyReleased(String keyName) {
        pressedKeys.remove(keyName);
    }

    /** E-STOP request state, driven by the latching on-screen button (and ESC). */
    public void setEstopRequested(boolean requested) {
        this.estopRequested = requested;
    }

    public boolean estopRequested() {
        return estopRequested;
    }

    /**
     * One-shot reset request: clears the local E-STOP request and marks the next
     * snapshot with {@code resetRequested=true}. The backend only honors it while
     * all demands are neutral and the deadman is released.
     */
    public void requestReset() {
        estopRequested = false;
        resetRequested = true;
    }

    public boolean deadmanHeld() {
        return pressedKeys.contains(KeyBindings.DEADMAN_KEY);
    }

    /**
     * Current merged demand per profile axis: keyboard vs. slider, larger
     * absolute value wins (keyboard wins ties).
     */
    public Map<String, Double> currentDemands() {
        Map<String, Double> keyboard = bindings.demandsFor(pressedKeys);
        Map<String, Double> merged = new LinkedHashMap<>();
        for (String axisId : axisIds) {
            double kb = keyboard.getOrDefault(axisId, 0.0);
            double slider = sliderDemands.getOrDefault(axisId, 0.0);
            merged.put(axisId, Math.abs(kb) >= Math.abs(slider) ? kb : slider);
        }
        return merged;
    }

    /**
     * Builds the command for this frame. Consumes a pending reset request
     * (reset is reported exactly once per {@link #requestReset()}).
     */
    public CraneCommand snapshot(long timestampMillis) {
        boolean reset = resetRequested;
        resetRequested = false;
        return new CraneCommand(timestampMillis, currentDemands(), deadmanHeld(), estopRequested, reset);
    }
}
