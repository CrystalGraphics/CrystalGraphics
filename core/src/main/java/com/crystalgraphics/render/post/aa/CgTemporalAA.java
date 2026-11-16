package com.crystalgraphics.render.post.aa;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgScreenTexture;
import com.crystalgraphics.render.post.CgPostContext;
import com.crystalgraphics.render.post.upscale.CgUpscaler;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.render.view.CgViewState;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.trace.CgGpuTrace;

import javax.annotation.Nullable;

/**
 * The post chain's temporal anti-aliasing: each frame's picture, drawn through a projection moved a fraction of a texel
 * ({@link CgView#jitter}), blended into the view's history, so edges, dithered masks and thin lines settle to their
 * coverage. After separate translucency is laid over and before {@code SCENE}, so bloom and the looks see the resolved
 * picture. Engine side; a view turns it on.
 *
 * <pre>{@code
 * view.showFlags().temporalAA(true);       // the resolve: settles a dithered mask, leaves an edge as drawn
 * view.jitter(true);                       // and the jitter, which resolves the edges: a view whose projection is ours
 * }</pre>
 *
 * <ul>
 *   <li>The history is fetched by velocity: what the velocity pass wrote, else the camera's motion over the depth.
 *       Asked for when it shows ({@code CgSceneTextures.requestVelocity}).</li>
 *   <li>Unreal's classic TAA's frame (the input filtered by the jitter, Catmull-Rom history, YCoCg), with a variance
 *       clip and three departures for what it shimmers or smears on: where the texels round one part in motion (a
 *       dithered or thin surface over what moves behind it) the history is cut to as many frames as smear it a texel;
 *       a still texel the clip keeps cutting (geometry thinner than a texel) relaxes its clip; and a still texel keeps
 *       up to 63 frames. The history's alpha holds the texel's age and its cut count.</li>
 *   <li>A view whose last frame is not one of this camera (a cut, a jump, a new size) starts over from this frame.</li>
 *   <li>Off on a view with no linear HDR scene, and off by default on every view. On the client's view, Minecraft's
 *       own draws are not jittered and its moving entities and our particles write no velocity.</li>
 *   <li>It resolves at the size the world draws at and never upscales: a view drawn below 100% is upscaled spatially
 *       after the chain.</li>
 * </ul>
 */
public final class CgTemporalAA implements CgUpscaler {

    /** The resolve's shader, precached with the chain's. */
    public static final String SHADER = "crystalgraphics:shaders/post/aa/taa_resolve.shader";
    /** The resolved colour, out of the history into the chain's picture. */
    public static final String OUTPUT = "crystalgraphics:shaders/post/aa/taa_output.shader";
    private static final CgViewState.HistoryKey HISTORY = new CgViewState.HistoryKey("crystalgraphics:taa");
    private static final int GPU_RESOLVE = CgGpuTrace.name("post.taa.resolve"), GPU_OUTPUT = CgGpuTrace.name("post.taa.output");
    /** The jitter's sequence: Unreal's {@code r.TemporalAASamples} 8. */
    private static final int SAMPLES = 8;
    /** This frame's least share of the blend, its share once a texel moves 8 texels a frame, and where its fetch is ambiguous. */
    private static final float SHARE = 0.04f, SHARE_MOVING = 0.2f, SHARE_AMBIGUOUS = 0.25f;

    /**
     * RCAS's sharpness on the resolved picture, in stops below FSR's most (FsrRcasCon's: 0 its most, FSR's own 0.2): what
     * the jitter's average softens, given back. 15% past FSR's most (-log2 1.15), its lobe still short of the -1/4 the
     * resolve divides by zero at.
     */
    private static final float SHARPEN_STOPS = -0.202f;
    private static volatile boolean sharpening = true;

    private final CgTemporalGlow glow = new CgTemporalGlow(new CgViewState.HistoryKey("crystalgraphics:taa_glow"));
    private CgMaterialInstance resolve, output;

