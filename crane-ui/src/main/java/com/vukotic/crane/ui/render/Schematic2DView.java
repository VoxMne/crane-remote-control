package com.vukotic.crane.ui.render;

import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.model.CraneState;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Pane;

/**
 * The default 2D schematic view: a resizable {@link Canvas} redrawn every
 * frame by {@link SchematicRenderer2D}. The canvas is bound to its holder
 * pane so it always fills whatever space the HMI grants the center area.
 *
 * <p>Viewport interaction: scroll wheel zooms around the cursor (~1.1 per
 * tick), left-drag pans, double-click resets to the auto-fit framing. The
 * handlers only mutate the renderer's viewport — the frame timer repaints on
 * the next pulse, so no state is cached here beyond the drag anchor.
 */
public final class Schematic2DView implements CraneSceneView {

    private static final String BACKGROUND = "#14181d";
    /** Zoom factor for one standard scroll-wheel notch (deltaY = 40). */
    private static final double ZOOM_PER_TICK = 1.1;

    private final Pane holder = new Pane();
    private final Canvas canvas = new Canvas();
    private final SchematicRenderer2D renderer = new SchematicRenderer2D();

    private double dragX;
    private double dragY;

    public Schematic2DView() {
        holder.setStyle("-fx-background-color: " + BACKGROUND + ";");
        canvas.widthProperty().bind(holder.widthProperty());
        canvas.heightProperty().bind(holder.heightProperty());
        holder.getChildren().add(canvas);
        installViewportHandlers();
    }

    private void installViewportHandlers() {
        canvas.setOnScroll(event -> {
            if (event.getDeltaY() == 0) {
                return;
            }
            // Exponent scales with the delta so trackpads zoom smoothly while a
            // wheel notch (deltaY = ±40) lands exactly on ZOOM_PER_TICK.
            double factor = Math.pow(ZOOM_PER_TICK, event.getDeltaY() / 40.0);
            renderer.zoomAt(event.getX(), event.getY(), factor);
            event.consume();
        });
        canvas.setOnMousePressed(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                dragX = event.getX();
                dragY = event.getY();
            }
        });
        canvas.setOnMouseDragged(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                renderer.panByScreenDelta(event.getX() - dragX, event.getY() - dragY);
                dragX = event.getX();
                dragY = event.getY();
                event.consume();
            }
        });
        canvas.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                renderer.resetViewport();
                event.consume();
            }
        });
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

    @Override
    public void setCargo(CargoType type) {
        renderer.setCargo(type);
    }
}
