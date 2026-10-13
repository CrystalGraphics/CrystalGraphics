package com.crystalgraphics.collision.mirror;

import com.crystalgraphics.platform.world.CgWorldQuery;

import java.util.Arrays;

/**
 * What a host says one block is, for the level mirror: its collision boxes, block-local, and its fluid. The caller
 * clears it and reuses it; {@link CgMirrorHost#describe} and {@link CgMirrorHost#resolveAt} fill it.
 *
 * <pre>{@code
 * // a host describing a state whose shape follows from the state alone
 * out.clear();
 * shape.forAllBoxes(out::box);
 * if (!fluid.isEmpty()) out.fluid(CgWorldQuery.FLUID_WATER, fluid.getOwnHeight(), true);
 *
 * // a state whose shape depends on where it stands: the level thread resolves it there
 * out.clear().positional();
 *
 * // resolved where it stands, a block whose shape changes with no write: resolved at every publish
 * out.clear().box(0, 0, 0, 1, progress, 1).dynamic();
 * }</pre>
 *
 * <ul>
 *   <li>Boxes are block-local, 0 to 1, min x, y, z then max x, y, z.</li>
 *   <li>A fluid that {@code followsAbove} stands a full block high under the same fluid, as Minecraft's does.</li>
 * </ul>
 */
public final class CgMirrorShape {

    float[] boxes = new float[6 * 8];
    int count;
    int fluidKind = CgWorldQuery.FLUID_NONE;
    float fluidHeight = Float.NaN;
    boolean followsAbove, positional, dynamic;

    /** Empty: no boxes, no fluid, not positional. */
    public CgMirrorShape clear() {
        count = 0;
        fluidKind = CgWorldQuery.FLUID_NONE;
        fluidHeight = Float.NaN;
        followsAbove = false;
        positional = false;
        dynamic = false;
        return this;
    }

    /** Adds a collision box, block-local. */
    public CgMirrorShape box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if ((count + 1) * 6 > boxes.length) boxes = Arrays.copyOf(boxes, boxes.length * 2);
        int at = count++ * 6;
        boxes[at] = (float) minX;
        boxes[at + 1] = (float) minY;
        boxes[at + 2] = (float) minZ;
        boxes[at + 3] = (float) maxX;
        boxes[at + 4] = (float) maxY;
        boxes[at + 5] = (float) maxZ;
        return this;
    }

    /** Its fluid: a {@code CgWorldQuery.FLUID_} kind, its own height, and whether the same fluid above raises it to 1. */
    public CgMirrorShape fluid(int kind, float height, boolean followsAbove) {
        fluidKind = kind;
        fluidHeight = kind == CgWorldQuery.FLUID_NONE ? Float.NaN : height;
        this.followsAbove = kind != CgWorldQuery.FLUID_NONE && followsAbove;
        return this;
    }

    /** Its shape depends on where it stands, so the level thread resolves it there ({@link CgMirrorHost#resolveAt}). */
    public CgMirrorShape positional() {
        positional = true;
        return this;
    }

    /** Its shape changes with no block write (a moving piston), so it is resolved at every publish; positional too. */
    public CgMirrorShape dynamic() {
        positional = true;
        dynamic = true;
        return this;
    }
}
