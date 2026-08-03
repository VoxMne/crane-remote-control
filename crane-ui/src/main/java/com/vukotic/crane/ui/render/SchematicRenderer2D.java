package com.vukotic.crane.ui.render;

import com.vukotic.crane.core.model.AxisSpec;
import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.model.CraneState;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.geometry.VPos;

import java.util.Locale;

/**
 * 2D schematic of a truck-mounted knuckle-boom crane.
 *
 * <p>Side view (main area): ground, truck silhouette, pillar, main boom
 * (rotated by the "boom" axis, lengthened by "extension"), jib rotated by
 * "jib" relative to the boom, and a hook hanging from the jib tip by the
 * "winch" rope length. Top view (inset, top-right): truck outline and boom
 * direction rotated by "slew". The inset lives in fixed screen space and is
 * never affected by the viewport.
 *
 * <p>The side view renders through an explicit viewport (world centre + zoom
 * over the auto-fit scale). While untouched, the viewport auto-fits and keeps
 * following canvas resizes — byte-for-byte the classic framing, which the dev
 * snapshot probe relies on. {@link #zoomAt}, {@link #panByScreenDelta} and
 * {@link #resetViewport} are mutated by {@link Schematic2DView}'s mouse
 * handlers on the FX thread.
 *
 * <p>Measurement annotations (dim, thin, small monospace — never louder than
 * the crane): dashed reach arcs around the pillar pivot, an adaptive height
 * tick scale on the left edge, a live outreach/height readout beside the hook
 * and a zoom-adaptive scale bar in the bottom-left corner.
 *
 * <p>All geometry is read from {@link CraneState} positions via the axis ids
 * {@code slew, boom, jib, extension, winch}; missing axes read as 0. Visual
 * proportions are fixed constants — physical limits stay in the profile.
 */
public final class SchematicRenderer2D implements CraneRenderer {

    // ---- fixed visual proportions (metres, side view) ----
    private static final double WORLD_WIDTH = 18.0;   // world window width
    private static final double WORLD_HEIGHT = 13.0;  // world window height
    private static final double BED_HEIGHT = 1.1;     // chassis top above ground
    private static final double PILLAR_HEIGHT = 2.0;
    private static final double BOOM_BASE_LENGTH = 5.0;
    private static final double JIB_LENGTH = 3.0;

    // ---- palette ----
    private static final Color BACKGROUND = Color.web("#14181d");
    private static final Color GRID = Color.web("#1e2630");
    private static final Color GROUND = Color.web("#3a444d");
    private static final Color TRUCK_FILL = Color.web("#232c35");
    private static final Color TRUCK_LINE = Color.web("#9aa7b0");
    private static final Color CRANE_AMBER = Color.web("#e8b716");
    private static final Color EXTENSION_AMBER = Color.web("#f5d35a");
    private static final Color ROPE = Color.web("#9aa7b0");
    private static final Color HOOK_RED = Color.web("#d64541");
    private static final Color JOINT = Color.web("#14181d");
    private static final Color TEXT_DIM = Color.web("#9aa7b0");
    private static final Color ALARM_RED = Color.web("#d64541");
    private static final Color ANNOTATION = Color.web("#4d5a66");       // dim measure lines
    private static final Color ANNOTATION_TEXT = Color.web("#84919d");  // small measure labels
    private static final Font ANNOTATION_FONT = Font.font("Monospaced", 11);

    // ---- viewport limits (zoom is a multiplier over the auto-fit scale) ----
    private static final double MIN_ZOOM = 0.3;
    private static final double MAX_ZOOM = 8.0;

    // Screen transform of the current frame (render() is single-threaded on the FX thread).
    private static final Color CARGO_FILL = Color.web("#2f7d8c");
    private static final Color CARGO_EDGE = Color.web("#63b3c2");

    /** Load drawn on the hook; matches the 3D view's selection. */
    private volatile CargoType cargo = CargoType.NONE;

    private double scale;
    private double canvasWidth; // width of the current frame, for edge-aware labels
    private double originX; // screen x of world x = 0 (pillar base)
    private double groundY; // screen y of world y = 0 (ground)
    private double fitScale;   // auto-fit scale of the current canvas size
    private double lastWidth;
    private double lastHeight;

