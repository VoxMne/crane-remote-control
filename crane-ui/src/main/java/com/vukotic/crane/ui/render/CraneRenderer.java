package com.vukotic.crane.ui.render;

import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.model.CraneState;
import javafx.scene.canvas.GraphicsContext;

/**
 * Visualization strategy for the center canvas. v1 ships a 2D schematic
 * ({@link SchematicRenderer2D}); a 3D view can slot in later without touching
 * the rest of the HMI.
 */
public interface CraneRenderer {

    /** Draws one frame of the given state onto a canvas of the given size. */
    void render(GraphicsContext g, double width, double height, CraneProfile profile, CraneState state);
}
