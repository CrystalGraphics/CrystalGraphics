package com.crystalgraphics.render.world;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgColorMask;
import com.crystalgraphics.api.state.CgCullState;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.state.CgStencilState;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgTextureDesc;
import com.crystalgraphics.render.stage.CgFrameKeys;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.trace.CgGpuTrace;
import java.util.Arrays;
import java.util.IdentityHashMap;

/**
 * Unreal's CustomDepth pass ({@code EMeshPass::CustomDepth}): every draw marked {@code customDepth} drawn depth-only into
 * a target of its own, writing its stencil value, then the stencil copied into the target's R8 colour. Published as
 * {@code CgFrameKeys.CUSTOM_DEPTH} for post materials to read.
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

    /** CustomDepth beside CustomStencil's copy (CgFrameKeys.CUSTOM_DEPTH): Unreal's PF_DepthStencil, cleared to far and 0. */
    static final CgFrameBufferFormat CUSTOM_DEPTH_FORMAT = CgFrameBufferFormat.builder("cg_custom_depth")
            .color(0, CgTextureType.R8).depth(CgTextureType.DEPTH32F_STENCIL8).build();

    static final CgLoad CUSTOM_DEPTH_CLEAR = CgLoad.clear(0f, 0f, 0f, 0f).andDepth(1.0).andStencil(0);

    static final int GPU_CUSTOM_DEPTH = CgGpuTrace.name("world.customDepth");

    /**
     * The stencil's copy, Unreal's on GL (CustomDepthRendering.cpp:436-450) by draws, since no device here samples a
     * stencil: bit b's draw passes where the stencil holds it and adds 2^b/255 into the R8 colour.
     */
    static final CgRenderState[] STENCIL_BIT_STATES = new CgRenderState[8];

    /** A depth pipeline's state's CustomDepth variants, by stencil value and write mask ({@link CgWorldDraws#customDepths}). */
    final IdentityHashMap<CgRenderState, StencilVariants> customDepthStates = new IdentityHashMap<>();

    boolean[] inCustomDepth = new boolean[64];

    CgGraphTexture customDepthTarget;

    CgMaterial customStencil;

    static {
        for (int b = 0; b < 8; b++) {
            STENCIL_BIT_STATES[b] = CgRenderState.builder().depth(CgDepthState.NONE).cull(CgCullState.NONE)
                    .blend(CgBlendState.SUM).stencil(CgStencilState.equal(1 << b, 1 << b))
                    .colorMask(CgColorMask.ALL).build();
        }
    }

    CgCustomDepthPass(CgWorldRenderer world) {
        this.world = world;
        this.draws = world.draws;
        this.commands = world.commands;
    }

    /**
     * Unreal's CustomDepth pass (CustomDepthRendering.cpp:216-326): every draw in view marked {@link Draw#customDepth}
     * drawn depth-only into a target of its own, tested against it alone and writing its stencil value; then the
     * stencil's copy into the target's R8 colour. Published as {@link CgFrameKeys#CUSTOM_DEPTH}. Transparent draws only
     * where their material writes depth (UseDefaultMaterial, :560-591).
     */
    void recordCustomDepth(CgStageFrame stage, CgRecording recording, CgHostView view) {
        if (inCustomDepth.length < draws.meshes.length) inCustomDepth = new boolean[draws.meshes.length];
        int marked = 0, bits = 0;
        for (int i = 0; i < draws.count; i++) {
            int queue = draws.queues[i];
            boolean transparent = queue >= CgRenderQueue.TRANSPARENT_THRESHOLD;
            boolean in = draws.customDepths[i] >= 0 && (!transparent || draws.materials[i].writesDepth())
                    && world.classify(i, transparent ? CgWorldRenderer.TRANSPARENT : CgWorldRenderer.OPAQUE, view) != CgWorldDraws.SKIP;
            inCustomDepth[i] = in;
            if (!in) continue;
            marked++;
            bits |= draws.customDepths[i] & draws.customDepths[i] >>> 8 & 0xFF;   // the value under its write mask
        }
        if (marked == 0) return;
        int w = Math.max(1, (int) world.targetWidth), h = Math.max(1, (int) world.targetHeight);
        if (customDepthTarget == null || customDepthTarget.getWidth() != w || customDepthTarget.getHeight() != h) {
            customDepthTarget = CgGraphTexture.transientTexture("cg_custom_depth", new CgTextureDesc(w, h, CUSTOM_DEPTH_FORMAT));
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
        if (customStencil == null) customStencil = CgMaterial.newInstance(CgWorldRenderer.CUSTOM_STENCIL_SHADER);
        CgPipeline copy = customStencil.pipeline(CgInstanceKind.OBJECT);
        if (copy != null) {
            int copyBindings = customStencil.captureBindings(recording.bindings());
            // From a cleared stencil, only the bits some draw writes can be set: copy those, not all eight
            for (int b = 0; b < 8; b++) {
                if ((bits >>> b & 1) == 0) continue;
                chunks.draw(copy.withState(STENCIL_BIT_STATES[b]), copyBindings, CgWorldRenderer.FULLSCREEN).sortKey(Long.MAX_VALUE);
                int at = chunks.instance();
                chunks.data()[at + 32] = (1 << b) / 255f;
            }
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
