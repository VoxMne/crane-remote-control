package com.vukotic.crane.ui.render;

import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.model.CraneState;
import javafx.geometry.Point3D;
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
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.Cylinder;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.Sphere;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;

/**
 * JavaFX 3D view of the truck-mounted knuckle-boom crane and its surroundings.
 *
 * <p>Same axis conventions and proportions as {@link SchematicRenderer2D}:
 * "slew" rotates the superstructure about the vertical axis, "boom" is the main
 * boom angle up from horizontal, the boom lengthens with "extension", the jib
 * knuckles down by "jib" relative to the boom, and a rope of length "winch"
 * hangs from the jib tip. The optional "loadSway" state entry (degrees, 0 when
 * absent) deflects the rope, hook and any attached cargo.
 *
 * <p>Coordinates follow the JavaFX default: <b>Y points down</b>, so "up" is
 * negative Y and the ground plane is y = 0. The scene graph is built once; the
 * per-frame {@link #update} only mutates transforms, shape lengths and
 * visibility — it never rebuilds the graph.
 *
 * <h2>Scene (M5)</h2>
 * Procedural meshes only (see {@link MeshFactory}) — no asset files. Tapered
 * boom/extension/jib sections, two animated hydraulic rams, a detailed truck,
 * a gradient sky with a sun and matching key light, blob shadows (including one
 * that tracks the hook), a dock with water and a moored boat, and selectable
 * {@link CargoType} that hangs from the hook, can be set down and picked up.
 *
 * <h2>Cameras</h2>
 * All four {@link CameraMode}s are expressed as the same six rig parameters
 * (orbit centre XYZ, azimuth, elevation, distance), so switching modes is a
 * smooth interpolation rather than a cut. Mouse orbit/zoom only applies in
 * {@link CameraMode#ORBIT}.
 */
public final class Crane3DView implements CraneSceneView {

    // ---- fixed visual proportions (metres) — must match SchematicRenderer2D ----
    private static final double BED_HEIGHT = 1.1;
    private static final double PILLAR_HEIGHT = 2.0;
    private static final double BOOM_BASE_LENGTH = 5.0;
    private static final double JIB_LENGTH = 3.0;
    private static final double HOOK_BLOCK_HEIGHT = 0.42;
    private static final double HOOK_GROUND_CLEARANCE = 0.85;

    // ---- palette ----
    private static final Color GROUND = Color.web("#1b2129");
    private static final Color GRID = Color.web("#2b3743");
    private static final Color TRUCK_FILL = Color.web("#39434f");
    private static final Color TRUCK_DARK = Color.web("#252d36");
    private static final Color GLASS = Color.web("#16202b");
    private static final Color WHEEL = Color.web("#15191e");
    private static final Color RIM = Color.web("#4a545f");
    private static final Color CRANE_AMBER = Color.web("#e8b716");
    private static final Color EXTENSION_AMBER = Color.web("#f5d35a");
    private static final Color STEEL = Color.web("#8b949c");
    private static final Color CHROME = Color.web("#c8d0d6");
    private static final Color ROPE_COLOR = Color.web("#9aa7b0");
    private static final Color HOOK_RED = Color.web("#d64541");
    private static final Color DOCK_COLOR = Color.web("#3a3630");
    private static final Color WATER = Color.web("#1d4f6b");
    private static final Color BOAT_HULL = Color.web("#d9dde0");
    private static final Color SHADOW = Color.rgb(0, 0, 0, 0.34);

    // ---- sky ----
    private static final Color SKY_ZENITH = Color.web("#0d1b2a");
    private static final Color SKY_HORIZON = Color.web("#5c6470");
    private static final double SKY_RADIUS = 260;

    // ---- world layout ----
    private static final double DOCK_EDGE_Z = 12.0;
    private static final double WATER_LEVEL_Y = 0.45;   // Y down: slightly below ground

    // ---- camera rig ----
    private static final double MIN_DISTANCE = 6.0;
    private static final double MAX_DISTANCE = 120.0;
    private static final Point3D ORBIT_CENTRE =
            new Point3D(2.5, -(BED_HEIGHT + PILLAR_HEIGHT + 1.0), 0);
    /**
     * Operator eye point for {@link CameraMode#CAB}: at the cab's side window and
     * offset in Z, so the line of sight to the load passes beside the crane pillar
     * instead of straight through it.
     */
    private static final Point3D CAB_EYE = new Point3D(-5.2, -2.5, -2.4);
    private static final double TRANSITION_SECONDS = 0.6;

