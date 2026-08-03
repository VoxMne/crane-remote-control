package com.vukotic.crane.ui.render;

/** Viewpoints offered by the 3D view. */
public enum CameraMode {

    /** Free orbit around the crane: drag rotates, scroll zooms. */
    ORBIT("Orbit"),

    /** From the truck cab, looking at the load. */
    CAB("Cab"),

    /** Directly above the hook, looking down — for precise placement. */
    HOOK("Hook"),

    /** Behind and above the hook, tracking it as the crane slews. */
    FOLLOW("Follow load");

    private final String label;

    CameraMode(String label) {
        this.label = label;
    }

    /** Human-readable name for the UI selector. */
    public String label() {
        return label;
    }
}
