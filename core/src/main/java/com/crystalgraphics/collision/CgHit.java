package com.crystalgraphics.collision;

import com.crystalgraphics.platform.service.CgWorldQuery;

import javax.annotation.Nullable;

/**
 * One thing a query met: an entity or a block, where, and how far from the query. A visitor's hit is the query's own,
 * valid during the call; {@link CgQuery#first} copies into one you hold.
 *
 * <pre>{@code
 * world.overlap(BLAST).at(x, y, z).forEach(h -> damage(h.entity(), 10f * h.falloff(2, 6)));
 *
 * private final CgHit hit = new CgHit();                        // a field, reused
 * if (world.ray().at(eyeX, eyeY, eyeZ).along(lookX, lookY, lookZ, 64).blocksToo().first(hit)) {
 *     if (hit.isBlock()) chip(hit.blockX(), hit.blockY(), hit.blockZ());
 *     else strike(hit.entity());
 * }
 * }</pre>
 *
 * <ul>
 *   <li>The normal points from the query toward the target: the way to knock it back. A surface's own normal, facing
 *       a ray, is its negation.</li>
 *   <li>For an overlap the point is the target's nearest to the query's origin and the distance is to it, 0 when the
 *       origin is inside; for a cast or a ray, where it was first touched and how far the query travelled.</li>
 *   <li>A multipart entity is hit once: {@link #entity()} is its nearest part, {@link #parent()} the whole.</li>
 * </ul>
 */
public final class CgHit {

    /**
     * What a hit met: one switch tells a struck mob from a wall.
     *
     * <pre>{@code
     * switch (hit.kind()) {
     *     case ENTITY -> damage(hit.entity());
     *     case BLOCK -> chip(hit.blockX(), hit.blockY(), hit.blockZ());
     *     case FLUID -> splash(hit.x(), hit.y(), hit.z());
     *     default -> { }                          // UNLOADED, BORDER, POINT: nothing there to act on
     * }
     * }</pre>
     */
    public enum Kind {
        /** An entity, or a part of one; in a {@code CgShapeWorld}, a shape, by its id. */
        ENTITY,
        /** A block. */
        BLOCK,
        /** A fluid's surface, with {@code fluids()} asked. */
        FLUID,
        /** The edge of what is loaded, where a cast or ray stopped. */
        UNLOADED,
        /** The world border, with {@code border()} asked: the block cell beyond it. */
        BORDER,
        /** Nothing, only a point: where an effect reached the point it was aimed at, or burst in open air. */
        POINT
    }

    int entity = -1, parent = -1;
    int blockX, blockY, blockZ;
    boolean block, unloaded, border;
    int fluid;
    double x, y, z;
    double normalX, normalY, normalZ;
    double distance, fraction;
    float exposure = Float.NaN;
    CgHitPart part;

    public Kind kind() {
        if (!block) return entity >= 0 ? Kind.ENTITY : Kind.POINT;
        if (unloaded) return Kind.UNLOADED;
        if (border) return Kind.BORDER;
        return fluid != CgWorldQuery.FLUID_NONE ? Kind.FLUID : Kind.BLOCK;
    }

    /** The entity met, a part of a multipart one included; -1 for a block. */
    public int entity() {
        return entity;
    }

    /** The whole entity: the Ender Dragon for one of its parts, else {@link #entity()}; -1 for a block. */
    public int parent() {
        return parent;
    }

    /** Whether a block was met rather than an entity. */
    public boolean isBlock() {
        return block;
    }

    public int blockX() {
        return blockX;
    }

    public int blockY() {
        return blockY;
    }

    public int blockZ() {
        return blockZ;
    }

    /** Whether a cast or ray stopped at the edge of what is loaded, rather than at a block. */
    public boolean unloaded() {
        return unloaded;
    }

    /** Whether the world border was met, with {@code border()} asked: {@link #blockX()} is the cell beyond it. */
    public boolean border() {
        return border;
    }

    /** The fluid surface met ({@code CgWorldQuery.FLUID_WATER}, {@code FLUID_LAVA}), with {@code fluids()} asked. */
    public int fluid() {
        return fluid;
    }

    /** The point on the target, in the world. */
    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    /** From the query toward the target, unit length. */
    public double normalX() {
        return normalX;
    }

    public double normalY() {
        return normalY;
    }

    public double normalZ() {
        return normalZ;
    }

    /** In blocks: from the query's origin to the point, or how far a cast or ray travelled. */
    public double distance() {
        return distance;
    }