    private final Translate camCentre = new Translate(
            ORBIT_CENTRE.getX(), ORBIT_CENTRE.getY(), ORBIT_CENTRE.getZ());
    private final Rotate camAzimuth = new Rotate(-35, Rotate.Y_AXIS);
    private final Rotate camElevation = new Rotate(-20, Rotate.X_AXIS);
    private final Translate camDistance = new Translate(0, 0, -34);

    /** Mouse-controlled orbit state (only used in ORBIT mode). */
    private double orbitAzimuth = -35;
    private double orbitElevation = -20;
    private double orbitDistance = 34;
    private double lastMouseX;
    private double lastMouseY;

    private CameraMode cameraMode = CameraMode.ORBIT;
    private double modeSwitchAgeSeconds = TRANSITION_SECONDS;
    private long lastUpdateNanos = -1;

    // ---- per-frame articulation ----
    private final Rotate slewRotate = new Rotate(0, Rotate.Y_AXIS);
    private final Rotate boomRotate = new Rotate(0, Rotate.Z_AXIS);
    private final Scale extensionScale = new Scale(0.001, 1, 1);
    private final Translate jibTranslate = new Translate(BOOM_BASE_LENGTH, 0, 0);
    private final Rotate jibRotate = new Rotate(0, Rotate.Z_AXIS);
    /** Cancels the accumulated boom+jib rotation so the rope hangs vertical. */
    private final Rotate ropePlumbRotate = new Rotate(0, Rotate.Z_AXIS);
    private final Rotate swayRotate = new Rotate(0, Rotate.Z_AXIS);

    private final MeshView extensionBeam;
    private final Cylinder rope;
    private final Box hookBlock;
    private final Sphere hookTip;
    private final HydraulicRam boomRam;
    private final HydraulicRam jibRam;

    // ---- shadows, cargo, scenery ----
    private final Group hookShadow;
    private final Translate hookShadowTranslate = new Translate(0, -0.03, 0);
    private final Scale hookShadowScale = new Scale(1, 1, 1);
    private final Group cargoGroup = new Group();
    private final Translate cargoTranslate = new Translate();
    private final Rotate cargoRotate = new Rotate(0, Rotate.Y_AXIS);
    private final Translate boatBob = new Translate();

    private CargoType cargoType = CargoType.NONE;
    private boolean cargoAttached = true;
    private double cargoX;
    private double cargoY;
    private double cargoZ;

    private final StackPane container = new StackPane();
    private final Label estopBanner;

