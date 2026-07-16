package com.vukotic.crane.ui.render;

import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.model.CraneState;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.geometry.VPos;

/**
 * 2D schematic of a truck-mounted knuckle-boom crane.
 *
 * <p>Side view (main area): ground, truck silhouette, pillar, main boom
 * (rotated by the "boom" axis, lengthened by "extension"), jib rotated by
 * "jib" relative to the boom, and a hook hanging from the jib tip by the
 * "winch" rope length. Top view (inset, top-right): truck outline and boom
 * direction rotated by "slew".
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

    // Screen transform of the current frame (render() is single-threaded on the FX thread).
    private double scale;
    private double originX; // screen x of world x = 0 (pillar base)
    private double groundY; // screen y of world y = 0 (ground)

    @Override
    public void render(GraphicsContext g, double width, double height, CraneProfile profile, CraneState state) {
        g.setFill(BACKGROUND);
        g.fillRect(0, 0, width, height);
        if (width < 40 || height < 40) {
            return;
        }

        scale = Math.min(width / WORLD_WIDTH, height / WORLD_HEIGHT);
        originX = width * 0.32;
        groundY = height * 0.88;

        drawGrid(g, width, height);
        drawGroundAndTruck(g, width);
        drawCraneSideView(g, state);
        drawTopViewInset(g, width, state);

        if (state.estopLatched()) {
            drawEstopBanner(g, width, height);
        }
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
        for (int metre = 0; metre <= WORLD_HEIGHT; metre += 2) {
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

        // Rope (dashed) + hook block, hanging straight down from the jib tip.
        // hookY = world y of the rope end; block and hook hang just below it.
        double blockWidth = 0.34, blockHeight = 0.42, hookDrop = 0.32;
        double hookY = Math.max(jibTipY - rope, blockHeight + hookDrop + 0.1);
        g.save();
        g.setStroke(ROPE);
        g.setLineWidth(Math.max(1.2, 0.05 * scale));
        g.setLineDashes(6, 6);
        g.strokeLine(sx(jibTipX), sy(jibTipY), sx(jibTipX), sy(hookY));
        g.restore();

        g.setFill(HOOK_RED);
        g.fillRect(sx(jibTipX - blockWidth / 2), sy(hookY), blockWidth * scale, blockHeight * scale);
        g.setStroke(HOOK_RED);
        g.setLineWidth(Math.max(1.5, 0.07 * scale));
        g.strokeArc(sx(jibTipX - 0.16), sy(hookY - blockHeight), 0.32 * scale, hookDrop * scale,
                200, 220, javafx.scene.shape.ArcType.OPEN);
    }

    private void drawJoint(GraphicsContext g, double worldX, double worldY, double radiusMetres) {
        g.setFill(JOINT);
        g.setStroke(CRANE_AMBER);
        g.setLineWidth(1.5);
        double r = radiusMetres * scale;
        g.fillOval(sx(worldX) - r / 2, sy(worldY) - r / 2, r, r);
        g.strokeOval(sx(worldX) - r / 2, sy(worldY) - r / 2, r, r);
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
