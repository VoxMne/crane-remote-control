package com.vukotic.crane.ui.render;

import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.model.CraneState;
import javafx.geometry.Pos;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.PerspectiveCamera;
import javafx.scene.PointLight;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.Cylinder;
import javafx.scene.shape.Sphere;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;

/**
 * JavaFX 3D view of the truck-mounted knuckle-boom crane.
 *
 * <p>Same axis conventions and visual proportions as {@link SchematicRenderer2D}:
 * "slew" rotates the whole superstructure about the vertical axis, "boom" is the
 * main boom angle up from horizontal, the boom lengthens with "extension", the
 * jib knuckles down by "jib" relative to the boom, and a rope of length "winch"
 * hangs straight down from the jib tip ending in the hook block. The optional
 * "loadSway" state entry (degrees, 0 when absent) deflects the rope + hook from
 * vertical in the boom's plane. Missing axes read as 0 and render sensibly.
 *
 * <p>Coordinates follow the JavaFX default: Y points down, so "up" is negative Y;
 * the ground plane is at y = 0. The scene graph is built once — the per-frame
 * {@link #update} only mutates pre-installed {@link Rotate}/{@link Translate}/
 * {@link Scale} transforms and shape lengths, never the graph itself.
 *
 * <p>Camera: perspective, orbiting the crane. Mouse drag changes azimuth and
 * elevation, scroll zooms.
 */
public final class Crane3DView implements CraneSceneView {

    // ---- fixed visual proportions (metres) — must match SchematicRenderer2D ----
    private static final double BED_HEIGHT = 1.1;
    private static final double PILLAR_HEIGHT = 2.0;
    private static final double BOOM_BASE_LENGTH = 5.0;
    private static final double JIB_LENGTH = 3.0;
    private static final double HOOK_BLOCK_HEIGHT = 0.42;
    private static final double HOOK_GROUND_CLEARANCE = 0.85;

    // ---- palette (matches the 2D schematic / dark HMI) ----
    private static final Color BACKGROUND = Color.web("#14181d");
    private static final Color GROUND = Color.web("#171c22");
    private static final Color GRID = Color.web("#232c35");
    private static final Color TRUCK_FILL = Color.web("#2a333d");
    private static final Color WHEEL = Color.web("#1a2026");
    private static final Color CRANE_AMBER = Color.web("#e8b716");
    private static final Color EXTENSION_AMBER = Color.web("#f5d35a");
    private static final Color ROPE = Color.web("#9aa7b0");
    private static final Color HOOK_RED = Color.web("#d64541");

    // ---- camera rig ----
    private static final double MIN_DISTANCE = 6.0;
    private static final double MAX_DISTANCE = 120.0;
    private final Rotate camAzimuth = new Rotate(-35, Rotate.Y_AXIS);
    private final Rotate camElevation = new Rotate(-20, Rotate.X_AXIS);
    private final Translate camDistance = new Translate(0, 0, -34);
    private double lastMouseX;
    private double lastMouseY;

    // ---- per-frame articulation (update() only sets angles/lengths on these) ----
    private final Rotate slewRotate = new Rotate(0, Rotate.Y_AXIS);
    private final Rotate boomRotate = new Rotate(0, Rotate.Z_AXIS);
    private final Scale extensionScale = new Scale(0.001, 1, 1);
    private final Translate jibTranslate = new Translate(BOOM_BASE_LENGTH, 0, 0);
    private final Rotate jibRotate = new Rotate(0, Rotate.Z_AXIS);
    /** Cancels the accumulated boom+jib rotation so the rope hangs vertical. */
    private final Rotate ropePlumbRotate = new Rotate(0, Rotate.Z_AXIS);
    private final Rotate swayRotate = new Rotate(0, Rotate.Z_AXIS);
    private final Box extensionBox;
    private final Cylinder rope;
    private final Box hookBlock;
    private final Sphere hookTip;

    private final StackPane container = new StackPane();
    private final Label estopBanner;

