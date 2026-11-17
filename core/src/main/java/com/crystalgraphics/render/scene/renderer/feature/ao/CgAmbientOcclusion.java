package com.crystalgraphics.render.scene.renderer.feature.ao;

import javax.annotation.Nullable;

import com.crystalgraphics.compute.ops.CgGpuOps;
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
 * picked by the player's {@code ambient_occlusion_method}: SSAO ({@link CgSSAO}) at up to three sizes, and GTAO
 * ({@link CgGTAO}) with its spatial and temporal
 * filters, the default, at its High level and half size; and a third, AMD's FidelityFX CACAO ({@link CgCACAO}).
 *
 * <p>The scene renderer drives it; a view turns it on or off and its post settings shape it:
 *
 * <pre>{@code
 * view.showFlags().ambientOcclusion(false);                      // this view draws without it
 * view.post().settings().ambientOcclusion(1f, 3f);               // full strength, 3 m of reach in world space
 * view.post().settings().ambientOcclusionRadiusInWorldSpace(true);
 * }</pre>
 *
 * <p>Which method, how many samples and at what size: the player's settings, and the post settings' quality (a level
 * from Lowest to Highest, {@link #LEVEL_QUALITIES}):
 *
 * <pre>{@code
 * // crystalgraphics.toml
 * [rendering]
 * ambient_occlusion_method = 1        # 0 SSAO, 1 GTAO, 2 CACAO
 * gtao_downsample = true              # GTAO at half size; CACAO's is ambient_occlusion_downsample
 * }</pre>
 * <pre>{@code
 * view.post().settings().ambientOcclusionQuality(65f);           // High: 0 Lowest, 15 Low, 40 Medium, 65 High, 100 Highest
 * }</pre>
 *
 * <h2>SSAO, GTAO and CACAO compared</h2>
 *
 * <p>Measured by the harness's {@code ambient-occlusion} gate on an RTX 4070 SUPER, at 2560 x 1440. GTAO's half size
 * is {@code gtao_downsample}; CACAO's quarter size is {@code ambient_occlusion_downsample}. SSAO has no such switch:
 * its level picks how many sizes it runs (one at Lowest and Low, two at Medium and High, three at Highest).
 *
 * <table>
 *   <caption>GPU time a frame, ms (GTAO's and SSAO's include their depth pyramid, 0.10 ms)</caption>
 *   <tr><th>Level</th><th>SSAO</th><th>GTAO</th><th>GTAO half</th><th>CACAO</th><th>CACAO quarter</th></tr>
 *   <tr><td>Lowest</td>  <td>0.60</td><td>0.66</td><td>0.30</td><td>0.33</td><td>0.21</td></tr>
 *   <tr><td>Low</td>     <td>0.60</td><td>0.81</td><td>0.33</td><td>0.57</td><td>0.28</td></tr>
 *   <tr><td>Medium</td>  <td>1.23</td><td>1.01</td><td>0.39</td><td>0.72</td><td>0.29</td></tr>
 *   <tr><td>High</td>    <td>1.60</td><td>1.13</td><td>0.44</td><td>0.97</td><td>0.40</td></tr>
 *   <tr><td>Highest</td> <td>2.37</td><td>1.51</td><td>0.53</td><td>0.99</td><td>0.45</td></tr>
 * </table>
 *
 * <table>
 *   <caption>What it leaves of the sky at full intensity, 1 open: a floor point 40 cm from a wall / a box's side</caption>
 *   <tr><th>Level</th><th>SSAO</th><th>GTAO</th><th>GTAO half</th><th>CACAO</th><th>CACAO quarter</th></tr>
 *   <tr><td>Lowest</td>  <td>0.55 / 0.68</td><td>0.91 / 0.85</td><td>0.94 / 0.86</td><td>0.60 / 0.47</td><td>0.62 / 0.60</td></tr>
 *   <tr><td>Low</td>     <td>0.55 / 0.67</td><td>0.83 / 0.72</td><td>0.87 / 0.75</td><td>0.53 / 0.41</td><td>0.57 / 0.51</td></tr>
 *   <tr><td>Medium</td>  <td>0.48 / 0.58</td><td>0.79 / 0.66</td><td>0.83 / 0.69</td><td>0.41 / 0.36</td><td>0.44 / 0.41</td></tr>
 *   <tr><td>High</td>    <td>0.47 / 0.53</td><td>0.77 / 0.66</td><td>0.80 / 0.69</td><td>0.42 / 0.35</td><td>0.50 / 0.43</td></tr>
 *   <tr><td>Highest</td> <td>0.49 / 0.54</td><td>0.76 / 0.67</td><td>0.77 / 0.70</td><td>0.42 / 0.36</td><td>0.50 / 0.45</td></tr>
 * </table>
 *
 * <p>At the wall point the exact answer is 0.61 counting the wall up to 2 m away, and 0.75 counting it through GTAO's
 * falloff, which lets an occluder fade out by 1.4 m.
 *
 * <ul>
 *   <li><b>GTAO</b> lands on its falloff's answer from Medium up, steady from frame to frame: a 6 x 4 cycle of sample
 *       directions averaged by its temporal filter, and the prepass's smooth normals, so a low-poly mesh shows no
 *       triangles. Half size halves its cost and softens fine creases and silhouettes.</li>
 *   <li><b>CACAO</b> is darker than either exact answer from Medium up: AMD's shadow multiplier and power, a stylised
 *       look. No temporal filter; its Lowest to Medium presets streak under figures standing near the floor.
 *       Downsampled is its quarter-size layers and a bilateral upscale.</li>
 *   <li><b>SSAO</b> is Unreal's deferred method with a bilinear pyramid read, an edge-aware smooth and GTAO's
 *       temporal filter: smooth, but several times GTAO's half-size cost and farther from the exact answer. Its
 *       smaller sizes reach wide and soft, and darken a wall behind a figure.</li>
 *   <li>The intensity scales either at the end: {@code 1 - (1 - ao) * intensity}, 0.5 by default (Unreal's).</li>
 * </ul>
 *
 * <p>It runs once the all-opaque prepass holds every solid's depth, ours and the host's, and the solids' velocity is
 * written, before any solid shades: the base pass and the lit solids read it. A translucent or a host's surface never
 * does, as in Unreal.
 */
public final class CgAmbientOcclusion {

    private static final int GPU_AO = CgGpuTrace.name("world.ambientOcclusion");
    /** This frame's occlusion, published for {@code crystalgraphics.post.debug=ao}; absent where none ran. */
    public static final CgViewKey<CgGraphTexture> RESULT = new CgViewKey<>("crystalgraphics:ambient_occlusion");
    private static final CgViewState.Key<Targets> TARGETS = new CgViewState.Key<>("crystalgraphics:ambient_occlusion", Targets::new);

    private CgAmbientOcclusion() {
    }

    /** Its kernels for the precache. */
    public static void precache() {
        CgGpuOps.precacheDepthPyramid();
        CgSSAO.precache();
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
        return view.showFlags().ambientOcclusion() && settings.ambientOcclusionOn() && enabled()
                && !view.camera().isOrthographic();
    }

    /**
     * Whether {@code view} runs SSAO or GTAO on the prepass's normals this frame: they are then written beside its
     * depth.
     */
    public static boolean wantsNormals(CgView view, CgPostSettings settings) {
        if (!wants(view, settings)) return false;
        return switch (Math.round(CgGraphicsSettings.AMBIENT_OCCLUSION_METHOD.get())) {
            case 0 -> true;
            case 1 -> CgGTAO.USE_NORMALS.get();
            default -> false;
        };
    }

    /** Whether its kernels are built; until then a frame draws without it. */
    public static boolean ready() {
        return CgSSAO.ready() && CgGTAO.ready() && CgCACAO.ready();
    }

    /**
     * This firing's occlusion, once {@code stage}'s depth holds every opaque surface and the solids' velocity is
     * written, SSAO and GTAO reading {@code normals} where the prepass wrote them (null: none): the texture the solids
     * bind at
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

        // SSAO and GTAO search the furthest depth pyramid (Unreal's HZB), built before the pass that samples it
        if (method != 2) CgGpuOps.depthPyramid(recording, depth, stage.constants(), t.pyramid);
        CgComputePass pass = recording.compute("world.ambientOcclusion", stage.constants()).readsDepth(depth).timed(GPU_AO);
        switch (method) {
            case 1 -> CgGTAO.record(pass, recording, view, s, level, t.pyramid, normals, t.ao, w, h, fadeMul, fadeAdd);
            case 2 -> CgCACAO.record(pass, view, s, level, t.ao, w, h,
                    view.showFlags().temporalAA() ? view.state().frameIndex() : -1);
            default -> CgSSAO.record(pass, recording, view, s, level, t.pyramid, t.pyramidLast, normals, t.ao, w, h,
                    fadeMul, fadeAdd);
        }
        pass.end();
        view.publish(RESULT, t.ao);
        return t.ao;
    }

    /**
     * The player's toggle for running the method in use smaller: CACAO's or GTAO's; null for SSAO, whose smaller sizes
     * are its levels ({@code ambient_occlusion_levels}).
     */
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
     * Whether any runs, Unreal's {@code r.AmbientOcclusionLevels} above 0: the player's {@code ambient_occlusion_levels},
     * else its Quality tier's ({@code BaseScalability.ini}: Low off).
     */
    private static boolean enabled() {
        int set = Math.round(CgGraphicsSettings.AMBIENT_OCCLUSION_LEVELS.get());
        if (set >= 0) return set > 0;
        return CgGraphicsSettings.QUALITY.get() != CgQuality.LOW;
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
        float q = quality(s);
        return (q > 75f ? 1 : 0) + (q > 55f ? 1 : 0) + (q > 25f ? 1 : 0) + (q > 5f ? 1 : 0);
    }

    /**
     * The settings' quality under {@code r.AmbientOcclusionMaxQuality}: the player's {@code ambient_occlusion_max_quality},
     * else its tier's (Low 0, Medium 60, High and Ultra 100).
     */
    static float quality(CgPostSettings s) {
        float set = CgGraphicsSettings.AMBIENT_OCCLUSION_MAX_QUALITY.get();
        float max = set >= 0f ? set : switch (CgGraphicsSettings.QUALITY.get()) {
            case LOW -> 0f;
            case MEDIUM -> 60f;
            case HIGH, ULTRA -> 100f;
        };
        return Math.min(max, s.ambientOcclusionQuality());
    }

    /**
     * A view's occlusion and the depth pyramid SSAO and GTAO search, remade when its size changes. The occlusion is kept,
     * not transient: what reads it may record in a later firing than the one it runs in (Unreal's {@code ScreenSpaceAO}
     * lives the frame's length).
     */
    private static final class Targets {
        CgGraphTexture ao, pyramid;
        int w, h, pyramidLast;

        Targets size(CgRecording recording, int width, int height) {
            if (ao != null && width == w && height == h) return this;
            if (ao != null) recording.release(ao);
            w = width;
            h = height;
            ao = CgGraphTexture.requested("cg_ambient_occlusion", new CgTextureDesc(w, h, CgFormat.R16_FLOAT));
            pyramid = CgGraphTexture.transientTexture("cg_ao.pyramid", new CgTextureDesc(w, h, CgGpuOps.PYRAMID_HALF_FORMAT).withMips());
            pyramidLast = 31 - Integer.numberOfLeadingZeros(Math.max(w, h));
            return this;
        }
    }
}
