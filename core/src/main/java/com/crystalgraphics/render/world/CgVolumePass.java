package com.crystalgraphics.render.world;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.draw.CgPipelineForm;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgRehearsal;
import com.crystalgraphics.render.graph.CgTextureDesc;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.trace.CgGpuTrace;
import javax.annotation.Nullable;

/**
 * Volumes: half-size premultiplied draws marched into a target of their own, blended with their last frame per firing
 * ordinal, and laid over the target in the transparent pass at the farthest one's key.
 *
 * <pre>{@code
 * volumeOut = volumeDrawn > 0 ? volumePass.recordVolumes(stage, recording, view) : null;
 * }</pre>
 */
final class CgVolumePass {

    private final CgWorldRenderer world;
    private final CgWorldDraws draws;
    private final CgMeshDrawCommands commands;

    static final int GPU_VOLUME = CgGpuTrace.name("world.volume"), GPU_VOLUME_RESOLVE = CgGpuTrace.name("world.volumeResolve");

    static final String RESOLVE_KERNELS = "crystalgraphics:shaders/env/compute/volume_resolve.compute";

    static final String COMPOSITE_SHADER = "crystalgraphics:shaders/world_volume_composite.shader";

    /** Half-size volumes: premultiplied light, and the eye depth each was marched to (0 for none). */
    static final CgFrameBufferFormat VOLUME_FORMAT = CgFrameBufferFormat.builder("cg_world_volume")
            .color(0, CgTextureType.RGBA16F).color(1, CgTextureType.R16F).build();

    /** Firings of a host frame with a volume history of their own; a later one composites this frame's alone. */
    static final int VOLUME_HISTORIES = 4;

    static final CgFrameBufferFormat VOLUME_HISTORY_FORMAT = CgFrameBufferFormat.builder("cg_world_volume_history")
            .color(0, CgTextureType.RGBA16F).build();

    CgMaterial composite;

    CgTexture compositeBound;

    /** {@code volume_resolve.compute}'s {@code Resolve}: made at the first warm-up or resolve. */
    @Nullable
    CgKernel resolve;

    /** One a firing ordinal, and the last a rehearsal's, so its passes never advance the real frame's. */
    final CgGraphTexture[] volumeHistories = new CgGraphTexture[VOLUME_HISTORIES + 1];

    /** Per ordinal: the ring frame its history was made in, which holds nothing of a frame before. */
    final long[] volumeMade = new long[VOLUME_HISTORIES + 1];

    // Volumes: half-size premultiplied draws, blended with their last frame per firing ordinal, then laid over the
    // target in the transparent pass at the farthest one's key.
    CgGraphTexture volumeTarget;

    CgVolumePass(CgWorldRenderer world) {
        this.world = world;
        this.draws = world.draws;
        this.commands = world.commands;
    }

