package com.crystalgraphics.platform.service;

/**
 * Somewhere a host writes a box: what lets a host fill the caller's own box with no array between. Core's
 * {@code CgAABB} is the one implementation; the platform cannot name it, since it sits below {@code shape}.
 *
 * <pre>{@code
 * CgAABB box = new CgAABB();                                    // once, and reused
 * CgPlatform.get(CgHostShapes.SERVICE).box(entity, box);        // the host writes into it
 * entities.box(id, partialTick, box);                           // as CgEntityQuery does
 * }</pre>
 */
public interface CgBoxSink {

    /** Takes the box, in absolute world coordinates; answers itself. */
    CgBoxSink set(double minX, double minY, double minZ, double maxX, double maxY, double maxZ);
}
