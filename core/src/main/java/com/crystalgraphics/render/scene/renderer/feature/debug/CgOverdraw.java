package com.crystalgraphics.render.scene.renderer.feature.debug;

import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.draw.CgDrawCommands;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.draw.CgPipelineForm;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.gpu.resource.CgReadback;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.meshpass.engine.CgEnginePasses;
import com.crystalgraphics.render.scene.pass.CgCommandForm;
import com.crystalgraphics.render.scene.pass.CgViewPasses;
import com.crystalgraphics.render.shader.CgBindingModel;
import com.crystalgraphics.render.stage.CgStageFiring;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * The overdraw view: every translucent draw counted again into a target of its own, one per fragment, and the counts
 * read back and summarised as counters. Drawn while {@code crystalgraphics.debug.view} is {@code overdraw}; the view's
 * {@code textures().overdraw()} holds the counts for a debug material to show.
 */
public final class CgOverdraw {

    private static final CgFormat FORMAT = CgFormat.R16_FLOAT;

    /** Pixels per overdraw count, the last bin everything from it up. */
    private final int[] counts = new int[256];
    private final CgReadback.Sink sink = this::summarise;
    @Nullable
    private CgGraphTexture target;
    @Nullable
    private CgDrawCommands merged;
    /** A translucent row's overdraw form: its Forward pass, or a pure haze's Distortion pass, its cost. */
    private final CgCommandForm counted = (rows, b, p) -> {
        if (p.pass() != CgRenderPassVariant.FORWARD) return null;
        CgPipeline pipeline = p;
        if (pipeline.state().writesNothing()) {
            CgMaterialProxy proxy = CgMaterialProxy.of(rows.material(b));
            pipeline = proxy.hasDistortionPass() ? proxy.pipeline(CgRenderPassVariant.DISTORTION, CgInstanceKind.OBJECT) : null;
        }
        return pipeline == null ? null : pipeline.as(CgPipelineForm.OVERDRAW);
    };

    /** Every translucent draw of the view, half-size ones too, counted into a target the view's size. */
    public void record(CgStageFiring firing, CgViewPasses passes) {
        CgView view = firing.view();
        CgRecording rec = firing.recording();
        int w = Math.max(1, view.width()), h = Math.max(1, view.height());
        if (target == null || target.getWidth() != w || target.getHeight() != h) {
            target = CgGraphTexture.transientTexture("cg_scene_overdraw", new CgTextureDesc(w, h, FORMAT));
        }
        if (merged == null || merged.table() != rec.primitives()) merged = new CgDrawCommands(rec.primitives());
        merged.clear();
        merged.append(passes.commands(CgEnginePasses.TRANSLUCENT, rec, counted));
        merged.append(passes.commands(CgEnginePasses.AFTER_DISTORTION, rec, counted));
        merged.append(passes.commands(CgEnginePasses.SEPARATE, rec, counted));
        merged.append(passes.commands(CgEnginePasses.HALF, rec, counted));
        merged.append(passes.commands(CgEnginePasses.VOLUME, rec, counted));
        merged.sort();
        rec.raster("scene.overdraw").color(target, CgLoad.CLEAR).constants(firing.constants()).readsDepth(firing.depth())
                .texture(CgBindingModel.LIGHTMAP, view.hostTextures().lightmapTexture()).add(merged).end();
        view.textures().overdraw(target);
        if (CgTrace.isEnabled(CgChannels.WORLD)) rec.readback(target, 0, 0, 0, w, h, sink);
    }

    /**
     * The counts read back, as counters a frame or two later: {@code scene.overdraw-covered} the share of pixels any
     * translucent fragment reached, and over those the mean, 95th percentile and most.
     */
    private void summarise(ByteBuffer data) {
        Arrays.fill(counts, 0);
        int pixels = data.remaining() / 2, base = data.position();
        long sum = 0;
        for (int i = 0; i < pixels; i++) {
            int n = halfCount(data.getShort(base + i * 2));
            sum += n;
            counts[Math.min(n, counts.length - 1)]++;
        }
        int covered = pixels - counts[0];
        CgTrace.counter(CgChannels.WORLD, "scene.overdraw-covered", pixels == 0 ? 0.0 : (double) covered / pixels);
        if (covered == 0) return;
        CgTrace.counter(CgChannels.WORLD, "scene.overdraw-mean", (double) sum / covered);
        int p95 = 0, max = 0;
        for (int c = 1, seen = 0; c < counts.length; c++) {
            if (counts[c] == 0) continue;
            max = c;
            seen += counts[c];
            if (p95 == 0 && seen >= 0.95 * covered) p95 = c;
        }
        CgTrace.counter(CgChannels.WORLD, "scene.overdraw-p95", p95);
        CgTrace.counter(CgChannels.WORLD, "scene.overdraw-max", max);
    }

    /** A half float holding a whole count, as an int; below 1 is 0. */
    static int halfCount(short half) {
        int exponent = (half >>> 10) & 0x1f;
        return exponent == 0 ? 0 : Math.round(Math.scalb((float) (1024 + (half & 0x3ff)), exponent - 25));
    }
}
