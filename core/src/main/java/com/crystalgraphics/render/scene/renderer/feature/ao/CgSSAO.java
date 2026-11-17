package com.crystalgraphics.render.scene.renderer.feature.ao;

import javax.annotation.Nullable;

import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgDispatch;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.post.settings.CgPostSettings;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.render.view.CgViewState;
import com.crystalgraphics.settings.CgGraphicsSettings;

/**
 * Screen-space ambient occlusion, {@code ambient_occlusion_method} 0: Unreal's deferred SSAO at up to three sizes.
 * {@link CgAmbientOcclusion} runs it, once the depth holds every solid.
 *
 * <ul>
 *   <li>Levels, by the post settings' quality: one (the render size) to 35, two (and half) to 70, three (and a
 *       quarter) above; {@code ambient_occlusion_levels} sets them.</li>
 *   <li>Setup halves the scene's normals (the prepass's where written, else depth's) and depth for each smaller
 *       size. Each size's occlusion goes into the next larger one's at the post settings' mip blend; the smaller
 *       sizes reach farther, by the mip scale a size.</li>
 *   <li>Each size walks 3 or 6 rotated pairs of samples, 1 to 3 steps each, through the furthest depth pyramid; the
 *       rotation is a 4 x 4 pattern, moved each frame while temporal anti-aliasing runs to resolve it.</li>
 *   <li>Low and Medium blur each 2 x 2 quad by its normals.</li>
 *   <li>Past Unreal: the pyramid read bilinear, then a 4 x 4 smooth that keeps depth and normal edges, then GTAO's
 *       temporal filter into the view's own history; with them the pattern and its grain average out.</li>
 * </ul>
 *
 * <p>The kernels are {@code world/ao/ssao.compute}.</p>
 */
final class CgSSAO {

    private static final String KERNELS = "crystalgraphics:shaders/world/ao/ssao.compute";
    private static final String[] NAMES = {"Setup", "Step", "Final", "Smooth", "Temporal"};
    private static final int SETUP = 0, STEP = 1, FINAL = 2, SMOOTH = 3, TEMPORAL = 4;
    private static final CgViewState.HistoryKey HISTORY = new CgViewState.HistoryKey("crystalgraphics:ssao");
    /** Steps a sample takes, by shader level 0 to 4. */
    private static final int[] STEPS = {1, 1, 2, 3, 3};
    /** The pattern's move a frame, in its texels: eight frames, then it repeats. */
    private static final float RANDOM_X = 2.48f, RANDOM_Y = 7.52f;
    private static final int GROUP = 8;
    private static final CgViewState.Key<Chain> CHAIN = new CgViewState.Key<>("crystalgraphics:ambient_occlusion.ssao", Chain::new);

    private static CgKernel[] kernels;

    private CgSSAO() {
    }

    static void precache() {
        CgCompute c = CgCompute.load(KERNELS);
        for (String name : NAMES) c.kernel(name).precache();
    }

    static boolean ready() {
        load();
        for (CgKernel k : kernels) if (!k.isReady()) return false;
        return true;
    }

    /**
     * Unreal's level count, 1 to 3, from the settings' quality under its cap: the player's {@code ambient_occlusion_levels}
     * where set.
     */
    static int levels(CgPostSettings s) {
        int set = Math.round(CgGraphicsSettings.AMBIENT_OCCLUSION_LEVELS.get());
        if (set >= 0) return Math.max(1, Math.min(3, set));
        float q = CgAmbientOcclusion.quality(s);
        return 1 + (q > 70f ? 1 : 0) + (q > 35f ? 1 : 0);
    }

    /**
     * Records every size's passes at shader {@code level} (0-4) into {@code ao}, {@code w} by {@code h}, from
     * {@code pyramid} (its last level {@code pyramidLast}) and {@code normals}, the prepass's (null: depth's alone);
     * {@code fadeMul} and {@code fadeAdd} fade it out over eye depth.
     */
    static void record(CgComputePass pass, CgRecording recording, CgView view, CgPostSettings s, int level,
                       CgGraphTexture pyramid, int pyramidLast, @Nullable CgGraphTexture normals, CgGraphTexture ao, int w,
                       int h, float fadeMul, float fadeAdd) {
        load();
        view.state().get(CHAIN).record(pass, recording, view, s, level, pyramid, pyramidLast, normals, ao, w, h, fadeMul, fadeAdd);
    }

    /** Unreal's {@code r.AmbientOcclusionMipLevelFactor} by Quality tier: how soon a step reads a coarser pyramid level. */
    private static float mipLevelFactor() {
        return switch (CgGraphicsSettings.QUALITY.get()) {
            case LOW, MEDIUM -> 1f;
            case HIGH -> 0.6f;
            case ULTRA -> 0.4f;
        };
    }

