package com.crystalgraphics.collision.hit;

import com.crystalgraphics.collision.CgQuery;
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
 *
 * Filled by hand, as an effect reports where it burst or a hit is read back from the network: where, then what.
 *
 * <pre>{@code
 * hit(burst.setPoint(bx, by, bz, PART_BURST));                     // nothing there: a point
 * hit(struck.setPoint(x, y, z, PART_BOLT).setEntity(id, id));       // an entity met at a point
 * hit.setPlace(x, y, z, nx, ny, nz, distance, fraction);            // where alone, as a query writes it
 * }</pre>
 *
 * <ul>
 *   <li>{@link #setPoint} clears what was met and the normal; a {@code set} of what was met keeps the place.</li>
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

    private int entity = -1, parent = -1;
    private int blockX, blockY, blockZ;
    private Kind kind = Kind.POINT;
    private int fluid;
    private double x, y, z;
    private double normalX, normalY, normalZ;
    private double distance, fraction;
    private float exposure = Float.NaN;
    private CgHitPart part;
    private CgHitbox hitbox;
    private CgHitPart hitboxPart;

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

    /** Makes this a hit of nothing, keeping its place: no entity, block or part, and exposure NaN. */
    public CgHit reset() {
        what(Kind.POINT);
        exposure = Float.NaN;
        part = null;
        return this;
    }

    /** Where it was met: the point, the unit normal toward it, the distance and the fraction along the query. */
    public CgHit setPlace(double x, double y, double z, double normalX, double normalY, double normalZ,
                          double distance, double fraction) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.normalX = normalX;
        this.normalY = normalY;
        this.normalZ = normalZ;
        this.distance = distance;
        this.fraction = fraction;
        return this;
    }

    /**
     * Makes this a hit of nothing at a point, with no normal or distance: where an effect reached the point it was
     * aimed at, or burst in open air. Chain a {@code set} of what was there to make it an entity or a block hit.
     */
    public CgHit setPoint(double x, double y, double z, @Nullable CgHitPart part) {
        reset();
        this.part = part;
        return setPlace(x, y, z, 0, 0, 0, 0, 0);
    }

    /** What was met is an entity, a part of {@code parent} or {@code parent} itself; keeps the place. */
    public CgHit setEntity(int entity, int parent) {
        what(entity >= 0 ? Kind.ENTITY : Kind.POINT);
        this.entity = entity;
        this.parent = parent;
        return this;
    }

    /** What was met is a hittable hitbox standing for {@code owner}; null for one read back from the network. */
    public CgHit setHitbox(int owner, @Nullable CgHitbox hitbox, @Nullable CgHitPart hitboxPart) {
        what(Kind.HITBOX);
        entity = parent = owner;
        this.hitbox = hitbox;
        this.hitboxPart = hitboxPart;
        return this;
    }

    /** What was met is a block; keeps the place. */
    public CgHit setBlock(int blockX, int blockY, int blockZ) {
        return terrain(Kind.BLOCK, blockX, blockY, blockZ);
    }

    /** What was met is a fluid's surface, {@code CgWorldQuery.FLUID_WATER} or {@code FLUID_LAVA}, in a block. */
    public CgHit setFluid(int blockX, int blockY, int blockZ, int fluid) {
        terrain(Kind.FLUID, blockX, blockY, blockZ);
        this.fluid = fluid;
        return this;
    }

    /** What was met is the edge of what is loaded, at a block. */
    public CgHit setUnloaded(int blockX, int blockY, int blockZ) {
        return terrain(Kind.UNLOADED, blockX, blockY, blockZ);
    }

    /** What was met is the world border; the block is the cell beyond it. */
    public CgHit setBorder(int blockX, int blockY, int blockZ) {
        return terrain(Kind.BORDER, blockX, blockY, blockZ);
    }

    public CgHit setExposure(float exposure) {
        this.exposure = exposure;
        return this;
    }

    public CgHit setPart(@Nullable CgHitPart part) {
        this.part = part;
        return this;
    }

    private CgHit terrain(Kind kind, int blockX, int blockY, int blockZ) {
        what(kind);
        this.blockX = blockX;
        this.blockY = blockY;
        this.blockZ = blockZ;
        return this;
    }

    private void what(Kind kind) {
        this.kind = kind;
        entity = parent = -1;
        blockX = blockY = blockZ = 0;
        fluid = CgWorldQuery.FLUID_NONE;
        hitbox = null;
        hitboxPart = null;
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
