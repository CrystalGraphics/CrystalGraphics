package com.crystalgraphics.render.world;

import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.gpu.resource.CgReadback;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.draw.CgPipelineForm;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.render.stage.CgFrameKeys;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import java.nio.ByteBuffer;
import java.util.Arrays;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.shader.CgBindingModel;
import com.crystalgraphics.api.material.CgMaterialInterface;

/**
 * The overdraw view's pass: every transparent draw counted again into a target of its own, one per fragment, and the
 * counts read back and summarised as a histogram. Drawn when {@code CgPostDebug.VIEW} is {@code "overdraw"}.
 *
 * <pre>{@code
 * if (overdraw && drawn > 0) overdrawPass.recordOverdraw(stage, recording, view);
 * }</pre>
 */
final class CgOverdrawPass {

    private final CgWorldRenderer world;
    private final CgWorldDraws draws;
    private final CgMeshDrawCommands commands;

    static final CgFormat OVERDRAW_FORMAT = CgFormat.R16_FLOAT;

    CgGraphTexture overdrawTarget;

    /** Pixels per overdraw count, the last bin everything from it up: {@link #summariseOverdraw}'s. */
    final int[] overdrawCounts = new int[256];

    final CgReadback.Sink overdrawSink = this::summariseOverdraw;

    CgOverdrawPass(CgWorldRenderer world) {
        this.world = world;
        this.draws = world.draws;
        this.commands = world.commands;
    }

    /**
     * Every transparent draw this stage drew, half-size ones too, again through its overdraw variant into a target of
     * the stage's size: 1 a fragment, hidden by the stage's depth as its own depth test would hide it.
     */
    void recordOverdraw(CgStageFrame stage, CgRecording recording, CgCamera view) {
        int w = Math.max(1, (int) world.targetWidth), h = Math.max(1, (int) world.targetHeight);
        if (overdrawTarget == null || overdrawTarget.getWidth() != w || overdrawTarget.getHeight() != h) {
            overdrawTarget = CgGraphTexture.transientTexture("cg_world_overdraw", new CgTextureDesc(w, h, OVERDRAW_FORMAT));
        }
        CgRasterPass pass = recording.raster("overdrawPass.overdrawTarget").color(overdrawTarget, CgWorldRenderer.CLEAR).constants(stage.constants()).readsDepth(stage.depth())
                .texture(CgBindingModel.LIGHTMAP, stage.host().textures().lightmapTexture());
        CgChunkBuilder chunks = recording.chunks().begin();
        for (int i = 0; i < draws.count; i++) {
            if (draws.phase[i] == CgWorldDraws.SKIP) continue;
            CgMaterialProxy proxy = CgMaterialProxy.of(draws.materials[i]);
            CgPipeline pipeline = proxy.pipeline(CgInstanceKind.OBJECT);
            // A pass writing nothing is skipped; a pure haze's cost is its Distortion pass.
            if (pipeline != null && pipeline.state().writesNothing()) {
                pipeline = proxy.hasDistortionPass() ? proxy.pipeline(CgRenderPassVariant.DISTORTION, CgInstanceKind.OBJECT) : null;
            }
            if (pipeline == null) continue;
            if (draws.sets[i] != null) {
                commands.drawSet(chunks, pipeline.as(CgPipelineForm.OVERDRAW), commands.bindingOf(draws.materials[i], recording), i);
                continue;
            }
            chunks.draw(pipeline.as(CgPipelineForm.OVERDRAW), commands.bindingOf(draws.materials[i], recording), draws.meshes[i]).sortKey(draws.keys[i]);
            commands.writeInstance(chunks, i);
        }
        pass.add(chunks.end());
        pass.end();
        stage.resources().put(CgFrameKeys.OVERDRAW, overdrawTarget);
        if (CgTrace.isEnabled(CgChannels.WORLD)) recording.readback(overdrawTarget, 0, 0, 0, w, h, overdrawSink);
    }

    /**
     * The overdraw count read back, as counters a frame or two later: {@code world.overdraw-covered} the share of pixels
     * any transparent fragment reached, and over those the mean, 95th percentile and most.
     */
    void summariseOverdraw(ByteBuffer data) {
        Arrays.fill(overdrawCounts, 0);
        int pixels = data.remaining() / 2, base = data.position();
        long sum = 0;
        for (int i = 0; i < pixels; i++) {
            int n = CgWorldRenderer.halfCount(data.getShort(base + i * 2));
            sum += n;
            overdrawCounts[Math.min(n, overdrawCounts.length - 1)]++;
        }
        int covered = pixels - overdrawCounts[0];
        CgTrace.counter(CgChannels.WORLD, "world.overdraw-covered", pixels == 0 ? 0.0 : (double) covered / pixels);
        if (covered == 0) return;
        CgTrace.counter(CgChannels.WORLD, "world.overdraw-mean", (double) sum / covered);
        int p95 = 0, max = 0;
        for (int c = 1, seen = 0; c < overdrawCounts.length; c++) {
            if (overdrawCounts[c] == 0) continue;
            max = c;
            seen += overdrawCounts[c];
            if (p95 == 0 && seen >= 0.95 * covered) p95 = c;
        }
        CgTrace.counter(CgChannels.WORLD, "world.overdraw-p95", p95);
        CgTrace.counter(CgChannels.WORLD, "world.overdraw-max", max);
    }
}