    public Crane3DView() {
        PhongMaterial amber = material(CRANE_AMBER);
        PhongMaterial lightAmber = material(EXTENSION_AMBER);
        PhongMaterial steel = material(STEEL);
        PhongMaterial chrome = material(CHROME);
        PhongMaterial red = material(HOOK_RED);

        // ---- superstructure: everything above the bed, rotated by "slew" ----
        Group superstructure = new Group();
        superstructure.getTransforms().addAll(new Translate(0, -BED_HEIGHT, 0), slewRotate);

        Cylinder pillar = new Cylinder(0.26, PILLAR_HEIGHT);
        pillar.setMaterial(amber);
        pillar.setTranslateY(-PILLAR_HEIGHT / 2);
        Cylinder slewRing = new Cylinder(0.5, 0.22);
        slewRing.setMaterial(steel);
        slewRing.setTranslateY(-0.11);

        Group boomGroup = new Group();
        boomGroup.getTransforms().addAll(new Translate(0, -PILLAR_HEIGHT, 0), boomRotate);

        MeshView boomBeam = MeshFactory.beam(BOOM_BASE_LENGTH, 0.20, 0.18, 0.15, 0.14, amber);

        Group extensionGroup = new Group();
        extensionGroup.getTransforms().addAll(
                new Translate(BOOM_BASE_LENGTH, 0, 0), extensionScale);
        extensionBeam = MeshFactory.beam(1.0, 0.13, 0.12, 0.11, 0.11, lightAmber);
        extensionBeam.setVisible(false);
        extensionGroup.getChildren().add(extensionBeam);

        Group jibGroup = new Group();
        jibGroup.getTransforms().addAll(jibTranslate, jibRotate);
        MeshView jibBeam = MeshFactory.beam(JIB_LENGTH, 0.14, 0.13, 0.10, 0.10, amber);

        // Rope + hook: plumb rotate keeps it vertical, sway rotate deflects it.
        Group ropeGroup = new Group();
        ropeGroup.getTransforms().addAll(
                new Translate(JIB_LENGTH, 0, 0), ropePlumbRotate, swayRotate);
        rope = new Cylinder(0.035, 0.001);
        rope.setMaterial(material(ROPE_COLOR));
        rope.setVisible(false);
        hookBlock = new Box(0.34, HOOK_BLOCK_HEIGHT, 0.3);
        hookBlock.setMaterial(red);
        hookBlock.setTranslateY(HOOK_BLOCK_HEIGHT / 2);
        hookTip = new Sphere(0.1);
        hookTip.setMaterial(red);
        hookTip.setTranslateY(HOOK_BLOCK_HEIGHT + 0.08);
        ropeGroup.getChildren().addAll(rope, hookBlock, hookTip);

        boomRam = new HydraulicRam(0.11, 0.06, steel, chrome);
        jibRam = new HydraulicRam(0.08, 0.045, steel, chrome);

        jibGroup.getChildren().addAll(jibBeam, ropeGroup);
        boomGroup.getChildren().addAll(boomBeam, extensionGroup, jibGroup);
        superstructure.getChildren().addAll(
                slewRing, pillar, boomGroup, boomRam.node(), jibRam.node());

        // ---- camera rig ----
        PerspectiveCamera camera = new PerspectiveCamera(true);
        camera.setNearClip(0.1);
        camera.setFarClip(900);
        camera.getTransforms().add(camDistance);
        Group cameraRig = new Group(camera);
        cameraRig.getTransforms().addAll(camCentre, camAzimuth, camElevation);

        // ---- lighting: warm sun key + cool ambient fill ----
        AmbientLight ambient = new AmbientLight(Color.rgb(104, 110, 118));
        PointLight sunLight = new PointLight(Color.rgb(255, 244, 214));
        sunLight.setTranslateX(-42);
        sunLight.setTranslateY(-58);
        sunLight.setTranslateZ(-34);

        hookShadow = buildBlobShadow(0.55);
        hookShadow.getTransforms().addAll(hookShadowTranslate, hookShadowScale);

        cargoGroup.getTransforms().addAll(cargoTranslate, cargoRotate);

        Group worldRoot = new Group(ambient, sunLight, buildSkyDome(), buildSun(), buildGround(),
                buildWaterAndDock(), buildStaticShadows(), buildTruck(),
                hookShadow, superstructure, cargoGroup, cameraRig);

        // Antialiasing is deliberately off: multisampling this SubScene cost more
        // than a whole frame budget on modest GPUs, and a stalled frame used to
        // starve the control loop's watchdog.
        SubScene subScene = new SubScene(worldRoot, 1, 1, true, SceneAntialiasing.DISABLED);
        // MUST stay a solid Color. A LinearGradient fill here silently skips the
        // SubScene's per-frame buffer clear, so moving geometry (boom, hook,
        // shadows) smears into swept fans of stale pixels. The sky gradient is
        // therefore real geometry — see buildSkyDome().
        subScene.setFill(SKY_HORIZON);
        subScene.setCamera(camera);
        subScene.widthProperty().bind(container.widthProperty());
        subScene.heightProperty().bind(container.heightProperty());
        installCameraControls(subScene);

        estopBanner = buildEstopBanner();
        container.setStyle("-fx-background-color: #14181d;");
        container.getChildren().addAll(subScene, estopBanner);
        StackPane.setAlignment(estopBanner, Pos.CENTER);
    }

    @Override
    public Node node() {
        return container;
    }

    // ---- public API (frozen for UI integration) ----

    /** Switches viewpoint; the change is interpolated, not cut. */
    public void setCameraMode(CameraMode mode) {
        if (mode != null && mode != cameraMode) {
            cameraMode = mode;
            modeSwitchAgeSeconds = 0;
        }
    }

    public CameraMode cameraMode() {
        return cameraMode;
    }

    /** Selects the load on the hook; a new load starts attached to the hook. */
    public void setCargo(CargoType type) {
        CargoType wanted = type == null ? CargoType.NONE : type;
        if (wanted == cargoType) {
            return;
        }
        cargoType = wanted;
        cargoGroup.getChildren().setAll(buildCargoNode(wanted));
        cargoGroup.setVisible(wanted != CargoType.NONE);
        cargoAttached = true;
    }

    public CargoType cargo() {
        return cargoType;
    }

    // ---- per-frame update ----

