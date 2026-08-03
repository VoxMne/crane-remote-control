package com.vukotic.crane.ui.render;

/**
 * Load hanging from the hook in the 3D view. Purely visual: the cargo has no
 * mass and does not affect the simulation — it hangs, can be set down on the
 * ground and picked back up.
 *
 * @param label      name shown in the UI selector
 * @param length     X extent in metres (0 for {@link #NONE})
 * @param height     Y extent in metres
 * @param width      Z extent in metres
 */
public enum CargoType {

    NONE("None", 0, 0, 0),
    PALLET("Pallet", 1.2, 0.5, 1.0),
    CONTAINER("Container", 3.0, 1.4, 1.4),
    BOAT("Small boat", 4.2, 1.1, 1.5);

    private final String label;
    private final double length;
    private final double height;
    private final double width;

    CargoType(String label, double length, double height, double width) {
        this.label = label;
        this.length = length;
        this.height = height;
        this.width = width;
    }

    public String label() {
        return label;
    }

    public double length() {
        return length;
    }

    public double height() {
        return height;
    }

    public double width() {
        return width;
    }
}
