package com.vukotic.crane.ui.render;

/**
 * Load hanging from the hook in the 3D view. Purely visual: the cargo has no
 * mass and does not affect the simulation — it hangs, can be set down on the
 * ground and picked back up.
 *
 * <p>Each constant carries its label plus its extent in metres along X (length),
 * Y (height) and Z (width); {@link #NONE} is zero-sized.
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
