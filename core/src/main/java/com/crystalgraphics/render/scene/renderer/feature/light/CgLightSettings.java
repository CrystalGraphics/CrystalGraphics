package com.crystalgraphics.render.scene.renderer.feature.light;

import com.crystalgraphics.settings.CgGraphicsSettings;
import com.crystalgraphics.settings.CgQuality;
import com.crystalgraphics.variable.CgVar;

/**
 * The scene lights' switches: Unreal's light, shadow and volumetric cvars, each a {@code -D} flag and a console
 * variable, and the part of its scalability groups the lights follow.
 *
 * <pre>{@code
 * CgLightSettings.SHADE_SCALE.set(1);               // MegaLights' shading at full resolution
 * -Dcrystalgraphics.world.lightsShadows=false      // every light through the grid alone, unshadowed
 * }</pre>
 *
 * <ul>
 *   <li>A quality tier sets the ones it names when {@code CgGraphicsSettings.QUALITY} changes; a {@code -D} flag or the
 *       console still wins.</li>
 * </ul>
 */
public final class CgLightSettings {

    /**
     * Target pixels each way one texel of MegaLights' shading, accumulation and filter stands for: 1, 2 or 4, the apply
     * upsampling their result by depth. A departure from Unreal, which shades MegaLights at full resolution. 2 by
     * default: the user saw no difference from 1 in the lights demo, at 280 fps on the fireflies' floor in game.
     */
    public static final CgVar.Int SHADE_SCALE = CgVar.integer("crystalgraphics.world.lightsShadeScale", 2, 1, 4, CgVar.DEV,
            "Target pixels each way one texel of the shadowed lights' shading, history and filter stands for: 1 full, 2 half,"
                    + " 4 quarter resolution (3 reads as 2), upsampled by depth.");

    /** Unreal's r.MinScreenRadiusForLights: a light whose bound is smaller on screen is not drawn. */
    static final CgVar.Num MIN_SCREEN = CgVar.number("crystalgraphics.world.lightsMinScreenRadius", 0.03f, 0f, 1f,
            CgVar.DEV, "A light whose bounding sphere is smaller than this share of the screen is not drawn; 0 keeps all.");
    /** A light's strength under a full day's open sky, as an eye adapted to the sun sees it; 1 never fades. */
    static final CgVar.Num DAY_FADE = CgVar.number("crystalgraphics.world.lightsDayFade", 0.1f, 0f, 1f, CgVar.DEV,
            "A light's strength under a full day's open sky, where an eye adapted to the sun sees it faint; full at night"
                    + " and where no sky reaches. 1 never fades.");

    // The shadows: Unreal's bCastShadows for the world, and MegaLights' traces
    public static final CgVar.Bool SHADOWS = CgVar.bool("crystalgraphics.world.lightsShadows", true, CgVar.DEV,
            "Lights that cast shadows are withheld by the blocks and entities between them and a surface.");
    /** Unreal's r.MegaLights.ScreenTraces.MaxDistance, in blocks: longer, since the world trace knows only blocks. */
    public static final CgVar.Num SCREEN_TRACE = CgVar.number("crystalgraphics.world.lightsScreenTrace", 1f, 0f, 64f,
            CgVar.DEV, "Blocks a shadow ray is followed across the screen's depth before the world trace takes over: "
                    + "Unreal's r.MegaLights.ScreenTraces.MaxDistance, 100 cm.");
    /** Unreal's r.MegaLights.ScreenTraces.Quality as a step count: its fast half-resolution traces as half the steps. */
    static final CgVar.Int SCREEN_STEPS = CgVar.integer("crystalgraphics.world.lightsScreenSteps", 24, 4, 64,
            CgVar.DEV, "Steps a shadow ray takes across the screen's depth.");
    /** Unreal's r.DistanceFieldShadowing: our meshes' distance fields in the lights' shadow rays. */
    static final CgVar.Bool MESH_SHADOWS = CgVar.bool("crystalgraphics.world.lightsMeshShadows", true, CgVar.DEV,
            "Our meshes withhold the lights through their distance fields; off, only blocks, entities and the screen do.");
    static final CgVar.Int SAMPLES = CgVar.integer("crystalgraphics.world.lightsSamplesPerPixel", 4, 1, 4, CgVar.DEV,
            "Unreal's r.MegaLights.NumSamplesPerPixel: 1, 2 or 4 shadow rays a downsampled pixel; fewer, noisier where "
                    + "many lights overlap.");
    public static final CgVar.Num DOWNSAMPLE = CgVar.number("crystalgraphics.world.lightsDownsample", 2f, 0f, 2f, CgVar.DEV,
            "Unreal's r.MegaLights.DownsampleMode: 0 traces every pixel, 1 every other (2x1), 2 a quarter (2x2), Unreal's "
                    + "default. In the lights demo at 2560x1440 in game the sample pass took 1.9 ms at 0, 0.65 at 2.");
    public static final CgVar.Num MAX_FRAMES = CgVar.number("crystalgraphics.world.lightsShadowFrames", 12f, 1f, 32f,
            CgVar.DEV, "Frames the shadowed lighting accumulates over: fewer ghost less and shimmer more.");
    /** Unreal's r.MegaLights.Spatial.KernelRadius, in pixels; 0 filters nothing. */
    public static final CgVar.Num SPATIAL_RADIUS = CgVar.number("crystalgraphics.world.lightsShadowSpatial", 8f, 0f, 32f,
            CgVar.DEV, "Pixels the shadowed lighting is filtered across where it is still noisy; 0 for none.");
    static final CgVar.Int SKIP = CgVar.integer("crystalgraphics.world.lightsSkip", 0, 0, 127, CgVar.DEV,
            "Parts the shadow rays leave out, a bit each, to tell which draws an artifact: 1 sun, 2 block light, 4 lights, "
                    + "8 meshes, 16 screen, 32 blocks, 64 entities.");
    static final CgVar.Bool ENTITY_TILES = CgVar.bool("crystalgraphics.world.lightsEntityTiles", true, CgVar.DEV,
            "A shadow ray tests only the entities its 16x16 tile can meet; off, every tile keeps every entity.");
    /** Minecraft's light-giving blocks as lights our objects shadow: how far from the eye, in blocks; 0 for none. */
    static final CgVar.Num BLOCK_REACH = CgVar.number("crystalgraphics.world.blockShadowReach", 48f, 0f, 64f,
            CgVar.DEV, "Blocks from the eye a torch, glowstone or any light-giving block is shadowed by our objects; 0 for none.");