    // User viewport: world point at the canvas centre + zoom over the fit scale.
    // Inactive (default, and after a double-click reset) = classic auto-fit framing
    // that keeps adapting to canvas resizes.
    private boolean userViewport;
    private double viewCenterX;
    private double viewCenterY;
    private double zoom = 1.0;

    @Override
    public void render(GraphicsContext g, double width, double height, CraneProfile profile, CraneState state) {
        canvasWidth = width;
        g.setFill(BACKGROUND);
        g.fillRect(0, 0, width, height);
        if (width < 40 || height < 40) {
            return;
        }

        fitScale = Math.min(width / WORLD_WIDTH, height / WORLD_HEIGHT);
        if (userViewport) {
            scale = fitScale * zoom;
            originX = width / 2 - viewCenterX * scale;
            groundY = height / 2 + viewCenterY * scale;
        } else {
            scale = fitScale;
            originX = width * 0.32;
            groundY = height * 0.88;
        }
        lastWidth = width;
        lastHeight = height;

        drawGrid(g, width, height);
        drawReachArcs(g, profile, state);
        drawGroundAndTruck(g, width);
        drawCraneSideView(g, state);
        drawHeightMarkers(g, height);
        drawScaleBar(g, height);
        drawTopViewInset(g, width, state);

        if (state.estopLatched()) {
            drawEstopBanner(g, width, height);
        }
    }

    // ---- viewport control (called from Schematic2DView's mouse handlers) ----

    /**
     * Zooms by {@code factor} anchored on a screen point: the world point under
     * the cursor stays put. Uses the last rendered frame's transform; no-op
     * before the first real frame.
     */
    /** Selects the load drawn under the hook. */
    public void setCargo(CargoType type) {
        this.cargo = type == null ? CargoType.NONE : type;
    }

    public void zoomAt(double screenX, double screenY, double factor) {
        if (scale <= 0) {
            return;
        }
        beginUserViewport();
        double anchorWorldX = (screenX - originX) / scale;
        double anchorWorldY = (groundY - screenY) / scale;
        zoom = Math.clamp(zoom * factor, MIN_ZOOM, MAX_ZOOM);
        double newScale = fitScale * zoom;
        viewCenterX = anchorWorldX + (lastWidth / 2 - screenX) / newScale;
        viewCenterY = anchorWorldY + (screenY - lastHeight / 2) / newScale;
        // Keep the frame transform coherent for further events before the next render.
        scale = newScale;
        originX = lastWidth / 2 - viewCenterX * scale;
        groundY = lastHeight / 2 + viewCenterY * scale;
    }

    /** Pans by a screen-pixel delta: the world follows the cursor drag. */
    public void panByScreenDelta(double dxPixels, double dyPixels) {
        if (scale <= 0) {
            return;
        }
        beginUserViewport();
        viewCenterX -= dxPixels / scale;
        viewCenterY += dyPixels / scale;
        originX += dxPixels;
        groundY += dyPixels;
    }

    /** Back to the auto-fit framing (which keeps following canvas resizes). */
    public void resetViewport() {
        userViewport = false;
        zoom = 1.0;
    }

    /** Captures the current auto-fit framing as the starting user viewport. */
    private void beginUserViewport() {
        if (userViewport) {
            return;
        }
        userViewport = true;
        zoom = 1.0;
        viewCenterX = (lastWidth / 2 - originX) / scale;
        viewCenterY = (groundY - lastHeight / 2) / scale;
    }

    // ---- world-to-screen helpers (side view) ----

    private double sx(double worldX) {
        return originX + worldX * scale;
    }

    private double sy(double worldY) {
        return groundY - worldY * scale;
    }

    // ---- layers ----

