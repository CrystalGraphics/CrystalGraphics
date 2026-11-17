package com.crystalgraphics.render.scene.renderer.feature.ao;

import javax.annotation.Nullable;

import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.post.settings.CgPostSettings;
import com.crystalgraphics.render.stage.CgStageFiring;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.render.view.CgViewState;
import com.crystalgraphics.settings.CgGraphicsSettings;
import com.crystalgraphics.settings.CgQuality;
import com.crystalgraphics.trace.CgGpuTrace;

/**
 * Screen-space ambient occlusion: how much of the sky each opaque pixel sees past what is near it, which a
 * {@code DefaultLit} solid takes off its sky light (Unreal's forward {@code IndirectOcclusionTexture}). Unreal's two methods,
 * picked by the player's {@code ambient_occlusion_method}: SSAO, the default, and GTAO, ported from Intel's XeGTAO.
 *
 * <p>The scene renderer drives it; a view turns it on or off and its post settings shape it:
 *
 * <pre>{@code
 * view.showFlags().ambientOcclusion(false);                      // this view draws without it
 * view.post().settings().ambientOcclusion(1f, 3f);               // full strength, 3 m of reach in world space
 * view.post().settings().ambientOcclusionRadiusInWorldSpace(true);
 * }</pre>
 *
 * <p>It runs where the lit solids draw, after the lights: their depth first, so it sees every opaque surface, ours and
 * the host's, and only they read it. A translucent or a host's surface never does, as in Unreal.
 */
public final class CgAmbientOcclusion {

    private static final String KERNELS = "crystalgraphics:shaders/world/ambient_occlusion.compute";
    private static final String[] NAMES = {"ViewDepth", "DepthMip", "Gtao", "Denoise", "Ssao"};
    /** GTAO's view-depth levels (XeGTAO's XE_GTAO_DEPTH_MIP_LEVELS). */
    private static final int LEVELS = 5;
    /** Unreal's {@code r.GTAO.FalloffEnd}, 200 cm: GTAO's reach. */
    private static final float GTAO_RADIUS = 2f;
    /**
     * XeGTAO's defaults, Intel's calibration against a ray-traced reference of the same reach: its radius multiplier,
     * falloff range, final power, sample distribution power, depth-level offset and denoise beta. Its horizon falloff
     * counts an occluder past the falloff's start for little, which the multiplier and the power make up.
     */
    private static final float XE_RADIUS = 1.457f, XE_FALLOFF_RANGE = 0.615f, XE_FINAL_POWER = 2.2f, XE_DISTRIBUTION = 2f,
            XE_MIP_OFFSET = 3.3f, XE_BLUR_BETA = 1.2f;
    /** Unreal's {@code r.GTAO.NumAngles} (2; 4 only at Cine, which has no tier here). */
    private static final int GTAO_SLICES = 2;
    /** GTAO's taps a side by shader level ({@code GTAO_NUMTAPS}). */
    private static final int[] GTAO_TAPS = {4, 6, 8, 8, 8};
    private static final int GPU_AO = CgGpuTrace.name("world.ambientOcclusion");
    private static final CgViewState.Key<Targets> TARGETS = new CgViewState.Key<>("crystalgraphics:ambient_occlusion", Targets::new);

    private static CgKernel viewDepth, depthMip, gtao, denoise, ssao;

    private CgAmbientOcclusion() {
    }

    /** Its kernels for the precache. */
    public static void precache() {
        CgCompute c = CgCompute.load(KERNELS);
        for (String k : NAMES) c.kernel(k).precache();
    }

    /**
     * Before a view's volumes and camera blend: Unreal's {@code r.DefaultFeature.AmbientOcclusion}. With the player's
     * {@code ambient_occlusion} off the default intensity is 0, which a volume or a camera may still raise.
     */
    public static void defaults(CgPostSettings settings) {
        if (!CgGraphicsSettings.AMBIENT_OCCLUSION.get()) settings.ambientOcclusionIntensity(0f);
    }

    /** Whether {@code view} runs it this frame: its show flag, an intensity and reach, a level, a perspective. */
    public static boolean wants(CgView view, CgPostSettings settings) {
        return view.showFlags().ambientOcclusion() && settings.ambientOcclusionOn() && levels() > 0
                && !view.camera().isOrthographic();
    }

    /** Whether its kernels are built; until then a frame draws without it. */
    public static boolean ready() {
        load();
        return viewDepth.isReady() && depthMip.isReady() && gtao.isReady() && denoise.isReady() && ssao.isReady();
    }

