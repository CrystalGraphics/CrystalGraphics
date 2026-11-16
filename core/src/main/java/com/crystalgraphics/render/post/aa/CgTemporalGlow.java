package com.crystalgraphics.render.post.aa;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgScreenTexture;
import com.crystalgraphics.render.post.CgPostContext;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.render.view.CgViewState;

import javax.annotation.Nullable;

/**
 * Bloom's light under a temporal upscaler: each jittered frame's excess past bloom's threshold, followed by the
 * resolve's motion and averaged over the jitter's frames, in a history of the view's at the size drawn. Thresholded
 * after the resolve, a light thinner than a texel falls under the threshold at its coverage and loses its glow; this
 * keeps the glow it has without temporal anti-aliasing. One per upscaler, each with its own history.
 *
 * <pre>{@code
 * private final CgTemporalGlow glow = new CgTemporalGlow(new CgViewState.HistoryKey("mymod:upscaler_glow"));
 *
 * public CgGraphTexture bloomLight(CgPostContext ctx, CgScreenTexture input, CgScreenTexture resolved, float threshold) {
 *     return glow.record(ctx, input, resolved, threshold);
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@code input} is the jittered picture the upscaler resolved, {@code resolved} what it answered, at the output
 *       size or the size drawn: the glow is held under the resolved light, so a light that moved leaves no glow behind.</li>
 *   <li>Render thread, once per firing, after the upscaler's {@code apply}.</li>
 * </ul>
 */
public final class CgTemporalGlow {

    /** Its shader, precached with the chain's. */
    public static final String SHADER = "crystalgraphics:shaders/post/aa/temporal_glow.shader";
    /** Each frame's share: two of temporal AA's jitter cycles, so a light one sample in eight lands on holds still. */
    private static final float SHARE = 1f / 16f;

    private final CgViewState.HistoryKey key;
    private CgMaterialInstance glow;

    public CgTemporalGlow(CgViewState.HistoryKey key) {
        this.key = key;
    }

    /** Records this frame's glow and answers it: bloom's light, the threshold taken. */
    public CgGraphTexture record(CgPostContext ctx, CgScreenTexture input, CgScreenTexture resolved, float threshold) {
        if (glow == null) glow = CgMaterial.load(SHADER).instance();
        CgView view = ctx.view();
        CgViewState state = view.state();
        CgGraphTexture history = state.history(key, new CgTextureDesc(input.width(), input.height(), CgFormat.RGBA16_FLOAT),
                ctx.recording());
        boolean blend = !state.fresh(key) && view.historyValid();
        CgGraphTexture velocity = ctx.scene().velocity();
        glow.sampler("_History", 0, history.previous()).vec4("_Glow", SHARE, threshold, blend ? 1f : 0f, 0f)
                .vec4("_Jitter", view.jitterNdcX(), view.jitterNdcY(), coversEngine(ctx, velocity), 0f);
        CgGraphTexture responsive = ctx.scene().responsive();
        if (velocity != null) glow.sampler("_Velocity", 1, velocity);
        glow.sampler("_Resolved", 2, resolved.texture());
        if (responsive != null) glow.sampler("_Responsive", 3, responsive);
        ctx.screen(glow).name("temporal.glow").from(input).reads(history.previous()).reads(velocity).reads(responsive)
                .reads(ctx.depth()).reads(resolved.texture()).into(CgScreenTexture.of(history));
        return history;
    }

    /**
     * 1 where every engine solid wrote velocity this frame ({@code requestVelocityWithStill}): a texel nothing wrote round
     * is then one the engine did not draw, which comes through as it came. 0 without it: every texel is the engine's.
     */
    public static float coversEngine(CgPostContext ctx, @Nullable CgGraphTexture velocity) {
        return velocity != null && ctx.view().textures().velocityWithStill() ? 1f : 0f;
    }
}
