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
import com.crystalgraphics.variable.CgVar;

/**
 * Ground-truth ambient occlusion, {@code ambient_occlusion_method} 1 (Jimenez et al. 2016): a horizon search through the
 * scene's furthest depth pyramid, a spatial filter, a temporal filter into the view's own history, and an upsample to
 * the render size. {@link CgAmbientOcclusion} runs it, once the depth holds every solid and the solids' velocity is
 * written.
 *
 * <ul>
 *   <li>The search runs at the render size, or half it under {@code gtao_downsample}.</li>
 *   <li>Its two directions turn every frame through a fixed 6 x 4 cycle of rotations and step offsets, which the
 *       temporal filter averages over about ten frames: with or without temporal anti-aliasing.</li>
 *   <li>The history reprojects by the velocity pass's target where it wrote, else by the camera over the depth, and is
 *       held within 0.1 of this frame's value while still, closing to none as the pixel moves.</li>
 *   <li>Power and intensity apply in the spatial filter, at half the power: the integral is cosine-weighted.</li>
 * </ul>
 *
 * <p>The kernels are {@code world/ao/gtao.compute}.</p>
 */
final class CgGTAO {

    /** The spatial filter; off, the search's occlusion goes on unfiltered, its power and intensity unapplied. */
    static final CgVar.Bool SPATIAL_FILTER = CgVar.bool("crystalgraphics.gtao.spatialFilter", true, CgVar.DEV,
            "GTAO's spatial filter; off, the search's occlusion goes on unfiltered, without its power and intensity.");
    /**
     * The prepass's normals where it wrote them, else normals from depth; off, depth alone, which shows a mesh's
     * triangles (Unreal's {@code r.GTAO.UseNormals}, there off by default).
     */
    static final CgVar.Bool USE_NORMALS = CgVar.bool("crystalgraphics.gtao.useNormals", true, CgVar.DEV,
            "GTAO reads the prepass's normals where it wrote them, else normals from depth; off, depth alone, which shows a"
                    + " low-poly mesh's triangles.");
    /** The temporal filter; off, each frame's occlusion stands alone, its noise unaveraged. */
    static final CgVar.Bool TEMPORAL_FILTER = CgVar.bool("crystalgraphics.gtao.temporalFilter", true, CgVar.DEV,
            "GTAO's temporal filter; off, each frame's occlusion stands alone, its noise unaveraged.");

    private static final String KERNELS = "crystalgraphics:shaders/world/ao/gtao.compute";
    private static final String[] NAMES = {"Horizon", "Spatial", "Temporal", "Upsample"};
    private static final int HORIZON = 0, SPATIAL = 1, TEMPORAL = 2, UPSAMPLE = 3;
    /** The spatial filter's group: a 16 x 8 tile. */
    private static final int SPATIAL_W = 16, SPATIAL_H = 8;
    /** The search's reach in the world, metres: an occluder past it counts for nothing. */
    private static final float RADIUS = 2f;
    /** How far a nearer, lower horizon pulls the best one down, 0 to 1: the shader takes 1 - b^2. */
    private static final float THICKNESS_BLEND = 0.5f;
    /** Directions a pixel searches, each both ways. */
    private static final int ANGLES = 2;
    /** Taps a direction, and the pyramid level the first reads (0 is half the render size), by shader level 0 to 4. */
    private static final int[] TAPS = {4, 6, 8, 12, 20}, BIAS_LEVEL = {2, 1, 0, 0, 0};
    /** The most render pixels a search reaches. */
    private static final float MAX_PIXEL_RADIUS = 256f;
    /** The frame cycle: six rotations, degrees, then four step offsets. */
    private static final float[] ROTATIONS = {60f, 300f, 180f, 240f, 120f, 0f}, OFFSETS = {0.1f, 0.6f, 0.35f, 0.85f};
    private static final CgViewState.HistoryKey HISTORY = new CgViewState.HistoryKey("crystalgraphics:gtao");
    private static final CgViewState.Key<Targets> TARGETS = new CgViewState.Key<>("crystalgraphics:ambient_occlusion.gtao", Targets::new);

    private static CgKernel[] kernels;

