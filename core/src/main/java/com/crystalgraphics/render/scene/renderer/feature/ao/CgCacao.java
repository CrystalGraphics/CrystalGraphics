package com.crystalgraphics.render.scene.renderer.feature.ao;

import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgDispatch;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.post.settings.CgPostSettings;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.render.view.CgViewState;

/**
 * SSAO, Unreal's {@code r.AmbientOcclusion.Method 0}, as AMD's FidelityFX CACAO: the scene's depth deinterleaved into
 * four half-size layers, each layer's occlusion generated and blurred along its edges, then applied back to full size.
 * {@link CgAmbientOcclusion} runs it.
 *
 * <p>Ported from FidelityFX CACAO 1.x (MIT; Copyright (c) 2016 Intel Corporation, modifications Copyright (c) 2021
 * Advanced Micro Devices, Inc.): {@code ffx_cacao.cpp}'s {@code FFX_CACAO_UpdateConstants} and
 * {@code FFX_CACAO_UpdatePerPassConstants}, {@code ffx_cacao_impl.cpp}'s dispatch order, and the sample's native
 * presets. The kernels are {@code world/ao/cacao.compute}; {@code THIRD-PARTY.md} holds the notice.</p>
 */
final class CgCacao {

    /** Its kernels, in two files since a file binds 8 images at most: the first {@link #PREPARE_KERNELS} are the prepare's. */
    private static final String KERNELS = "crystalgraphics:shaders/world/ao/cacao.compute",
            PREPARE = "crystalgraphics:shaders/world/ao/cacao_prepare.compute";
    private static final int PREPARE_KERNELS = 4;
    private static final String[] NAMES = {"PrepareDepths", "PrepareDepthsHalf", "DepthMip", "PrepareNormals", "GenerateQ0",
            "GenerateQ1", "GenerateQ2", "GenerateQ3Base", "GenerateQ3", "ImportanceMap", "ImportanceA", "ImportanceB",
            "ClearLoadCounter", "Blur", "Apply", "NonSmartApply", "NonSmartHalfApply"};
    private static final int PREPARE_DEPTHS = 0, PREPARE_DEPTHS_HALF = 1, DEPTH_MIP = 2, PREPARE_NORMALS = 3, GENERATE_Q0 = 4,
            GENERATE_Q3_BASE = 7, GENERATE_Q3 = 8, IMPORTANCE_MAP = 9, IMPORTANCE_A = 10, IMPORTANCE_B = 11, CLEAR_LOAD_COUNTER = 12,
            BLUR = 13, APPLY = 14, NON_SMART_APPLY = 15, NON_SMART_HALF_APPLY = 16;
    /** FFX_CACAO_Quality, LOWEST to HIGHEST: Unreal's shader levels 0 to 4. */
    private static final int LOWEST = 0, LOW = 1, MEDIUM = 2, HIGHEST = 4;
    /** Blur passes by quality: the sample's native presets. */
    private static final int[] BLUR_PASSES = {6, 6, 2, 2, 2};
    /** FFX_CACAO_DEFAULT_SETTINGS, for what Unreal's settings do not name. */
    private static final float SHADOW_MULTIPLIER = 1f, SHADOW_CLAMP = 0.98f, HORIZON_ANGLE_THRESHOLD = 0.06f,
            ADAPTIVE_QUALITY_LIMIT = 0.45f, SHARPNESS = 0.98f, DETAIL_SHADOW_STRENGTH = 0.5f, DEPTH_PRECISION_OFFSET = 0.9992f;
    /**
     * Unreal's radius onto AMD's calibration: its default 2 m lands on CACAO's default 1.2 m. CACAO's falloff counts
     * an occluder at full weight to near its radius, so at Unreal's reach a figure standing a metre off the floor
     * shadows it in streaks. XeGTAO's radius multiplier does the same for GTAO.
     */
    private static final float RADIUS_SCALE = 0.6f;
    /** The depth levels CACAO keeps (its mips 0 to 3). */
    private static final int MIPS = 4;
    /** FFX_CACAO_BLUR_WIDTH / HEIGHT: a blur group's threads; each covers a 4x3 tile. */
    private static final int BLUR_WIDTH = 16, BLUR_HEIGHT = 16;
    private static final String[] ROTATIONS = {"_Rot0", "_Rot1", "_Rot2", "_Rot3", "_Rot4"};
    /** UpdatePerPassConstants' sub-pass order. */
    private static final int[] SUB_PASS_MAP = {0, 1, 4, 3, 2};
    private static final CgViewState.Key<Targets> TARGETS = new CgViewState.Key<>("crystalgraphics:ambient_occlusion.cacao", Targets::new);

