package com.crystalgraphics.asset.load;

import java.util.function.BooleanSupplier;

/**
 * Says what a kind's compiled definition needs on the GPU before it is {@link CgAssetState#READY}: its uploads, the
 * pipelines and kernels to warm, its clips to bake. Run once per asset when it compiles; the steps it lists run on
 * the render thread at the frame boundary, within the frame's budgets.
 *
 * <pre>{@code
 * assets.preparer(CgParticleKinds.SYSTEM, (definition, plan) -> {
 *     SystemDef s = (SystemDef) definition;
 *     for (TextureDef t : s.textures()) plan.upload(t.bytes(), (offset, length) -> t.uploadRange(offset, length));
 *     for (Object key : s.kernelKeys()) plan.warm("kernel " + key, () -> kernels.prepare(key));
 * });
 * }</pre>
 *
 * <ul>
 *   <li>{@code plan} runs on the asset loop's thread and lists work; it uploads and builds nothing itself.</li>
 *   <li>An upload's pieces are at most the staging chunk and the frame's bytes left, so no single copy passes a
 *       Vulkan staging chunk: the sink maps each byte range onto mip levels or regions.</li>
 *   <li>A warm step is called each frame the warm budget allows until it answers true; it must start work without
 *       waiting, as {@code CgWorldRenderer.prepare} does.</li>
 *   <li>A kind with no preparer is READY once it and its hard dependencies are; a dedicated server registers none.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgAssetPreparer {

    /** Lists {@code definition}'s GPU work into {@code plan}. */
    void plan(Object definition, Plan plan);

    /**
     * As {@link #plan(Object, Plan)}, knowing the ref {@code definition} was loaded through: for work kept per ref, as
     * a system's instance pool is. The engine calls this one; implement it where the ref matters.
     *
     * <pre>{@code
     * public void plan(CgAssetRef<?> ref, Object definition, Plan plan) {
     *     plan(definition, plan);
     *     plan.warm("pool of " + ref.id(), () -> { vfx.pool(systemRef(ref)).prime(1); return true; });
     * }
     * }</pre>
     */
    default void plan(CgAssetRef<?> ref, Object definition, Plan plan) {
        plan(definition, plan);
    }

    /** What an asset needs before it is READY, in the order it runs. */
    interface Plan {
        /** {@code bytes} uploaded through {@code sink}, a piece at a time. */
        Plan upload(long bytes, Upload sink);

        /** A pipeline, kernel or clip started by {@code attempt}, which answers true once built. */
        Plan warm(String what, BooleanSupplier attempt);
    }

    /** Uploads one byte range of an upload. Render thread. */
    @FunctionalInterface
    interface Upload {
        void piece(long offset, long length);
    }
}