    /**
     * This firing's occlusion, once {@code stage}'s depth holds every opaque surface: the texture the lit solids bind at
     * {@code CgBindingModel.AMBIENT_OCCLUSION}, 1 where open; null where its kernels are not built yet.
     */
    @Nullable
    public static CgGraphTexture record(CgStageFiring stage, CgRecording recording, CgView view, CgPostSettings s) {
        if (!ready()) return null;
        CgGraphTexture depth = stage.depth();
        // The render size: a host's depth, imported, does not know its own
        int w = Math.max(1, view.width()), h = Math.max(1, view.height());
        boolean gtaoMethod = Math.round(CgGraphicsSettings.AMBIENT_OCCLUSION_METHOD.get()) == 1;
        Targets t = view.state().get(TARGETS).size(w, h, gtaoMethod);
        int level = shaderLevel(s);
        float fadeRadius = Math.max(0.01f, s.ambientOcclusionFadeRadius());
        float fadeMul = 1f / fadeRadius, fadeAdd = -(s.ambientOcclusionFadeDistance() - fadeRadius) / fadeRadius;
        // The noise moves each frame only where the temporal step resolves it
        float noise = view.showFlags().temporalAA() ? view.state().frameIndex() % 64 : 0f;

        CgComputePass pass = recording.compute("world.ambientOcclusion", stage.constants()).readsDepth(depth).timed(GPU_AO);
        if (gtaoMethod) {
            float radius = GTAO_RADIUS * XE_RADIUS, range = XE_FALLOFF_RANGE;
            pass.dispatch(viewDepth, w, h, 1).image("DEPTH", t.depth[0]).set("_Size", w, h, 1f / w, 1f / h);
            for (int l = 1; l < LEVELS; l++) {
                CgGraphTexture to = t.depth[l];
                pass.dispatch(depthMip, to.getWidth(), to.getHeight(), 1).image("DEPTH", to).texture("_Prev", t.depth[l - 1])
                        .set("_Gtao", radius, range, GTAO_SLICES, GTAO_TAPS[level]);
            }
            pass.dispatch(gtao, w, h, 1).image("WORKING", t.working).texture("_Depth0", t.depth[0])
                    .texture("_Depth1", t.depth[1]).texture("_Depth2", t.depth[2]).texture("_Depth3", t.depth[3])
                    .texture("_Depth4", t.depth[4]).set("_Size", w, h, 1f / w, 1f / h)
                    .set("_Gtao", radius, range, GTAO_SLICES, GTAO_TAPS[level])
                    .set("_Gtao2", XE_FINAL_POWER, XE_DISTRIBUTION, XE_MIP_OFFSET, XE_BLUR_BETA).set("_Noise", noise, 0f, 0f, 0f);
            pass.dispatch(denoise, w, h, 1).image("AO", t.ao).texture("_Working", t.working).texture("_Depth0", t.depth[0])
                    .set("_Size", w, h, 1f / w, 1f / h).set("_Gtao2", XE_FINAL_POWER, XE_DISTRIBUTION, XE_MIP_OFFSET, XE_BLUR_BETA)
                    .set("_Output", s.ambientOcclusionIntensity(), s.ambientOcclusionPower(), fadeMul, fadeAdd);
        } else {
            boolean world = s.ambientOcclusionRadiusInWorldSpace();
            // Unreal's radius off world space is a view-space 400 units a screen, a quarter of that per its mip scale:
            // 200, an eighth of the screen's height. Ours is metres, 2 the default
            float radius = world ? s.ambientOcclusionRadius() : s.ambientOcclusionRadius() / 16f;
            pass.dispatch(ssao, w, h, 1).image("AO", t.ao).set("_Size", w, h, 1f / w, 1f / h)
                    .set("_Ssao", radius, world ? 1f : 0f, s.ambientOcclusionBias() / 1000f, level).set("_Noise", noise, 0f, 0f, 0f)
                    .set("_Output", s.ambientOcclusionIntensity(), s.ambientOcclusionPower(), fadeMul, fadeAdd);
        }
        pass.end();
        return t.ao;
    }

    /**
     * Unreal's {@code r.AmbientOcclusionLevels} on forward, 0 (off) or 1: the player's {@code ambient_occlusion_levels},
     * else its Quality tier's ({@code BaseScalability.ini}: Low 0, the rest 1).
     */
    private static int levels() {
        int set = Math.round(CgGraphicsSettings.AMBIENT_OCCLUSION_LEVELS.get());
        if (set >= 0) return Math.min(set, 1);
        return CgGraphicsSettings.QUALITY.get() == CgQuality.LOW ? 0 : 1;
    }

    /**
     * Unreal's shader level, 0 to 4, from the settings' quality under {@code r.AmbientOcclusionMaxQuality}: the player's
     * {@code ambient_occlusion_max_quality}, else its tier's (Low 0, Medium 60, High and Ultra 100).
     */
    private static int shaderLevel(CgPostSettings s) {
        float set = CgGraphicsSettings.AMBIENT_OCCLUSION_MAX_QUALITY.get();
        float max = set >= 0f ? set : switch (CgGraphicsSettings.QUALITY.get()) {
            case LOW -> 0f;
            case MEDIUM -> 60f;
            case HIGH, ULTRA -> 100f;
        };
        float q = Math.min(max, s.ambientOcclusionQuality());
        return (q > 75f ? 1 : 0) + (q > 55f ? 1 : 0) + (q > 25f ? 1 : 0) + (q > 5f ? 1 : 0);
    }

    private static void load() {
        if (viewDepth != null) return;
        CgCompute c = CgCompute.load(KERNELS);
        viewDepth = c.kernel("ViewDepth");
        depthMip = c.kernel("DepthMip");
        gtao = c.kernel("Gtao");
        denoise = c.kernel("Denoise");
        ssao = c.kernel("Ssao");
        for (CgKernel k : new CgKernel[] {viewDepth, depthMip, gtao, denoise, ssao}) k.precache();
    }

    /** A view's textures, remade when its size or method changes. */
    private static final class Targets {
        final CgGraphTexture[] depth = new CgGraphTexture[LEVELS];
        CgGraphTexture working, ao;
        int w, h;
        boolean gtao;

        Targets size(int width, int height, boolean method) {
            if (ao != null && width == w && height == h && method == gtao) return this;
            w = width;
            h = height;
            gtao = method;
            ao = CgGraphTexture.transientTexture("cg_ambient_occlusion", new CgTextureDesc(w, h, CgFormat.R16_FLOAT));
            if (method) {
                for (int l = 0; l < LEVELS; l++) {
                    depth[l] = CgGraphTexture.transientTexture("cg_ambient_occlusion.depth" + l,
                            new CgTextureDesc(Math.max(1, w >> l), Math.max(1, h >> l), CgFormat.R32_FLOAT));
                }
                working = CgGraphTexture.transientTexture("cg_ambient_occlusion.working", new CgTextureDesc(w, h, CgFormat.RG16_FLOAT));
            }
            return this;
        }
    }
}
