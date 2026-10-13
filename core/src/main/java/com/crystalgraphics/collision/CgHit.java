package com.crystalgraphics.collision;

import com.crystalgraphics.collision.hitbox.CgHitbox;
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
 *     if (hit.entity() >= 0) strike(hit.entity());
 *     else if (hit.kind() == CgHit.Kind.BLOCK) chip(hit.blockX(), hit.blockY(), hit.blockZ());
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
     *     case HITBOX -> parry(hit.entity());       // a shield its owner holds up
     *     default -> { }                          // UNLOADED, BORDER, POINT: nothing there to act on
     * }
     * }</pre>
     */
    public enum Kind {
        /** An entity, or a part of one. */
        ENTITY,
        /** A {@link CgHitbox#hittable() hittable} hitbox: {@link #entity()} is its owner, {@link #hitbox()} which one. */
        HITBOX,
        /** A block. */
        BLOCK,
        /** A fluid's surface, with the query's {@code fluids()} asked: {@link #fluid()} says which. */
        FLUID,
        /** The edge of what is loaded, where a cast or ray stopped. */
        UNLOADED,
        /** The world border, with the query's {@code border()} asked: the block is the cell beyond it. */
        BORDER,
        /** Nothing, only a point: where an effect reached the point it was aimed at, or burst in open air. */
        POINT;

        /**
         * Whether it is terrain rather than an entity or a point: a block, a fluid, the border or the edge of what is
         * loaded, each naming its cell in {@link #blockX()}.
         */
        public boolean isTerrain() {
            return this == BLOCK || this == FLUID || this == UNLOADED || this == BORDER;
        }
    }

    int entity = -1, parent = -1;
    int blockX, blockY, blockZ;
    Kind kind = Kind.POINT;
    int fluid;
    double x, y, z;
    double normalX, normalY, normalZ;
    double distance, fraction;
    float exposure = Float.NaN;
    CgHitPart part;
    CgHitbox hitbox;
    CgHitPart hitboxPart;

    public Kind kind() {
        return kind;
    }

    /** The entity met, a part of a multipart one included, or the owner of the hitbox met; -1 for a block. */
    public int entity() {
        return entity;
    }

    /** The whole entity: the Ender Dragon for one of its parts, else {@link #entity()}; -1 for a block. */
    public int parent() {
        return parent;
    }

    /** The hittable hitbox met; null for any other kind, and for a hit that crossed the network. */
    public @Nullable CgHitbox hitbox() {
        return hitbox;
    }

    /** Which part of the {@link #hitbox()} was met; null for its one unnamed part, and for any other kind. */
    public @Nullable CgHitPart hitboxPart() {
        return hitboxPart;
    }

    /** The block cell met, for a hit whose kind is {@link Kind#isTerrain()}. */
    public int blockX() {
        return blockX;
    }

    public int blockY() {
        return blockY;
    }

    public int blockZ() {
        return blockZ;
    }

    /** The fluid surface met, {@code CgWorldQuery.FLUID_WATER} or {@code FLUID_LAVA}, for a {@code FLUID} hit. */
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
        kind = Kind.POINT;
        fluid = CgWorldQuery.FLUID_NONE;
        exposure = Float.NaN;
        part = hitboxPart = null;
        hitbox = null;
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
        kind = entity >= 0 ? Kind.ENTITY : Kind.POINT;
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
        kind = Kind.BLOCK;
        this.blockX = blockX;
        this.blockY = blockY;
        this.blockZ = blockZ;
        return this;
    }

    CgHit setFluid(int blockX, int blockY, int blockZ, int fluid, double x, double y, double z,
                   @Nullable CgHitPart part) {
        setBlock(blockX, blockY, blockZ, x, y, z, part);
        kind = Kind.FLUID;
        this.fluid = fluid;
        return this;
    }

    CgHit setUnloaded(int blockX, int blockY, int blockZ, double x, double y, double z, @Nullable CgHitPart part) {
        setBlock(blockX, blockY, blockZ, x, y, z, part);
        kind = Kind.UNLOADED;
        return this;
    }

    CgHit setBorder(int blockX, int blockY, int blockZ, double x, double y, double z, @Nullable CgHitPart part) {
        setBlock(blockX, blockY, blockZ, x, y, z, part);
        kind = Kind.BORDER;
        return this;
    }

    /**
     * Makes this a hit of nothing at a point: where an effect reached the point it was aimed at, or burst in open air.
     * {@link #entity()} is -1 and its kind {@code POINT}.
     */
    public CgHit setPoint(double x, double y, double z, @Nullable CgHitPart part) {
        return setEntity(-1, -1, x, y, z, Float.NaN, part);
    }

    /** A hittable hitbox's hit read back from the network: its owner, without the hitbox. */
    CgHit setHitbox(int owner, double x, double y, double z, @Nullable CgHitPart part) {
        setEntity(owner, owner, x, y, z, Float.NaN, part);
        kind = Kind.HITBOX;
        return this;
    }

    /** Copies {@code o} into this: a visitor's hit kept past the call. */
    public CgHit set(CgHit o) {
        entity = o.entity;
        parent = o.parent;
        blockX = o.blockX;
        blockY = o.blockY;
        blockZ = o.blockZ;
        kind = o.kind;
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
        hitbox = o.hitbox;
        hitboxPart = o.hitboxPart;
        return this;
    }

    @Override
    public String toString() {
        String what = kind.isTerrain() ? blockX + "," + blockY + "," + blockZ
                : kind == Kind.HITBOX ? "of " + entity
                : kind == Kind.ENTITY ? entity + (parent != entity ? " of " + parent : "") : "";
        return "CgHit[" + kind + " " + what + " at " + distance + ", point " + x + "," + y + "," + z + "]";
    }
}
