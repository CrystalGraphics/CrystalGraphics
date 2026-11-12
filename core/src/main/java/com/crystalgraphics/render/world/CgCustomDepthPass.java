package com.crystalgraphics.render.world;

import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.state.CgStencilState;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.render.stage.CgFrameKeys;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.trace.CgGpuTrace;
import java.util.Arrays;
import java.util.IdentityHashMap;
import com.crystalgraphics.platform.rhi.format.CgFormat;

/**
 * Unreal's CustomDepth pass ({@code EMeshPass::CustomDepth}): every draw marked {@code customDepth} drawn depth-only into
 * a depth-stencil target of its own, writing its stencil value. Published as {@code CgFrameKeys.CUSTOM_DEPTH}; post
 * materials read its stencil through {@code CgGraphTexture.stencil()}, Unreal's CustomStencil SRV.
 *
 * <pre>{@code
 * if (which == TRANSPARENT) customDepthPass.recordCustomDepth(stage, recording, view);
 * }</pre>
 */
final class CgCustomDepthPass {

    private final CgWorldRenderer world;
    private final CgWorldDraws draws;
    private final CgMeshDrawCommands commands;

    /** A state's CustomDepth states, found by a scan: a state has a few. */
    static final class StencilVariants {
        int[] keys = new int[4];
        CgRenderState[] states = new CgRenderState[4];
        int n;
    }

    /** CustomDepth and CustomStencil (CgFrameKeys.CUSTOM_DEPTH): Unreal's PF_DepthStencil, cleared to far and 0. */
    static final CgFormat CUSTOM_DEPTH_FORMAT = CgFormat.DEPTH32_FLOAT_STENCIL8;

    static final CgLoad CUSTOM_DEPTH_CLEAR = CgLoad.clear(0f, 0f, 0f, 0f).andDepth(1.0).andStencil(0);

    static final int GPU_CUSTOM_DEPTH = CgGpuTrace.name("world.customDepth");

    /** A depth pipeline's state's CustomDepth variants, by stencil value and write mask ({@link CgWorldDraws#customDepths}). */
    final IdentityHashMap<CgRenderState, StencilVariants> customDepthStates = new IdentityHashMap<>();

    boolean[] inCustomDepth = new boolean[64];

    CgGraphTexture customDepthTarget;

    CgCustomDepthPass(CgWorldRenderer world) {
        this.world = world;
        this.draws = world.draws;
        this.commands = world.commands;
    }

    /**
     * Unreal's CustomDepth pass (CustomDepthRendering.cpp:216-326): every draw in view marked {@link Draw#customDepth}
     * drawn depth-only into a target of its own, tested against it alone and writing its stencil value. Published as
     * {@link CgFrameKeys#CUSTOM_DEPTH}. Transparent draws only where their material writes depth (UseDefaultMaterial,
     * :560-591).
     */
    void recordCustomDepth(CgStageFrame stage, CgRecording recording, CgHostView view) {
        if (inCustomDepth.length < draws.meshes.length) inCustomDepth = new boolean[draws.meshes.length];
        int marked = 0;
        for (int i = 0; i < draws.count; i++) {
            int queue = draws.queues[i];
            boolean transparent = queue >= CgRenderQueue.TRANSPARENT_THRESHOLD;
            boolean in = draws.customDepths[i] >= 0 && (!transparent || draws.materials[i].writesDepth())
                    && world.classify(i, transparent ? CgWorldRenderer.TRANSPARENT : CgWorldRenderer.OPAQUE, view) != CgWorldDraws.SKIP;
            inCustomDepth[i] = in;
            if (!in) continue;
            marked++;
        }
        if (marked == 0) return;
        int w = Math.max(1, (int) world.targetWidth), h = Math.max(1, (int) world.targetHeight);
        if (customDepthTarget == null || customDepthTarget.getWidth() != w || customDepthTarget.getHeight() != h) {
            customDepthTarget = CgGraphTexture.transientTexture("cg_custom_depth", CgTextureDesc.of(w, h, CUSTOM_DEPTH_FORMAT));
        }
        CgRasterPass pass = recording.raster(customDepthTarget, CUSTOM_DEPTH_CLEAR, stage.constants(), null, CgOrder.SORTED)
                .timed(GPU_CUSTOM_DEPTH);
        CgChunkBuilder chunks = recording.chunks().begin();
        for (int i = 0; i < draws.count; i++) {
            if (!inCustomDepth[i]) continue;
            CgPipeline depth = commands.depthPipeline(draws.materials[i]);
            if (depth == null) continue;
            commands.passDraw(chunks, customDepthPipeline(depth, draws.customDepths[i]), draws.materials[i], recording, i);
        }
        pass.add(chunks.end());
        pass.end();
        stage.resources().put(CgFrameKeys.CUSTOM_DEPTH, customDepthTarget);
    }

    /**
     * {@code depth} writing CustomDepth: depth tested near-or-equal and written, the stencil set to the value in the
     * mask's bits, kept where depth fails under the full mask and replaced under a partial one, as
     * {@code GetCustomDepthStencilState} (CustomDepthRendering.cpp:534-557). Cached per state and value.
     */
    CgPipeline customDepthPipeline(CgPipeline depth, int packed) {
        CgRenderState own = depth.state();
        StencilVariants v = customDepthStates.get(own);
        if (v == null) customDepthStates.put(own, v = new StencilVariants());
        for (int k = 0; k < v.n; k++) if (v.keys[k] == packed) return depth.withState(v.states[k]);
        int value = packed & 0xFF, mask = packed >>> 8;
        CgRenderState state = own.withDepth(CgDepthState.TEST_WRITE)
                .withStencil(CgStencilState.write(value, mask, mask != 0xFF));
        if (v.n == v.keys.length) {
            v.keys = Arrays.copyOf(v.keys, v.n * 2);
            v.states = Arrays.copyOf(v.states, v.n * 2);
        }
        v.keys[v.n] = packed;
        v.states[v.n++] = state;
        return depth.withState(state);
    }
}
