package com.vukotic.crane.ui.input;

import com.vukotic.crane.core.model.CraneCommand;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Merges the two operator input sources — on-screen sliders and the keyboard —
 * into a single demand map plus the safety flags (deadman, E-STOP, reset), and
 * snapshots them into {@link CraneCommand}s.
 *
 * <p>Combination rule: per axis, whichever source has the larger absolute value
 * wins (keyboard wins ties). Key press = full demand of +/-1.0, key release = 0.
 *
 * <p>Plain class, no JavaFX types: the UI feeds it key names
 * ({@code KeyCode.name()}) and slider values.
 *
 * <p><b>Thread-safe by design.</b> Input events arrive on the JavaFX thread while
 * the command submitter samples this object on its own fixed-rate thread — that
 * separation is what keeps the safety-critical command stream alive when the
 * renderer stalls (see {@code CraneRemoteApp}).
 */
public final class OperatorInput {

    private final KeyBindings bindings;
    private final List<String> axisIds;
    private final Map<String, Double> sliderDemands = new ConcurrentHashMap<>();
    private final Set<String> pressedKeys = ConcurrentHashMap.newKeySet();
    private volatile boolean estopRequested;
    private final AtomicBoolean resetRequested = new AtomicBoolean();

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

    /**
     * Drops every held key, including the deadman.
     *
     * <p>Called whenever the window stops receiving input — losing focus, being
     * minimised, closing. Key releases are delivered only to the focused window,
     * so without this a held deadman would stay "held" forever after the operator
     * alt-tabs away, and the crane would keep running on cached intent.
     */
    public void releaseAllKeys() {
        pressedKeys.clear();
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
        resetRequested.set(true);
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
        boolean reset = resetRequested.getAndSet(false);
        return new CraneCommand(timestampMillis, currentDemands(), deadmanHeld(), estopRequested, reset);
    }
}