    /** Unreal's {@code r.AmbientOcclusionRadiusScale} by Quality tier. */
    private static float radiusScale() {
        return switch (CgGraphicsSettings.QUALITY.get()) {
            case LOW -> 1.2f;
            case MEDIUM, HIGH -> 1.5f;
            case ULTRA -> 1f;
        };
    }

    private static int groups(int size) {
        return (size + GROUP - 1) / GROUP;
    }

    private static void load() {
        if (kernels != null) return;
        CgCompute c = CgCompute.load(KERNELS);
        CgKernel[] k = new CgKernel[NAMES.length];
        for (int i = 0; i < NAMES.length; i++) {
            k[i] = c.kernel(NAMES[i]);
            k[i].precache();
        }
        kernels = k;
    }

    /**
     * A view's chain: its setups and smaller sizes' occlusion, remade when the sizes or the level count change, and the
     * frame's values its passes share while it records them.
     */
    private static final class Chain {
        @Nullable
        CgGraphTexture setupHalf, setupQuarter, stepHalf, stepQuarter, unsmoothed, filtered;
        int levels, w0, h0, hw, hh, qw, qh;

        @Nullable
        CgView view;
        @Nullable
        CgPostSettings s;
        @Nullable
        CgGraphTexture pyramid, normals;
        int level, pyramidLast, w, h;
        float fadeMul, fadeAdd;

        void record(CgComputePass pass, CgRecording recording, CgView view, CgPostSettings s, int level,
                    CgGraphTexture pyramid, int pyramidLast, @Nullable CgGraphTexture normals, CgGraphTexture ao, int w,
                    int h, float fadeMul, float fadeAdd) {
            this.view = view;
            this.s = s;
            this.level = level;
            this.pyramid = pyramid;
            this.pyramidLast = pyramidLast;
            this.normals = normals;
            this.w = w;
            this.h = h;
            this.fadeMul = fadeMul;
            this.fadeAdd = fadeAdd;
            int halfW = Math.max(1, (w + 1) / 2), halfH = Math.max(1, (h + 1) / 2);
            size(levels(s), w, h, halfW, halfH, Math.max(1, (halfW + 1) / 2), Math.max(1, (halfH + 1) / 2));

            if (levels >= 2) setup(pass, setupHalf, hw, hh, w, h);
            if (levels >= 3) setup(pass, setupQuarter, qw, qh, hw, hh);
            CgGraphTexture smaller = null;
            if (levels >= 3) {
                occlusion(pass, kernels[STEP], "STEP", stepQuarter, qw, qh, 4, setupQuarter, null);
                smaller = stepQuarter;
            }
            if (levels >= 2) {
                occlusion(pass, kernels[STEP], "STEP", stepHalf, hw, hh, 2, setupHalf, smaller);
                smaller = stepHalf;
            }
            occlusion(pass, kernels[FINAL], "AO", unsmoothed, w, h, 1, levels >= 2 ? setupHalf : null, smaller);
            common(pass.dispatchGroups(kernels[SMOOTH], groups(w), groups(h), 1), w, h).image("FILTERED", filtered).texture("_Unsmoothed", unsmoothed)
                    .set("_Ssao3", 0f, 0f, normals != null ? 1f : 0f, 0f);
            CgViewState state = view.state();
            CgGraphTexture history = state.history(HISTORY, new CgTextureDesc(w, h, CgFormat.R16_FLOAT), recording);
            boolean valid = !state.fresh(HISTORY) && view.historyValid();
            CgDispatch temporal = common(pass.dispatch(kernels[TEMPORAL], w, h, 1), w, h).image("HISTORY", history).image("AO", ao)
                    .texture("_Filtered", filtered).texture("_History", history.previous())
                    .set("_AoSize", w, h, 1f / w, 1f / h)
                    .set("_Temporal", s.ambientOcclusionTemporalBlendWeight(), valid ? 1f : 0f, 0f, 0f)
                    .set("_Output", s.ambientOcclusionIntensity(), s.ambientOcclusionPower(), fadeMul, fadeAdd);
            CgGraphTexture velocity = view.textures().velocity();
            if (velocity != null) temporal.texture("_Velocity", velocity);
            this.view = null;
            this.s = null;
            this.pyramid = null;
            this.normals = null;
        }

        private CgDispatch common(CgDispatch d, int ow, int oh) {
            d.set("_Size", w, h, 1f / w, 1f / h).set("_OutSize", ow, oh, 1f / ow, 1f / oh);
            if (normals != null) d.texture("_Normals", normals);
            return d;
        }