    /** How far along a cast or ray, 0 to 1; 0 for an overlap. */
    public double fraction() {
        return fraction;
    }

    /** How much of the target the query's origin sees, 0 to 1, as Minecraft's explosions sample; NaN unless asked. */
    public float exposure() {
        return exposure;
    }

    /** The part that met it: a hitbox's, or a query's {@link CgQuery#part}; null for neither. */
    public CgHitPart part() {
        return part;
    }

    /**
     * 1 within {@code inner} blocks of the query, 0 from {@code outer} on, falling linearly between: Unreal's radial
     * falloff, for damage that weakens with distance.
     */
    public float falloff(double inner, double outer) {
        if (distance <= inner) return 1f;
        if (distance >= outer) return 0f;
        return (float) (1 - (distance - inner) / (outer - inner));
    }

    void reset() {
        entity = parent = -1;
        blockX = blockY = blockZ = 0;
        block = unloaded = border = false;
        fluid = CgWorldQuery.FLUID_NONE;
        exposure = Float.NaN;
        part = null;
    }

    /**
     * Makes this an entity hit at a point, with no normal or distance: what a hit that crossed the network is read back
     * into. {@code exposure} is NaN where it was not sampled.
     */
    public CgHit setEntity(int entity, int parent, double x, double y, double z, float exposure,
                           @Nullable CgHitPart part) {
        reset();
        this.entity = entity;
        this.parent = parent;
        this.x = x;
        this.y = y;
        this.z = z;
        this.exposure = exposure;
        normalX = normalY = normalZ = distance = fraction = 0;
        this.part = part;
        return this;
    }

    /** As {@link #setEntity}, for a block met at a point. */
    public CgHit setBlock(int blockX, int blockY, int blockZ, double x, double y, double z, @Nullable CgHitPart part) {
        setPoint(x, y, z, part);
        block = true;
        this.blockX = blockX;
        this.blockY = blockY;
        this.blockZ = blockZ;
        return this;
    }

    /** As {@link #setBlock}, for a fluid's surface: {@code fluid} is {@code CgWorldQuery.FLUID_WATER} or {@code FLUID_LAVA}. */
    public CgHit setFluid(int blockX, int blockY, int blockZ, int fluid, double x, double y, double z,
                          @Nullable CgHitPart part) {
        setBlock(blockX, blockY, blockZ, x, y, z, part);
        this.fluid = fluid;
        return this;
    }

    /** As {@link #setBlock}, for the edge of what is loaded. */
    public CgHit setUnloaded(int blockX, int blockY, int blockZ, double x, double y, double z, @Nullable CgHitPart part) {
        setBlock(blockX, blockY, blockZ, x, y, z, part);
        unloaded = true;
        return this;
    }

    /** As {@link #setBlock}, for the world border: the block is the cell beyond it. */
    public CgHit setBorder(int blockX, int blockY, int blockZ, double x, double y, double z, @Nullable CgHitPart part) {
        setBlock(blockX, blockY, blockZ, x, y, z, part);
        border = true;
        return this;
    }

    /**
     * Makes this a hit of nothing at a point: where an effect reached the point it was aimed at, or burst in open air.
     * {@link #entity()} is -1 and {@link #isBlock()} false.
     */
    public CgHit setPoint(double x, double y, double z, @Nullable CgHitPart part) {
        return setEntity(-1, -1, x, y, z, Float.NaN, part);
    }

    /** Copies {@code o} into this: a visitor's hit kept past the call. */
    public CgHit set(CgHit o) {
        entity = o.entity;
        parent = o.parent;
        blockX = o.blockX;
        blockY = o.blockY;
        blockZ = o.blockZ;
        block = o.block;
        unloaded = o.unloaded;
        border = o.border;
        fluid = o.fluid;
        x = o.x;
        y = o.y;
        z = o.z;
        normalX = o.normalX;
        normalY = o.normalY;
        normalZ = o.normalZ;
        distance = o.distance;
        fraction = o.fraction;
        exposure = o.exposure;
        part = o.part;
        return this;
    }

    @Override
    public String toString() {
        String what = block ? (unloaded ? "unloaded " : border ? "border " : fluid != CgWorldQuery.FLUID_NONE ? "fluid "
                : "block ")
                + blockX + "," + blockY + "," + blockZ : "entity " + entity + (parent != entity ? " of " + parent : "");
        return "CgHit[" + what + " at " + distance + ", point " + x + "," + y + "," + z + "]";
    }
}