    private void drawGrid(GraphicsContext g, double width, double height) {
        g.setStroke(GRID);
        g.setLineWidth(1);
        // Default framing draws the classic fixed window; a user viewport covers
        // whatever 2 m lines are actually visible (bounded by the canvas, so the
        // loop never explodes however far the view was panned).
        int firstMetre = 0;
        int lastMetre = (int) WORLD_HEIGHT;
        if (userViewport) {
            firstMetre = Math.max(0, 2 * (int) Math.ceil((groundY - height) / scale / 2));
            lastMetre = Math.max(lastMetre, 2 * (int) Math.floor(groundY / scale / 2));
            lastMetre = Math.min(lastMetre, firstMetre + 2 * (int) Math.ceil(height / scale / 2) + 2);
        }
        for (int metre = firstMetre; metre <= lastMetre; metre += 2) {
            double y = sy(metre);
            if (y > 0 && y < height) {
                g.strokeLine(0, y, width, y);
            }
        }
    }

    private void drawGroundAndTruck(GraphicsContext g, double width) {
        // Ground line + hatching.
        g.setStroke(GROUND);
        g.setLineWidth(2);
        g.strokeLine(0, sy(0), width, sy(0));
        g.setLineWidth(1);
        for (double x = 0; x < width; x += 14) {
            g.strokeLine(x, sy(0), x - 7, sy(0) + 8);
        }

        // Chassis, cab, wheels — a simple flatbed silhouette.
        g.setFill(TRUCK_FILL);
        g.setStroke(TRUCK_LINE);
        g.setLineWidth(1.5);

        double bedLeft = -4.2, bedRight = 2.8;
        g.fillRect(sx(bedLeft), sy(BED_HEIGHT), (bedRight - bedLeft) * scale, (BED_HEIGHT - 0.55) * scale);
        g.strokeRect(sx(bedLeft), sy(BED_HEIGHT), (bedRight - bedLeft) * scale, (BED_HEIGHT - 0.55) * scale);

        double cabLeft = -5.7, cabRight = -4.2, cabTop = 2.3;
        g.fillRoundRect(sx(cabLeft), sy(cabTop), (cabRight - cabLeft) * scale, (cabTop - 0.55) * scale,
                0.4 * scale, 0.4 * scale);
        g.strokeRoundRect(sx(cabLeft), sy(cabTop), (cabRight - cabLeft) * scale, (cabTop - 0.55) * scale,
                0.4 * scale, 0.4 * scale);

        double wheelRadius = 0.55;
        for (double wheelX : new double[]{-4.9, -3.3, 0.9, 2.1}) {
            g.setFill(TRUCK_FILL);
            g.fillOval(sx(wheelX - wheelRadius), sy(wheelRadius * 2),
                    wheelRadius * 2 * scale, wheelRadius * 2 * scale);
            g.setStroke(TRUCK_LINE);
            g.strokeOval(sx(wheelX - wheelRadius), sy(wheelRadius * 2),
                    wheelRadius * 2 * scale, wheelRadius * 2 * scale);
        }
    }