    private CgGTAO() {
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
     * Records the search, the filters and the upsample at shader {@code level} (0-4) into {@code ao},
     * {@code w} by {@code h}, from the scene's furthest depth {@code pyramid} and {@code normals}, the prepass's (null: depth's
     * alone); {@code fadeMul} and {@code fadeAdd} fade it out over eye depth.
     */
    static void record(CgComputePass pass, CgRecording recording, CgView view, CgPostSettings s, int level,
                       CgGraphTexture pyramid, @Nullable CgGraphTexture normals, CgGraphTexture ao, int w, int h,
                       float fadeMul, float fadeAdd) {
        load();
        int aw = aoWidth(w), ah = aoHeight(h);
        Targets t = view.state().get(TARGETS).size(w, h, aw, ah);
        CgViewState state = view.state();
        CgGraphTexture history = state.history(HISTORY, new CgTextureDesc(aw, ah, CgFormat.R8_UNORM), recording);
        boolean valid = !state.fresh(HISTORY) && view.historyValid();

        int frame = state.frameIndex();
        double rotation = Math.toRadians(ROTATIONS[frame % 6]) * 0.5;
        float offset = OFFSETS[(frame / 6) % 4] * 0.25f;
        double delta = Math.PI / ANGLES;
        float thickness = Math.max(0f, Math.min(0.99f, 1f - THICKNESS_BLEND * THICKNESS_BLEND));
        int taps = TAPS[Math.max(0, Math.min(4, level))], bias = BIAS_LEVEL[Math.max(0, Math.min(4, level))];

        CgDispatch horizon = common(pass.dispatch(kernels[HORIZON], aw, ah, 1), w, h, aw, ah, fadeMul, fadeAdd, s);
        if (normals != null && USE_NORMALS.get()) {
            horizon.texture("_Normals", normals).set("_Fade", fadeMul, fadeAdd, s.ambientOcclusionFadeDistance(), 1f);
        }
        horizon.image("HORIZON", t.horizon).texture("_Pyramid", pyramid)
                .set("_Search", RADIUS, thickness, taps, ANGLES)
                .set("_Search2", bias, MAX_PIXEL_RADIUS, (float) Math.sin(delta), (float) Math.cos(delta))
                .set("_Frame", (float) Math.cos(rotation), (float) Math.sin(rotation), offset, 0f);
        CgGraphTexture filtered = t.horizon;
        if (SPATIAL_FILTER.get()) {
            common(pass.dispatchGroups(kernels[SPATIAL], ceilDiv(aw, SPATIAL_W), ceilDiv(ah, SPATIAL_H), 1), w, h, aw, ah,
                    fadeMul, fadeAdd, s).image("FILTERED", t.filtered).texture("_Horizon", t.horizon);
            filtered = t.filtered;
        }
        CgGraphTexture resolved = filtered;
        if (TEMPORAL_FILTER.get()) {
            CgDispatch temporal = common(pass.dispatch(kernels[TEMPORAL], aw, ah, 1), w, h, aw, ah, fadeMul, fadeAdd, s)
                    .texture("_Filtered", filtered).texture("_History", history.previous()).image("HISTORY", history)
                    .set("_Output", s.ambientOcclusionIntensity(), s.ambientOcclusionPower(),
                            s.ambientOcclusionTemporalBlendWeight(), valid ? 1f : 0f);
            CgGraphTexture velocity = view.textures().velocity();
            if (velocity != null) temporal.texture("_Velocity", velocity);
            resolved = history;
        }
        common(pass.dispatch(kernels[UPSAMPLE], w, h, 1), w, h, aw, ah, fadeMul, fadeAdd, s).image("AO", ao)
                .texture("_Resolved", resolved);
    }

    private static CgDispatch common(CgDispatch d, int w, int h, int aw, int ah, float fadeMul, float fadeAdd, CgPostSettings s) {
        return d.set("_Size", w, h, 1f / w, 1f / h).set("_AoSize", aw, ah, 1f / aw, 1f / ah)
                .set("_Fade", fadeMul, fadeAdd, s.ambientOcclusionFadeDistance(), 0f)
                .set("_Output", s.ambientOcclusionIntensity(), s.ambientOcclusionPower(), s.ambientOcclusionTemporalBlendWeight(), 0f);
    }

    private static int aoWidth(int w) {
        return CgGraphicsSettings.GTAO_DOWNSAMPLE.get() ? Math.max(1, (w + 1) / 2) : w;
    }

    private static int aoHeight(int h) {
        return CgGraphicsSettings.GTAO_DOWNSAMPLE.get() ? Math.max(1, (h + 1) / 2) : h;
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
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

    /** A view's AO-size textures, remade when its size changes. */
    private static final class Targets {
        @Nullable
        CgGraphTexture horizon, filtered;
        int w, h, aw, ah;

        Targets size(int width, int height, int aoWidth, int aoHeight) {
            if (horizon != null && width == w && height == h && aoWidth == aw && aoHeight == ah) return this;
            w = width;
            h = height;
            aw = aoWidth;
            ah = aoHeight;
            horizon = CgGraphTexture.transientTexture("cg_gtao.horizon", new CgTextureDesc(aw, ah, CgFormat.R8_UNORM));
            filtered = CgGraphTexture.transientTexture("cg_gtao.filtered", new CgTextureDesc(aw, ah, CgFormat.R8_UNORM));
            return this;
        }
    }
}
