package com.vukotic.crane.viewer;

import com.jme3.app.SimpleApplication;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.post.FilterPostProcessor;
import com.jme3.post.filters.FXAAFilter;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Cylinder;
import com.jme3.shadow.DirectionalLightShadowFilter;
import com.jme3.shadow.EdgeFilteringMode;
import com.jme3.system.AppSettings;
import com.vukotic.crane.core.model.CraneState;

/**
 * The jMonkeyEngine visualiser, running as its own process.
 *
 * <p>Why a separate process rather than a second {@code CraneSceneView} inside the
 * cockpit:
 * <ul>
 *   <li><b>It cannot touch the control path.</b> This module does not depend on
 *       crane-sim, crane-driver-serial or the control loop, and {@link StateFeed}
 *       only reads. A renderer that hangs, leaks or crashes takes nothing with it.
 *       The in-process JavaFX view needed a UI-heartbeat interlock precisely
 *       because a stalled render thread shared a JVM with the commands.</li>
 *   <li><b>jME wants the main thread and its own GL context.</b> Embedding it in a
 *       JavaFX window means either an offscreen render-to-image bridge (a frame
 *       copy every frame) or an AWT canvas with its own focus quirks. Neither is
 *       free, and both put a native GL driver inside the cockpit's process.</li>
 *   <li>It is also how a customer-supplied model would be shipped later: swap the
 *       viewer, keep the cockpit.</li>
 * </ul>
 *
 * <p>What jME buys over the JavaFX view: real directional shadow mapping, a
 * post-processing chain, and glTF/OBJ model loading instead of geometry written in
 * Java. This class is the working skeleton of that — articulation driven by live
 * state, shadows on, FXAA on — not the finished scene.
 */
public final class ViewerMain extends SimpleApplication {

    private static final float BED_HEIGHT = 1.1f;
    private static final float MAST_HEIGHT = 2.0f;
    private static final float BOOM_LENGTH = 5.0f;
    private static final float JIB_LENGTH = 3.0f;

    private final StateFeed feed;

    private Node slewNode;
    private Node boomNode;
    private Node jibNode;
    private Geometry ropeGeometry;

    private ViewerMain(StateFeed feed) {
        this.feed = feed;
    }

    public static void main(String[] args) {
        int port = StateFeed.DEFAULT_PORT;
        for (String arg : args) {
            if (arg.startsWith("--port=")) {
                port = Integer.parseInt(arg.substring("--port=".length()));
            }
        }
        StateFeed feed = new StateFeed(port);
        feed.start();

        ViewerMain app = new ViewerMain(feed);
        AppSettings settings = new AppSettings(true);
        settings.setTitle("Crane Remote Control — Viewer");
        settings.setResolution(1280, 800);
        settings.setSamples(4);
        settings.setVSync(true);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.setPauseOnLostFocus(false);
        app.start();
    }

    @Override
    public void simpleInitApp() {
        viewPort.setBackgroundColor(new ColorRGBA(0.42f, 0.62f, 0.78f, 1f));
        flyCam.setMoveSpeed(12f);
        cam.setLocation(new Vector3f(12f, 8f, 12f));
        cam.lookAt(new Vector3f(2f, 2f, 0f), Vector3f.UNIT_Y);

        // A sun that actually casts. This is the thing JavaFX 3D cannot do at all,
        // and the single biggest reason the old view reads as a diagram: without
        // contact shadows every object looks pasted on rather than standing on
        // something.
        DirectionalLight sun = new DirectionalLight();
        sun.setDirection(new Vector3f(-0.6f, -0.9f, -0.4f).normalizeLocal());
        sun.setColor(ColorRGBA.White.mult(1.1f));
        rootNode.addLight(sun);

        AmbientLight ambient = new AmbientLight();
        ambient.setColor(new ColorRGBA(0.32f, 0.36f, 0.42f, 1f));
        rootNode.addLight(ambient);

        FilterPostProcessor post = new FilterPostProcessor(assetManager);
        DirectionalLightShadowFilter shadows =
                new DirectionalLightShadowFilter(assetManager, 2048, 3);
        shadows.setLight(sun);
        shadows.setEdgeFilteringMode(EdgeFilteringMode.PCFPOISSON);
        shadows.setShadowIntensity(0.55f);
        post.addFilter(shadows);
        post.addFilter(new FXAAFilter());
        viewPort.addProcessor(post);

        rootNode.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);