    @Override
    public void update(CraneProfile profile, CraneState state) {
        double dt = frameSeconds();

        double slewDeg = state.position("slew");
        double boomDeg = state.position("boom");
        double jibDeg = state.position("jib");
        double extension = Math.max(0, state.position("extension"));
        double ropeOut = Math.max(0, state.position("winch"));
        double swayDeg = state.position("loadSway"); // 0 when the sim omits it

        double boomLength = BOOM_BASE_LENGTH + extension;

        // Articulation. Y points down, so raising the boom is a negative Z rotation.
        slewRotate.setAngle(slewDeg);
        boomRotate.setAngle(-boomDeg);
        extensionScale.setX(Math.max(extension, 0.001));
        extensionBeam.setVisible(extension > 0.01);
        jibTranslate.setX(boomLength);
        jibRotate.setAngle(jibDeg);
        ropePlumbRotate.setAngle(boomDeg - jibDeg);
        swayRotate.setAngle(-swayDeg);

        // Hydraulic rams, in superstructure-local coordinates.
        boomRam.aim(new Point3D(0.18, -0.55, 0), boomPoint(2.2, 0.22, boomDeg));
        jibRam.aim(boomPoint(boomLength - 1.1, -0.24, boomDeg),
                jibPoint(1.2, 0.20, boomDeg, jibDeg, boomLength));

        // Rope length, clamped so the hook stays above the ground like the 2D view.
        Point3D jibTip = jibPoint(JIB_LENGTH, 0, boomDeg, jibDeg, boomLength);
        double jibTipHeight = BED_HEIGHT - jibTip.getY();
        double ropeLength = Math.clamp(ropeOut, 0.0,
                Math.max(0.0, jibTipHeight - HOOK_GROUND_CLEARANCE));
        rope.setHeight(Math.max(ropeLength, 0.001));
        rope.setTranslateY(ropeLength / 2);
        rope.setVisible(ropeLength > 0.02);
        hookBlock.setTranslateY(ropeLength + HOOK_BLOCK_HEIGHT / 2);
        hookTip.setTranslateY(ropeLength + HOOK_BLOCK_HEIGHT + 0.08);

        Point3D hookWorld = hookWorldPosition(jibTip, ropeLength, swayDeg, slewDeg);
        updateHookShadow(hookWorld);
        updateCargo(hookWorld, slewDeg);
        updateCamera(dt, hookWorld, slewDeg);
        bobBoat();

        estopBanner.setVisible(state.estopLatched());
    }

    /** Seconds since the previous update, clamped against pauses and hiccups. */
    private double frameSeconds() {
        long now = System.nanoTime();
        double dt = lastUpdateNanos < 0 ? 1.0 / 60 : (now - lastUpdateNanos) / 1e9;
        lastUpdateNanos = now;
        return Math.clamp(dt, 0.0, 0.1);
    }

    // ---- geometry helpers (all in superstructure-local coordinates) ----

    /**
     * A point on the main boom: {@code along} metres from the boom pivot,
     * {@code perp} metres perpendicular to it (positive = below the boom axis).
     */
    private static Point3D boomPoint(double along, double perp, double boomDeg) {
        double c = Math.cos(Math.toRadians(boomDeg));
        double s = Math.sin(Math.toRadians(boomDeg));
        return new Point3D(along * c + perp * s, -PILLAR_HEIGHT - along * s + perp * c, 0);
    }

    /** A point on the jib, measured from the boom tip along the jib axis. */
    private static Point3D jibPoint(double along, double perp,
                                    double boomDeg, double jibDeg, double boomLength) {
        Point3D tip = boomPoint(boomLength, 0, boomDeg);
        double angle = boomDeg - jibDeg;
        double c = Math.cos(Math.toRadians(angle));
        double s = Math.sin(Math.toRadians(angle));
        return tip.add(along * c + perp * s, -along * s + perp * c, 0);
    }

    /** World position of the hook block, including sway deflection and slew. */
    private static Point3D hookWorldPosition(Point3D jibTip, double ropeLength,
                                             double swayDeg, double slewDeg) {
        double sway = Math.toRadians(swayDeg);
        double localX = jibTip.getX() + ropeLength * Math.sin(sway);
        double localY = jibTip.getY() + ropeLength * Math.cos(sway) + HOOK_BLOCK_HEIGHT;
        double slew = Math.toRadians(slewDeg);
        return new Point3D(localX * Math.cos(slew), localY - BED_HEIGHT, -localX * Math.sin(slew));
    }

    // ---- shadows ----

    private void updateHookShadow(Point3D hookWorld) {
        double height = Math.max(0, -hookWorld.getY());
        double spread = 1.0 + height * 0.06;
        hookShadowTranslate.setX(hookWorld.getX());
        hookShadowTranslate.setZ(hookWorld.getZ());
        hookShadowScale.setX(spread);
        hookShadowScale.setZ(spread);
        hookShadow.setVisible(cargoType == CargoType.NONE || cargoAttached);
    }

    // ---- cargo ----

