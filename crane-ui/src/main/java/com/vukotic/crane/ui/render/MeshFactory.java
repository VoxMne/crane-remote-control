package com.vukotic.crane.ui.render;

import javafx.scene.paint.Material;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;

/**
 * Procedural {@link TriangleMesh} builders — the 3D view ships no asset files,
 * every shape is generated here from a handful of dimensions.
 *
 * <h2>Winding</h2>
 * Every triangle is emitted twice, once in each winding order. JavaFX derives a
 * face normal from vertex order, so the pair guarantees that whichever side the
 * camera looks from is the front face and is lit correctly — no per-face winding
 * bookkeeping, at the cost of doubling triangle counts on meshes this small.
 */
final class MeshFactory {

    /** Fraction of the half-extent cut off at each corner of a beam cross-section. */
    private static final double CHAMFER = 0.32;

    private MeshFactory() {
    }

    /**
     * A straight beam running along +X from {@code x = 0} to {@code x = length},
     * with an octagonal (chamfered rectangular) cross-section that tapers from
     * the root to the tip — the shape of a real telescopic crane section.
     *
     * @param length     beam length along X, metres
     * @param rootHalfH  half-height (Y) at x = 0
     * @param rootHalfW  half-width (Z) at x = 0
     * @param tipHalfH   half-height (Y) at x = length
     * @param tipHalfW   half-width (Z) at x = length
     */
    static MeshView beam(double length, double rootHalfH, double rootHalfW,
                         double tipHalfH, double tipHalfW, Material material) {
        TriangleMesh mesh = new TriangleMesh();
        mesh.getTexCoords().addAll(0, 0);

        addOctagon(mesh, 0, rootHalfH, rootHalfW);
        addOctagon(mesh, length, tipHalfH, tipHalfW);

        // Side quads between the two rings.
        for (int i = 0; i < 8; i++) {
            int next = (i + 1) % 8;
            addQuad(mesh, i, 8 + i, 8 + next, next);
        }
        // End caps as fans around vertex 0 / 8 of each ring.
        for (int i = 1; i < 7; i++) {
            addTriangle(mesh, 0, i, i + 1);
            addTriangle(mesh, 8, 8 + i + 1, 8 + i);
        }
        return view(mesh, material);
    }

    /**
     * A stylised boat hull: pointed bow at +X, flat transom at x = 0, deck at
     * {@code y = 0} and keel at {@code y = +depth} (JavaFX Y points down, so the
     * keel is below the deck).
     */
    static MeshView boatHull(double length, double width, double depth, Material material) {
        TriangleMesh mesh = new TriangleMesh();
        mesh.getTexCoords().addAll(0, 0);

        double halfW = width / 2;
        mesh.getPoints().addAll(
                0f, 0f, (float) -halfW,                        // 0 transom deck left
                0f, 0f, (float) halfW,                         // 1 transom deck right
                (float) (0.55 * length), 0f, (float) -halfW,   // 2 mid deck left
                (float) (0.55 * length), 0f, (float) halfW,    // 3 mid deck right
                (float) length, 0f, 0f,                        // 4 bow point
                0f, (float) depth, (float) (-halfW / 2),       // 5 transom keel left
                0f, (float) depth, (float) (halfW / 2),        // 6 transom keel right
                (float) (0.55 * length), (float) depth, (float) (-halfW / 2.5), // 7 mid keel left
                (float) (0.55 * length), (float) depth, (float) (halfW / 2.5),  // 8 mid keel right
                (float) (0.93 * length), (float) (depth * 0.5), 0f);            // 9 bow keel

        int[][] faces = {
                {0, 1, 3}, {0, 3, 2}, {2, 3, 4},          // deck
                {0, 2, 7}, {0, 7, 5}, {2, 4, 9}, {2, 9, 7}, // port side
                {1, 3, 8}, {1, 8, 6}, {3, 4, 9}, {3, 9, 8}, // starboard side
                {5, 7, 8}, {5, 8, 6}, {7, 9, 8},          // bottom
                {0, 5, 6}, {0, 6, 1}                      // transom
        };
        for (int[] face : faces) {
            addTriangle(mesh, face[0], face[1], face[2]);
        }
        return view(mesh, material);
    }

    /** Eight points of a chamfered rectangle in the YZ plane at the given X. */
    private static void addOctagon(TriangleMesh mesh, double x, double halfH, double halfW) {
        double cy = halfH * CHAMFER;
        double cz = halfW * CHAMFER;
        double[][] ring = {
                {-halfH, -halfW + cz}, {-halfH + cy, -halfW},
                {halfH - cy, -halfW}, {halfH, -halfW + cz},
                {halfH, halfW - cz}, {halfH - cy, halfW},
                {-halfH + cy, halfW}, {-halfH, halfW - cz}
        };
        for (double[] point : ring) {
            mesh.getPoints().addAll((float) x, (float) point[0], (float) point[1]);
        }
    }

    private static void addQuad(TriangleMesh mesh, int a, int b, int c, int d) {
        addTriangle(mesh, a, b, c);
        addTriangle(mesh, a, c, d);
    }

    /** Emits the triangle in both winding orders (see class Javadoc). */
    private static void addTriangle(TriangleMesh mesh, int a, int b, int c) {
        mesh.getFaces().addAll(a, 0, b, 0, c, 0);
        mesh.getFaces().addAll(a, 0, c, 0, b, 0);
    }

    private static MeshView view(TriangleMesh mesh, Material material) {
        MeshView view = new MeshView(mesh);
        view.setMaterial(material);
        return view;
    }
}