    private void drawCraneSideView(GraphicsContext g, CraneState state) {
        double boomDeg = state.position("boom");
        double jibDeg = state.position("jib");
        double extension = state.position("extension");
        double rope = state.position("winch");

        // Pillar.
        double pivotX = 0, pivotY = BED_HEIGHT + PILLAR_HEIGHT;
        g.setStroke(CRANE_AMBER);
        g.setLineWidth(0.32 * scale);
        g.strokeLine(sx(0), sy(BED_HEIGHT), sx(pivotX), sy(pivotY));

        // Main boom: base section + extension section (lighter) rotated by "boom".
        double boomRad = Math.toRadians(boomDeg);
        double baseTipX = pivotX + BOOM_BASE_LENGTH * Math.cos(boomRad);
        double baseTipY = pivotY + BOOM_BASE_LENGTH * Math.sin(boomRad);
        double boomTipX = pivotX + (BOOM_BASE_LENGTH + extension) * Math.cos(boomRad);
        double boomTipY = pivotY + (BOOM_BASE_LENGTH + extension) * Math.sin(boomRad);

        g.setLineWidth(0.26 * scale);
        g.setStroke(CRANE_AMBER);
        g.strokeLine(sx(pivotX), sy(pivotY), sx(baseTipX), sy(baseTipY));
        if (extension > 0.01) {
            g.setLineWidth(0.17 * scale);
            g.setStroke(EXTENSION_AMBER);
            g.strokeLine(sx(baseTipX), sy(baseTipY), sx(boomTipX), sy(boomTipY));
        }

        // Jib: rotated by "jib" relative to the boom (0 = straight prolongation,
        // positive knuckles downward).
        double jibRad = Math.toRadians(boomDeg - jibDeg);
        double jibTipX = boomTipX + JIB_LENGTH * Math.cos(jibRad);
        double jibTipY = boomTipY + JIB_LENGTH * Math.sin(jibRad);
        g.setLineWidth(0.18 * scale);
        g.setStroke(CRANE_AMBER);
        g.strokeLine(sx(boomTipX), sy(boomTipY), sx(jibTipX), sy(jibTipY));

        // Joints.
        drawJoint(g, pivotX, pivotY, 0.22);
        drawJoint(g, boomTipX, boomTipY, 0.18);

        // Rope (dashed) + hook block, hanging from the jib tip and deflected from
        // vertical by the optional "loadSway" pendulum angle (reads 0 when absent).
        double blockWidth = 0.34, blockHeight = 0.42, hookDrop = 0.32;
        double ropeLen = Math.max(0.0,
                jibTipY - Math.max(jibTipY - rope, blockHeight + hookDrop + 0.1));
        double swayRad = Math.toRadians(state.position("loadSway"));
        double hookX = jibTipX + ropeLen * Math.sin(swayRad);
        double hookY = jibTipY - ropeLen * Math.cos(swayRad);
        g.save();
        g.setStroke(ROPE);
        g.setLineWidth(Math.max(1.2, 0.05 * scale));
        g.setLineDashes(6, 6);
        g.strokeLine(sx(jibTipX), sy(jibTipY), sx(hookX), sy(hookY));
        g.restore();

        g.setFill(HOOK_RED);
        g.fillRect(sx(hookX - blockWidth / 2), sy(hookY), blockWidth * scale, blockHeight * scale);
        g.setStroke(HOOK_RED);
        g.setLineWidth(Math.max(1.5, 0.07 * scale));
        g.strokeArc(sx(hookX - 0.16), sy(hookY - blockHeight), 0.32 * scale, hookDrop * scale,
                200, 220, javafx.scene.shape.ArcType.OPEN);

        drawCargo(g, hookX, hookY - blockHeight - hookDrop);

        // Live annotation beside the hook: horizontal outreach from the slew
        // axis (world x = 0) and hook height above ground — the same world
        // coordinates the hook was just drawn from.
        String readout = "out " + fmt(hookX) + " m / h " + fmt(hookY) + " m";
        // Flip to the hook's left when the label would run past the canvas edge.
        boolean flip = sx(hookX + blockWidth) + 4 + readout.length() * 6.2 > canvasWidth;
        g.setFill(ANNOTATION_TEXT);
        g.setFont(ANNOTATION_FONT);
        g.setTextAlign(flip ? TextAlignment.RIGHT : TextAlignment.LEFT);
        g.setTextBaseline(VPos.CENTER);
        g.fillText(readout,
                flip ? sx(hookX - blockWidth) - 4 : sx(hookX + blockWidth) + 4,
                sy(hookY - blockHeight / 2));
        g.setTextAlign(TextAlignment.LEFT);
    }

