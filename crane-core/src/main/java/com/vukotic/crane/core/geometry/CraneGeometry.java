package com.vukotic.crane.core.geometry;

import com.vukotic.crane.core.model.CraneState;

import java.util.ArrayList;
import java.util.List;

/**
 * The physical shape of the machine: where its parts are for a given set of axis
 * positions, and which parts of itself the arm can run into.
 *
 * <p>This is <b>data, not hardcoded logic</b> — a different crane is a different
 * {@code CraneGeometry}. {@link #standardLoaderCrane()} describes the machine the
 * bundled profiles model, and matches the proportions the renderers draw. (The
 * natural next step is to carry it inside {@code CraneProfile} so a JSON profile
 * fully describes its machine; see docs/BACKLOG.md.)
 *
 * <p>Frame and units: see {@link Vec3} — Y up, metres, origin on the ground below
 * the slew axis.
 *
 * @param deckTopY        height of the load bed's top surface
 * @param mastHeight      boom pivot height above the deck
 * @param boomBaseLength  length of the boom's fixed section
 * @param jibLength       length of the jib
 * @param boomRadius      collision radius of the boom/extension
 * @param jibRadius       collision radius of the jib
 * @param bedFrontX       load bed's front edge (nearest the cab)
 * @param bedRearX        load bed's rear edge
 * @param bedHalfWidth    half the bed's width
 * @param cabFrontX       cab's front face
 * @param cabRearX        cab's rear face (facing the mast)
 * @param cabTopY         cab roof height
 */
public record CraneGeometry(
        double deckTopY,
        double mastHeight,
        double boomBaseLength,
        double jibLength,
        double boomRadius,
        double jibRadius,
        double bedFrontX,
        double bedRearX,
        double bedHalfWidth,
        double cabFrontX,
        double cabRearX,
        double cabTopY) {

    /** The truck-mounted loader crane the bundled profiles describe. */
    public static CraneGeometry standardLoaderCrane() {
        return new CraneGeometry(
                1.1, 2.0, 5.0, 3.0,
                0.28, 0.22,
                -0.85, 6.15, 1.2,
                -2.55, -0.95, 2.3);
    }

    /** Boom pivot, at the top of the mast. */
    public Vec3 boomPivot() {
        return new Vec3(0, deckTopY + mastHeight, 0);
    }

    /**
     * Tip of the main boom (including its extension) for the given axis
     * positions, in the crane frame.
     */
    public Vec3 boomTip(double slewDeg, double boomDeg, double extension) {
        double length = boomBaseLength + Math.max(0, extension);
        double boomRad = Math.toRadians(boomDeg);
        Vec3 local = new Vec3(length * Math.cos(boomRad), length * Math.sin(boomRad), 0);
        return boomPivot().plus(local.rotatedAboutY(slewDeg));
    }

    /** Tip of the jib, which knuckles down from the boom tip by {@code jibDeg}. */
    public Vec3 jibTip(double slewDeg, double boomDeg, double jibDeg, double extension) {
        double jibRad = Math.toRadians(boomDeg - jibDeg);
        Vec3 local = new Vec3(jibLength * Math.cos(jibRad), jibLength * Math.sin(jibRad), 0);
        return boomTip(slewDeg, boomDeg, extension).plus(local.rotatedAboutY(slewDeg));
    }

    /** Where the hook hangs, with {@code ropeOut} metres of rope paid out. */
    public Vec3 hook(double slewDeg, double boomDeg, double jibDeg,
                     double extension, double ropeOut) {
        Vec3 tip = jibTip(slewDeg, boomDeg, jibDeg, extension);
        return new Vec3(tip.x(), tip.y() - Math.max(0, ropeOut), tip.z());
    }

    /** Convenience: reads the axes straight out of a published state. */
    public Vec3 jibTip(CraneState state) {
        return jibTip(state.position("slew"), state.position("boom"),
                state.position("jib"), state.position("extension"));
    }

    // ---- the machine's own structure, as obstacles ----

    public Aabb cabBox() {
        return Aabb.of(cabFrontX, deckTopY - 0.55, -bedHalfWidth,
                cabRearX, cabTopY, bedHalfWidth);
    }

    public Aabb deckBox() {
        return Aabb.of(bedFrontX, deckTopY - 0.55, -bedHalfWidth,
                bedRearX, deckTopY, bedHalfWidth);
    }

    /** Everything solid the arm could swing into, excluding the arm itself. */
    public List<Aabb> structureObstacles() {
        List<Aabb> obstacles = new ArrayList<>();
        obstacles.add(cabBox());
        obstacles.add(deckBox());
        return obstacles;
    }

    /** True when the load bed lies under this point (so a load would rest on it). */
    public boolean isOverDeck(double x, double z) {
        return x > bedFrontX && x < bedRearX && Math.abs(z) < bedHalfWidth;
    }

    /** Height of the surface under a point: the deck if over the bed, else the ground. */
    public double supportHeight(double x, double z) {
        return isOverDeck(x, z) ? deckTopY : 0.0;
    }
}
