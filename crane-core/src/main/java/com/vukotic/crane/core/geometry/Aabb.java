package com.vukotic.crane.core.geometry;

/**
 * An axis-aligned box in the crane frame ({@link Vec3}), used as the collision
 * volume for the solid parts of the machine and for a load standing on the
 * ground or the deck.
 *
 * <p>Axis-aligned is deliberate: the obstacles that matter (cab, deck, a load
 * set down on the deck) are all square to the truck, and it keeps the distance
 * test exact and cheap. The moving arm is modelled as a swept capsule instead,
 * which is what {@link #distanceToSegment} measures against.
 */
public record Aabb(Vec3 min, Vec3 max) {

    public static Aabb of(double minX, double minY, double minZ,
                          double maxX, double maxY, double maxZ) {
        return new Aabb(new Vec3(minX, minY, minZ), new Vec3(maxX, maxY, maxZ));
    }

    /** A box of the given size centred on {@code centre}. */
    public static Aabb centred(Vec3 centre, double sizeX, double sizeY, double sizeZ) {
        return of(centre.x() - sizeX / 2, centre.y() - sizeY / 2, centre.z() - sizeZ / 2,
                centre.x() + sizeX / 2, centre.y() + sizeY / 2, centre.z() + sizeZ / 2);
    }

    /** Grows the box by {@code margin} on every side. */
    public Aabb expanded(double margin) {
        return of(min.x() - margin, min.y() - margin, min.z() - margin,
                max.x() + margin, max.y() + margin, max.z() + margin);
    }

    public boolean contains(Vec3 point) {
        return point.x() >= min.x() && point.x() <= max.x()
                && point.y() >= min.y() && point.y() <= max.y()
                && point.z() >= min.z() && point.z() <= max.z();
    }

    /** Distance from a point to this box; 0 inside it. */
    public double distanceTo(Vec3 point) {
        double dx = Math.max(0, Math.max(min.x() - point.x(), point.x() - max.x()));
        double dy = Math.max(0, Math.max(min.y() - point.y(), point.y() - max.y()));
        double dz = Math.max(0, Math.max(min.z() - point.z(), point.z() - max.z()));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Approximate distance from the segment {@code a→b} to this box, found by
     * sampling along the segment. {@value #SEGMENT_SAMPLES} samples over a boom
     * a few metres long put the sampling error well below the safety margin the
     * collision guard already applies.
     */
    public double distanceToSegment(Vec3 a, Vec3 b) {
        double closest = Double.MAX_VALUE;
        for (int i = 0; i <= SEGMENT_SAMPLES; i++) {
            double t = (double) i / SEGMENT_SAMPLES;
            Vec3 point = a.plus(b.minus(a).scaled(t));
            closest = Math.min(closest, distanceTo(point));
            if (closest == 0) {
                return 0;
            }
        }
        return closest;
    }

    private static final int SEGMENT_SAMPLES = 24;
}