    /**
     * Cargo state machine: while attached the load hangs below the hook (sway
     * included). Touching the ground detaches it and it stays put; bringing the
     * hook back down to it picks it up again.
     */
    private void updateCargo(Point3D hookWorld, double slewDeg) {
        if (cargoType == CargoType.NONE) {
            return;
        }
        double halfHeight = cargoType.height() / 2;
        if (cargoAttached) {
            cargoX = hookWorld.getX();
            cargoZ = hookWorld.getZ();
            cargoY = hookWorld.getY() + 0.12 + halfHeight;
            if (cargoY + halfHeight >= 0) {      // Y down: >= 0 is at/below ground
                cargoY = -halfHeight;
                cargoAttached = false;
            }
            cargoRotate.setAngle(slewDeg);
        } else {
            Point3D top = new Point3D(cargoX, cargoY - halfHeight, cargoZ);
            if (hookWorld.distance(top) < 0.7) {
                cargoAttached = true;
            }
        }
        cargoTranslate.setX(cargoX);
        cargoTranslate.setY(cargoY);
        cargoTranslate.setZ(cargoZ);
    }

    // ---- camera ----

    /**
     * Drives the six rig parameters toward the active mode's target. All modes
     * share the parametrization, so a mode switch is a smooth interpolation;
     * the blend is slowed for {@value #TRANSITION_SECONDS} s after a switch.
     */
    private void updateCamera(double dt, Point3D hookWorld, double slewDeg) {
        modeSwitchAgeSeconds += dt;

        Point3D centre;
        double azimuth;
        double elevation;
        double distance;

        switch (cameraMode) {
            case CAB -> {
                centre = hookWorld;
                double[] rig = rigFromEye(centre, CAB_EYE);
                azimuth = rig[0];
                elevation = rig[1];
                distance = Math.max(rig[2], 4.0);
            }
            case HOOK -> {
                centre = hookWorld;
                azimuth = -slewDeg;
                elevation = -78;
                distance = 10;
            }
            case FOLLOW -> {
                centre = hookWorld;
                double radius = Math.hypot(hookWorld.getX(), hookWorld.getZ());
                double dirX = radius < 0.5 ? Math.cos(Math.toRadians(slewDeg))
                        : hookWorld.getX() / radius;
                double dirZ = radius < 0.5 ? -Math.sin(Math.toRadians(slewDeg))
                        : hookWorld.getZ() / radius;
                Point3D eye = new Point3D(hookWorld.getX() - dirX * 9,
                        hookWorld.getY() - 4.5, hookWorld.getZ() - dirZ * 9);
                double[] rig = rigFromEye(centre, eye);
                azimuth = rig[0];
                elevation = rig[1];
                distance = rig[2];
            }
            default -> {
                centre = ORBIT_CENTRE;
                azimuth = orbitAzimuth;
                elevation = orbitElevation;
                distance = orbitDistance;
            }
        }

        boolean transitioning = modeSwitchAgeSeconds < TRANSITION_SECONDS;
        double tau = transitioning ? 0.22 : (cameraMode == CameraMode.ORBIT ? 0.04 : 0.16);
        double blend = 1 - Math.exp(-dt / tau);

        camCentre.setX(lerp(camCentre.getX(), centre.getX(), blend));
        camCentre.setY(lerp(camCentre.getY(), centre.getY(), blend));
        camCentre.setZ(lerp(camCentre.getZ(), centre.getZ(), blend));
        camAzimuth.setAngle(lerpAngle(camAzimuth.getAngle(), azimuth, blend));
        camElevation.setAngle(lerp(camElevation.getAngle(), elevation, blend));
        camDistance.setZ(-lerp(-camDistance.getZ(), distance, blend));
    }

    /**
     * Rig parameters {azimuth, elevation, distance} that place the camera at
     * {@code eye} while looking at {@code centre}. Inverse of the rig transform
     * chain {@code Translate(centre) · Rotate(az, Y) · Rotate(el, X) · (0,0,-d)}.
     */
    private static double[] rigFromEye(Point3D centre, Point3D eye) {
        Point3D v = eye.subtract(centre);
        double distance = Math.max(v.magnitude(), 1e-3);
        double elevation = Math.toDegrees(Math.asin(Math.clamp(v.getY() / distance, -1, 1)));
        double azimuth = Math.toDegrees(Math.atan2(-v.getX(), -v.getZ()));
        return new double[]{azimuth, elevation, distance};
    }

    private static double lerp(double from, double to, double blend) {
        return from + (to - from) * blend;
    }

