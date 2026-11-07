package com.crystalgraphics.platform.world;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;

/**
 * The host's boxes and shapes, converted: what {@code CgAABB.of(Object)}, {@code CgAABB.toHost()} and
 * {@code CgShape.of(Object)} reach, so a modder passes the box or block shape Minecraft already gave them. A consumer
 * uses those calls and rarely names this.
 *
 * <pre>{@code
 * CgHostShapes.box(entity.getBoundingBox(), box);                     // a host box or an entity, into a CgBoxSink
 * int n = CgHostShapes.boxes(state.getCollisionShape(level, pos), out);   // a block shape's boxes, six doubles each
 * AABB mc = (AABB) CgHostShapes.toHost(0, 0, 0, 1, 1, 1);
 *
 * // a host, once, on both sides
 * CgPlatform.provide(CgHostShapes.HOST, new HostShapesModern());
 * }</pre>
 *
 * <ul>
 *   <li>{@link #box} takes the host's own box type or an entity; {@link #boxes} a host box, a block shape (modern's
 *       {@code VoxelShape}) or a {@code List} of host boxes, which is what legacy and 1.7.10 blocks hand out.</li>
 *   <li>Anything else throws {@link IllegalArgumentException} naming its class and what this host accepts.</li>
 *   <li>{@link #box} allocates nothing; {@link #toHost} builds one host box.</li>
 *   <li>Without a host (the harness, tests) every conversion throws, saying there is none.</li>
 * </ul>
 */
public final class CgHostShapes {

    /** What a host answers, both sides. One class per era: {@code HostShapesModern}, {@code HostShapesLegacy}, {@code HostShapes1710}. */
    public interface Host {

        /** A host box, or an entity's bounding box as it is now, into {@code out}. */
        void box(Object hostBox, CgBoxSink out);

        /** A block shape's boxes, a host box, or a {@code List} of host boxes, six doubles a box; how many there are. */
        int boxes(Object hostShape, double[] out);

        /** A new host box. */
        Object toHost(double minX, double minY, double minZ, double maxX, double maxY, double maxZ);

        /** Whether {@code hostShape} never changes, so a conversion of it may be kept. */
        boolean immutable(Object hostShape);

        /** No host: every conversion throws. */
        Host NONE = new Host() {
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
    }

    /** The slot a host fills. */
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:host_shapes", Host.NONE);

    private CgHostShapes() {
    }

    /** A host box, or an entity's bounding box as it is now, into {@code out}. */
    public static void box(Object hostBox, CgBoxSink out) {
        host().box(hostBox, out);
    }

    /**
     * A block shape's boxes, a host box, or a {@code List} of host boxes, into {@code out} as min x, y, z then max x,
     * y, z, six doubles a box, as many as fit. Answers how many there are, which may be more than fit; 0 for an empty
     * shape. A block shape's boxes are within the block, 0 to 1.
     */
    public static int boxes(Object hostShape, double[] out) {
        return host().boxes(hostShape, out);
    }

    /** A new host box. */
    public static Object toHost(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return host().toHost(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Whether {@code hostShape} never changes, so a conversion of it may be kept: a modern block shape or box, a legacy
     * box. A {@code List}, or 1.7.10's mutable box, is not.
     */
    public static boolean immutable(Object hostShape) {
        return host().immutable(hostShape);
    }

    private static Host host() {
        return CgPlatform.get(HOST);
    }
}