    private static CgKernel[] kernels;

    private CgCacao() {
    }

    static void precache() {
        for (int i = 0; i < NAMES.length; i++) kernel(i).precache();
    }

    private static CgKernel kernel(int i) {
        return CgCompute.load(i < PREPARE_KERNELS ? PREPARE : KERNELS).kernel(NAMES[i]);
    }

    static boolean ready() {
        load();
        for (CgKernel k : kernels) if (!k.isReady()) return false;
        return true;
    }

    /** Records CACAO at Unreal's shader {@code level} (0-4) into {@code ao}, {@code w} by {@code h}. */
    static void record(CgComputePass pass, CgView view, CgPostSettings s, int level, CgGraphTexture ao, int w, int h) {
        load();
        int quality = Math.max(LOWEST, Math.min(HIGHEST, level));
        Consts c = new Consts(view, s, quality, w, h);
        Targets t = view.state().get(TARGETS).size(c.hw, c.hh, c.iw, c.ih);
        // Only Q2 and up sample the depth levels (FFX_CACAO_DEPTH_MIPS_ENABLE_AT_QUALITY_PRESET)
        boolean half = quality == LOWEST, mips = quality > MEDIUM;

        // Depths, a layer each (the lowest quality's two diagonal layers alone), their levels, and normals
        CgDispatch d = c.prepare(pass.dispatch(kernels[half ? PREPARE_DEPTHS_HALF : PREPARE_DEPTHS], c.hw, c.hh, 1));
        d.image("DEPTH0", t.depth[0][0]).image("DEPTH3", t.depth[3][0]);
        if (!half) d.image("DEPTH1", t.depth[1][0]).image("DEPTH2", t.depth[2][0]);
        if (mips) {
            for (int layer = 0; layer < 4; layer++) {
                for (int l = 1; l < MIPS; l++) {
                    CgGraphTexture to = t.depth[layer][l];
                    c.prepare(pass.dispatch(kernels[DEPTH_MIP], to.getWidth(), to.getHeight(), 1)).image("DEPTH0", to)
                            .texture("_Prev", t.depth[layer][l - 1]);
                }
            }
        }
        c.prepare(pass.dispatch(kernels[PREPARE_NORMALS], c.hw, c.hh, 1)).image("NORMAL0", t.normals[0])
                .image("NORMAL1", t.normals[1]).image("NORMAL2", t.normals[2]).image("NORMAL3", t.normals[3]);

        // Generation; at the highest, the adaptive base, its importance map and load, then the adaptive pass over it
        CgGraphTexture[] generated = t.ping, blurred = t.pong;
        if (quality == HIGHEST) {
            for (int p = 0; p < 4; p++) {
                c.layer(pass.dispatch(kernels[GENERATE_Q3_BASE], c.hw, c.hh, 1), t, p, mips).image("SSAO", t.ping[p])
                        .bind("LOAD_COUNTER", t.loadCounter);
            }
            c.common(pass.dispatch(kernels[IMPORTANCE_MAP], c.iw, c.ih, 1)).image("IMPORTANCE", t.importance)
                    .texture("_Ssao0", t.ping[0]).texture("_Ssao1", t.ping[1]).texture("_Ssao2", t.ping[2]).texture("_Ssao3", t.ping[3]);
            c.common(pass.dispatch(kernels[IMPORTANCE_A], c.iw, c.ih, 1)).image("IMPORTANCE", t.importancePong)
                    .texture("_Importance", t.importance);
            pass.dispatch(kernels[CLEAR_LOAD_COUNTER], 1).bind("LOAD_COUNTER", t.loadCounter);
            c.common(pass.dispatch(kernels[IMPORTANCE_B], c.iw, c.ih, 1)).image("IMPORTANCE", t.importance)
                    .texture("_Importance", t.importancePong).bind("LOAD_COUNTER", t.loadCounter);
            for (int p = 0; p < 4; p++) {
                c.layer(pass.dispatch(kernels[GENERATE_Q3], c.hw, c.hh, 1), t, p, mips).image("SSAO", t.pong[p])
                        .texture("_Base", t.ping[p]).texture("_Importance", t.importance).bind("LOAD_COUNTER", t.loadCounter);
            }
            generated = t.pong;
            blurred = t.ping;
        } else {
            int generate = GENERATE_Q0 + Math.max(0, quality - 1);
            for (int p = 0; p < 4; p++) {
                if (half && (p == 1 || p == 2)) continue;
                // Every generate kernel names the counter: they share one body, whose adaptive branch reads it
                c.layer(pass.dispatch(kernels[generate], c.hw, c.hh, 1), t, p, mips).image("SSAO", t.ping[p])
                        .bind("LOAD_COUNTER", t.loadCounter);
            }
        }

        // The edge-sensitive blur, a layer at a time; a group's tiles overlap their neighbours' by the passes it runs
        int groupsX = ceilDiv(c.hw, 4 * BLUR_WIDTH - 2 * c.blurPasses), groupsY = ceilDiv(c.hh, 3 * BLUR_HEIGHT - 2 * c.blurPasses);
        for (int p = 0; p < 4; p++) {
            if (half && (p == 1 || p == 2)) continue;
            c.layer(pass.dispatchGroups(kernels[BLUR], groupsX, groupsY, 1), t, p, mips).image("SSAO", blurred[p])
                    .texture("_Ssao0", generated[p]);
        }

        // Back to full size; the lowest quality's two layers stand in for the two it never made
        int apply = half ? NON_SMART_HALF_APPLY : quality == LOW ? NON_SMART_APPLY : APPLY;
        c.common(pass.dispatch(kernels[apply], w, h, 1)).image("AO", ao).texture("_Ssao0", blurred[0])
                .texture("_Ssao1", blurred[half ? 0 : 1]).texture("_Ssao2", blurred[half ? 3 : 2]).texture("_Ssao3", blurred[3]);
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static void load() {
        if (kernels != null) return;
        CgKernel[] k = new CgKernel[NAMES.length];
        for (int i = 0; i < NAMES.length; i++) {
            k[i] = kernel(i);
            k[i].precache();
        }
        kernels = k;
    }

    /** FFX_CACAO_UpdateConstants, from Unreal's settings and AMD's defaults. */
    private static final class Consts {
        final int w, h, hw, hh, iw, ih, blurPasses;
        final float radius, strength, power, fadeMul, fadeAdd, nearLimit, loadDiv, intensity;

        Consts(CgView view, CgPostSettings s, int quality, int w, int h) {
            this.w = w;
            this.h = h;
            hw = (w + 1) / 2;
            hh = (h + 1) / 2;
            iw = (hw + 1) / 2;
            ih = (hh + 1) / 2;
            blurPasses = BLUR_PASSES[quality];
            float tanHalfFovY = 1f / Math.abs(view.camera().projection().m11());
            float r = Math.max(0f, s.ambientOcclusionRadius()) * RADIUS_SCALE, near = r * 1.2f;
            if (quality <= LOW) {
                near *= 1.5f;
                if (quality == LOWEST) r *= 0.8f;
            }
            radius = r;
            nearLimit = near / tanHalfFovY;
            // Unreal's fade: full to the fade distance less its radius, none past the distance
            float fadeTo = Math.max(1e-3f, s.ambientOcclusionFadeDistance());
            float fadeFrom = Math.max(0f, fadeTo - Math.max(0.01f, s.ambientOcclusionFadeRadius()));
            fadeMul = -1f / Math.max(1e-3f, fadeTo - fadeFrom);
            fadeAdd = fadeFrom / Math.max(1e-3f, fadeTo - fadeFrom) + 1f;
            strength = clamp(SHADOW_MULTIPLIER * 4.3f, 0f, 10f);
            power = clamp(s.ambientOcclusionPower(), 0f, 10f);
            // The load counter sums every ninth importance texel, 0-255 each
            loadDiv = 9f / (iw * ih * 255f);
            intensity = s.ambientOcclusionIntensity();
        }

        /** The prepare file's two values. */
        CgDispatch prepare(CgDispatch d) {
            return d.set("_Size", w, h, 1f / w, 1f / h).set("_Effect", radius, strength, power, clamp(SHADOW_CLAMP, 0f, 1f));
        }

        CgDispatch common(CgDispatch d) {
            return d.set("_Size", w, h, 1f / w, 1f / h).set("_SsaoSize", hw, hh, 1f / hw, 1f / hh)
                    .set("_ImportanceSize", iw, ih, 1f / iw, 1f / ih)
                    .set("_Effect", radius, strength, power, clamp(SHADOW_CLAMP, 0f, 1f))
                    .set("_Effect2", fadeMul, fadeAdd, clamp(HORIZON_ANGLE_THRESHOLD, 0f, 1f), 1f / Math.max(1e-4f, nearLimit))
                    .set("_Effect3", DEPTH_PRECISION_OFFSET, -1f / Math.max(1e-4f, radius), loadDiv, ADAPTIVE_QUALITY_LIMIT)
                    .set("_Effect4", clamp(1f - SHARPNESS, 0f, 1f), DETAIL_SHADOW_STRENGTH, 0f, intensity);
        }

        /** {@link #common} and a layer's own: its depth levels, normals, offset and sample rotations (UpdatePerPassConstants). */
        CgDispatch layer(CgDispatch d, Targets t, int p, boolean mips) {
            common(d).set("_Effect4", clamp(1f - SHARPNESS, 0f, 1f), DETAIL_SHADOW_STRENGTH, p, intensity)
                    .set("_PassOffset", (p % 2) / (float) hw, (p / 2) / (float) hh, blurPasses, 0f)
                    .texture("_Depth", t.depth[p][0]).texture("_Normals", t.normals[p]);
            // Below the qualities that sample levels, level 0 stands in for the levels never made
            for (int l = 1; l < MIPS; l++) d.texture("_DepthMip" + l, t.depth[p][mips ? l : 0]);
            for (int sub = 0; sub < 5; sub++) {
                float a = p, b = SUB_PASS_MAP[sub];
                // CACAO 1.x reads temporal angle and radius offsets in its settings and applies neither
                float angle = (a + b / 5f) * (float) Math.PI * 0.5f;
                float ca = (float) Math.cos(angle), sa = (float) Math.sin(angle);
                float scale = 1f + (a - 1.5f + (b - 2f) / 5f) * 0.07f;
                d.set(ROTATIONS[sub], scale * ca, scale * -sa, -scale * sa, -scale * ca);
            }
            return d;
        }
    }

    /** A view's CACAO textures, remade when its size changes, and its load counter. */
    private static final class Targets {
        final CgGraphTexture[][] depth = new CgGraphTexture[4][MIPS];
        final CgGraphTexture[] normals = new CgGraphTexture[4], ping = new CgGraphTexture[4], pong = new CgGraphTexture[4];
        final CgGraphBuffer loadCounter = CgGraphBuffer.persistent("cg_ambient_occlusion.cacao.load",
                CgBufferDesc.of(16, CgBufferUsage.STORAGE));
        CgGraphTexture importance, importancePong;
        int hw, hh;

        Targets size(int halfW, int halfH, int impW, int impH) {
            if (importance != null && halfW == hw && halfH == hh) return this;
            hw = halfW;
            hh = halfH;
            for (int layer = 0; layer < 4; layer++) {
                for (int l = 0; l < MIPS; l++) {
                    depth[layer][l] = CgGraphTexture.transientTexture("cg_ambient_occlusion.cacao.depth" + layer + "." + l,
                            new CgTextureDesc(Math.max(1, hw >> l), Math.max(1, hh >> l), CgFormat.R32_FLOAT));
                }
                normals[layer] = CgGraphTexture.transientTexture("cg_ambient_occlusion.cacao.normals" + layer,
                        new CgTextureDesc(hw, hh, CgFormat.RGBA16_FLOAT));
                ping[layer] = CgGraphTexture.transientTexture("cg_ambient_occlusion.cacao.ping" + layer, new CgTextureDesc(hw, hh, CgFormat.RG16_FLOAT));
                pong[layer] = CgGraphTexture.transientTexture("cg_ambient_occlusion.cacao.pong" + layer, new CgTextureDesc(hw, hh, CgFormat.RG16_FLOAT));
            }
            importance = CgGraphTexture.transientTexture("cg_ambient_occlusion.cacao.importance", new CgTextureDesc(impW, impH, CgFormat.R16_FLOAT));
            importancePong = CgGraphTexture.transientTexture("cg_ambient_occlusion.cacao.importancePong", new CgTextureDesc(impW, impH, CgFormat.R16_FLOAT));
            return this;
        }
    }
}
