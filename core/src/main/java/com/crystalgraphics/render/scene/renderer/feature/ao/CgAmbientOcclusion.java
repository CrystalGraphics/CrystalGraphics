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
import com.crystalgraphics.render.view.CgViewKey;
import com.crystalgraphics.render.view.CgViewState;
import com.crystalgraphics.settings.CgGraphicsSettings;
import com.crystalgraphics.settings.CgQuality;
import com.crystalgraphics.settings.CgSetting;
import com.crystalgraphics.trace.CgGpuTrace;

/**
 * Screen-space ambient occlusion: how much of the sky each opaque pixel sees past what is near it, which a
 * {@code DefaultLit} solid takes off its sky light (Unreal's forward {@code IndirectOcclusionTexture}). Unreal's two methods,
 * picked by the player's {@code ambient_occlusion_method}: SSAO, and GTAO ({@link CgGTAO}) with its spatial and temporal
 * filters; and a third, AMD's FidelityFX CACAO ({@link CgCACAO}), the default, downsampled at its Highest preset.
 *
 * <p>The scene renderer drives it; a view turns it on or off and its post settings shape it:
 *
 * <pre>{@code
 * view.showFlags().ambientOcclusion(false);                      // this view draws without it
 * view.post().settings().ambientOcclusion(1f, 3f);               // full strength, 3 m of reach in world space
 * view.post().settings().ambientOcclusionRadiusInWorldSpace(true);
 * }</pre>
 *
 * <p>It runs once the all-opaque prepass holds every solid's depth, ours and the host's, and the solids' velocity is
 * written, before any solid shades: the base pass and the lit solids read it. A translucent or a host's surface never
 * does, as in Unreal.
 */
public final class CgAmbientOcclusion {

    private static final String KERNELS = "crystalgraphics:shaders/world/ao/ambient_occlusion.compute";
    private static final int GPU_AO = CgGpuTrace.name("world.ambientOcclusion");
    /** This frame's occlusion, published for {@code crystalgraphics.post.debug=ao}; absent where none ran. */
    public static final CgViewKey<CgGraphTexture> RESULT = new CgViewKey<>("crystalgraphics:ambient_occlusion");
    private static final CgViewState.Key<Targets> TARGETS = new CgViewState.Key<>("crystalgraphics:ambient_occlusion", Targets::new);

    private static CgKernel ssao;

    private CgAmbientOcclusion() {
    }

    /** Its kernels for the precache. */
    public static void precache() {
        CgCompute.load(KERNELS).kernel("Ssao").precache();
        CgGTAO.precache();
        CgCACAO.precache();
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

    /** Whether {@code view} runs GTAO on the prepass's normals this frame: they are then written beside its depth. */
    public static boolean wantsNormals(CgView view, CgPostSettings settings) {
        return wants(view, settings) && Math.round(CgGraphicsSettings.AMBIENT_OCCLUSION_METHOD.get()) == 1
                && CgGTAO.USE_NORMALS.get();
    }

    /** Whether its kernels are built; until then a frame draws without it. */
    public static boolean ready() {
        load();
        return ssao.isReady() && CgGTAO.ready() && CgCACAO.ready();
    }

    /**
     * This firing's occlusion, once {@code stage}'s depth holds every opaque surface and the solids' velocity is
     * written, GTAO reading {@code normals} where the prepass wrote them (null: none): the texture the solids bind at
     * {@code CgBindingModel.AMBIENT_OCCLUSION}, 1 where open, published as {@link #RESULT} for the rest of the frame;
     * null where its kernels are not built yet.
     */
    @Nullable
    public static CgGraphTexture record(CgStageFiring stage, CgRecording recording, CgView view, CgPostSettings s,
                                        @Nullable CgGraphTexture normals) {
        if (!ready()) return null;
        CgGraphTexture depth = stage.depth();
        // The render size: a host's depth, imported, does not know its own
        int w = Math.max(1, view.width()), h = Math.max(1, view.height());
        int method = Math.round(CgGraphicsSettings.AMBIENT_OCCLUSION_METHOD.get());
        Targets t = view.state().get(TARGETS).size(recording, w, h);
        int level = shaderLevel(s);
        float fadeRadius = Math.max(0.01f, s.ambientOcclusionFadeRadius());
        float fadeMul = 1f / fadeRadius, fadeAdd = -(s.ambientOcclusionFadeDistance() - fadeRadius) / fadeRadius;
        // SSAO's noise moves each frame only where the temporal step resolves it
        float noise = view.showFlags().temporalAA() ? view.state().frameIndex() % 64 : 0f;

        CgGraphTexture pyramid = method == 1 ? CgGTAO.pyramid(recording, depth, stage.constants(), view, w, h) : null;
        CgComputePass pass = recording.compute("world.ambientOcclusion", stage.constants()).readsDepth(depth).timed(GPU_AO);
        if (pyramid != null) {
            CgGTAO.record(pass, recording, view, s, level, pyramid, normals, t.ao, w, h, fadeMul, fadeAdd);
        } else if (method == 2) {
            CgCACAO.record(pass, view, s, level, t.ao, w, h, view.showFlags().temporalAA() ? view.state().frameIndex() : -1);
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
        view.publish(RESULT, t.ao);
        return t.ao;
    }

    /** The player's toggle for running the method in use smaller: CACAO's or GTAO's; null for SSAO, which has none. */
    @Nullable
    public static CgSetting.Toggle downsampleSetting() {
        return switch (Math.round(CgGraphicsSettings.AMBIENT_OCCLUSION_METHOD.get())) {
            case 1 -> CgGraphicsSettings.GTAO_DOWNSAMPLE;
            case 2 -> CgGraphicsSettings.AMBIENT_OCCLUSION_DOWNSAMPLE;
            default -> null;
        };
    }

    /** The player's method by name: SSAO, GTAO or CACAO. */
    public static String methodName() {
        return switch (Math.round(CgGraphicsSettings.AMBIENT_OCCLUSION_METHOD.get())) {
            case 1 -> "GTAO";
            case 2 -> "CACAO";
            default -> "SSAO";
        };
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

    /** Unreal's shader levels by name, 0 to 4: CACAO's presets of the same rank. */
    public static final String[] LEVEL_NAMES = {"Lowest", "Low", "Medium", "High", "Highest"};
    /** A post settings quality that lands on each shader level, 0 to 4 (r.AmbientOcclusionQuality's steps). */
    public static final float[] LEVEL_QUALITIES = {0f, 15f, 40f, 65f, 100f};

    /**
     * Unreal's shader level, 0 to 4, from the settings' quality under {@code r.AmbientOcclusionMaxQuality}: the player's
     * {@code ambient_occlusion_max_quality}, else its tier's (Low 0, Medium 60, High and Ultra 100).
     */
    public static int shaderLevel(CgPostSettings s) {
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
        if (ssao != null) return;
        ssao = CgCompute.load(KERNELS).kernel("Ssao");
        ssao.precache();
    }

    /**
     * A view's occlusion, remade when its size changes. Kept, not transient: what reads it may record in a later firing
     * than the one it runs in (Unreal's {@code ScreenSpaceAO} lives the frame's length).
     */
    private static final class Targets {
        CgGraphTexture ao;
        int w, h;

        Targets size(CgRecording recording, int width, int height) {
            if (ao != null && width == w && height == h) return this;
            if (ao != null) recording.release(ao);
            w = width;
            h = height;
            ao = CgGraphTexture.requested("cg_ambient_occlusion", new CgTextureDesc(w, h, CgFormat.R16_FLOAT));
            return this;
        }
    }
}