    public Crane3DView() {
        PhongMaterial amber = material(CRANE_AMBER);
        PhongMaterial lightAmber = material(EXTENSION_AMBER);
        PhongMaterial red = material(HOOK_RED);
        PhongMaterial ropeMaterial = material(ROPE);

        // Superstructure: everything above the truck bed, rotated by "slew".
        Group superstructure = new Group();
        superstructure.getTransforms().addAll(new Translate(0, -BED_HEIGHT, 0), slewRotate);

        Cylinder pillar = new Cylinder(0.22, PILLAR_HEIGHT);
        pillar.setMaterial(amber);
        pillar.setTranslateY(-PILLAR_HEIGHT / 2);

        // Boom pivots at the pillar top; positive "boom" raises it from horizontal.
        Group boomGroup = new Group();
        boomGroup.getTransforms().addAll(new Translate(0, -PILLAR_HEIGHT, 0), boomRotate);

        Box boomBase = new Box(BOOM_BASE_LENGTH, 0.34, 0.34);
        boomBase.setMaterial(amber);
        boomBase.setTranslateX(BOOM_BASE_LENGTH / 2);

        // Extension: a unit box anchored at the base tip, stretched to the
        // extension length by a pivot-at-origin Scale.
        Group extensionGroup = new Group();
        extensionGroup.getTransforms().addAll(new Translate(BOOM_BASE_LENGTH, 0, 0), extensionScale);
        extensionBox = new Box(1.0, 0.24, 0.24);
        extensionBox.setMaterial(lightAmber);
        extensionBox.setTranslateX(0.5);
        extensionBox.setVisible(false);
        extensionGroup.getChildren().add(extensionBox);

        // Jib: attached at the (moving) boom tip, knuckling down by "jib".
        Group jibGroup = new Group();
        jibGroup.getTransforms().addAll(jibTranslate, jibRotate);
        Box jibBox = new Box(JIB_LENGTH, 0.26, 0.26);
        jibBox.setMaterial(amber);
        jibBox.setTranslateX(JIB_LENGTH / 2);

        // Rope + hook: hangs from the jib tip; plumb rotate keeps it vertical,
        // sway rotate deflects it by "loadSway" in the boom's plane.
        Group ropeGroup = new Group();
        ropeGroup.getTransforms().addAll(new Translate(JIB_LENGTH, 0, 0), ropePlumbRotate, swayRotate);
        rope = new Cylinder(0.035, 0.001);
        rope.setMaterial(ropeMaterial);
        rope.setVisible(false);
        hookBlock = new Box(0.34, HOOK_BLOCK_HEIGHT, 0.3);
        hookBlock.setMaterial(red);
        hookBlock.setTranslateY(HOOK_BLOCK_HEIGHT / 2);
        hookTip = new Sphere(0.1);
        hookTip.setMaterial(red);
        hookTip.setTranslateY(HOOK_BLOCK_HEIGHT + 0.08);
        ropeGroup.getChildren().addAll(rope, hookBlock, hookTip);

        jibGroup.getChildren().addAll(jibBox, ropeGroup);
        boomGroup.getChildren().addAll(boomBase, extensionGroup, jibGroup);
        superstructure.getChildren().addAll(pillar, boomGroup);

        // Camera rig: orbit centre a little above the boom pivot.
        PerspectiveCamera camera = new PerspectiveCamera(true);
        camera.setNearClip(0.1);
        camera.setFarClip(600);
        camera.getTransforms().add(camDistance);
        Group cameraRig = new Group(camera);
        // Orbit centre shifted along +X so the extended arm stays in frame.
        cameraRig.getTransforms().addAll(
                new Translate(2.5, -(BED_HEIGHT + PILLAR_HEIGHT + 1.0), 0),
                camAzimuth, camElevation);

        AmbientLight ambient = new AmbientLight(Color.rgb(96, 102, 108));
        PointLight keyLight = new PointLight(Color.rgb(210, 214, 218));
        keyLight.setTranslateX(-18);
        keyLight.setTranslateY(-26);
        keyLight.setTranslateZ(-14);

        Group worldRoot = new Group(ambient, keyLight,
                buildGround(), buildTruck(), superstructure, cameraRig);

        SubScene subScene = new SubScene(worldRoot, 1, 1, true, SceneAntialiasing.BALANCED);
        subScene.setFill(BACKGROUND);
        subScene.setCamera(camera);
        subScene.widthProperty().bind(container.widthProperty());
        subScene.heightProperty().bind(container.heightProperty());
        installCameraControls(subScene);

        estopBanner = buildEstopBanner();

        container.setStyle("-fx-background-color: " + toWeb(BACKGROUND) + ";");
        container.getChildren().addAll(subScene, estopBanner);
        StackPane.setAlignment(estopBanner, Pos.CENTER);
    }

    @Override
    public Node node() {
        return container;
    }

    @Override
    public void update(CraneProfile profile, CraneState state) {
        double slewDeg = state.position("slew");
        double boomDeg = state.position("boom");
        double jibDeg = state.position("jib");
        double extension = Math.max(0, state.position("extension"));
        double ropeOut = Math.max(0, state.position("winch"));
        double swayDeg = state.position("loadSway"); // 0 when the sim doesn't publish it

        // Positive slew = clockwise seen from above (2D top-view convention).
        slewRotate.setAngle(slewDeg);
        // Y points down, so raising the boom is a negative Z rotation.
        boomRotate.setAngle(-boomDeg);
        extensionScale.setX(Math.max(extension, 0.001));
        extensionBox.setVisible(extension > 0.01);
        jibTranslate.setX(BOOM_BASE_LENGTH + extension);
        // Positive jib knuckles downward relative to the boom (2D: boom - jib).
        jibRotate.setAngle(jibDeg);
        // Undo the parents' net Z rotation (-boom + jib) so the rope is vertical...
        ropePlumbRotate.setAngle(boomDeg - jibDeg);
        // ...then deflect it by the pendulum angle (positive = outward, +X side).
        swayRotate.setAngle(-swayDeg);

        // Clamp the rope so the hook stays above the ground, like the 2D view.
        double jibTipHeight = BED_HEIGHT + PILLAR_HEIGHT
                + (BOOM_BASE_LENGTH + extension) * Math.sin(Math.toRadians(boomDeg))
                + JIB_LENGTH * Math.sin(Math.toRadians(boomDeg - jibDeg));
        double ropeLength = Math.clamp(ropeOut, 0.0,
                Math.max(0.0, jibTipHeight - HOOK_GROUND_CLEARANCE));
        rope.setHeight(Math.max(ropeLength, 0.001));
        rope.setTranslateY(ropeLength / 2);
        rope.setVisible(ropeLength > 0.02);
        hookBlock.setTranslateY(ropeLength + HOOK_BLOCK_HEIGHT / 2);
        hookTip.setTranslateY(ropeLength + HOOK_BLOCK_HEIGHT + 0.08);

        estopBanner.setVisible(state.estopLatched());
    }

