package com.vukotic.crane.ui.render;

import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.model.CraneState;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.layout.Pane;

/**
 * The default 2D schematic view: a resizable {@link Canvas} redrawn every
 * frame by {@link SchematicRenderer2D}. The canvas is bound to its holder
 * pane so it always fills whatever space the HMI grants the center area.
 */
public final class Schematic2DView implements CraneSceneView {

    private static final String BACKGROUND = "#14181d";

    private final Pane holder = new Pane();
    private final Canvas canvas = new Canvas();
    private final CraneRenderer renderer = new SchematicRenderer2D();

    public Schematic2DView() {
        holder.setStyle("-fx-background-color: " + BACKGROUND + ";");
        canvas.widthProperty().bind(holder.widthProperty());
        canvas.heightProperty().bind(holder.heightProperty());
        holder.getChildren().add(canvas);
    }

    @Override
    public Node node() {
        return holder;
    }

    @Override
    public void update(CraneProfile profile, CraneState state) {
        renderer.render(canvas.getGraphicsContext2D(),
                canvas.getWidth(), canvas.getHeight(), profile, state);
    }
}
