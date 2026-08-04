package com.vukotic.crane.core.geometry;

/**
 * A point or direction in the crane's physical frame, in metres.
 *
 * <p>Convention for everything in this package: <b>Y is up</b>, the origin sits
 * on the ground under the slew axis, +X points along the boom at slew 0 (out
 * over the load bed) and +Z is to the right of it. The renderers convert into
 * their own conventions — JavaFX, for instance, points Y down.
 */
public record Vec3(double x, double y, double z) {

    public static final Vec3 ZERO = new Vec3(0, 0, 0);

    public Vec3 plus(Vec3 other) {
        return new Vec3(x + other.x, y + other.y, z + other.z);
    }

    public Vec3 minus(Vec3 other) {
        return new Vec3(x - other.x, y - other.y, z - other.z);
    }

    public Vec3 scaled(double factor) {
        return new Vec3(x * factor, y * factor, z * factor);
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public double distanceTo(Vec3 other) {
        return minus(other).length();
    }

    /** Rotates about the vertical axis by {@code degrees}, positive = toward +Z. */
    public Vec3 rotatedAboutY(double degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Vec3(x * cos - z * sin, y, x * sin + z * cos);
    }
}
