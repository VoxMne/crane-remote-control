package com.vukotic.crane.ui.render;

import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.model.CraneState;
import javafx.scene.Node;

/**
 * A switchable center visualization of the crane. Implementations own their
 * scene node (canvas, 3D subscene, ...) and redraw themselves from the latest
 * {@link CraneState} once per frame.
 *
 * <p>The HMI frame timer only calls {@link #update} on the currently active
 * view; an inactive view keeps its node but is simply not updated. Views are
 * recreated on profile switch, alongside the rest of the cockpit.
 */
public interface CraneSceneView {

    /** The scene-graph node to mount in the center of the HMI. */
    Node node();

    /** Applies one frame of state. Called on the FX thread by the frame timer. */
    void update(CraneProfile profile, CraneState state);
}