    /**
     * The selected load hanging under the hook, in side view: a silhouette in
     * the cargo's own colour with a dimension label. Mirrors what the 3D view
     * shows, so switching views never changes what is on the hook.
     *
     * @param topY world height of the cargo's top face (the hook's underside)
     */
    private void drawCargo(GraphicsContext g, double centreX, double topY) {
        CargoType type = cargo;
        if (type == CargoType.NONE) {
            return;
        }
        // Rest on the ground once lowered, exactly like the 3D view's cargo.
        double height = type.height();
        double bottomY = Math.max(0.0, topY - height);
        double drawTopY = bottomY + height;
        double length = type.length();

        g.setFill(CARGO_FILL);
        g.fillRect(sx(centreX - length / 2), sy(drawTopY), length * scale, height * scale);
        g.setStroke(CARGO_EDGE);
        g.setLineWidth(1.5);
        g.strokeRect(sx(centreX - length / 2), sy(drawTopY), length * scale, height * scale);

        g.setFill(ANNOTATION_TEXT);
        g.setFont(ANNOTATION_FONT);
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.CENTER);
        g.fillText(type.label(), sx(centreX), sy(drawTopY - height / 2));
        g.setTextAlign(TextAlignment.LEFT);
    }

    private void drawJoint(GraphicsContext g, double worldX, double worldY, double radiusMetres) {
        g.setFill(JOINT);
        g.setStroke(CRANE_AMBER);
        g.setLineWidth(1.5);
        double r = radiusMetres * scale;
        g.fillOval(sx(worldX) - r / 2, sy(worldY) - r / 2, r, r);
        g.strokeOval(sx(worldX) - r / 2, sy(worldY) - r / 2, r, r);
    }

    // ---- measurement annotations (dim, thin, small monospace) ----

    /**
     * Dashed reach arcs centred on the pillar pivot: the maximum tip radius
     * (boom base + the profile's extension limit + jib, fully straight) and
     * the current tip radius, both labelled in metres. The radius equals the
     * horizontal outreach when the boom is level — the arc is the tip envelope.
     */
    private void drawReachArcs(GraphicsContext g, CraneProfile profile, CraneState state) {
        double pivotY = BED_HEIGHT + PILLAR_HEIGHT;
        double maxExtension = profile.axisById("extension")
                .map(AxisSpec::maxPosition).orElse(0.0);
        double maxReach = BOOM_BASE_LENGTH + maxExtension + JIB_LENGTH;

        // Current tip radius from the pivot — the same articulation the boom
        // and jib are drawn with in drawCraneSideView.
        double boomRad = Math.toRadians(state.position("boom"));
        double jibRad = Math.toRadians(state.position("boom") - state.position("jib"));
        double boomLen = BOOM_BASE_LENGTH + state.position("extension");
        double tipX = boomLen * Math.cos(boomRad) + JIB_LENGTH * Math.cos(jibRad);
        double tipY = boomLen * Math.sin(boomRad) + JIB_LENGTH * Math.sin(jibRad);
        double currentReach = Math.hypot(tipX, tipY);

        drawReachArc(g, pivotY, maxReach, "R max " + fmt(maxReach) + " m");
        if (currentReach > 0.5 && currentReach < maxReach - 0.15) {
            drawReachArc(g, pivotY, currentReach, "R " + fmt(currentReach) + " m");
        }
    }

    private void drawReachArc(GraphicsContext g, double pivotY, double radius, String label) {
        double r = radius * scale;
        g.save();
        g.setStroke(ANNOTATION);
        g.setLineWidth(1);
        g.setLineDashes(4, 6);
        // From slightly below horizontal to past vertical (positive = CCW on screen).
        g.strokeArc(sx(0) - r, sy(pivotY) - r, r * 2, r * 2, -12, 114,
                javafx.scene.shape.ArcType.OPEN);
        g.restore();

        double labelRad = Math.toRadians(18);
        g.setFill(ANNOTATION_TEXT);
        g.setFont(ANNOTATION_FONT);
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.CENTER);
        g.fillText(label,
                sx(radius * Math.cos(labelRad)) + 5,
                sy(pivotY + radius * Math.sin(labelRad)));
    }

    /** Small metre tick scale up the left edge; tick spacing adapts to zoom. */
    private void drawHeightMarkers(GraphicsContext g, double height) {
        double step = niceStep(26 / scale); // >= ~26 px between ticks
        double topWorldY = groundY / scale;
        double bottomWorldY = (groundY - height) / scale;
        double first = Math.max(0, Math.ceil(bottomWorldY / step) * step);

        g.setStroke(ANNOTATION);
        g.setLineWidth(1);
        g.setFill(ANNOTATION_TEXT);
        g.setFont(ANNOTATION_FONT);
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.CENTER);
        for (double metre = first; metre <= topWorldY; metre += step) {
            double y = sy(metre);
            if (y < 14 || y > height - 26) {
                continue; // keep clear of the top edge and the scale bar corner
            }
            g.strokeLine(0, y, 7, y);
            g.fillText(fmtTick(metre), 10, y);
        }
    }

    /** Zoom-adaptive scale bar ("5 m") in the bottom-left corner. */
    private void drawScaleBar(GraphicsContext g, double height) {
        double len = niceBarLength(120 / scale); // longest nice length <= ~120 px
        double px = len * scale;
        double x = 16;
        double y = height - 14;

        g.setStroke(ANNOTATION);
        g.setLineWidth(1);
        g.strokeLine(x, y, x + px, y);
        g.strokeLine(x, y - 4, x, y + 4);
        g.strokeLine(x + px, y - 4, x + px, y + 4);

        g.setFill(ANNOTATION_TEXT);
        g.setFont(ANNOTATION_FONT);
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.BOTTOM);
        g.fillText(fmtTick(len) + " m", x + px / 2, y - 4);
    }

    /** Smallest "nice" metre step that is at least {@code minWorldStep}. */
    private static double niceStep(double minWorldStep) {
        for (double s : new double[]{0.5, 1, 2, 5, 10, 20, 50, 100}) {
            if (s >= minWorldStep) {
                return s;
            }
        }
        return 200;
    }

    /** Largest "nice" metre length that fits within {@code maxWorldLen}. */
    private static double niceBarLength(double maxWorldLen) {
        for (double s : new double[]{100, 50, 20, 10, 5, 2, 1, 0.5, 0.2}) {
            if (s <= maxWorldLen) {
                return s;
            }
        }
        return 0.1;
    }

    private static String fmt(double metres) {
        return String.format(Locale.ROOT, "%.1f", metres);
    }

    private static String fmtTick(double metres) {
        return metres == Math.rint(metres)
                ? String.format(Locale.ROOT, "%.0f", metres)
                : String.format(Locale.ROOT, "%.1f", metres);
    }

    private void drawTopViewInset(GraphicsContext g, double width, CraneState state) {
        double radius = 90;
        double cx = width - radius - 18;
        double cy = radius + 18;

        g.setFill(Color.web("#1c232b"));
        g.setStroke(GROUND);
        g.setLineWidth(1.5);
        g.fillOval(cx - radius, cy - radius, radius * 2, radius * 2);
        g.strokeOval(cx - radius, cy - radius, radius * 2, radius * 2);

        g.setFill(TEXT_DIM);
        g.setFont(Font.font("System", FontWeight.BOLD, 11));
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.TOP);
        g.fillText("TOP VIEW — SLEW", cx, cy - radius + 8);

        // Truck outline, forward = up.
        double truckWidth = 26, truckLength = 74;
        g.setStroke(TRUCK_LINE);
        g.setLineWidth(1.5);
        g.strokeRoundRect(cx - truckWidth / 2, cy - truckLength / 2, truckWidth, truckLength, 6, 6);
        g.strokeRect(cx - truckWidth / 2, cy - truckLength / 2, truckWidth, 14); // cab

        // Boom direction line: slew 0 = forward (up), positive = clockwise.
        double slewRad = Math.toRadians(state.position("slew"));
        double reach = (BOOM_BASE_LENGTH + state.position("extension"))
                * Math.cos(Math.toRadians(state.position("boom"))) + JIB_LENGTH;
        double maxReach = BOOM_BASE_LENGTH + 6.0 + JIB_LENGTH;
        double lineLength = radius * 0.88 * Math.clamp(reach / maxReach, 0.15, 1.0);
        double tipX = cx + Math.sin(slewRad) * lineLength;
        double tipY = cy - Math.cos(slewRad) * lineLength;

        g.setStroke(CRANE_AMBER);
        g.setLineWidth(4);
        g.strokeLine(cx, cy, tipX, tipY);
        g.setFill(CRANE_AMBER);
        g.fillOval(cx - 5, cy - 5, 10, 10);
        g.setFill(HOOK_RED);
        g.fillOval(tipX - 4, tipY - 4, 8, 8);
    }

    private void drawEstopBanner(GraphicsContext g, double width, double height) {
        double bannerHeight = 84;
        double y = height / 2 - bannerHeight / 2;
        g.setFill(ALARM_RED.deriveColor(0, 1, 1, 0.88));
        g.fillRect(0, y, width, bannerHeight);
        g.setFill(Color.WHITE);
        g.setFont(Font.font("System", FontWeight.EXTRA_BOLD, 46));
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.CENTER);
        g.fillText("E-STOP", width / 2, y + bannerHeight / 2);
    }
}