    // The sun's shadows: our objects cast them on everything, and receive them from everything
    static final CgVar.Bool SUN = CgVar.bool("crystalgraphics.world.sunShadows", true, CgVar.DEV,
            "The sun is withheld from our objects by blocks, entities and each other, and from Minecraft's world by our"
                    + " objects.");
    /** Unreal's r.Shadow.CSM.MaxCascades: the cascades drawn, the rest of the atlas left empty. */
    static final CgVar.Int SUN_CASCADES = CgVar.integer("crystalgraphics.world.sunShadowCascades", CgSunShadows.CASCADES, 1,
            CgSunShadows.CASCADES, CgVar.DEV, "Cascades of the sun's shadow map drawn, each 3 times the depth of the last.");
    static final CgVar.Num SUN_DISTANCE = CgVar.number("crystalgraphics.world.sunShadowDistance", 96f, 8f, 512f,
            CgVar.DEV, "Blocks from the eye the sun's shadow map reaches, in four cascades: Unreal's "
                    + "DynamicShadowDistanceMovableLight.");
    /** Unreal's r.Shadow.MaxCSMResolution is 2048 of depth alone; ours keeps a float copy beside it, so half. */
    static final CgVar.Int SUN_RESOLUTION = CgVar.integer("crystalgraphics.world.sunShadowResolution", 1024, 256,
            2048, CgVar.DEV, "Texels a side of each of the sun's four shadow cascades, 8 bytes each: the atlas is twice it.");
    /** How far toward the sun past a cascade its map takes casters from. */
    static final CgVar.Num SUN_TRACE = CgVar.number("crystalgraphics.world.sunCasterReach", 48f, 0f, 128f,
            CgVar.DEV, "Blocks toward the sun past each shadow cascade its casters are taken from: their longest shadow.");

