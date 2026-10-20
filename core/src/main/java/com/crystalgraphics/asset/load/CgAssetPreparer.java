package com.crystalgraphics.asset.load;

import java.util.function.BooleanSupplier;

/**
 * Says what a kind's compiled definition needs on the GPU before it is {@link CgAssetState#READY}: its uploads, the
 * pipelines and kernels to warm, its clips to bake, the particles its pools must hold. Run once per asset when it
 * compiles; the steps it lists run on the render thread at the frame boundary, within the frame's budgets.
 *
 * <pre>{@code
 * assets.preparer(CgParticleKinds.SYSTEM, (definition, plan) -> {
 *     SystemDef s = (SystemDef) definition;
 *     for (TextureDef t : s.textures()) plan.upload(t.bytes(), (offset, length) -> t.uploadRange(offset, length));
 *     for (Object key : s.kernelKeys()) plan.warm("kernel " + key, () -> kernels.prepare(key));
 *     for (ShapeDef shape : s.shapes()) plan.peak(shape.key(), shape.peakAlive());
 * });
 * }</pre>
 *
 * <ul>
 *   <li>{@code plan} runs on the asset loop's thread and lists work; it uploads and builds nothing itself.</li>
 *   <li>An upload's pieces are at most the staging chunk and the frame's bytes left, so no single copy passes a
 *       Vulkan staging chunk: the sink maps each byte range onto mip levels or regions.</li>
 *   <li>A warm step is called each frame the warm budget allows until it answers true; it must start work without
 *       waiting, as {@code CgWorldRenderer.prepare} does.</li>
 *   <li>Peaks are summed over a bundle and handed to the {@link PoolSizer} once the bundle compiled: a pool grows at
 *       load, never while a move plays.</li>
 *   <li>A kind with no preparer is READY once it and its hard dependencies are; a dedicated server registers none.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgAssetPreparer {

    /** Lists {@code definition}'s GPU work into {@code plan}. */
    void plan(Object definition, Plan plan);

    /** What an asset needs before it is READY, in the order it runs. */
    interface Plan {
        /** {@code bytes} uploaded through {@code sink}, a piece at a time. */
        Plan upload(long bytes, Upload sink);

        /** A pipeline, kernel or clip started by {@code attempt}, which answers true once built. */
        Plan warm(String what, BooleanSupplier attempt);

        /** {@code alive} particles of shape {@code shapeKey} at this asset's peak. */
        Plan peak(Object shapeKey, int alive);
    }

    /** Uploads one byte range of an upload. Render thread. */
    @FunctionalInterface
    interface Upload {
        void piece(long offset, long length);
    }

    /** Grows a shape's pool to hold {@code alive} particles; called with each bundle's sum, so it keeps the most. */
    @FunctionalInterface
    interface PoolSizer {
        void reserve(Object shapeKey, int alive);
    }
}