    // ---- static scenery ----

    private static Node buildGround() {
        Group ground = new Group();

        Box plane = new Box(60, 0.12, 60);
        plane.setMaterial(material(GROUND));
        plane.setTranslateY(0.06); // top surface exactly at y = 0
        ground.getChildren().add(plane);

        // Subtle 2 m grid: thin flat boxes just above the plane.
        PhongMaterial gridMaterial = material(GRID);
        for (int metre = -30; metre <= 30; metre += 2) {
            Box alongZ = new Box(0.05, 0.01, 60);
            alongZ.setMaterial(gridMaterial);
            alongZ.setTranslateX(metre);
            alongZ.setTranslateY(-0.01);
            Box alongX = new Box(60, 0.01, 0.05);
            alongX.setMaterial(gridMaterial);
            alongX.setTranslateZ(metre);
            alongX.setTranslateY(-0.01);
            ground.getChildren().addAll(alongZ, alongX);
        }
        return ground;
    }

    /** Flatbed silhouette in boxes, matching the 2D side view's footprint. */
    private static Node buildTruck() {
        PhongMaterial body = material(TRUCK_FILL);
        PhongMaterial wheelMaterial = material(WHEEL);
        Group truck = new Group();

        Box bed = new Box(7.0, 0.55, 2.4);        // world x -4.2 .. 2.8, top at bed height
        bed.setMaterial(body);
        bed.setTranslateX(-0.7);
        bed.setTranslateY(-(BED_HEIGHT - 0.55 / 2));

        Box cab = new Box(1.5, 1.75, 2.4);        // world x -5.7 .. -4.2, top at 2.3
        cab.setMaterial(body);
        cab.setTranslateX(-4.95);
        cab.setTranslateY(-(0.55 + 1.75 / 2));

        truck.getChildren().addAll(bed, cab);

        for (double wheelX : new double[]{-4.9, -3.3, 0.9, 2.1}) {
            for (double wheelZ : new double[]{-1.05, 1.05}) {
                Cylinder wheel = new Cylinder(0.55, 0.4);
                wheel.setMaterial(wheelMaterial);
                wheel.setRotationAxis(Rotate.X_AXIS);
                wheel.setRotate(90);              // axle along Z
                wheel.setTranslateX(wheelX);
                wheel.setTranslateY(-0.55);
                wheel.setTranslateZ(wheelZ);
                truck.getChildren().add(wheel);
            }
        }
        return truck;
    }

    private static Label buildEstopBanner() {
        Label banner = new Label("E-STOP");
        banner.setStyle("-fx-background-color: rgba(214,69,65,0.88); -fx-text-fill: white;"
                + " -fx-font-size: 46px; -fx-font-weight: 800;");
        banner.setMaxWidth(Double.MAX_VALUE);
        banner.setMinHeight(84);
        banner.setMaxHeight(84);
        banner.setAlignment(Pos.CENTER);
        banner.setMouseTransparent(true);
        banner.setVisible(false);
        return banner;
    }

    // ---- camera controls ----

    private void installCameraControls(SubScene subScene) {
        subScene.setOnMousePressed(event -> {
            lastMouseX = event.getSceneX();
            lastMouseY = event.getSceneY();
        });
        subScene.setOnMouseDragged(event -> {
            double dx = event.getSceneX() - lastMouseX;
            double dy = event.getSceneY() - lastMouseY;
            lastMouseX = event.getSceneX();
            lastMouseY = event.getSceneY();
            camAzimuth.setAngle(camAzimuth.getAngle() - dx * 0.35);
            camElevation.setAngle(Math.clamp(camElevation.getAngle() + dy * 0.25, -85.0, 0.0));
        });
        subScene.setOnScroll(event -> {
            double distance = -camDistance.getZ();
            distance = Math.clamp(distance * Math.exp(-event.getDeltaY() * 0.002),
                    MIN_DISTANCE, MAX_DISTANCE);
            camDistance.setZ(-distance);
        });
    }

    // ---- helpers ----

    private static PhongMaterial material(Color diffuse) {
        PhongMaterial material = new PhongMaterial(diffuse);
        material.setSpecularColor(Color.rgb(70, 70, 70));
        return material;
    }

    private static String toWeb(Color color) {
        return String.format("#%02x%02x%02x",
                (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255),
                (int) Math.round(color.getBlue() * 255));
    }
}