    // The translucency lighting volume and the lit air
    static final CgVar.Bool VOLUME = CgVar.bool("crystalgraphics.world.lightsVolume", true, CgVar.DEV,
            "Transparent surfaces and particles lit by the world's lights, through Unreal's translucency lighting volume.");
    /** Unreal's r.TranslucencyLightingVolume.Dim. */
    static final CgVar.Int VOLUME_CELLS = CgVar.integer("crystalgraphics.world.lightsVolumeCells", 64, 8, 64,
            CgVar.DEV, "Cells a side of each of the translucency lighting volume's two cascades: Unreal's "
                    + "r.TranslucencyLightingVolume.Dim.");
    static final CgVar.Num VOLUME_INNER = CgVar.number("crystalgraphics.world.lightsVolumeInner", 15f, 1f, 128f,
            CgVar.DEV, "Blocks from the eye the translucency lighting volume's first cascade reaches: Unreal's "
                    + "r.TranslucencyLightingVolume.InnerDistance, 15 m.");
    static final CgVar.Num VOLUME_OUTER = CgVar.number("crystalgraphics.world.lightsVolumeOuter", 50f, 2f, 256f,
            CgVar.DEV, "Blocks from the eye its second reaches: r.TranslucencyLightingVolume.OuterDistance, 50 m.");
    static final CgVar.Int VOLUME_RAYS = CgVar.integer("crystalgraphics.world.lightsVolumeShadowRays", 4, 0, 64,
            CgVar.DEV, "Shadowed lights traced from each cell of the translucency volume; past them a cell's lights shine unshadowed.");
    static final CgVar.Bool AIR = CgVar.bool("crystalgraphics.world.lightsAir", true, CgVar.DEV,
            "Smoke, gas and haze in the air lit by the world's lights: Unreal's volumetric fog's light scattering.");
    static final CgVar.Int AIR_RAYS = CgVar.integer("crystalgraphics.world.lightsAirShadowRays", 1, 0, 64, CgVar.DEV,
            "Shadowed lights traced from each froxel of lit air; past them a froxel's lights shine unshadowed.");
    static final CgVar.Bool AIR_HISTORY = CgVar.bool("crystalgraphics.world.lightsAirHistory", true, CgVar.DEV,
            "Lit air jittered through each froxel and blended over the last frames: Unreal's r.VolumetricFog.TemporalReprojection.");
    static final CgVar.Num AIR_HISTORY_WEIGHT = CgVar.number("crystalgraphics.world.lightsAirHistoryWeight", 0.9f, 0f,
            0.99f, CgVar.DEV, "How much of the lit air's last frames each frame keeps: Unreal's r.VolumetricFog.HistoryWeight.");
    /**
     * Unreal lights a froxel at one point a frame and leaves the noise to its temporal anti-aliasing, which a Minecraft
     * frame lacks: at one, a searchlight's beam flickered 4 of 255 at its base (lights-air-quality's 99th percentile),
     * at four 1.2, and down its length as steady as froxel middles.
     */
    static final CgVar.Int AIR_SAMPLES = CgVar.integer("crystalgraphics.world.lightsAirSamples", 4, 1, 8, CgVar.DEV,
            "Jittered points each froxel of lit air is lit at a frame: its noise falls with their square root, its cost rises with them.");

    /** Unreal's r.Shadow.DistanceScale and r.TranslucencyLightingVolume.Dim, by tier. */
    private static final float[] SHADOW_DISTANCE_SCALE = {0.6f, 0.7f, 0.85f, 1f};
    private static final int[] VOLUME_DIM = {24, 32, 48, 64};
    /** The tier {@link #quality} last set. */
    private static CgQuality applied;

    private CgLightSettings() {
    }

    /** The graphics quality's tier onto these, when it changed since the last call. Render thread, each firing. */
    static void follow() {
        CgQuality tier = CgGraphicsSettings.QUALITY.get();
        if (tier != applied) quality(tier);
    }

    /**
     * The lights' part of Unreal's scalability groups (BaseScalability.ini's ShadowQuality and EffectsQuality), Ultra
     * today's look and each lower tier Unreal's step down from it, set as code's.
     */
    static void quality(CgQuality tier) {
        int t = tier.ordinal();
        boolean high = tier.atLeast(CgQuality.HIGH);
        AIR.set(high);                                          // r.VolumetricFog 0 0 1 1
        SCREEN_STEPS.set(high ? 24 : 12);                       // r.MegaLights.ScreenTraces.Quality 0 0 1 1
        MESH_SHADOWS.set(high);                                 // r.DistanceFieldShadowing 0 0 1 1
        SAMPLES.set(high ? 4 : 2);                              // r.MegaLights.NumSamplesPerPixel 2 2 4 4
        SUN_CASCADES.set(high ? CgSunShadows.CASCADES : 1);     // r.Shadow.CSM.MaxCascades 1 1 4 10
        // r.Shadow.MaxCSMResolution 512 1024 2048 2048: ours is half Unreal's at High and up (it keeps a float copy),
        // and 512 below, Unreal's Low: at 256 the caster bias, 10 texels, outgrew a box's height off the ground.
        SUN_RESOLUTION.set(high ? 1024 : 512);
        SUN_DISTANCE.set(96f * SHADOW_DISTANCE_SCALE[t]);       // r.Shadow.DistanceScale
        BLOCK_REACH.set(high ? 48f : 24f);                      // r.LightMaxDrawDistanceScale 0.5 0.5 1 1
        VOLUME_CELLS.set(VOLUME_DIM[t]);                        // r.TranslucencyLightingVolume.Dim
        applied = tier;
    }
}