    /**
     * The resolve and its jitter on or off together on {@code view}: a developer's switch, the harness's and the render
     * demo's F7.
     *
     * <pre>{@code
     * CgTemporalAA.enable(CgView.main(), !CgTemporalAA.enabled(CgView.main()));
     * }</pre>
     */
    public static void enable(CgView view, boolean on) {
        view.showFlags().temporalAA(on);
        view.jitter(on);
    }

    /**
     * Whether the resolved picture is sharpened (RCAS), on every view: a developer's switch, the harness's and the
     * render demo's F8. On by default.
     *
     * <pre>{@code
     * CgTemporalAA.sharpening(!CgTemporalAA.sharpening());
     * }</pre>
     */
    public static void sharpening(boolean on) {
        sharpening = on;
    }

    /** Whether the resolved picture is sharpened ({@link #sharpening(boolean)}). */
    public static boolean sharpening() {
        return sharpening;
    }

    /** RCAS's sharpness for a temporal step's resolved picture ({@code lib/rcas.glsl}), 0 while sharpening is off. */
    public static float sharpness() {
        return sharpening ? (float) Math.pow(2.0, -SHARPEN_STOPS) : 0f;
    }

    /** Whether {@code view} asks for the resolve (its show flag). */
    public static boolean enabled(CgView view) {
        return view.showFlags().temporalAA();
    }

    /** Whether it resolves this firing: the view asks and has a linear scene. */
    public static boolean shows(CgView view) {
        return view.showFlags().temporalAA() && view.textures().linear();
    }

    /** Bloom's light averaged over the jitter's frames ({@link CgTemporalGlow}). */
    @Override
    public CgGraphTexture bloomLight(CgPostContext ctx, CgScreenTexture input, CgScreenTexture resolved, float threshold) {
        return glow.record(ctx, input, resolved, threshold);
    }

    @Override
    public float minFraction() {
        return 1f;
    }

    @Override
    public int jitterSamples(float fraction) {
        return SAMPLES;
    }

    @Override
    public float materialMipBias(float fraction) {
        return 0f;
    }

    /** Resolves {@code input} into the view's history, and answers a copy of it the chain may draw over. */
    @Override
    public CgScreenTexture apply(CgPostContext ctx, CgScreenTexture input) {
        if (resolve == null) {
            resolve = CgMaterial.load(SHADER).instance();
            output = CgMaterial.load(OUTPUT).instance();
        }
        CgView view = ctx.view();
        CgViewState state = view.state();
        CgGraphTexture history = state.history(HISTORY, new CgTextureDesc(input.width(), input.height(), CgFormat.RGBA16_FLOAT),
                ctx.recording());
        boolean blend = !state.fresh(HISTORY) && view.historyValid();
        CgGraphTexture velocity = ctx.scene().velocity();
        float covers = CgTemporalGlow.coversEngine(ctx, velocity);
        resolve.sampler("_History", 0, history.previous()).vec4("_Resolve", SHARE, SHARE_MOVING, blend ? 1f : 0f, SHARE_AMBIGUOUS)
                .vec4("_Jitter", view.jitterNdcX(), view.jitterNdcY(), covers, 0f);
        CgGraphTexture responsive = ctx.scene().responsive();
        if (velocity != null) resolve.sampler("_Velocity", 1, velocity);
        if (responsive != null) resolve.sampler("_Responsive", 2, responsive);
        CgScreenTexture out = ctx.texture("cg.taa.out", input.width(), input.height(), CgFormat.RGBA16_FLOAT);
        ctx.screen(resolve).name("taa.resolve").timed(GPU_RESOLVE).from(input).reads(history.previous()).reads(velocity).reads(responsive)
                .reads(ctx.depth()).into(CgScreenTexture.of(history));
        // The chain may draw over its picture in place: it gets a copy, without the history's age
        output.sampler("_History", 0, history).vec4("_Sharpen", sharpness(), covers, 0f, 0f);
        if (velocity != null) output.sampler("_Velocity", 1, velocity);
        if (responsive != null) output.sampler("_Responsive", 2, responsive);
        ctx.screen(output).name("taa.output").timed(GPU_OUTPUT).from(input).reads(history).reads(velocity).reads(responsive).into(out);
        return out;
    }
}
