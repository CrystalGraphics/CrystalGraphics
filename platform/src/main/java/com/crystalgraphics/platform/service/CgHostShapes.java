package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.CgService;

/**
 * The host's boxes and shapes, converted: what {@code CgAABB.of(Object)}, {@code CgAABB.toHost()} and
 * {@code CgShape.of(Object)} reach, so a modder passes the box or block shape Minecraft already gave them. Core reads
 * it through those calls; a consumer rarely names it.
 *
 * <pre>{@code
 * CgHostShapes shapes = CgPlatform.get(CgHostShapes.SERVICE);
 * shapes.box(entity.getBoundingBox(), box);                     // a host box or an entity, into a CgBoxSink
 * int n = shapes.boxes(state.getCollisionShape(level, pos), boxes);   // a block shape's boxes, six doubles each
 * AABB mc = (AABB) shapes.toHost(0, 0, 0, 1, 1, 1);
 *
 * // a host, once, on both sides
 * CgPlatform.provide(CgHostShapes.SERVICE, new HostShapesModern());
 * }</pre>
 *
 * <ul>
 *   <li>{@link #box} takes the host's own box type or an entity; {@link #boxes} a host box, a block shape (modern's
 *       {@code VoxelShape}) or a {@code List} of host boxes, which is what legacy and 1.7.10 blocks hand out.</li>
 *   <li>Anything else throws {@link IllegalArgumentException} naming its class and what this host accepts.</li>
 *   <li>{@link #box} allocates nothing; {@link #toHost} builds one host box.</li>
 *   <li>{@link #NONE} (the harness, tests) throws on every call, saying there is no host.</li>
 * </ul>
 */
public interface CgHostShapes {

    /** No host: every call throws. */
    CgHostShapes NONE = new CgHostShapes() {
        @Override public void box(Object hostBox, CgBoxSink out) { throw none(hostBox); }
        @Override public int boxes(Object hostShape, double[] out) { throw none(hostShape); }
        @Override public Object toHost(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            throw none(null);
        }
        @Override public boolean immutable(Object hostShape) { return false; }

        private IllegalArgumentException none(Object o) {
            return new IllegalArgumentException("no host shapes here (the harness or a test) to convert "
                    + (o == null ? "a box" : o.getClass().getName()));
        }
    };

    CgService<CgHostShapes> SERVICE = CgService.of("crystalgraphics:host_shapes", NONE);

    /** A host box, or an entity's bounding box as it is now, into {@code out}. */
    void box(Object hostBox, CgBoxSink out);

    /**
     * A block shape's boxes, a host box, or a {@code List} of host boxes, into {@code out} as min x, y, z then max x,
     * y, z, six doubles a box, as many as fit. Answers how many there are, which may be more than fit; 0 for an empty
     * shape. A block shape's boxes are within the block, 0 to 1.
     */
    int boxes(Object hostShape, double[] out);

    /** A new host box. */
    Object toHost(double minX, double minY, double minZ, double maxX, double maxY, double maxZ);

    /**
     * Whether {@code hostShape} never changes, so a conversion of it may be kept: a modern block shape or box, a legacy
     * box. A {@code List}, or 1.7.10's mutable box, is not.
     */
    boolean immutable(Object hostShape);
}