        /** {@code out}, {@code ow} by {@code oh}: half its input, {@code iw} by {@code ih}. */
        private void setup(CgComputePass pass, CgGraphTexture out, int ow, int oh, int iw, int ih) {
            // Unreal's threshold is per centimetre of depth at the render size
            float similarity = s.ambientOcclusionMipThreshold() * 100f * ow / w;
            common(pass.dispatch(kernels[SETUP], ow, oh, 1), ow, oh).image("SETUP", out)
                    .set("_InSize", iw, ih, 1f / iw, 1f / ih)
                    .set("_Ssao3", 0f, 0f, normals != null ? 1f : 0f, similarity);
        }

        /**
         * One size's occlusion into {@code image} {@code out}, {@code ow} by {@code oh}, {@code scale} render pixels a
         * pixel: from {@code setup} at a smaller size (and the upsample's weights), blending {@code smaller} where one ran.
         */
        private void occlusion(CgComputePass pass, CgKernel kernel, String image, CgGraphTexture out, int ow, int oh, int scale,
                               @Nullable CgGraphTexture setup, @Nullable CgGraphTexture smaller) {
            boolean step = kernel == kernels[STEP];
            boolean world = s.ambientOcclusionRadiusInWorldSpace();
            // Unreal's radius off world space is a share of 400 units; its /4 and mip scale are for the levels
            float radius = (world ? s.ambientOcclusionRadius() : s.ambientOcclusionRadius() / 4f) * radiusScale()
                    * (float) Math.pow(s.ambientOcclusionMipScale(), Integer.numberOfTrailingZeros(scale)) / 4f;
            int samples = step || level >= 4 ? 6 : 3;
            int taps = smaller == null ? 0 : step ? 9 : 4;
            boolean blur = level == 1 || level == 2;
            int index = view.showFlags().temporalAA() ? view.state().frameIndex() % 8 : 0;
            CgDispatch d = common(pass.dispatchGroups(kernel, groups(ow), groups(oh), 1), ow, oh).image(image, out)
                    .texture("_Pyramid", pyramid)
                    .set("_Ssao", radius, world ? 1f : 0f, s.ambientOcclusionBias() / 1000f, scale)
                    .set("_Ssao2", mipLevelFactor(), smaller != null ? s.ambientOcclusionMipBlend() : 0f,
                            STEPS[Math.max(0, Math.min(4, level))], samples)
                    .set("_Ssao3", taps, blur ? 1f : 0f, normals != null ? 1f : 0f, 0f)
                    .set("_Random", RANDOM_X * index, RANDOM_Y * index, pyramidLast, 0f)
                    .set("_Output", s.ambientOcclusionIntensity(), s.ambientOcclusionPower(), fadeMul, fadeAdd);
            if (step) d.texture("_Setup", setup);
            if (setup != null) d.texture("_UpNormals", setup);
            if (smaller != null) d.texture("_Smaller", smaller);
        }

        private void size(int levelCount, int fullW, int fullH, int halfW, int halfH, int quarterW, int quarterH) {
            if (levelCount == levels && fullW == w0 && fullH == h0 && halfW == hw && halfH == hh && quarterW == qw
                    && quarterH == qh) return;
            levels = levelCount;
            w0 = fullW;
            h0 = fullH;
            unsmoothed = CgGraphTexture.transientTexture("cg_ssao.unsmoothed", new CgTextureDesc(w0, h0, CgFormat.R16_FLOAT));
            filtered = CgGraphTexture.transientTexture("cg_ssao.filtered", new CgTextureDesc(w0, h0, CgFormat.R16_FLOAT));
            hw = halfW;
            hh = halfH;
            qw = quarterW;
            qh = quarterH;
            setupHalf = levels >= 2 ? CgGraphTexture.transientTexture("cg_ssao.setup_half", new CgTextureDesc(hw, hh, CgFormat.RGBA16_FLOAT)) : null;
            stepHalf = levels >= 2 ? CgGraphTexture.transientTexture("cg_ssao.half", new CgTextureDesc(hw, hh, CgFormat.R8_UNORM)) : null;
            setupQuarter = levels >= 3 ? CgGraphTexture.transientTexture("cg_ssao.setup_quarter", new CgTextureDesc(qw, qh, CgFormat.RGBA16_FLOAT)) : null;
            stepQuarter = levels >= 3 ? CgGraphTexture.transientTexture("cg_ssao.quarter", new CgTextureDesc(qw, qh, CgFormat.R8_UNORM)) : null;
        }
    }
}
