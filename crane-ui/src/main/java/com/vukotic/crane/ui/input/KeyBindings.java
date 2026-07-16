package com.vukotic.crane.ui.input;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Maps keyboard key names to axis motion demands and to the safety keys
 * (deadman, E-STOP). Key names are plain strings matching
 * {@code javafx.scene.input.KeyCode.name()} (e.g. "Q", "SPACE", "ESCAPE"),
 * so this class is fully testable without the JavaFX toolkit.
 */
public final class KeyBindings {

    /** A key's effect on one axis: full demand in the given direction. */
    public record AxisAction(String axisId, double direction) {
    }

    /** Key held to satisfy the hold-to-run (deadman) requirement. */
    public static final String DEADMAN_KEY = "SPACE";

    /** Key that requests an E-STOP. */
    public static final String ESTOP_KEY = "ESCAPE";

    private final Map<String, AxisAction> axisKeys;

    private KeyBindings(Map<String, AxisAction> axisKeys) {
        this.axisKeys = Map.copyOf(axisKeys);
    }

    /**
     * Default operator map: Q/A = slew +/-, W/S = boom +/-, E/D = jib +/-,
     * R/F = extension +/-, T/G = winch +/-.
     */
    public static KeyBindings defaults() {
        Map<String, AxisAction> map = new LinkedHashMap<>();
        map.put("Q", new AxisAction("slew", +1.0));
        map.put("A", new AxisAction("slew", -1.0));
        map.put("W", new AxisAction("boom", +1.0));
        map.put("S", new AxisAction("boom", -1.0));
        map.put("E", new AxisAction("jib", +1.0));
        map.put("D", new AxisAction("jib", -1.0));
        map.put("R", new AxisAction("extension", +1.0));
        map.put("F", new AxisAction("extension", -1.0));
        map.put("T", new AxisAction("winch", +1.0));
        map.put("G", new AxisAction("winch", -1.0));
        return new KeyBindings(map);
    }

    /** Axis effect of a key, if it is an axis key. */
    public Optional<AxisAction> axisActionFor(String keyName) {
        return Optional.ofNullable(axisKeys.get(keyName));
    }

    public boolean isDeadmanKey(String keyName) {
        return DEADMAN_KEY.equals(keyName);
    }

    public boolean isEstopKey(String keyName) {
        return ESTOP_KEY.equals(keyName);
    }

    /** True if this binding set reacts to the key in any way (axis, deadman or E-STOP). */
    public boolean isBound(String keyName) {
        return axisKeys.containsKey(keyName) || isDeadmanKey(keyName) || isEstopKey(keyName);
    }

    /**
     * Net keyboard demand per axis for the currently pressed keys: each axis key
     * contributes its full direction (+/-1.0); opposing keys on the same axis cancel.
     * The result is clamped to [-1.0, +1.0] per axis.
     */
    public Map<String, Double> demandsFor(Collection<String> pressedKeys) {
        Map<String, Double> demands = new LinkedHashMap<>();
        for (String key : pressedKeys) {
            AxisAction action = axisKeys.get(key);
            if (action != null) {
                demands.merge(action.axisId(), action.direction(), Double::sum);
            }
        }
        demands.replaceAll((axis, demand) -> Math.clamp(demand, -1.0, 1.0));
        return demands;
    }
}