    /** Angle interpolation taking the shortest way round. */
    private static double lerpAngle(double from, double to, double blend) {
        double delta = ((to - from) % 360 + 540) % 360 - 180;
        return from + delta * blend;
    }

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
            if (cameraMode != CameraMode.ORBIT) {
                return; // scripted viewpoints ignore the mouse
            }
            orbitAzimuth -= dx * 0.35;
            orbitElevation = Math.clamp(orbitElevation + dy * 0.25, -85.0, -2.0);
        });
        subScene.setOnScroll(event -> {
            if (cameraMode != CameraMode.ORBIT) {
                return;
            }
            orbitDistance = Math.clamp(orbitDistance * Math.exp(-event.getDeltaY() * 0.002),
                    MIN_DISTANCE, MAX_DISTANCE);
        });
    }

    // ---- static scenery ----

    /**
     * The sky, as geometry rather than a SubScene fill: a large sphere seen from
     * the inside, painted with a procedurally generated vertical gradient and
     * lit by its own self-illumination map so the scene lights never touch it.
     */
    private static Node buildSkyDome() {
        int height = 256;
        WritableImage gradient = new WritableImage(1, height);
        PixelWriter pixels = gradient.getPixelWriter();
        for (int y = 0; y < height; y++) {
            // Sphere texture v runs top → bottom, matching zenith → horizon.
            pixels.setColor(0, y, SKY_ZENITH.interpolate(SKY_HORIZON, (double) y / (height - 1)));
        }

        PhongMaterial material = new PhongMaterial(Color.BLACK);
        material.setSelfIlluminationMap(gradient);

        Sphere dome = new Sphere(SKY_RADIUS);
        dome.setMaterial(material);
        dome.setCullFace(CullFace.FRONT); // we are inside it
        dome.setMouseTransparent(true);
        return dome;
    }

    private static Node buildSun() {
        Sphere sun = new Sphere(3.4);
        PhongMaterial glow = new PhongMaterial(Color.web("#ffe9a8"));
        glow.setSpecularColor(Color.WHITE);
        sun.setMaterial(glow);
        sun.setTranslateX(-42);
        sun.setTranslateY(-58);
        sun.setTranslateZ(-34);
        return sun;
    }

    private static Node buildGround() {
        Group ground = new Group();

        // The quay is the shoreline: the apron stops exactly at DOCK_EDGE_Z and the
        // water takes over from there, so no ground ever covers the harbour.
        double apronDepth = 220;
        Box plane = new Box(260, 0.12, apronDepth);
        plane.setMaterial(material(GROUND));
        plane.setTranslateY(0.06); // top surface exactly at y = 0
        plane.setTranslateZ(DOCK_EDGE_Z - apronDepth / 2);
        ground.getChildren().add(plane);

        PhongMaterial gridMaterial = material(GRID);
        double gridDepth = 44;
        double gridCentreZ = DOCK_EDGE_Z - gridDepth / 2;
        for (int metre = -28; metre <= 28; metre += 4) {   // 4 m grid: half the nodes
            Box alongZ = new Box(0.05, 0.01, gridDepth);
            alongZ.setMaterial(gridMaterial);
            alongZ.setTranslateX(metre);
            alongZ.setTranslateY(-0.01);
            alongZ.setTranslateZ(gridCentreZ);
            ground.getChildren().add(alongZ);

            if (metre <= DOCK_EDGE_Z) {
                Box alongX = new Box(60, 0.01, 0.05);
                alongX.setMaterial(gridMaterial);
                alongX.setTranslateZ(metre);
                alongX.setTranslateY(-0.01);
                ground.getChildren().add(alongX);
            }
        }
        return ground;
    }

    /** Dock edge with the harbour water beyond it, and a moored boat. */
    private Node buildWaterAndDock() {
        Group harbour = new Group();

        Box quay = new Box(90, 0.5, 1.6);
        quay.setMaterial(material(DOCK_COLOR));
        quay.setTranslateY(-0.2);
        quay.setTranslateZ(DOCK_EDGE_Z);

        for (double x = -24; x <= 24; x += 8) {  // bollards
            Cylinder bollard = new Cylinder(0.16, 0.7);
            bollard.setMaterial(material(TRUCK_DARK));
            bollard.setTranslateX(x);
            bollard.setTranslateY(-0.75);
            bollard.setTranslateZ(DOCK_EDGE_Z - 0.55);
            harbour.getChildren().add(bollard);
        }

        // Large enough that its far edge sits beyond the horizon from any camera.
        Box water = new Box(600, 0.06, 400);
        PhongMaterial waterMaterial = new PhongMaterial(Color.web("#1d4f6b", 0.72));
        waterMaterial.setSpecularColor(Color.web("#7fb4cf"));
        water.setMaterial(waterMaterial);
        water.setTranslateY(WATER_LEVEL_Y);
        water.setTranslateZ(DOCK_EDGE_Z + 200);

        MeshView hull = MeshFactory.boatHull(6.5, 2.2, 1.0, material(BOAT_HULL));
        Box cabin = new Box(1.8, 0.9, 1.5);
        cabin.setMaterial(material(Color.web("#e8eaec")));
        cabin.setTranslateX(2.6);
        cabin.setTranslateY(-0.45);
        Group boat = new Group(hull, cabin);
        // Moored alongside the quay, within the crane's reach — the boat this
        // project started with, waiting to be lifted onto the truck.
        boat.getTransforms().addAll(
                new Translate(1.5, WATER_LEVEL_Y - 0.55, DOCK_EDGE_Z + 3.2),
                new Rotate(-96, Rotate.Y_AXIS));
        boat.getTransforms().add(boatBob);

        harbour.getChildren().addAll(quay, water, boat);
        return harbour;
    }

    /** Gentle vertical bob so the moored boat is not dead still. */
    private void bobBoat() {
        double t = System.nanoTime() / 1e9;
        boatBob.setY(Math.sin(t * 0.9) * 0.05);
        boatBob.setZ(Math.sin(t * 0.6) * 0.04);
    }

    /** Blob shadows that never move: the truck body and the crane pillar. */
    private static Node buildStaticShadows() {
        Box truckShadow = new Box(8.2, 0.02, 2.9);
        truckShadow.setMaterial(material(SHADOW));
        truckShadow.setTranslateX(-1.4);
        truckShadow.setTranslateY(-0.03);

        Group pillarShadow = buildBlobShadow(0.9);
        pillarShadow.setTranslateY(-0.035);
        return new Group(truckShadow, pillarShadow);
    }

    private static Group buildBlobShadow(double radius) {
        Cylinder blob = new Cylinder(radius, 0.02);
        blob.setMaterial(material(SHADOW));
        return new Group(blob);
    }

    /** Flatbed truck: chassis, cab with windows, wheels with rims. */
    private static Node buildTruck() {
        PhongMaterial body = material(TRUCK_FILL);
        PhongMaterial dark = material(TRUCK_DARK);
        PhongMaterial glass = material(GLASS);
        PhongMaterial rim = material(RIM);
        Group truck = new Group();

        Box bed = new Box(7.0, 0.55, 2.4);
        bed.setMaterial(body);
        bed.setTranslateX(-0.7);
        bed.setTranslateY(-(BED_HEIGHT - 0.55 / 2));

        Box chassis = new Box(8.4, 0.22, 1.1);   // rail under the bed
        chassis.setMaterial(dark);
        chassis.setTranslateX(-1.2);
        chassis.setTranslateY(-0.72);

        Box cab = new Box(1.5, 1.75, 2.4);
        cab.setMaterial(body);
        cab.setTranslateX(-4.95);
        cab.setTranslateY(-(0.55 + 1.75 / 2));

        Box windscreen = new Box(0.08, 0.72, 2.1);
        windscreen.setMaterial(glass);
        windscreen.setTranslateX(-5.72);
        windscreen.setTranslateY(-1.85);
        Box sideWindowLeft = new Box(1.0, 0.6, 0.08);
        sideWindowLeft.setMaterial(glass);
        sideWindowLeft.setTranslateX(-4.95);
        sideWindowLeft.setTranslateY(-1.85);
        sideWindowLeft.setTranslateZ(-1.21);
        Box sideWindowRight = new Box(1.0, 0.6, 0.08);
        sideWindowRight.setMaterial(glass);
        sideWindowRight.setTranslateX(-4.95);
        sideWindowRight.setTranslateY(-1.85);
        sideWindowRight.setTranslateZ(1.21);

        truck.getChildren().addAll(chassis, bed, cab, windscreen,
                sideWindowLeft, sideWindowRight);

        for (double wheelX : new double[]{-4.9, -3.3, 0.9, 2.1}) {
            for (double wheelZ : new double[]{-1.05, 1.05}) {
                Cylinder tyre = new Cylinder(0.55, 0.4);
                tyre.setMaterial(material(WHEEL));
                tyre.setRotationAxis(Rotate.X_AXIS);
                tyre.setRotate(90);              // axle along Z
                tyre.setTranslateX(wheelX);
                tyre.setTranslateY(-0.55);
                tyre.setTranslateZ(wheelZ);

                Cylinder hub = new Cylinder(0.26, 0.44);
                hub.setMaterial(rim);
                hub.setRotationAxis(Rotate.X_AXIS);
                hub.setRotate(90);
                hub.setTranslateX(wheelX);
                hub.setTranslateY(-0.55);
                hub.setTranslateZ(wheelZ);

                truck.getChildren().addAll(tyre, hub);
            }
        }
        return truck;
    }

    /** Procedural cargo geometry, centred on the group origin. */
    private static Node buildCargoNode(CargoType type) {
        Group group = new Group();
        double halfHeight = type.height() / 2;
        switch (type) {
            case PALLET -> {
                PhongMaterial wood = material(Color.web("#a9763f"));
                Box deck = new Box(type.length(), 0.1, type.width());
                deck.setMaterial(wood);
                deck.setTranslateY(-halfHeight + 0.05);
                group.getChildren().add(deck);
                for (double z : new double[]{-type.width() / 2 + 0.12, 0, type.width() / 2 - 0.12}) {
                    Box bearer = new Box(type.length(), type.height() - 0.1, 0.16);
                    bearer.setMaterial(material(Color.web("#8a5f31")));
                    bearer.setTranslateY(0.05);
                    bearer.setTranslateZ(z);
                    group.getChildren().add(bearer);
                }
            }
            case CONTAINER -> {
                Box shell = new Box(type.length(), type.height(), type.width());
                shell.setMaterial(material(Color.web("#2f7d8c")));
                group.getChildren().add(shell);
                PhongMaterial ridgeMaterial = material(Color.web("#276a77"));
                for (double x = -type.length() / 2 + 0.25; x < type.length() / 2; x += 0.5) {
                    Box ridge = new Box(0.09, type.height() * 0.86, type.width() + 0.04);
                    ridge.setMaterial(ridgeMaterial);
                    ridge.setTranslateX(x);
                    group.getChildren().add(ridge);
                }
            }
            case BOAT -> {
                MeshView hull = MeshFactory.boatHull(
                        type.length(), type.width(), type.height(), material(BOAT_HULL));
                // Hull deck sits at local y = 0; centre it on the group origin.
                hull.setTranslateX(-type.length() / 2);
                hull.setTranslateY(-halfHeight);
                group.getChildren().add(hull);
            }
            default -> {
                // NONE: nothing to draw.
            }
        }
        return group;
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

    private static PhongMaterial material(Color diffuse) {
        PhongMaterial material = new PhongMaterial(diffuse);
        material.setSpecularColor(Color.rgb(70, 70, 70));
        return material;
    }

    /**
     * A two-part hydraulic ram (barrel + polished rod) stretched between two
     * points. The cylinders are modelled along Y and rotated onto the ram axis,
     * so {@link #aim} is the only thing the frame loop touches.
     */
    private static final class HydraulicRam {

        private final Group group = new Group();
        private final Cylinder barrel;
        private final Cylinder rod;
        // Reused every frame — allocating transforms per frame is needless GC
        // pressure on the thread that also has to render.
        private final Translate position = new Translate();
        private final Rotate orientation = new Rotate();

        HydraulicRam(double barrelRadius, double rodRadius,
                     PhongMaterial barrelMaterial, PhongMaterial rodMaterial) {
            barrel = new Cylinder(barrelRadius, 1);
            barrel.setMaterial(barrelMaterial);
            rod = new Cylinder(rodRadius, 1);
            rod.setMaterial(rodMaterial);
            group.getChildren().addAll(rod, barrel);
            group.getTransforms().addAll(position, orientation);
        }

        Node node() {
            return group;
        }

        /** Positions the ram to span {@code from} → {@code to}. */
        void aim(Point3D from, Point3D to) {
            Point3D direction = to.subtract(from);
            double length = direction.magnitude();
            if (length < 1e-4) {
                group.setVisible(false);
                return;
            }
            group.setVisible(true);
            rod.setHeight(length);
            barrel.setHeight(length * 0.55);
            barrel.setTranslateY(-length * 0.225); // barrel end sits at "from"

            Point3D midpoint = from.midpoint(to);
            Point3D axis = new Point3D(0, 1, 0).crossProduct(direction);
            double angle = Math.toDegrees(Math.acos(
                    Math.clamp(direction.normalize().getY(), -1, 1)));
            if (axis.magnitude() < 1e-6) {
                axis = new Point3D(1, 0, 0); // parallel to Y: any perpendicular axis
            }
            position.setX(midpoint.getX());
            position.setY(midpoint.getY());
            position.setZ(midpoint.getZ());
            orientation.setAxis(axis);
            orientation.setAngle(angle);
        }
    }
}
