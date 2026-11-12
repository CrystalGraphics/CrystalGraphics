package com.crystalgraphics.render.graph;

/**
 * What a raster pass does with the depth it declares ({@link CgRasterPass#depth}): writes it, or only tests against it.
 * Unreal's {@code FExclusiveDepthStencil}.
 *
 * <pre>{@code
 * rec.raster("world.opaque").color(scene, CgLoad.load()).depth(depth, CgLoad.load(), CgDepthAccess.WRITE);
 * rec.raster("world.fog").color(scene, CgLoad.load()).depth(depth, CgLoad.load(), CgDepthAccess.READ)
 *         .readsDepth(depth);   // tested and sampled at once: read-only depth
 * }</pre>
 */
public enum CgDepthAccess {
    /** Tested and written, as its pipelines' depth state says. */
    WRITE,
    /** Tested, never written: a pass may sample the same depth while it draws. */
    READ
}
