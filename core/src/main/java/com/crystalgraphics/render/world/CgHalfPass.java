package com.crystalgraphics.render.world;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.texture.CgTexture;
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
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.trace.CgGpuTrace;

/**
 * Half-resolution translucency: transparent draws marked {@code halfResolution()} drawn at half the target's size, then
 * added over the target depth-aware, as Unreal's separate translucency at a lower screen percentage.
 *
 * <pre>{@code
 * if (halfDrawn > 0) halfPass.recordHalf(stage, recording, view);
 * }</pre>
 */
final class CgHalfPass {

    private final CgWorldRenderer world;
    private final CgWorldDraws draws;
    private final CgMeshDrawCommands commands;

    static final int GPU_HALF = CgGpuTrace.name("world.half"), GPU_HALF_ADD = CgGpuTrace.name("world.halfAdd");

    static final CgFrameBufferFormat HALF_FORMAT = CgFrameBufferFormat.builder("cg_world_half")
            .color(0, CgTextureType.R11F_G11F_B10F).build();

    CgGraphTexture halfTarget;

    CgMaterial upsample;

    CgTexture upsampleBound;

    CgHalfPass(CgWorldRenderer world) {
        this.world = world;
        this.draws = world.draws;
        this.commands = world.commands;
    }

    /**
     * The draws asking for half resolution ({@link CgWorldDraws#HALF}) into a target half the stage's size, cleared, then added over
     * the stage's target with a depth-aware upsample: each pixel weighs the four half-size texels round it by how close
     * the scene's depth where each was drawn is to its own, so light does not bleed across a silhouette. Before the
     * transparent pass, so what draws after (a haze bending it, smoke over it) sees it in the target.
     */
    void recordHalf(CgStageFrame stage, CgRecording recording, CgHostView view) {
        int w = Math.max(1, (int) (world.targetWidth * 0.5f)), h = Math.max(1, (int) (world.targetHeight * 0.5f));
        if (halfTarget == null || halfTarget.getWidth() != w || halfTarget.getHeight() != h) {
            halfTarget = CgGraphTexture.transientTexture("cg_world_half", new CgTextureDesc(w, h, HALF_FORMAT));
        }
        stage.constants().write(world.constantsBlock, 0);
        world.halfConstants.read(world.constantsBlock, 0).resolution(w, h);
        CgRasterPass pass = recording.raster(halfTarget, CgWorldRenderer.CLEAR, world.halfConstants, CgWorldRenderer.TRANSPARENT_STATE,
                        CgOrder.SORTED).sceneDepth(CgBindingPoints.DEPTH_TEXTURE_UNIT, stage.target())
                .texture(CgBindingPoints.LIGHTMAP_TEXTURE_UNIT, stage.host().textures().lightmapTexture()).timed(GPU_HALF);
        world.mediated(pass);
        CgChunkBuilder chunks = recording.chunks().begin();
        for (int i = 0; i < draws.count; i++) {
            if (draws.phase[i] != CgWorldDraws.HALF) continue;
            for (CgMaterial link = draws.materials[i]; link != null; link = link.getNextPass()) {
                CgPipeline pipeline = link.pipeline(CgInstanceKind.OBJECT);
                if (pipeline == null || commands.unready(pipeline, i)) continue;
                if (draws.sets[i] != null) {
                    commands.drawSet(chunks, pipeline, commands.bindingOf(link, recording), i);
                    continue;
                }
                chunks.draw(pipeline, commands.bindingOf(link, recording), draws.meshes[i]).sortKey(draws.keys[i]);
                commands.group(chunks, i);
                commands.writeInstance(chunks, i);
            }
        }
        pass.add(chunks.end());
        pass.end();

        if (upsample == null) upsample = CgMaterial.newInstance(CgWorldRenderer.UPSAMPLE_SHADER);
        if (halfTarget != upsampleBound) {
            CgTexture half = halfTarget;
            upsample.applyProperties(b -> b.sampler("_Half", 0, half));
            upsampleBound = half;
        }
        CgPipeline pipeline = upsample.pipeline(CgInstanceKind.OBJECT);
        if (pipeline == null) return;
        CgRasterPass add = recording.raster(stage.target(), CgLoad.load(), stage.constants(), null, CgOrder.SORTED)
                .sceneDepth(CgBindingPoints.DEPTH_TEXTURE_UNIT).timed(GPU_HALF_ADD);
        CgChunkBuilder upsampled = recording.chunks().begin();
        upsampled.draw(pipeline, upsample.captureBindings(recording.bindings()), CgWorldRenderer.FULLSCREEN);
        upsampled.instance();
        add.add(upsampled.end());
        add.end();
    }
}
