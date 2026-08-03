package com.vukotic.crane.ui.render;

import javafx.scene.paint.Material;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.shape.VertexFormat;

import java.util.ArrayList;
import java.util.List;

/**
 * Procedural {@link TriangleMesh} builders — the 3D view ships no asset files,
 * every shape is generated here from a handful of dimensions.
 *
 * <h2>Orientation and normals</h2>
 * Each triangle is emitted <b>once</b>, oriented so its geometric normal points
 * away from a reference point inside the solid, and that normal is supplied
 * explicitly ({@link VertexFormat#POINT_NORMAL_TEXCOORD}) instead of being left
 * to JavaFX's vertex averaging. Faces render with {@link CullFace#NONE}, so a
 * surface can never disappear even if a solid's interior point is off.
 *
 * <p>An earlier version emitted every triangle twice, in both winding orders, to
 * dodge the orientation question. That placed two coincident triangles at the
 * same depth: some drivers culled one cleanly, others z-fought them into
 * stippled garbage while the opposed face normals averaged to zero and blew out
 * the lighting. Hence the explicit approach here — flat shading, one triangle
 * per surface.
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
        Builder builder = new Builder(new double[]{length / 2, 0, 0});

        for (double[] point : octagon(0, rootHalfH, rootHalfW)) {
            builder.point(point);
        }
        for (double[] point : octagon(length, tipHalfH, tipHalfW)) {
            builder.point(point);
        }

        for (int i = 0; i < 8; i++) {
            int next = (i + 1) % 8;
            builder.quad(i, 8 + i, 8 + next, next);           // side panels
        }
        for (int i = 1; i < 7; i++) {
            builder.triangle(0, i, i + 1);                     // root cap fan
            builder.triangle(8, 8 + i, 8 + i + 1);             // tip cap fan
        }
        return builder.build(material);
    }

    /**
     * A stylised boat hull: pointed bow at +X, flat transom at x = 0, deck at
     * {@code y = 0} and keel at {@code y = +depth} (JavaFX Y points down, so the
     * keel sits below the deck).
     */
    static MeshView boatHull(double length, double width, double depth, Material material) {
        double halfW = width / 2;
        Builder builder = new Builder(new double[]{0.45 * length, 0.5 * depth, 0});

        builder.point(new double[]{0, 0, -halfW});                       // 0 transom deck left
        builder.point(new double[]{0, 0, halfW});                        // 1 transom deck right
        builder.point(new double[]{0.55 * length, 0, -halfW});           // 2 mid deck left
        builder.point(new double[]{0.55 * length, 0, halfW});            // 3 mid deck right
        builder.point(new double[]{length, 0, 0});                       // 4 bow point
        builder.point(new double[]{0, depth, -halfW / 2});               // 5 transom keel left
        builder.point(new double[]{0, depth, halfW / 2});                // 6 transom keel right
        builder.point(new double[]{0.55 * length, depth, -halfW / 2.5}); // 7 mid keel left
        builder.point(new double[]{0.55 * length, depth, halfW / 2.5});  // 8 mid keel right
        builder.point(new double[]{0.93 * length, 0.5 * depth, 0});      // 9 bow keel

        int[][] faces = {
                {0, 1, 3}, {0, 3, 2}, {2, 3, 4},            // deck
                {0, 2, 7}, {0, 7, 5}, {2, 4, 9}, {2, 9, 7}, // port side
                {1, 3, 8}, {1, 8, 6}, {3, 4, 9}, {3, 9, 8}, // starboard side
                {5, 7, 8}, {5, 8, 6}, {7, 9, 8},            // bottom
                {0, 5, 6}, {0, 6, 1}                        // transom
        };
        for (int[] face : faces) {
            builder.triangle(face[0], face[1], face[2]);
        }
        return builder.build(material);
    }

    /** Eight points of a chamfered rectangle in the YZ plane at the given X. */
    private static double[][] octagon(double x, double halfH, double halfW) {
        double cy = halfH * CHAMFER;
        double cz = halfW * CHAMFER;
        double[][] ring = {
                {-halfH, -halfW + cz}, {-halfH + cy, -halfW},
                {halfH - cy, -halfW}, {halfH, -halfW + cz},
                {halfH, halfW - cz}, {halfH - cy, halfW},
                {-halfH + cy, halfW}, {-halfH, halfW - cz}
        };
        double[][] points = new double[8][];
        for (int i = 0; i < 8; i++) {
            points[i] = new double[]{x, ring[i][0], ring[i][1]};
        }
        return points;
    }

    /**
     * Accumulates points and outward-oriented triangles, then assembles the mesh.
     * {@code interior} is any point inside the solid; it decides which way is out.
     */
    private static final class Builder {

        private final double[] interior;
        private final List<double[]> points = new ArrayList<>();
        private final List<int[]> triangles = new ArrayList<>();
        private final List<double[]> normals = new ArrayList<>();  // one per triangle

        Builder(double[] interior) {
            this.interior = interior;
        }

        void point(double[] xyz) {
            points.add(xyz);
        }

        void quad(int a, int b, int c, int d) {
            triangle(a, b, c);
            triangle(a, c, d);
        }

        /** Adds the triangle, flipping its winding if the normal points inward. */
        void triangle(int a, int b, int c) {
            double[] pa = points.get(a);
            double[] pb = points.get(b);
            double[] pc = points.get(c);
            double[] normal = cross(subtract(pb, pa), subtract(pc, pa));

            double[] centroid = {
                    (pa[0] + pb[0] + pc[0]) / 3,
                    (pa[1] + pb[1] + pc[1]) / 3,
                    (pa[2] + pb[2] + pc[2]) / 3
            };
            if (dot(normal, subtract(centroid, interior)) < 0) {
                int swap = b;
                b = c;
                c = swap;
                normal = new double[]{-normal[0], -normal[1], -normal[2]};
            }

            double magnitude = Math.sqrt(dot(normal, normal));
            if (magnitude < 1e-9) {
                return; // degenerate triangle: nothing to draw
            }
            triangles.add(new int[]{a, b, c});
            normals.add(new double[]{
                    normal[0] / magnitude, normal[1] / magnitude, normal[2] / magnitude});
        }

        MeshView build(Material material) {
            TriangleMesh mesh = new TriangleMesh(VertexFormat.POINT_NORMAL_TEXCOORD);
            mesh.getTexCoords().addAll(0, 0);
            for (double[] p : points) {
                mesh.getPoints().addAll((float) p[0], (float) p[1], (float) p[2]);
            }
            for (double[] n : normals) {
                mesh.getNormals().addAll((float) n[0], (float) n[1], (float) n[2]);
            }
            for (int i = 0; i < triangles.size(); i++) {
                int[] t = triangles.get(i);
                // Per vertex: point, normal, texCoord. Flat shading — the whole
                // face shares face normal i.
                mesh.getFaces().addAll(t[0], i, 0, t[1], i, 0, t[2], i, 0);
            }

            MeshView view = new MeshView(mesh);
            view.setMaterial(material);
            view.setCullFace(CullFace.NONE);
            return view;
        }

        private static double[] subtract(double[] u, double[] v) {
            return new double[]{u[0] - v[0], u[1] - v[1], u[2] - v[2]};
        }

        private static double[] cross(double[] u, double[] v) {
            return new double[]{
                    u[1] * v[2] - u[2] * v[1],
                    u[2] * v[0] - u[0] * v[2],
                    u[0] * v[1] - u[1] * v[0]};
        }

        private static double dot(double[] u, double[] v) {
            return u[0] * v[0] + u[1] * v[1] + u[2] * v[2];
        }
    }
}