        buildGround();
        buildTruck();
        buildCrane();
    }

    private Material lit(ColorRGBA colour, float shininess) {
        Material material = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");
        material.setBoolean("UseMaterialColors", true);
        material.setColor("Diffuse", colour);
        material.setColor("Ambient", colour.mult(0.4f));
        material.setColor("Specular", ColorRGBA.White.mult(0.25f));
        material.setFloat("Shininess", shininess);
        material.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Back);
        return material;
    }

    private Geometry box(String name, float x, float y, float z, ColorRGBA colour) {
        Geometry geometry = new Geometry(name, new Box(x / 2, y / 2, z / 2));
        geometry.setMaterial(lit(colour, 24f));
        return geometry;
    }

    private void buildGround() {
        Geometry ground = box("ground", 80f, 0.2f, 80f, new ColorRGBA(0.30f, 0.33f, 0.37f, 1f));
        ground.setLocalTranslation(0f, -0.1f, 0f);
        ground.setShadowMode(RenderQueue.ShadowMode.Receive);
        rootNode.attachChild(ground);
    }

    private void buildTruck() {
        Node truck = new Node("truck");

        Geometry deck = box("deck", 7.0f, 0.5f, 2.4f, new ColorRGBA(0.29f, 0.25f, 0.21f, 1f));
        deck.setLocalTranslation(2.65f, BED_HEIGHT - 0.25f, 0f);
        truck.attachChild(deck);

        Geometry cab = box("cab", 1.6f, 1.75f, 2.4f, new ColorRGBA(0.22f, 0.26f, 0.31f, 1f));
        cab.setLocalTranslation(-1.75f, 0.55f + 0.875f, 0f);
        truck.attachChild(cab);

        Geometry headboard = box("headboard", 0.12f, 0.9f, 2.4f,
                new ColorRGBA(0.15f, 0.18f, 0.21f, 1f));
        headboard.setLocalTranslation(6.15f, BED_HEIGHT + 0.45f, 0f);
        truck.attachChild(headboard);

        rootNode.attachChild(truck);
    }

    private void buildCrane() {
        ColorRGBA amber = new ColorRGBA(0.91f, 0.72f, 0.09f, 1f);

        slewNode = new Node("slew");
        slewNode.setLocalTranslation(0f, BED_HEIGHT, 0f);
        rootNode.attachChild(slewNode);

        Geometry mast = new Geometry("mast", new Cylinder(12, 24, 0.26f, MAST_HEIGHT, true));
        mast.setMaterial(lit(amber, 32f));
        mast.rotate(FastMath.HALF_PI, 0f, 0f);
        mast.setLocalTranslation(0f, MAST_HEIGHT / 2, 0f);
        slewNode.attachChild(mast);

        boomNode = new Node("boom");
        boomNode.setLocalTranslation(0f, MAST_HEIGHT, 0f);
        slewNode.attachChild(boomNode);

        Geometry boom = box("boomBeam", BOOM_LENGTH, 0.36f, 0.32f, amber);
        boom.setLocalTranslation(BOOM_LENGTH / 2, 0f, 0f);
        boomNode.attachChild(boom);

        jibNode = new Node("jib");
        jibNode.setLocalTranslation(BOOM_LENGTH, 0f, 0f);
        boomNode.attachChild(jibNode);

        Geometry jib = box("jibBeam", JIB_LENGTH, 0.26f, 0.22f, amber);
        jib.setLocalTranslation(JIB_LENGTH / 2, 0f, 0f);
        jibNode.attachChild(jib);

        ropeGeometry = box("rope", 0.05f, 1f, 0.05f, new ColorRGBA(0.75f, 0.77f, 0.80f, 1f));
        jibNode.attachChild(ropeGeometry);
    }

    @Override
    public void simpleUpdate(float tpf) {
        CraneState state = feed.latest();
        if (state == null) {
            return;   // nothing published yet; hold the parked pose
        }

        float slew = (float) state.position("slew");
        float boom = (float) state.position("boom");
        float jib = (float) state.position("jib");
        float rope = (float) Math.max(0, state.position("winch"));

        slewNode.setLocalRotation(new Quaternion().fromAngles(
                0f, -slew * FastMath.DEG_TO_RAD, 0f));
        boomNode.setLocalRotation(new Quaternion().fromAngles(
                0f, 0f, boom * FastMath.DEG_TO_RAD));
        jibNode.setLocalRotation(new Quaternion().fromAngles(
                0f, 0f, -jib * FastMath.DEG_TO_RAD));

        // Rope hangs plumb: undo the accumulated boom+jib rotation, then drop.
        float length = Math.max(rope, 0.01f);
        ropeGeometry.setLocalScale(1f, length, 1f);
        ropeGeometry.setLocalRotation(new Quaternion().fromAngles(
                0f, 0f, (jib - boom) * FastMath.DEG_TO_RAD));
        ropeGeometry.setLocalTranslation(JIB_LENGTH, -length / 2, 0f);
    }

    @Override
    public void destroy() {
        feed.close();
        super.destroy();
    }
}