    /**
     * The volumes ({@link CgWorldDraws#VOLUME}) into a half-size target, cleared: their light, premultiplied, and the eye depth
     * each marched to ({@code CG_VOLUME_DEPTH}). Then resolved into this firing ordinal's history by a kernel: last
     * frame's, reprojected through {@code cg_PrevViewProj} and held to this frame's neighbourhood, blended with this one. Answers
     * what the transparent pass lays over the target: the history, or this frame's alone past the last ordinal kept.
     */
    @Nullable
    CgTexture recordVolumes(CgStageFrame stage, CgRecording recording, CgHostView view) {
        int w = Math.max(1, (int) (world.targetWidth * 0.5f)), h = Math.max(1, (int) (world.targetHeight * 0.5f));
        if (volumeTarget == null || volumeTarget.getWidth() != w || volumeTarget.getHeight() != h) {
            volumeTarget = CgGraphTexture.transientTexture("cg_world_volume", new CgTextureDesc(w, h, VOLUME_FORMAT));
        }
        stage.constants().write(world.constantsBlock, 0);
        world.halfConstants.read(world.constantsBlock, 0).resolution(w, h);
        CgRasterPass pass = recording.raster(volumeTarget, CgWorldRenderer.CLEAR, world.halfConstants, CgWorldRenderer.TRANSPARENT_STATE,
                        CgOrder.SORTED).sceneDepth(CgBindingPoints.DEPTH_TEXTURE_UNIT, stage.target())
                .texture(CgBindingPoints.LIGHTMAP_TEXTURE_UNIT, stage.host().textures().lightmapTexture()).timed(GPU_VOLUME);
        CgChunkBuilder chunks = recording.chunks().begin();
        for (int i = 0; i < draws.count; i++) {
            if (draws.phase[i] != CgWorldDraws.VOLUME) continue;
            for (CgMaterial link = draws.materials[i]; link != null; link = link.getNextPass()) {
                CgPipeline pipeline = link.pipeline(CgInstanceKind.OBJECT);
                if (pipeline == null) continue;
                CgPipeline folded = world.volumeFolds && link.hasEmissivePass() ? pipeline.as(CgPipelineForm.FOLD) : null;
                CgPipeline marching = (folded != null ? folded : pipeline).as(CgPipelineForm.VOLUME);
                if (marching == null || commands.unready(marching, i)) continue;
                if (draws.sets[i] != null) {
                    commands.drawSet(chunks, marching, commands.bindingOf(link, recording), i);
                    continue;
                }
                chunks.draw(marching, commands.bindingOf(link, recording), draws.meshes[i]).sortKey(draws.keys[i]);
                commands.group(chunks, i);
                commands.writeInstance(chunks, i);
            }
        }
        pass.add(chunks.end());
        pass.end();

        int ordinal = CgRehearsal.active() ? VOLUME_HISTORIES : stage.ordinal();
        if (ordinal > VOLUME_HISTORIES || ordinal == VOLUME_HISTORIES && !CgRehearsal.active()) {
            return volumeTarget.attachment(0);
        }
        CgGraphTexture history = volumeHistories[ordinal];
        if (history == null || history.getWidth() != w || history.getHeight() != h) {
            if (history != null) recording.release(history);
            history = CgGraphTexture.history("cg_world_volume_history" + ordinal,
                    new CgTextureDesc(w, h, VOLUME_HISTORY_FORMAT));
            volumeHistories[ordinal] = history;
            volumeMade[ordinal] = CgFrameRing.frame();
        }
        if (!resolveKernel().isReady()) return volumeTarget.attachment(0);
        boolean fresh = volumeMade[ordinal] == CgFrameRing.frame();
        CgComputePass resolving = recording.compute("world.volumeResolve", world.halfConstants).sceneDepth(stage.target())
                .timed(GPU_VOLUME_RESOLVE);
        resolving.dispatch(resolve, w, h, 1).image("RESOLVED", history).texture("_Now", volumeTarget.attachment(0))
                .texture("_NowDepth", volumeTarget.attachment(1)).texture("_Last", history.previous())
                .set("_Fresh", fresh ? 1f : 0f);
        resolving.end();
        return history;
    }

    /** {@link #resolve}, made and its build started. */
    CgKernel resolveKernel() {
        if (resolve == null) resolve = CgCompute.load(RESOLVE_KERNELS).kernel("Resolve").precache();
        return resolve;
    }

    /** The volumes {@link #recordVolumes} resolved, laid over the target first in the transparent pass. */
    void addComposite(CgChunkBuilder chunks, CgRecording recording) {
        if (composite == null) composite = CgMaterial.newInstance(COMPOSITE_SHADER);
        if (compositeBound != world.volumeOut) {
            CgTexture volumes = world.volumeOut;
            composite.applyProperties(b -> b.sampler("_Volumes", 0, volumes));
            compositeBound = volumes;
        }
        CgPipeline pipeline = composite.pipeline(CgInstanceKind.OBJECT);
        if (pipeline == null) return;
        // First of the pass: every surface after it is dimmed by the smoke in front of it (CgWorldMedia), so one
        // behind the smoke must not have been covered by it too.
        chunks.draw(pipeline, composite.captureBindings(recording.bindings()), CgWorldRenderer.FULLSCREEN).sortKey(Long.MIN_VALUE);
        chunks.instance();
    }
}
