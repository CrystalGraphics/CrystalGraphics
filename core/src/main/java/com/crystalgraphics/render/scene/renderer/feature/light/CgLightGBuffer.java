package com.crystalgraphics.render.scene.renderer.feature.light;

import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.gpu.resource.CgFallbackTextures;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.stage.CgStageFiring;
import com.crystalgraphics.trace.CgGpuTrace;

import javax.annotation.Nullable;

/**
 * Each pixel's normal in the view, written once a firing for every lighting pass (Unreal's GBufferA in role): our opaque
 * surfaces' own where the view wrote them, the rest rebuilt from depth. Half floats, so it is the normal each pass
 * rebuilt from depth, to a thousandth.
 */
final class CgLightGBuffer {

    static final String KERNELS = "crystalgraphics:shaders/world/lights/gbuffer.compute";
    private static final CgFormat FORMAT = CgFormat.RGBA16_FLOAT;
    private static final int GPU_GBUFFER = CgGpuTrace.name("world.lightsGBuffer");

    private final CgLightGrid grid;
    private CgKernel kernel;
    /** This firing's. */
    CgGraphTexture texture;
    /** Our opaque surfaces' normals this firing (its view's normal target), alpha their eye depth; null for none. */
    @Nullable
    private CgGraphTexture normals;

    CgLightGBuffer(CgLightGrid grid) {
        this.grid = grid;
    }

    static void precache() {
        CgCompute.load(KERNELS).kernel("GBuffer").precache();
    }

    /** Its kernel, made and started once; whether it is built. */
    boolean ready() {
        if (kernel == null) {
            kernel = CgCompute.load(KERNELS).kernel("GBuffer");
            kernel.precache();
        }
        return kernel.isReady();
    }

    /** Before the firing: the normals its view wrote in its opaque firing this frame, or null. */
    void normals(@Nullable CgGraphTexture target) {
        normals = target;
    }

    /** Whether our surfaces' normals are this firing's: something of ours was drawn opaque, so it can shadow. */
    boolean hasNormals() {
        return normals != null;
    }

    /** This firing's, after {@link #ready}. */
    void record(CgStageFiring stage, CgRecording recording) {
        int w = Math.max(1, (int) grid.targetW), h = Math.max(1, (int) grid.targetH);
        if (texture == null || texture.getWidth() != w || texture.getHeight() != h) {
            texture = CgGraphTexture.transientTexture("cg_world_lights.gbuffer", new CgTextureDesc(w, h, FORMAT));
        }
        CgComputePass pass = recording.compute("world.lightsGBuffer", stage.constants()).readsDepth(stage.depth())
                .timed(GPU_GBUFFER);
        grid.values(pass.dispatch(kernel, w, h, 1).image("GBUFFER", texture).values()
                .sampler("_Normals", 0, normals != null ? normals : CgFallbackTextures.BLACK_1x1));
        pass.end();
    }
}
