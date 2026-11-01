package com.crystalgraphics.asset.load;

import java.util.function.BooleanSupplier;

import com.crystalgraphics.render.world.CgPipelinePrecache;

/**
 * Says what a kind's compiled definition needs on the GPU before it is {@link CgAssetState#READY}: its uploads, the
 * files to read, the pipelines and kernels to build, its clips to bake. Run once per asset when it compiles; the steps it lists run on
 * the render thread at the frame boundary, within the frame's budgets.
 *
 * <pre>{@code
 * assets.preparer(CgParticleKinds.SYSTEM, (definition, plan) -> {
 *     SystemDef s = (SystemDef) definition;
 *     for (TextureDef t : s.textures()) plan.upload(t.bytes(), (offset, length) -> t.uploadRange(offset, length));
 *     plan.preload(l -> s.meshes().forEach(l::mesh));
 *     plan.pipelines(p -> p.kernels(() -> s.kernels().forEach(CgKernel::precache)));
 * });
 * }</pre>
 *
 * <ul>
 *   <li>{@code plan} runs on the asset loop's thread and lists work; it uploads and builds nothing itself.</li>
 *   <li>An upload's pieces are at most the staging chunk and the frame's bytes left, so no single copy passes a
 *       Vulkan staging chunk: the sink maps each byte range onto mip levels or regions.</li>
 *   <li>A warm step is called each frame the warm budget allows until it answers true; it must start work without
 *       waiting. Pipelines are a {@code pipelines} step, never a warm one: the precache keeps them built under every
 *       setting.</li>
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

    /**
     * This preparer's steps, then {@code next}'s: what {@link CgVfxAssets#preparer} makes of a second preparer of a kind.
     *
     * <pre>{@code
     * CgAssetPreparer sequence = new CgSequenceSoundPreparer(audio).then(new CgPostVolumePreparer());
     * }</pre>
     */
    default CgAssetPreparer then(CgAssetPreparer next) {
        CgAssetPreparer first = this;
        return new CgAssetPreparer() {
            @Override
            public void plan(Object definition, Plan plan) {
                first.plan(definition, plan);
                next.plan(definition, plan);
            }

            @Override
            public void plan(CgAssetRef<?> ref, Object definition, Plan plan) {
                first.plan(ref, definition, plan);
                next.plan(ref, definition, plan);
            }
        };
    }

    /** What an asset needs before it is READY, in the order it runs. */
    interface Plan {
        /** {@code bytes} uploaded through {@code sink}, a piece at a time. */
        Plan upload(long bytes, Upload sink);

        /**
         * Work started by {@code attempt}, called each frame the warm budget allows until it answers true: a clip baked,
         * a pool primed, anything with no step of its own below.
         */
        Plan warm(String what, BooleanSupplier attempt);

        /** Textures decoded and mesh files read on a worker ({@code CgPreload}); the next step waits for them. */
        Plan preload(CgPreload.Collector collector);

        /** Pipelines built through {@code CgPipelinePrecache}, a request started when the step is reached. */
        Plan pipelines(CgPipelinePrecache.Collector collector);
    }

    /** Uploads one byte range of an upload. Render thread. */
    @FunctionalInterface
    interface Upload {
        void piece(long offset, long length);
    }
}
