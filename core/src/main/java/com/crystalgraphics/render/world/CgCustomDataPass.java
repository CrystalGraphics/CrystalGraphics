package com.crystalgraphics.render.world;

import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.draw.CgPipelineForm;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.render.stage.CgFrameKeys;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.trace.CgGpuTrace;
import java.util.IdentityHashMap;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.graph.CgDepthAccess;
import com.crystalgraphics.api.material.CgMaterialInterface;

/**
 * The custom data pass: each draw's custom data and shading model written where it is nearest, beside the host's depth,
 * as Unreal's GBuffer carries them, so a post material can tell our surfaces apart. Published as
 * {@code CgFrameKeys.CUSTOM_DATA} when a post material asks for it.
 *
 * <pre>{@code
 * if (stage.resources().has(CgFrameKeys.CUSTOM_DATA_READ)) customDataPass.recordCustomData(stage, recording, view);
 * }</pre>
 */
final class CgCustomDataPass {

    private final CgWorldRenderer world;
    private final CgWorldDraws draws;
    private final CgMeshDrawCommands commands;

    /** Custom data and the shading model, packed as {@code handed_scene.glsl} reads them: two exact floats. */
    static final CgFormat CUSTOM_DATA_FORMAT = CgFormat.RG32_FLOAT;

    static final int GPU_CUSTOM_DATA = CgGpuTrace.name("world.customData");

    /**
     * Custom data cleared to DefaultLit's shading model (packed x = 256 * model): a pixel the host drew is a lit surface,
     * as every world pixel is in Unreal's GBuffer, so a post material testing for Unlit grades the host's world too.
     */
    static final CgLoad HOST_LIT_CLEAR = CgLoad.clear(256f, 0f, 0f, 0f);

    static final CgLoad DEPTH_CLEAR = CgLoad.CLEAR_DEPTH.andStencil(0);


    /** A custom-data pipeline's state tested against the host's depth and writing none ({@link #recordCustomData}). */
    final IdentityHashMap<CgRenderState, CgRenderState> customDataStates = new IdentityHashMap<>();

    CgGraphTexture customDataTarget;
    /** The depth {@link #customDataTarget} is drawn with: the host's, or its own where the host lends none. */
    private CgGraphTexture customDataDepth;

    CgMaterialInterface depthIn;

    CgCustomDataPass(CgWorldRenderer world) {
        this.world = world;
        this.draws = world.draws;
        this.commands = world.commands;
    }

    /**
     * Unreal's custom data and shading model (GBufferD, GBufferB's model), which our forward passes write nowhere: every
     * opaque draw in view whose material writes them ({@link CgMaterial#writesCustomData}) drawn again through
     * {@link CgPipeline#customDataTarget()} into an RG32F texture beside the host's depth, tested against it and writing
     * none, so only what shows lands. Published as {@link CgFrameKeys#CUSTOM_DATA}; only in a firing asked for it.
     * A host lending no depth (the window's) gets a target with a depth of its own, the host's copied in first.
     */
    void recordCustomData(CgStageFrame stage, CgRecording recording, CgCamera view) {
        int w = Math.max(1, (int) world.targetWidth), h = Math.max(1, (int) world.targetHeight);
        CgFormat depth = stage.host().target().lendsAttachments() ? null : stage.host().depthFormat();
        boolean ownDepth = depth != null;
        if (customDataTarget == null || customDataTarget.getWidth() != w || customDataTarget.getHeight() != h
                || customDataDepth.isCurrent() == ownDepth) {
            customDataDepth = ownDepth
                    ? CgGraphTexture.transientTexture("cg_custom_data_depth", CgTextureDesc.of(w, h, depth))
                    : CgGraphTexture.current();
            customDataTarget = CgGraphTexture.transientTexture("cg_custom_data", CgTextureDesc.of(w, h, CUSTOM_DATA_FORMAT));
        }
        CgRasterPass pass = recording.raster("world.customData").color(customDataTarget, HOST_LIT_CLEAR)
                .constants(stage.constants()).timed(GPU_CUSTOM_DATA);
        pass.depth(customDataDepth, ownDepth ? DEPTH_CLEAR : CgLoad.load(), CgDepthAccess.WRITE);
        CgChunkBuilder chunks = recording.chunks().begin();
        if (ownDepth) {
            if (depthIn == null) depthIn = CgMaterial.load(CgWorldRenderer.DEPTH_IN_SHADER).instance();
            CgPipeline copy = CgMaterialProxy.of(depthIn).pipeline(CgInstanceKind.OBJECT);
            if (copy != null) {
                pass.readsDepth(CgGraphTexture.current());
                chunks.draw(copy, CgMaterialProxy.of(depthIn).bindings(), CgWorldRenderer.FULLSCREEN).sortKey(Long.MIN_VALUE);
                chunks.instance();
            }
        }
        for (int i = 0; i < draws.count; i++) {
            if (draws.queues[i] >= CgRenderQueue.TRANSPARENT_THRESHOLD || !CgMaterialProxy.of(draws.materials[i]).writesCustomData()) continue;
            if (world.classify(i, CgWorldRenderer.OPAQUE, view) == CgWorldDraws.SKIP) continue;
            CgPipeline forward = CgMaterialProxy.of(draws.materials[i]).pipeline(CgInstanceKind.OBJECT);
            CgPipeline data = forward == null ? null : forward.as(CgPipelineForm.CUSTOM_DATA);
            if (data == null) continue;
            CgRenderState own = data.state();
            CgRenderState tested = customDataStates.get(own);
            if (tested == null) {
                customDataStates.put(own, tested = own.withDepth(CgDepthState.TEST_ONLY).withBlend(CgBlendState.DISABLED));
            }
            commands.passDraw(chunks, data.withState(tested), draws.materials[i], recording, i);
        }
        pass.add(chunks.end());
        pass.end();
        stage.resources().put(CgFrameKeys.CUSTOM_DATA, customDataTarget);
    }
}
