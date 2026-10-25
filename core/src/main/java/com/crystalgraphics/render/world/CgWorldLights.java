package com.crystalgraphics.render.world;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.shader.CgShaderBindings;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.compute.ops.CgGpuCount;
import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.gl.buffer.CgReadback;
import com.crystalgraphics.gl.texture.CgFallbackTextures;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgDispatch;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgRehearsal;
import com.crystalgraphics.render.graph.CgTextureDesc;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.variable.CgVar;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The lights in the world this frame, Unreal's local lights: points, spots and tubes, each a position, a radius, a
 * colour and a falloff, which the world renderer bins into its light grid and adds onto the scene behind each pixel. An
 * effect or a mod adds its lights from the CPU; a kernel appends its own into the same list (a light per particle).
 *
 * <pre>{@code
 * // from the CPU, once a frame, before the world records (a tick function of the client world)
 * CgWorldLights lights = CgWorldRenderer.get().lights();
 * lights.add(x, y + 1.5, z, 6f, 0xFFFF9030, 40f, 0f);     // an orange glow, 6 blocks, inverse-squared
 * lights.add(x, y, z, 3f, 0xFF40A0FF, 8f, 8f);            // a blue one, falling off as Unreal's exponent 8
 * }</pre>
 *
 * <pre>{@code
 * // a spot: the light just added, limited to a cone pointing down, full inside 20 degrees, gone past 35
 * lights.add(x, y + 6, z, 12f, 0xFFFFF0D0, 300f, 0f).spot(0f, -1f, 0f, 20f, 35f);
 * // a tube: the light just added, coming from a line 30 blocks long along the beam (Unreal's SourceLength)
 * lights.add(midX, midY, midZ, 24f, 0xFF60A0FF, 40f, 0f).tube(dirX, dirY, dirZ, 30f);
 * // one that casts no shadow (Unreal's bCastShadows off): lit through the grid alone, at no tracing cost
 * lights.add(x, y, z, 4f, 0xFFFFC080, 10f, 0f).shadowed(false);
 * }</pre>
 *
 * <pre>{@code
 * // from a kernel: an appender, run at the top of each transparent firing
 * CgWorldRenderer.get().lights().add(new CgWorldLights.Appender() {
 *     public boolean append(CgComputePass pass, CgWorldLights lights) {
 *         if (embers == 0) return false;                        // nothing to add this firing
 *         lights.into(pass.dispatch(gather, embers)).bind("EMBERS", records);
 *         return true;
 *     }
 * });
 *
 * // its kernel: CgLight declared in its own file (STRUCT), LIGHTS an append buffer of it
 * #pragma kernel Gather append
 * #include "crystalgraphics:shaders/lib/lights.glsl"
 * struct CgLight { vec4 positionRadius; vec4 colorExponent; vec4 spotCos; vec4 segmentCone; vec4 ambient; vec4 air; };
 * Properties { _LightEye ("The firing's eye, absolute", vec4) = (0, 0, 0, 0) }
 * Buffers { EMBERS ("Embers", vec4, readonly)  LIGHTS ("Lights", CgLight, append) }
 * void Gather() {
 *     vec4 e = EMBERS(CG_ELEMENT);
 *     LIGHTS_APPEND(cg_light(e.xyz - _LightEye.xyz, 2.0, vec3(4.0, 1.6, 0.3), 0.0));
 * }
 * }</pre>
 *
 * <ul>
 *   <li>A light lives one frame: add it again each frame it shines.</li>
 *   <li>{@code add}'s colour is sRGB, as authored colours are; a kernel appends linear colour, premultiplied by its
 *       intensity.</li>
 *   <li>A light lights a surface as Unreal's simple light does: colour × intensity × falloff × N·L × albedo / π, the
 *       albedo the scene's colour over the lightmap at the light's block (read when it is added; a kernel's light sets
 *       its {@code ambient}, or the scene is taken as it is).</li>
 *   <li>Author a light for the night. Under an open sky by day it fades, as an eye adapted to the sun would see it:
 *       full at night and wherever no sky reaches, a tenth under a full day's open sky
 *       ({@code -Dcrystalgraphics.world.lightsDayFade}, 1 for never).</li>
 *   <li>Exponent 0 is inverse-squared falloff in blocks, faded to nothing at the radius: a quarter as strong at two
 *       blocks as at one. Any other exponent is Unreal's {@code (1 - d²/r²)^exponent}.</li>
 *   <li>{@code spot}, {@code tube} and {@code source} change the light added last, and all may: a spot that comes from
 *       a line. The radius is still from the centre, so a tube's radius reaches past its ends.</li>
 *   <li>A point in a surface's plane only grazes it: give a light that touches surfaces (an ember on the ground) a
 *       {@code source} radius. A particle's light takes its drawn radius.</li>
 *   <li>A kernel's positions are from the firing's eye ({@link #cameraX()}, {@code _LightEye}), as the froxels are.</li>
 *   <li>Past {@link #CAPACITY} lights a frame the rest are dropped and counted ({@code world.lights-dropped}).</li>
 *   <li>A light added from the CPU casts shadows unless {@code shadowed(false)}: blocks and entities between it and a
 *       surface withhold it, traced as Unreal's MegaLights traces them (docs/LIGHTS.md). A kernel's light casts none
 *       unless its {@code ambient.w} is 1. Past the screen the rays trace the world's blocks round the camera
 *       ({@link CgWorldVoxelWindow}) and the entities round it ({@link CgWorldEntityBoxes}), each a capsule; meshes drawn
 *       through the world renderer shadow only where they are on screen.</li>
 *   <li>Minecraft's own light is shadowed by our objects too: the sun's, and the block light of every light-giving block
 *       within {@code -Dcrystalgraphics.world.blockShadowReach} blocks (torches, glowstone, lava) the voxel window has
 *       found, while something of ours is drawn opaque.</li>
 * </ul>
 */
public final class CgWorldLights {

    /** The most lights a frame, the CPU's and the kernels' together: {@code -Dcrystalgraphics.world.lights}. */
    public static final int CAPACITY = Integer.getInteger("crystalgraphics.world.lights", 65536);
    /** The append buffer {@link #into} binds, of {@code lights.glsl}'s {@code CgLight}. */
    public static final String BUFFER = "LIGHTS";
    /**
     * The light as a kernel declares it, in its own file before its {@code Buffers}: a buffer's element is laid out
     * from the kernel's file alone, not its includes.
     */
    public static final String STRUCT =
            "struct CgLight { vec4 positionRadius; vec4 colorExponent; vec4 spotCos; vec4 segmentCone; vec4 ambient; vec4 air; };";
    /**
     * Floats a light: position and radius, colour and exponent, a spot's direction and the cosine of its outer angle (-2
     * for none), a tube's length along its axis and a spot's 1 / (cos inner - cos outer), the block and sky light at
     * the light (-1 unknown) and its source radius.
     */
    public static final int FLOATS = 24;

    /** Adds lights from a kernel, each transparent firing. */
    public interface Appender {
        /** Before the firing's pass opens, its eye known: uploads what {@link #append} binds. */
        default void upload(CgRecording recording, CgWorldLights lights) {
        }

        /** Appends its lights: {@link #into} each dispatch that does; false when it appends none this firing. */
        boolean append(CgComputePass pass, CgWorldLights lights);

        /**
         * Whether every light it appends is a point, never a spot or a tube. While every light shaded exactly is, the
         * apply runs a program without the spots' and tubes' path, a third cheaper on a floor of particle lights.
         */
        default boolean pointsOnly() {
            return false;
        }
    }

    /**
     * The light grid, Unreal's (LightGridInjection.cpp:34-50): as {@code lights.glsl}'s {@code CG_LIGHT_*}. Its cells'
     * lists share {@code PER_CELL} indices a cell, as Unreal's MaxCulledLightsPerCell does under its linked lists.
     */
    public static final int TILE = 64, SLICES = 32, PER_CELL = 32;
    /** Cells a parent cell spans each way: Unreal's r.LightCulling.TwoLevel.Exponent of 2. */
    public static final int PARENT = 4;
    /**
     * The slices' near and far eye depths, blocks: Minecraft's near plane past Unreal's offset of 9.5 cm, and a far
     * plane fixed where Unreal fits it to the furthest light, since a kernel's lights are not known here.
     */
    private static final float GRID_NEAR = 0.05f + 0.095f, GRID_FAR = 256.1f, GRID_SPREAD = 4.05f;
    private static final String KERNELS = "crystalgraphics:shaders/env/compute/light_grid.compute";
    /**
     * Unreal's translucency lighting volume (TranslucentLighting.cpp): two cascades of {@link #VOLUME_CELLS} a side round
     * the view, which light_volume.compute fills and every lit transparent pass reads ({@code cg_LocalLights}).
     */
    private static final String VOLUME_KERNELS = "crystalgraphics:shaders/env/compute/light_volume.compute";
    private static final int VOLUME_CELLS = 64, GPU_VOLUME = CgGpuTrace.name("world.lightsVolume");
    private static final CgFrameBufferFormat VOLUME_FORMAT = CgFrameBufferFormat.builder("cg_world_lights.volume")
            .color(0, CgTextureType.RGBA16F).build();
    private static final CgVar.Bool VOLUME = CgVar.bool("crystalgraphics.world.lightsVolume", true, CgVar.DEV,
            "Transparent surfaces and particles lit by the world's lights, through Unreal's translucency lighting volume.");
    private static final CgVar.Num VOLUME_INNER = CgVar.number("crystalgraphics.world.lightsVolumeInner", 15f, 1f, 128f,
            CgVar.DEV, "Blocks from the eye the translucency lighting volume's first cascade reaches: Unreal's "
                    + "r.TranslucencyLightingVolume.InnerDistance, 15 m.");
    private static final CgVar.Bool AIR = CgVar.bool("crystalgraphics.world.lightsAir", true, CgVar.DEV,
            "Smoke, gas and haze in the air lit by the world's lights: Unreal's volumetric fog's light scattering.");
    private static final CgVar.Int VOLUME_RAYS = CgVar.integer("crystalgraphics.world.lightsVolumeShadowRays", 4, 0, 64,
            CgVar.DEV, "Shadowed lights traced from each cell of the translucency volume; past them a cell's lights shine unshadowed.");
    private static final CgVar.Int AIR_RAYS = CgVar.integer("crystalgraphics.world.lightsAirShadowRays", 1, 0, 64, CgVar.DEV,
            "Shadowed lights traced from each froxel of lit air; past them a froxel's lights shine unshadowed.");
    private static final CgVar.Bool AIR_HISTORY = CgVar.bool("crystalgraphics.world.lightsAirHistory", true, CgVar.DEV,
            "Lit air jittered through each froxel and blended over the last frames: Unreal's r.VolumetricFog.TemporalReprojection.");
    private static final CgVar.Num AIR_HISTORY_WEIGHT = CgVar.number("crystalgraphics.world.lightsAirHistoryWeight", 0.9f, 0f,
            0.99f, CgVar.DEV, "How much of the lit air's last frames each frame keeps: Unreal's r.VolumetricFog.HistoryWeight.");
    /**
     * Unreal lights a froxel at one point a frame and leaves the noise to its temporal anti-aliasing, which a Minecraft
     * frame lacks: at one, a searchlight's beam flickered 4 of 255 at its base (lights-air-quality's 99th percentile),
     * at four 1.2, and down its length as steady as froxel middles.
     */
    private static final CgVar.Int AIR_SAMPLES = CgVar.integer("crystalgraphics.world.lightsAirSamples", 4, 1, 8, CgVar.DEV,
            "Jittered points each froxel of lit air is lit at a frame: its noise falls with their square root, its cost rises with them.");
    /** Unreal's ExponentialHeightFog VolumetricFogScatteringDistribution: a little forward scattering. */
    private static final float AIR_PHASE = 0.2f;
    private static final CgVar.Num VOLUME_OUTER = CgVar.number("crystalgraphics.world.lightsVolumeOuter", 50f, 2f, 256f,
            CgVar.DEV, "Blocks from the eye its second reaches: r.TranslucencyLightingVolume.OuterDistance, 50 m.");
    /** What adds the lights onto the scene: a full-screen pass reading the grid. */
    static final String APPLY_SHADER = "crystalgraphics:shaders/world_lights.shader";
    /**
     * Each pixel's normal in the view, written once a firing for every lighting pass (Unreal's GBufferA in role): half
     * floats, so it is the normal each pass rebuilt from depth, to a thousandth.
     */
    static final String GBUFFER_SHADER = "crystalgraphics:shaders/world_lights_gbuffer.shader";
    private static final CgFrameBufferFormat GBUFFER_FORMAT = CgFrameBufferFormat.builder("cg_world_lights.gbuffer")
            .color(0, CgTextureType.RGBA16F).build();
    private static final int GPU_GBUFFER = CgGpuTrace.name("world.lightsGBuffer");
    private static final CgMesh FULLSCREEN = CgMesh.vertices(3, CgMeshTopology.TRIANGLES);
    private static final int GPU_APPLY = CgGpuTrace.name("world.lightsApply");
    /** The shadow passes, MegaLights': samples traced, shaded, accumulated over frames, filtered across the screen. */
    static final String SAMPLE_SHADER = "crystalgraphics:shaders/world_lights_sample.shader",
            SHADE_SHADER = "crystalgraphics:shaders/world_lights_shade.shader",
            TEMPORAL_SHADER = "crystalgraphics:shaders/world_lights_temporal.shader",
            DENOISE_SHADER = "crystalgraphics:shaders/world_lights_denoise.shader",
            HOST_DEPTH_SHADER = "crystalgraphics:shaders/world_lights_host_depth.shader",
            TILES_SHADER = "crystalgraphics:shaders/world_lights_tiles.shader",
            TILE_DEPTH_SHADER = "crystalgraphics:shaders/world_lights_tile_depth.shader";
    private static final int GPU_SAMPLE = CgGpuTrace.name("world.lightsSample"), GPU_SHADE = CgGpuTrace.name("world.lightsShade"),
            GPU_TEMPORAL = CgGpuTrace.name("world.lightsTemporal"), GPU_DENOISE = CgGpuTrace.name("world.lightsDenoise"),
            GPU_HOST_DEPTH = CgGpuTrace.name("world.lightsHostDepth"), GPU_TILES = CgGpuTrace.name("world.lightsTiles"),
            GPU_TILE_DEPTH = CgGpuTrace.name("world.lightsTileDepth");
    /** Unreal's capsule shadow tiles: a tile's entities, a bit each, 16 a channel. */
    private static final int ENTITY_TILE = 16;
    private static final CgFrameBufferFormat TILES_FORMAT = CgFrameBufferFormat.builder("cg_world_lights.tiles")
            .color(0, CgTextureType.RGBA32F).build();
    /** Each 4x4 pixels' least and most window depth, the tiles' first step. */
    private static final CgFrameBufferFormat RANGES_FORMAT = CgFrameBufferFormat.builder("cg_world_lights.tile-ranges")
            .color(0, CgTextureType.RG32F).build();
    private static final CgVar.Bool ENTITY_TILES = CgVar.bool("crystalgraphics.world.lightsEntityTiles", true, CgVar.DEV,
            "A shadow ray tests only the entities its 16x16 tile can meet; off, every tile keeps every entity.");
    /** How far a kernel's light may reach, unknown here: what a tile keeps entities within while any appends. */
    private static final float KERNEL_REACH = 64f;
    /** A sample's light index, exact as a float to 2^24, its weight, and the sun's visibility. */
    private static final CgFrameBufferFormat SAMPLES_FORMAT = CgFrameBufferFormat.builder("cg_world_lights.samples")
            .color(0, CgTextureType.RGBA32F).build();
    /** The lighting, alpha where valid; the sun's visibility. */
    private static final CgFrameBufferFormat LIGHTING_FORMAT = CgFrameBufferFormat.builder("cg_world_lights.lighting")
            .color(0, CgTextureType.RGBA16F).color(1, CgTextureType.RGBA16F).build();
    /** The accumulated lighting and its frames; its luminance's moments, eye depth and confidence; the sun's and block light's visibility and their squares. */
    private static final CgFrameBufferFormat HISTORY_FORMAT = CgFrameBufferFormat.builder("cg_world_lights.history")
            .color(0, CgTextureType.RGBA16F).color(1, CgTextureType.RGBA16F).color(2, CgTextureType.RGBA16F).build();
    /** The host's window depth before our opaque draws. */
    private static final CgFrameBufferFormat HOST_DEPTH_FORMAT = CgFrameBufferFormat.builder("cg_world_lights.host-depth")
            .color(0, CgTextureType.R32F).build();
    /**
     * The sun's cascaded shadow map, Unreal's for a movable directional light (DirectionalLightComponent.cpp:1043-1057):
     * 4 cascades, each 3 times the depth of the last, overlapping by a tenth of each for the blend into the next.
     */
    private static final int CASCADES = 4;
    private static final float CASCADE_EXPONENT = 3f, CASCADE_TRANSITION = 0.1f, SUN_NEAR = 0.05f;
    /** Blocks the sun's ray through the blocks and entities runs: world_lights_sample.shader's sun_visibility. */
    private static final float SUN_RAY = 96f;
    static final String SUN_SHADOW_SHADER = "crystalgraphics:shaders/world_sun_shadow.shader";
    private static final int GPU_SUN = CgGpuTrace.name("world.sunShadow");
    /** The cascades' atlas, a quadrant each: the casters' depth from the light in blocks, and the depth test's. */
    private static final CgFrameBufferFormat SUN_MAP_FORMAT = CgFrameBufferFormat.builder("cg_world_lights.sun")
            .color(0, CgTextureType.R32F).depth(CgTextureType.DEPTH32F).build();
    /** Nothing cast: a depth past any receiver's. */
    private static final CgLoad SUN_CLEAR = CgLoad.clear(1.0e30f, 0f, 0f, 0f).andDepth(1.0);
    private static final CgVar.Num SUN_DISTANCE = CgVar.number("crystalgraphics.world.sunShadowDistance", 96f, 8f, 512f,
            CgVar.DEV, "Blocks from the eye the sun's shadow map reaches, in four cascades: Unreal's "
                    + "DynamicShadowDistanceMovableLight.");
    /** Unreal's r.Shadow.MaxCSMResolution is 2048 of depth alone; ours keeps a float copy beside it, so half. */
    private static final CgVar.Int SUN_RESOLUTION = CgVar.integer("crystalgraphics.world.sunShadowResolution", 1024, 256,
            2048, CgVar.DEV, "Texels a side of each of the sun's four shadow cascades, 8 bytes each: the atlas is twice it.");
    private static final CgLoad CLEAR = CgLoad.clear(0f, 0f, 0f, 0f);
    private static final int GPU_ZONE = CgGpuTrace.name("world.lights");
    /** The CPU's recording of each part, inside world.recordTransparent. */
    private static final int ZONE_RECORD = CgTrace.name("world.lights.record"), ZONE_BIN = CgTrace.name("world.lights.bin"),
            ZONE_APPLY = CgTrace.name("world.lights.apply"), ZONE_SHADOWS = CgTrace.name("world.lights.shadows"),
            ZONE_SUN = CgTrace.name("world.lights.sun"), ZONE_VOLUME = CgTrace.name("world.lights.volume");
    private static final int GPU_COARSE = CgGpuTrace.name("world.lightsCoarse");
    private static final int GPU_CELLS = CgGpuTrace.name("world.lightsCells");
    /** Firings between reads of the count, for what was dropped. */
    private static final int COUNT_EVERY = 30;
    private static final float[] SRGB = new float[256];

    static {
        for (int i = 0; i < 256; i++) {
            float c = i / 255f;
            SRGB[i] = c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4);
        }
    }

    private final List<Appender> appenders = new CopyOnWriteArrayList<>();
    private final Vector3f eye = new Vector3f();
    private final Matrix4f unproject = new Matrix4f(), toView = new Matrix4f(), perspective = new Matrix4f(),
            bob = new Matrix4f(), host = new Matrix4f();
    private final Vector4f corner = new Vector4f();
    /** The grid this firing: cells across and up, unit-depth rays at the target's edges, the slices' B, O and S. */
    private int gridX, gridY;
    private final float[] rays = new float[4], zParams = new float[3];
    /** How far past a spot's cone the lit air widens it: CgWorldMedia.spread's. */
    private final float[] airSpread = new float[2];
    private CgGraphBuffer cells, indices, viewed, alloc, parentCells, parentIndices, counts, starts;
    private CgGraphTexture pyramid;
    /** The pyramid's slack: its eye depth is linearised under the host's projection, bob and all. */
    private static final float HZB_MARGIN = 0.02f;
    /** Unreal's r.MinScreenRadiusForLights: a light whose bound is smaller on screen is not drawn. */
    private static final CgVar.Num MIN_SCREEN = CgVar.number("crystalgraphics.world.lightsMinScreenRadius", 0.03f, 0f, 1f,
            CgVar.DEV, "A light whose bounding sphere is smaller than this share of the screen is not drawn; 0 keeps all.");
    /** A light's strength under a full day's open sky, as an eye adapted to the sun sees it; 1 never fades. */
    private static final CgVar.Num DAY_FADE = CgVar.number("crystalgraphics.world.lightsDayFade", 0.1f, 0f, 1f, CgVar.DEV,
            "A light's strength under a full day's open sky, where an eye adapted to the sun sees it faint; full at night"
                    + " and where no sky reaches. 1 never fades.");
    /** Unreal's bCastShadows for the world: off lights every light through the grid alone. */
    private static final CgVar.Bool SHADOWS = CgVar.bool("crystalgraphics.world.lightsShadows", true, CgVar.DEV,
            "Lights that cast shadows are withheld by the blocks and entities between them and a surface.");
    /** Unreal's r.MegaLights.ScreenTraces.MaxDistance, in blocks: longer, since the world trace knows only blocks. */
    private static final CgVar.Num SCREEN_TRACE = CgVar.number("crystalgraphics.world.lightsScreenTrace", 1f, 0f, 64f,
            CgVar.DEV, "Blocks a shadow ray is followed across the screen's depth before the world trace takes over: "
                    + "Unreal's r.MegaLights.ScreenTraces.MaxDistance, 100 cm.");
    /** Unreal's r.MegaLights.Temporal.MaxFramesAccumulated. */
    private static final CgVar.Int SAMPLE_SKIP = CgVar.integer("crystalgraphics.world.lightsSampleSkip", 0, 0, 127, CgVar.DEV,
            "Parts of the shadow rays left out, to measure what each costs: 1 the sun, 2 Minecraft's block light, 4 the "
                    + "lights, 8 our meshes, 16 the screen trace, 32 the blocks, 64 the entities.");

    private static final CgVar.Int SAMPLES = CgVar.integer("crystalgraphics.world.lightsSamplesPerPixel", 1, 1, 4, CgVar.DEV,
            "Unreal's r.MegaLights.NumSamplesPerPixel: 1, 2 or 4 shadow rays a pixel (or a downsampled one). Unreal's 4 "
                    + "fall on a 2x2 downsampled pixel, a ray a screen pixel; 1 at full resolution is the same budget.");
    private static final CgVar.Num DOWNSAMPLE = CgVar.number("crystalgraphics.world.lightsDownsample", 2f, 0f, 2f, CgVar.DEV,
            "Unreal's r.MegaLights.DownsampleMode: 0 traces every pixel, 1 every other (2x1), 2 a quarter (2x2), Unreal's "
                    + "default. In the lights demo at 2560x1440 in game the sample pass took 1.9 ms at 0, 0.65 at 2.");
    /**
     * MegaLights' shading, accumulation and filter at half resolution each way, a quarter of the pixels, the apply
     * upsampling their result by depth: a departure from Unreal, which shades MegaLights at full resolution. On by
     * default: the user saw no difference in the lights demo, at 280 fps on the fireflies' floor in game.
     */
    public static final CgVar.Bool HALF = CgVar.bool("crystalgraphics.world.lightsHalf", true, CgVar.DEV,
            "The shadowed lights' shading, history and filter at half resolution, upsampled by depth; off shades every "
                    + "pixel.");

    private static final CgVar.Num MAX_FRAMES = CgVar.number("crystalgraphics.world.lightsShadowFrames", 12f, 1f, 32f,
            CgVar.DEV, "Frames the shadowed lighting accumulates over: fewer ghost less and shimmer more.");
    /** Unreal's r.MegaLights.Spatial.KernelRadius, in pixels; 0 filters nothing. */
    private static final CgVar.Num SPATIAL_RADIUS = CgVar.number("crystalgraphics.world.lightsShadowSpatial", 8f, 0f, 32f,
            CgVar.DEV, "Pixels the shadowed lighting is filtered across where it is still noisy; 0 for none.");
    /** The sun's shadows: our objects cast them on everything, and receive them from everything. */
    private static final CgVar.Bool SUN = CgVar.bool("crystalgraphics.world.sunShadows", true, CgVar.DEV,
            "The sun is withheld from our objects by blocks, entities and each other, and from Minecraft's world by our"
                    + " objects.");
    /** How much of Minecraft's sky light is the sun's own: what a shadow of it takes away. */
    private static final CgVar.Num SUN_SHARE = CgVar.number("crystalgraphics.world.sunShare", 0.6f, 0f, 1f, CgVar.DEV,
            "The sun's direct share of a full day's sky light: how dark its shadow is. 0 casts none.");
    /** How far toward the sun past a cascade its map takes casters from. */
    private static final CgVar.Num SUN_TRACE = CgVar.number("crystalgraphics.world.sunCasterReach", 48f, 0f, 128f,
            CgVar.DEV, "Blocks toward the sun past each shadow cascade its casters are taken from: their longest shadow.");
    /** Minecraft's light-giving blocks as lights our objects shadow: how far from the eye, in blocks; 0 for none. */
    private static final CgVar.Num BLOCK_REACH = CgVar.number("crystalgraphics.world.blockShadowReach", 48f, 0f, 64f,
            CgVar.DEV, "Blocks from the eye a torch, glowstone or any light-giving block is shadowed by our objects; 0 for none.");
    /** The most of them a frame, nearest found first only by the window's order. */
    private static final int BLOCK_MOST = 1024;
    /** Our opaque draws casting shadows through their meshes' distance fields: how many a firing, and how far. */
    private static final int MOST_CASTERS = 256, CASTER_FLOATS = 20;
    /** Where the sun's cascade rows start in the casters' texture: world_lights_sample.shader's SUN_ROWS_AT. */
    private static final int SUN_ROWS_AT = MOST_CASTERS * CASTER_FLOATS / 4;
    private static final float CASTER_REACH = 96f;
    /** Unreal's r.Forward.LightGridHZBCull. */
    /**
     * Off: in Minecraft 1.20.1 it cut the lit air into the grid's 64-pixel squares (the harness never shows it), and in
     * the lights demo at 2560x1440 it saved 0.02 ms.
     */
    private static final CgVar.Bool HZB = CgVar.bool("crystalgraphics.world.lightsHzb", false, CgVar.DEV,
            "The light grid skips cells behind the scene and pulls each cell's far edge in to it.");
    private CgKernel view, coarse, cell, countCells, writeCells;
    private float tileU, tileV;
    /** The apply, without and with the spots' and tubes' path ({@code CG_LIGHTS_SHAPED}). */
    private CgMaterial applyPoints, applyShaped;
    private CgMaterial gbufferPass, sample, shade, temporal, denoise, tiles, tileDepth;
    private CgGraphTexture gbuffer;
    private final Consumer<CgShaderBindings> gbufferValues = b -> this.gridValues.accept(b.sampler("_Normals", 0,
            this.normalsNow() ? this.normals : CgFallbackTextures.BLACK_1x1));
    /** The translucency lighting volume: this firing's, or null; each cascade's lowest corner from the eye and cell size. */
    @Nullable
    private CgGraphTexture localLights;
    private CgGraphTexture volume;
    private CgKernel fill, scatterKernel;
    /** The air lit, this firing's and the last's, swapped each firing: each froxel's light scattered toward the eye. */
    private final CgGraphTexture[] air = new CgGraphTexture[2];
    private int airNewest;
    /** The media's firing that last lit the air: the history holds that firing's alone. */
    private long airFiring = -2;
    private final float[] cascades = new float[8];
    /** This firing's entities per tile, and the farthest a shadowed light's ray runs, blocks. */
    private CgGraphTexture entityTiles, tileRanges;
    private float rayReach;
    private final Consumer<CgShaderBindings> rangeValues = b -> b.vec4("_Screen", this.targetW, this.targetH, 0f, 0f);
    private final Consumer<CgShaderBindings> tileValues = b -> b.vec4("_Screen", this.targetW, this.targetH, 0f, 0f)
            .sampler("_Ranges", 0, this.tileRanges)
            .vec4("_Rays", rays[0], rays[1], rays[2], rays[3])
            .vec4("_ToView0", toView.m00(), toView.m10(), toView.m20(), toView.m30())
            .vec4("_ToView1", toView.m01(), toView.m11(), toView.m21(), toView.m31())
            .vec4("_ToView2", toView.m02(), toView.m12(), toView.m22(), toView.m32())
            .vec4("_Depth", perspective.m22(), perspective.m32(), perspective.m23(), perspective.m33())
            .vec4("_EyeFrac", this.eyeFrac[0], this.eyeFrac[1], this.eyeFrac[2], 0f)
            .vec4("_Reach", ENTITY_TILES.get() ? this.rayReach : 1.0e9f, this.sunOn ? 1f : 0f, SUN_RAY, 0f).set1i("_Entities", this.entities);
    /** Lights added from the CPU this frame that cast shadows. */
    private int shadowedAdded;
    /**
     * Whether a spot or a tube may be among this frame's lights shaded exactly ({@code shapedExact}), or among all of
     * them ({@code shapedAny}): the apply's program then carries their path ({@code CG_LIGHTS_SHAPED}).
     */
    private boolean shapedExact, shapedAny;
    /** Our meshes' distance fields, and this firing's casters: {@link #CASTER_FLOATS} floats each. */
    private final CgMeshDistanceFields meshFields = new CgMeshDistanceFields();
    private final float[] casterRows = new float[MOST_CASTERS * CASTER_FLOATS];
    private int casterCount;
    /** The casters' rows, five RGBA32F texels each: a texture, since the sample pass reads four buffers already. */
    private CgGraphTexture casters;
    private ByteBuffer casterStaging;
    private final Matrix4f casterLocal = new Matrix4f(), casterInverse = new Matrix4f(), casterVoxel = new Matrix4f();
    /** This firing's casters for the sun's map: mesh, range, eye-relative model and bounding sphere. */
    private final CgMesh[] sunMeshes = new CgMesh[MOST_CASTERS];
    private final int[] sunRanges = new int[MOST_CASTERS * 3];
    private final float[] sunModels = new float[MOST_CASTERS * 16], sunBounds = new float[MOST_CASTERS * 4];
    private int sunCasterCount;
    private final CgAABB sunBox = new CgAABB();
    private final Matrix4f sunModel = new Matrix4f(), sunRotation = new Matrix4f(), viewToEye = new Matrix4f();
    private final Vector3f sunCentre = new Vector3f();
    private CgMaterial sunShadow;
    private CgGraphTexture sunMap;
    /**
     * Each cascade's rows, four texels after the casters' in {@link #casters}: eye-relative world to its uv (two), to
     * its depth from the light, then its view depths and depth bias.
     */
    private final float[] sunRowData = new float[CASCADES * 16];
    private ByteBuffer sunRowStaging;
    private final CgPassConstants sunConstants = new CgPassConstants();
    private boolean sunMapped;
    private final Vector3f casterScale = new Vector3f();
    /** This firing's light-giving blocks, x, y, z and level each, and how many: the list's last lights. */
    private int[] blocks = new int[4 * 64];
    private int blockCount;
    private float blockReach;
    private final CgWorldVoxelWindow.Emitters gatherBlock = (x, y, z, level) -> {
        double dx = x + 0.5 - this.cameraX, dy = y + 0.5 - this.cameraY, dz = z + 0.5 - this.cameraZ;
        if (this.blockCount == BLOCK_MOST || dx * dx + dy * dy + dz * dz > (double) this.blockReach * this.blockReach) return;
        if (this.blockCount * 4 == this.blocks.length) this.blocks = Arrays.copyOf(this.blocks, this.blocks.length * 2);
        int b = this.blockCount++ * 4;
        this.blocks[b] = x;
        this.blocks[b + 1] = y;
        this.blocks[b + 2] = z;
        this.blocks[b + 3] = level;
    };
    private float targetW, targetH;
    private CgGraphTexture samples, shaded, filtered, scratchHistory, history, accumulated;
    /** Target pixels the shading, history and filter's texels span each way this firing: 1, or 2 under {@link #HALF}. */
    private int lightScale = 1;
    private long historyMade = -1;
    private boolean shadowsOn, freshHistory, binned, sunOn;
    /** Zeros: the light list, cells and entities when none were made this firing. */
    private CgGraphBuffer zeroes;
    private CgGraphTexture hostDepth;
    private long hostDepthFrame = -1;
    /** Our opaque surfaces' normals this frame (the world renderer's normal target), alpha 1 where one was drawn. */
    private CgGraphTexture normals;
    private long normalsFrame = -1;
    private CgMaterial hostDepthCopy;
    private final float[] sunDirection = new float[3];
    private final float[] eyeFrac = new float[3];
    private final int[] eyeBlock = new int[3];
    private final Consumer<CgShaderBindings> sampleValues = b -> this.gridValues.accept(b.vec4("_Screen", targetW, targetH, this.downX, this.downY)
            .vec4("_Trace", SCREEN_TRACE.get(), 24f, 0.02f, 0.005f).set1i("_EyeX", eyeBlock[0]).set1i("_EyeY", eyeBlock[1])
            .set1i("_EyeZ", eyeBlock[2]).vec4("_EyeFrac", eyeFrac[0], eyeFrac[1], eyeFrac[2], 0f)
            .set1i("_Entities", this.entities).set1i("_Casters", this.casterCount)
            .vec4("_Sun", this.sunOn ? 1f : 0f, this.sunMapped ? 1f : 0f, 0f, 0f)
            .set1f("_Own", this.ownPixels() ? 1f : 0f).set1i("_Skip", SAMPLE_SKIP.get())
            .vec4("_SampleGrid", this.sampleX, this.sampleY, this.sampleX * this.sampleY, 0f)
            .sampler("_HostDepth", 3, this.ownPixels() ? this.hostDepth : CgFallbackTextures.WHITE_1x1)
            .sampler("_CasterRows", 4, this.casterCount > 0 || this.sunMapped ? this.casters : CgFallbackTextures.BLACK_1x1)
            .sampler("_MeshFields", 5, this.casterCount > 0 ? this.meshFields.atlas() : CgFallbackTextures.BLACK_1x1)
            .sampler("_GBuffer", 6, this.gbuffer)
            .sampler("_SunMap", 7, this.sunMapped ? this.sunMap : CgFallbackTextures.WHITE_1x1)
            .sampler("_EntityTiles", 8, this.entities > 0 ? this.entityTiles : CgFallbackTextures.BLACK_1x1));
    private final Consumer<CgShaderBindings> windowValues = b -> CgWorldVoxelWindow.get().bind(b, 0);
    private int entities;
    /** The downsample factor the sample and shade passes read this frame: {@link #DOWNSAMPLE}'s mode. */
    private int downX = 2, downY = 2;
    /** The samples a downsampled pixel takes, as their block of the samples texture: {@link #SAMPLES}' layout. */
    private int sampleX = 2, sampleY = 2;
    private final Consumer<CgShaderBindings> shadeValues = b -> this.gridValues.accept(b.vec4("_Screen", targetW, targetH, this.downX, this.downY)
            .sampler("_Samples", 0, samples).set1f("_MaxWeight", 20f)
            .vec4("_SampleGrid", this.sampleX, this.sampleY, this.sampleX * this.sampleY, 0f)
            .sampler("_GBuffer", 1, this.gbuffer).set1i("_Scale", lightScale));
    private final Consumer<CgShaderBindings> temporalValues = b -> this.gridValues.accept(b.vec4("_Eye", eye.x, eye.y, eye.z, 0f)
            .sampler("_Now", 0, shaded).sampler("_Last", 1, freshHistory ? shaded : history.previous())
            .sampler("_LastMoments", 2, freshHistory ? shaded : history.previous().attachment(1))
            .sampler("_NowVis", 3, shaded.attachment(1))
            .sampler("_LastVis", 4, freshHistory ? shaded.attachment(1) : history.previous().attachment(2))
            .set1f("_Fresh", freshHistory ? 1f : 0f)
            .vec4("_Frames", Math.min(4f, MAX_FRAMES.get()), MAX_FRAMES.get(), 1f, 0.03f).set1i("_Scale", lightScale));
    private final Consumer<CgShaderBindings> denoiseValues = b -> this.gridValues.accept(b.sampler("_Lighting", 0, accumulated)
            .sampler("_Moments", 1, accumulated.attachment(1)).sampler("_Vis", 2, accumulated.attachment(2))
            .sampler("_GBuffer", 3, this.gbuffer)
            .vec4("_Spatial", SPATIAL_RADIUS.get() / lightScale, 4f, 10000f, 2f)
            .vec4("_Spread", 2f, 0.15f, MAX_FRAMES.get(), 0f).set1i("_Scale", lightScale));
    private final Consumer<CgShaderBindings> applyValues = b -> this.gridValues.accept(b.set1f("_Shadows", shadowsOn ? 1f : 0f)
            .sampler("_Shadowed", 0, shadowsOn ? filtered : CgFallbackTextures.BLACK_1x1)
            .sampler("_Visibility", 1, shadowsOn ? filtered.attachment(1) : CgFallbackTextures.WHITE_1x1)
            .vec4("_Sun", shadowsOn && sunOn ? 1f : 0f, SUN_SHARE.get(), 0f, 0f)
            .set1i("_EyeX", eyeBlock[0]).set1i("_EyeY", eyeBlock[1]).set1i("_EyeZ", eyeBlock[2])
            .vec4("_EyeFrac", eyeFrac[0], eyeFrac[1], eyeFrac[2], 0f).set1i("_Scale", lightScale)
            .sampler("_GBuffer", 5, this.gbuffer));
    private final Consumer<CgShaderBindings> applyWindow = b -> CgWorldVoxelWindow.get().bind(b, 2);
    /** The grid's values onto the apply material, read from the fields this firing set. */
    private final Consumer<CgShaderBindings> gridValues = b -> b.vec4("_Grid", gridX, gridY, SLICES, 0f)
            .vec4("_Tile", tileU, tileV, 0f, 0f).vec4("_ZParams", zParams[0], zParams[1], zParams[2], 0f)
            .vec4("_Rays", rays[0], rays[1], rays[2], rays[3])
            .vec4("_ToView0", toView.m00(), toView.m10(), toView.m20(), toView.m30())
            .vec4("_ToView1", toView.m01(), toView.m11(), toView.m21(), toView.m31())
            .vec4("_ToView2", toView.m02(), toView.m12(), toView.m22(), toView.m32())
            .vec4("_Depth", perspective.m22(), perspective.m32(), perspective.m23(), perspective.m33())
            .set1f("_DayFade", DAY_FADE.get());
    private double[] at = new double[3 * 64];
    private float[] light = new float[FLOATS * 64];
    private int added;
    private long addedFrame = -1;
    private CgGraphBuffer list, count;
    private ByteBuffer staging;
    private final int[] start = new int[4];
    private double cameraX, cameraY, cameraZ;
    private int listed, firings;
    private volatile long dropped;
    /** The CPU's dropped as of the count being read back. */
    private long readCpuDropped;
    private final CgReadback.Sink readCount =
            data -> dropped = readCpuDropped + Math.max(0L, Integer.toUnsignedLong(data.getInt(0)) - CAPACITY);
    private final int[] zeros = new int[4];
    private volatile long cellsDropped;
    /**
     * The cells' shared budget, in {@link #PER_CELL}s a cell: doubled whenever a firing drops lights past it, up to
     * {@link #MOST_BUDGET}. Unreal's is fixed and warns; a flash lighting every cell would otherwise blank the cells
     * that allocate last, the far slices, along tile edges.
     */
    private int budgetScale = 2;
    private static final int MOST_BUDGET = 32;
    private final CgReadback.Sink readAlloc = data -> cellsDropped = Integer.toUnsignedLong(data.getInt(0));

    CgWorldLights() {
    }

    /**
     * A light this frame at an absolute position: {@code radius} blocks to where it reaches nothing, {@code argb} its
     * sRGB colour (alpha a multiplier) times {@code intensity}, {@code exponent} 0 for inverse-squared falloff. Render
     * thread, before the world records.
     */
    public CgWorldLights add(double x, double y, double z, float radius, int argb, float intensity, float exponent) {
        float scale = (argb >>> 24) / 255f * intensity;
        return addLinear(x, y, z, radius, SRGB[argb >> 16 & 0xFF] * scale, SRGB[argb >> 8 & 0xFF] * scale,
                SRGB[argb & 0xFF] * scale, exponent);
    }

    /**
     * As {@link #add(double, double, double, float, int, float, float)}, its colour linear already, times its
     * intensity: what a light worked out in code adds, the VFX engine's light renderers included.
     */
    public CgWorldLights addLinear(double x, double y, double z, float radius, float r, float g, float b, float exponent) {
        long frame = CgFrameRing.frame();
        if (frame != addedFrame) {
            addedFrame = frame;
            added = 0;
        }
        if (added * 3 == at.length) {
            at = Arrays.copyOf(at, at.length * 2);
            light = Arrays.copyOf(light, light.length * 2);
        }
        int p = added * 3, l = added * FLOATS;
        at[p] = x;
        at[p + 1] = y;
        at[p + 2] = z;
        light[l + 3] = radius;
        light[l + 4] = r;
        light[l + 5] = g;
        light[l + 6] = b;
        light[l + 7] = exponent;
        light[l + 8] = light[l + 9] = light[l + 10] = 0f;
        light[l + 11] = -2f;
        light[l + 12] = light[l + 13] = light[l + 14] = light[l + 15] = 0f;
        // What Minecraft lit the scene round it by, which the apply divides out to find the albedo.
        int lit = CgWorldLight.at(x, y, z);
        light[l + 16] = CgWorldLight.block(lit);
        light[l + 17] = CgWorldLight.sky(lit);
        light[l + 18] = 0f;
        light[l + 19] = 1f;
        light[l + 20] = 1f;
        light[l + 21] = light[l + 23] = 0f;
        light[l + 22] = 1f;
        added++;
        return this;
    }

    /**
     * The light added last as a spot pointing along {@code (dx, dy, dz)}: full within {@code innerDegrees} of that axis,
     * fading to nothing at {@code outerDegrees} (half angles, Unreal's InnerConeAngle and OuterConeAngle, clamped as
     * SpotLightComponent.cpp:49-55 does: the inner to 0-89, the outer just past it and at most 89).
     */
    public CgWorldLights spot(float dx, float dy, float dz, float innerDegrees, float outerDegrees) {
        int l = last();
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length == 0f) throw new IllegalArgumentException("a spot points somewhere: (0, 0, 0)");
        double inner = Math.toRadians(Math.max(0f, Math.min(89f, innerDegrees)));
        double outer = Math.max(inner + 0.001, Math.min(Math.toRadians(outerDegrees), Math.toRadians(89) + 0.001));
        double cosOuter = Math.cos(outer);
        light[l + 8] = dx / length;
        light[l + 9] = dy / length;
        light[l + 10] = dz / length;
        light[l + 11] = (float) cosOuter;
        light[l + 15] = (float) (1.0 / (Math.cos(inner) - cosOuter));
        return this;
    }

    /**
     * The light added last as a tube: coming from a line {@code length} blocks long along {@code (dx, dy, dz)}, centred
     * where it was added (Unreal's SourceLength). Its radius stays measured from that centre.
     */
    public CgWorldLights tube(float dx, float dy, float dz, float length) {
        int l = last();
        float norm = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (norm == 0f) throw new IllegalArgumentException("a tube runs along something: (0, 0, 0)");
        float s = Math.max(length, 0f) / norm;
        light[l + 12] = dx * s;
        light[l + 13] = dy * s;
        light[l + 14] = dz * s;
        return this;
    }

    /**
     * The light added last as a sphere {@code radius} blocks across from its centre (Unreal's SourceRadius): a surface
     * it touches or half sinks into is lit, where a point in the surface's plane only grazes it. 0, a point, unless set.
     */
    public CgWorldLights source(float radius) {
        light[last() + 18] = Math.max(radius, 0f);
        return this;
    }

    /**
     * Whether the light added last casts shadows (Unreal's bCastShadows): true unless set. One that does not is lit
     * through the grid alone and traces no ray.
     */
    public CgWorldLights shadowed(boolean shadowed) {
        light[last() + 19] = shadowed ? 1f : 0f;
        return this;
    }

    /**
     * Whether the shadow passes sample the light added last, as MegaLights does (Unreal's bAllowMegaLights): true unless
     * set. One that is not is shaded exactly at every pixel it reaches, as Unreal's clustered deferred lighting does: for
     * a few small lights that must not turn grainy. A light that casts shadows is always sampled.
     *
     * <pre>{@code
     * lights.add(x, y, z, 2f, 0xFFFFE080, 4f, 0f).shadowed(false).sampled(false);   // a lantern's glow, exact
     * }</pre>
     */
    public CgWorldLights sampled(boolean sampled) {
        light[last() + 22] = sampled ? 1f : 0f;
        return this;
    }

    /**
     * How strongly the light added last shines in the air ({@code docs/LIGHTS.md} § In the air), as a multiple of its
     * intensity: Unreal's VolumetricScatteringIntensity, 1 unless set. A beam meant to be seen in a thin haze takes
     * more; 0 lights surfaces alone.
     *
     * <pre>{@code
     * lights.add(x, y, z, 45f, 0xFFFFF4E0, 1800f, 0f).spot(dx, -0.3f, dz, 5f, 10f).scattering(6f);   // a searchlight
     * }</pre>
     */
    public CgWorldLights scattering(float intensity) {
        light[last() + 20] = Math.max(intensity, 0f);
        return this;
    }

    /** The light added last's first float; it throws when none was added this frame. */
    private int last() {
        if (added == 0 || addedFrame != CgFrameRing.frame()) throw new IllegalStateException("no light added this frame to change");
        return (added - 1) * FLOATS;
    }

    /** An sRGB channel decoded to linear, 0 to 1; past 1 (a colour times an intensity) it carries on linearly. */
    public static float linear(float srgb) {
        if (srgb >= 1f) return srgb;
        if (srgb <= 0f) return 0f;
        return srgb <= 0.04045f ? srgb / 12.92f : (float) Math.pow((srgb + 0.055f) / 1.055f, 2.4);
    }

    /** Adds {@code appender}, from any thread; a frame recording now may miss it. */
    public void add(Appender appender) {
        appenders.add(appender);
    }

    public void remove(Appender appender) {
        appenders.remove(appender);
    }

    /** {@code dispatch} appending into this firing's list: {@link #BUFFER}, its count, and {@code _LightEye}. */
    public CgDispatch into(CgDispatch dispatch) {
        return dispatch.bind(BUFFER, list).counter(BUFFER, count, 0)
                .set("_LightEye", (float) cameraX, (float) cameraY, (float) cameraZ, 0f);
    }

    /** Where the firing's eye is, absolute: what a kernel's positions are from. */
    public double cameraX() {
        return cameraX;
    }

    public double cameraY() {
        return cameraY;
    }

    public double cameraZ() {
        return cameraZ;
    }

    /** Lights past {@link #CAPACITY} in a recent firing, read back a few frames late; the CPU's at once. */
    public long dropped() {
        return dropped;
    }

    /** {@code -Dcrystalgraphics.world.lightsMinScreenRadius}: Unreal's r.MinScreenRadiusForLights, 0.03 unless set. */
    public static float minScreenRadius() {
        return MIN_SCREEN.get();
    }

    /** The cells' shared budget this firing, in lights a cell on average. */
    public int budgetPerCell() {
        return PER_CELL * budgetScale;
    }

    /** Lights a recent firing's cells dropped past their shared budget, read back a few frames late. */
    public long cellsDropped() {
        return cellsDropped;
    }

    /**
     * Each cell's lights as the last firing binned them: two {@code uint}s a cell, its count and where its list starts
     * in {@link #indices}, x fastest, then y, then the slice.
     */
    public CgGraphBuffer cells() {
        return cells;
    }

    /** The cells' lists of light indices, each in index order, {@link #PER_CELL} a cell on average. */
    public CgGraphBuffer indices() {
        return indices;
    }

    /** Cells across and up this firing; {@link #SLICES} deep. */
    public int gridX() {
        return gridX;
    }

    public int gridY() {
        return gridY;
    }

    /** Unit-depth view rays at the target's left, bottom, right and top edges: what a cell's x and y run along. */
    /**
     * How far past its cone the light grid lists a spot at eye depth z, {@code z * [0] + [1]} blocks: the lit air's
     * widening of its cone ({@link CgWorldMedia}). Read only.
     */
    public float[] airSpread() {
        return airSpread;
    }

    public float[] rays() {
        return rays;
    }

    /** A tile's share of the target across, as the last firing cut its grid: {@link #TILE} over its width. */
    public float tileU() {
        return tileU;
    }

    /** A tile's share of the target up. */
    public float tileV() {
        return tileV;
    }

    /** The last firing's camera-relative world to view: what a light's position is turned by to meet its cell. */
    public Matrix4fc toView() {
        return toView;
    }

    /** The slices' B, O and S: slice = log2(depth * B + O) * S. */
    public float[] zParams() {
        return zParams;
    }

    /**
     * One of our opaque draws as a shadow caster this firing, after {@link #record}: {@code submesh}, {@code first} and
     * {@code count} its range (submesh -1 the whole mesh), at an absolute position, its local transform the 16 floats
     * from {@code at}. Traced through its mesh's distance field once that is made; past {@link #MOST_CASTERS} or
     * {@link #CASTER_REACH} blocks, not at all.
     */
    void caster(CgMesh mesh, int submesh, int first, int count, double x, double y, double z, float[] transforms, int at) {
        if (!SHADOWS.get()) return;
        double dx = x - cameraX, dy = y - cameraY, dz = z - cameraZ;
        if (dx * dx + dy * dy + dz * dz > (double) CASTER_REACH * CASTER_REACH) return;
        if (sunCasterCount < MOST_CASTERS) sunCaster(mesh, submesh, first, count, (float) dx, (float) dy, (float) dz, transforms, at);
        if (casterCount == MOST_CASTERS) return;
        CgMeshDistanceFields.Entry entry = meshFields.field(mesh, submesh, first, count);
        if (entry == null) return;
        CgMeshDistanceFields.Field field = entry.made;
        casterLocal.set(transforms, at);
        casterInverse.translation((float) dx, (float) dy, (float) dz).mul(casterLocal).invertAffine();
        casterVoxel.scaling(1f / field.voxel()).translate(-field.ox(), -field.oy(), -field.oz()).mul(casterInverse);
        // World units a unit of the mesh, its narrowest axis: a distance read from the field never overstates the world's.
        casterLocal.getScale(casterScale);
        float scale = Math.min(casterScale.x, Math.min(casterScale.y, casterScale.z));
        int r = casterCount++ * CASTER_FLOATS;
        Matrix4f m = casterVoxel;
        float[] c = casterRows;
        c[r] = m.m00(); c[r + 1] = m.m10(); c[r + 2] = m.m20(); c[r + 3] = m.m30();
        c[r + 4] = m.m01(); c[r + 5] = m.m11(); c[r + 6] = m.m21(); c[r + 7] = m.m31();
        c[r + 8] = m.m02(); c[r + 9] = m.m12(); c[r + 10] = m.m22(); c[r + 11] = m.m32();
        c[r + 12] = field.nx(); c[r + 13] = field.ny(); c[r + 14] = field.nz(); c[r + 15] = entry.offset(meshFields);
        c[r + 16] = scale; c[r + 17] = field.voxel() * scale; c[r + 18] = field.voxel(); c[r + 19] = 0f;
    }

    /** One caster of the sun's map: its eye-relative model, and its bounds as a sphere for the cascades' culling. */
    private void sunCaster(CgMesh mesh, int submesh, int first, int count, float dx, float dy, float dz, float[] transforms,
                           int at) {
        if (mesh.bounds(sunBox) == null) return;
        int s = sunCasterCount++;
        sunMeshes[s] = mesh;
        sunRanges[s * 3] = submesh;
        sunRanges[s * 3 + 1] = first;
        sunRanges[s * 3 + 2] = count;
        sunModel.set(transforms, at).translateLocal(dx, dy, dz).get(sunModels, s * 16);
        sunModel.transformPosition(sunCentre.set((float) sunBox.centerX(), (float) sunBox.centerY(), (float) sunBox.centerZ()));
        sunModel.getScale(casterScale);
        float half = (float) Math.sqrt(sunBox.halfX() * sunBox.halfX() + sunBox.halfY() * sunBox.halfY()
                + sunBox.halfZ() * sunBox.halfZ());
        sunBounds[s * 4] = sunCentre.x;
        sunBounds[s * 4 + 1] = sunCentre.y;
        sunBounds[s * 4 + 2] = sunCentre.z;
        sunBounds[s * 4 + 3] = half * Math.max(casterScale.x, Math.max(casterScale.y, casterScale.z));
    }

    /** This firing's list: {@link #FLOATS} floats a light, camera-relative. */
    public CgGraphBuffer list() {
        return list;
    }

    /** This firing's count, a {@code uint} at 0, possibly past {@link #CAPACITY}: read it clamped. */
    public CgGraphBuffer count() {
        return count;
    }

    /**
     * The firing's list under {@code view}: the CPU's lights placed from its eye, then each appender's. False when it
     * holds none, and nothing is recorded.
     */
    boolean record(CgStageFrame frame, CgHostView view, Matrix4fc toWorld, float targetWidth, float targetHeight) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, ZONE_RECORD)) {
            CgRecording recording = frame.recording();
            CgPassConstants stage = frame.constants();
            int cpu = addedFrame == CgFrameRing.frame() ? added : 0;
            // The froxels' eye (CgWorldMedia's): the view's position plus where toWorld puts the eye.
            toWorld.transformPosition(eye.set(0f, 0f, 0f));
            cameraX = view.x() + eye.x;
            cameraY = view.y() + eye.y;
            cameraZ = view.z() + eye.z;
            // The view, every firing: the sun's shadows need it with no light listed.
            place(view.projection(), toWorld, targetWidth, targetHeight);
            binned = false;
            gridX = gridY = 1;
            shadowedAdded = 0;
            shapedExact = shapedAny = false;
            casterCount = 0;
            sunCasterCount = 0;
            // Minecraft's light-giving blocks, only where something of ours can shadow them.
            blockCount = 0;
            blockReach = BLOCK_REACH.get();
            if (SHADOWS.get() && blockReach > 0f && ownPixels()) {
                CgWorldVoxelWindow window = CgWorldVoxelWindow.get();
                window.useDistance();
                window.emitters(gatherBlock);
            }
            rayReach = appenders.isEmpty() ? 0f : KERNEL_REACH;
            if (cpu == 0 && blockCount == 0 && appenders.isEmpty()) return false;
            if (list == null) {
                list = CgGraphBuffer.persistent("cg_world_lights",
                        CgBufferDesc.of((long) CAPACITY * FLOATS * 4, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
                count = CgGraphBuffer.persistent("cg_world_lights.count",
                        CgBufferDesc.of(16, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
                staging = ByteBuffer.allocateDirect(CAPACITY * FLOATS * 4).order(ByteOrder.nativeOrder());
                viewed = CgGraphBuffer.persistent("cg_world_lights.view",
                        CgBufferDesc.elements(CAPACITY, 80, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
                alloc = CgGraphBuffer.persistent("cg_world_lights.alloc",
                        CgBufferDesc.elements(4, 4, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
            }
            listed = Math.min(cpu, CAPACITY);
            long cpuDropped = cpu - listed;
            blockCount = Math.min(blockCount, CAPACITY - listed);
            if (listed + blockCount > 0) {
                staging.clear();
                for (int i = 0; i < listed; i++) {
                    int p = i * 3, l = i * FLOATS;
                    if (light[l + 19] > 0.5f) {
                        shadowedAdded++;
                        // A lit surface is within its radius of the centre; the ray ends on its line or its sphere.
                        float s0 = light[l + 12], s1 = light[l + 13], s2 = light[l + 14];
                        rayReach = Math.max(rayReach, light[l + 3] + 0.5f * (float) Math.sqrt(s0 * s0 + s1 * s1 + s2 * s2)
                                + light[l + 18]);
                    }
                    // lights.glsl's CG_VIEW_SPOT and CG_VIEW_TUBE; CG_LIGHT_SAMPLED.
                    if (light[l + 11] > -1.5f || light[l + 12] != 0f || light[l + 13] != 0f || light[l + 14] != 0f) {
                        shapedAny = true;
                        if (light[l + 19] <= 0.5f && light[l + 22] <= 0.5f) shapedExact = true;
                    }
                    staging.putFloat((float) (at[p] - cameraX)).putFloat((float) (at[p + 1] - cameraY))
                            .putFloat((float) (at[p + 2] - cameraZ));
                    for (int w = 3; w < FLOATS; w++) staging.putFloat(light[l + w]);
                }
                // CG_LIGHT_MINECRAFT: black, reaching its level, its ambient.w 2.
                for (int i = 0; i < blockCount; i++) {
                    int b = i * 4;
                    rayReach = Math.max(rayReach, blocks[b + 3] + 0.5f);
                    staging.putFloat((float) (blocks[b] + 0.5 - cameraX)).putFloat((float) (blocks[b + 1] + 0.5 - cameraY))
                            .putFloat((float) (blocks[b + 2] + 0.5 - cameraZ)).putFloat(blocks[b + 3] + 0.5f)
                            .putFloat(0f).putFloat(0f).putFloat(0f).putFloat(0f)
                            .putFloat(0f).putFloat(0f).putFloat(0f).putFloat(-2f)
                            .putFloat(0f).putFloat(0f).putFloat(0f).putFloat(0f)
                            .putFloat(-1f).putFloat(-1f).putFloat(0.5f).putFloat(2f)
                            .putFloat(0f).putFloat(0f).putFloat(0f).putFloat(0f);
                }
                staging.flip();
                recording.update(list, 0, staging);
                listed += blockCount;
            }
            start[0] = listed;
            recording.update(count, 0, start, 0, 4);
            recording.update(alloc, 0, zeros, 0, 4);
            boolean appended = false;
            for (Appender a : appenders) a.upload(recording, this);
            // The scene's farthest depth per tile, for Unreal's HZB cull: built before the passes that read it open.
            pyramid = frame.depthPyramid();
            CgComputePass pass = recording.compute("world.lights", stage).timed(GPU_ZONE);
            for (Appender a : appenders) {
                if (!a.append(pass, this)) continue;
                appended = true;
                if (!a.pointsOnly()) shapedExact = shapedAny = true;
            }
            boolean binned = listed > 0 || appended;
            if (binned) bin(recording, stage, pass, view.projection(), toWorld, targetWidth, targetHeight,
                    frame.host().textures().lightmapTexture());
            else pass.end();
            // Every firing: a surge (a flash, a burst) drops lights the frame it comes, and the budget grows for the next.
            if (binned) recording.readback(alloc, 4, 4, readAlloc);
            if (appended && firings++ % COUNT_EVERY == 0) {
                readCpuDropped = cpuDropped;
                recording.readback(count, 0, 4, readCount);
            } else if (!appended) {
                dropped = cpuDropped;
            }
            CgTrace.counter(CgChannels.WORLD, "world.lights", listed);
            CgTrace.counter(CgChannels.WORLD, "world.lights-dropped", dropped);
            CgTrace.counter(CgChannels.WORLD, "world.lights-cell-dropped", cellsDropped);
            CgTrace.counter(CgChannels.WORLD, "world.lights-shadowed", shadowedAdded);
            CgTrace.counter(CgChannels.WORLD, "world.lights-blocks", blockCount);
            return listed > 0 || appended;
        }
    }

    /**
     * Each cell's lights, as Unreal 5 bins them (LightGridInjection.usf): the lights turned into the grid's space once, a
     * parent grid {@link #PARENT} cells a side culled first where compute runs (LightGridInjection.cpp:1818-1851), then
     * each cell from its parent's list. The grid is sized to the target, {@link #TILE} pixels a cell
     * (LightGridInjection.cpp:659-670).
     */
    private void bin(CgRecording recording, CgPassConstants stage, CgComputePass pass, Matrix4fc projection,
                     Matrix4fc toWorld, float targetWidth, float targetHeight, CgTexture lightmap) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, ZONE_BIN)) {
            int gx = Math.max(1, (int) Math.ceil(targetWidth / TILE)), gy = Math.max(1, (int) Math.ceil(targetHeight / TILE));
            gridX = gx;
            gridY = gy;
            binned = true;
            if (view == null) {
                CgCompute kernels = CgCompute.load(KERNELS);
                view = kernels.kernel("View");
                coarse = kernels.kernel("Coarse");
                cell = kernels.kernel("Cell");
                countCells = kernels.kernel("Count");
                writeCells = kernels.kernel("Write");
                view.prepare();
                if (coarse.runs()) {
                    coarse.prepare();
                    cell.prepare();
                } else {
                    countCells.prepare();
                    writeCells.prepare();
                    CgGpuOps.prepareScan(CgGpuOps.Scan.EXCLUSIVE, CgGpuOps.Fold.SUM, CgGpuOps.Element.UINT);
                }
            }
            // Below compute, every cell reads every light: the same lists, by the longer route.
            boolean twoLevel = coarse.runs();
            int px = (gx + PARENT - 1) / PARENT, py = (gy + PARENT - 1) / PARENT, pz = SLICES / PARENT;
            // Persistent, grown only: what a later recording reads back is the last firing's grid.
            if (cellsDropped > 0 && budgetScale < MOST_BUDGET) {
                budgetScale *= 2;
                cellsDropped = 0;
            }
            int n = gx * gy * SLICES, budget = n * PER_CELL * budgetScale, parentBudget = twoLevel ? budget : 1,
                    parents = px * py * pz;
            if (cells == null || cells.size() < n * 16L || parentCells.size() < parents * 8L
                    || parentIndices.size() < parentBudget * 4L || indices.size() < budget * 4L) {
                if (cells != null) {
                    recording.release(cells);
                    recording.release(indices);
                    recording.release(parentCells);
                    recording.release(parentIndices);
                    recording.release(counts);
                    recording.release(starts);
                }
                counts = CgGraphBuffer.persistent("cg_world_lights.counts",
                        CgBufferDesc.elements(n, 4, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
                starts = CgGraphBuffer.persistent("cg_world_lights.starts",
                        CgBufferDesc.elements(n, 4, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
                // Four words a cell: lights.glsl's CG_LIGHT_CELL_START.
                cells = CgGraphBuffer.persistent("cg_world_lights.cells",
                        CgBufferDesc.elements(n * 4, 4, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
                indices = CgGraphBuffer.persistent("cg_world_lights.indices",
                        CgBufferDesc.elements(budget, 4, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
                parentCells = CgGraphBuffer.persistent("cg_world_lights.parent-cells",
                        CgBufferDesc.elements(parents * 2, 4, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
                parentIndices = CgGraphBuffer.persistent("cg_world_lights.parent-indices",
                        CgBufferDesc.elements(parentBudget, 4, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
            }
            grid(pass.dispatch(view, CAPACITY).bind("LIGHTS", list).bind("COUNT", count).bind("VIEW", viewed)
                    .set("_MinScreen", MIN_SCREEN.get()).set("_DayFade", DAY_FADE.get())
                    .texture("_Lightmap", lightmap != null ? lightmap : CgFallbackTextures.WHITE_1x1),
                    gx, gy, 0, 0, 0, 0, budget, parentBudget);
            pass.end();
            // A pass a level, each timed: what the levels cost is the lever.
            if (twoLevel) {
                pass = recording.compute("world.lightsCoarse", stage).timed(GPU_COARSE);
                grid(pass.dispatchGroups(coarse, px, py, pz).bind("COUNT", count).bind("VIEWED", viewed).bind("ALLOC", alloc)
                        .bind("PARENT_CELLS", parentCells).bind("PARENT_INDICES", parentIndices), gx, gy, px, py, pz, PARENT,
                        budget, parentBudget);
                pass.end();
            }
            pass = recording.compute("world.lightsCells", stage).timed(GPU_CELLS);
            int factor = twoLevel ? PARENT : 0;
            if (twoLevel) {
                grid(pass.dispatchGroups(cell, gx, gy, SLICES).bind("COUNT", count).bind("VIEWED", viewed).bind("ALLOC", alloc)
                        .bind("PARENT_CELLS", parentCells).bind("PARENT_INDICES", parentIndices).bind("CELLS", cells)
                        .bind("INDICES", indices), gx, gy, px, py, pz, factor, budget, parentBudget);
                pass.end();
                return;
            }
            grid(pass.dispatch(countCells, n).bind("COUNT", count).bind("VIEWED", viewed).bind("PARENT_CELLS", parentCells)
                    .bind("PARENT_INDICES", parentIndices).bind("COUNTS", counts), gx, gy, px, py, pz, factor, budget,
                    parentBudget);
            CgGpuOps.scan(pass, CgGpuOps.Scan.EXCLUSIVE, CgGpuOps.Fold.SUM, CgGpuOps.Element.UINT, counts, CgGpuCount.of(n),
                    starts);
            grid(pass.dispatch(writeCells, n).bind("COUNT", count).bind("VIEWED", viewed).bind("ALLOC", alloc)
                    .bind("PARENT_CELLS", parentCells).bind("PARENT_INDICES", parentIndices).bind("STARTS", starts)
                    .bind("CELLS", cells).bind("INDICES", indices), gx, gy, px, py, pz, factor, budget, parentBudget);
            pass.end();
        }
    }

    /**
     * The firing's view: the slices, the bob-free perspective and the bob, the unit-depth rays at the target's edges, the
     * eye-relative world to the view, and the tile size. What the grid, the apply and the shadow passes are placed by.
     */
    private void place(Matrix4fc projection, Matrix4fc toWorld, float targetWidth, float targetHeight) {
        targetW = targetWidth;
        targetH = targetHeight;
        // GetLightGridZParams: slice = log2(z * B + O) * S, with the last slice's start at GRID_FAR.
        double o = (GRID_FAR - GRID_NEAR * Math.pow(2, (SLICES - 1) / GRID_SPREAD)) / (GRID_FAR - GRID_NEAR);
        zParams[0] = (float) ((1 - o) / GRID_NEAR);
        zParams[1] = (float) o;
        zParams[2] = GRID_SPREAD;
        // The grid lives in the bobbed view: a host folding its camera's bob into the projection (Minecraft 1.17-1.21.5)
        // keeps a pure perspective over it, so the rays and the depth come from that and the lights go through the bob.
        splitPerspective(projection, perspective, bob);
        host.set(projection);
        perspective.invert(unproject);
        for (int c = 0; c < 2; c++) {
            float ndc = c == 0 ? -1f : 1f;
            unproject.transform(corner.set(ndc, ndc, 0.5f, 1f));
            float z = -corner.z / corner.w;
            rays[c * 2] = corner.x / corner.w / z;
            rays[c * 2 + 1] = corner.y / corner.w / z;
        }
        // The lights are placed from the eye, not the view's position: back to the view's, then into the view.
        toWorld.invert(toView).translate(eye);
        bob.mul(toView, toView);
        tileU = TILE / targetWidth;
        tileV = TILE / targetHeight;
        CgWorldMedia.spread((rays[2] - rays[0]) / targetWidth, (rays[3] - rays[1]) / targetHeight, airSpread);
    }

    /** The grid's values onto one of its kernels' dispatches. */
    private void grid(CgDispatch dispatch, int gx, int gy, int px, int py, int pz, int factor, int budget, int parentBudget) {
        if (dispatch.kernel() != view) {
            int side = Math.max(pyramid.getWidth(), pyramid.getHeight());
            dispatch.texture("_Pyramid", pyramid).set("_Hzb", HZB.get() ? 1f : 0f, 0f, HZB_MARGIN, 31 - Integer.numberOfLeadingZeros(side))
                    .set("_HostDepth", host.m22(), host.m32(), host.m23(), host.m33())
                    .set("_Depth", perspective.m22(), perspective.m32(), perspective.m23(), perspective.m33());
        }
        dispatch.set("_Grid", gx, gy, SLICES, CAPACITY).set("_Parent", px, py, pz, factor)
                .set("_Budget", parentBudget, budget, 0f, 0f)
                .set("_Tile", tileU, tileV, 0f, 0f)
                .set("_ZParams", zParams[0], zParams[1], zParams[2], 0f)
                .set("_Rays", rays[0], rays[1], rays[2], rays[3])
                .set("_ToView0", toView.m00(), toView.m10(), toView.m20(), toView.m30())
                .set("_ToView1", toView.m01(), toView.m11(), toView.m21(), toView.m31())
                .set("_ToView2", toView.m02(), toView.m12(), toView.m22(), toView.m32())
                .set("_AirSpread", airSpread[0], airSpread[1], 0f, 0f);
    }

    /**
     * {@code projection} as {@code perspective × bob}: a perspective looking down -z from the origin, and the rigid
     * turn and shift a host folded in after it (Minecraft's view bobbing, carried in its projection). Without one,
     * {@code perspective} is {@code projection} and {@code bob} the identity. An RQ split of the x, y and w rows.
     */
    static void splitPerspective(Matrix4fc projection, Matrix4f perspective, Matrix4f bob) {
        Vector3f a0 = new Vector3f(projection.m00(), projection.m10(), projection.m20());
        Vector3f a1 = new Vector3f(projection.m01(), projection.m11(), projection.m21());
        Vector3f r2 = new Vector3f(projection.m03(), projection.m13(), projection.m23());
        float k22 = r2.length();
        // An orthographic projection's w row is constant: no eye to turn about.
        if (k22 < 1e-6f) {
            perspective.set(projection);
            bob.identity();
            return;
        }
        r2.div(k22);
        float k12 = a1.dot(r2);
        Vector3f r1 = new Vector3f(r2).mul(-k12).add(a1);
        float k11 = r1.length();
        r1.div(k11);
        float k02 = a0.dot(r2), k01 = a0.dot(r1);
        Vector3f r0 = new Vector3f(a0).sub(new Vector3f(r1).mul(k01)).sub(new Vector3f(r2).mul(k02));
        float k00 = r0.length();
        r0.div(k00);
        // A flipped axis (a y-down projection) stays the perspective's, so the bob is a turn and never a mirror.
        if (r0.x < 0f) {
            r0.negate();
            k00 = -k00;
        }
        if (r1.y < 0f) {
            r1.negate();
            k11 = -k11;
            k01 = -k01;
        }
        float t2 = projection.m33() / k22, t1 = (projection.m31() - k12 * t2) / k11;
        float t0 = (projection.m30() - k01 * t1 - k02 * t2) / k00;
        // The w row is -z: the bob's third row is r2 turned round, so no bob is the identity.
        bob.set(r0.x, r1.x, -r2.x, 0f, r0.y, r1.y, -r2.y, 0f, r0.z, r1.z, -r2.z, 0f, t0, t1, -t2, 1f);
        projection.mul(bob.invertAffine(perspective), perspective);
    }

    /**
     * Adds this firing's lights onto the scene behind each pixel, after {@link #record} answered true: one full-screen
     * pass over the stage's target, Unreal's simple lights through the grid. Skipped while its program is unbuilt.
     */
    void apply(CgStageFrame stage, CgRecording recording, boolean transparents) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, ZONE_APPLY)) {
            localLights = null;
            if (applyPoints == null) {
                applyPoints = CgMaterial.newInstance(APPLY_SHADER);
                applyShaped = CgMaterial.newInstance(APPLY_SHADER);
                applyShaped.enableKeyword("CG_LIGHTS_SHAPED");
                gbufferPass = CgMaterial.newInstance(GBUFFER_SHADER);
            }
            // Both warmed, so a spot turning up never compiles in a frame.
            if (!applyPoints.prepare() | !applyShaped.prepare() | !gbufferPass.prepare()) return;
            recordGBuffer(stage, recording);
            sunOn = sunWanted(stage);
            shadowsOn = SHADOWS.get() && (shadowedAdded > 0 || blockCount > 0 || sunOn) && recordShadows(stage, recording);
            if (transparents && binned && VOLUME.get()) recordVolume(stage, recording);
            if (!binned && !shadowsOn) return;
            CgGraphBuffer none = zeroes(recording);
            CgMaterial apply = (shadowsOn ? shapedExact : shapedAny) ? applyShaped : applyPoints;
            apply.buffer("CELLS", binned ? cells : none)
                    .buffer("INDICES", binned ? indices : none).buffer("VIEWED", binned ? viewed : none)
                    .applyProperties(applyValues);
            apply.applyProperties(applyWindow);
            CgPipeline pipeline = apply.pipeline(CgInstanceKind.OBJECT);
            if (pipeline == null) return;
            CgRasterPass add = recording.raster(stage.target(), CgLoad.load(), stage.constants(), null, CgOrder.SORTED)
                    .sceneDepth(CgBindingPoints.DEPTH_TEXTURE_UNIT).sceneColor(CgBindingPoints.SCENE_COLOR_TEXTURE_UNIT)
                    // What the scene was lit by, divided out to find its albedo: unbound, it reads white and divides by 1.
                    .texture(CgBindingPoints.LIGHTMAP_TEXTURE_UNIT, stage.host().textures().lightmapTexture()).timed(GPU_APPLY);
            CgChunkBuilder chunks = recording.chunks().begin();
            chunks.draw(pipeline, apply.captureBindings(recording.bindings()), FULLSCREEN);
            chunks.instance();
            add.add(chunks.end());
            add.end();
        }
    }

    /** This firing's G-buffer: every pixel's normal, which the apply and the shadow passes read. */
    private void recordGBuffer(CgStageFrame stage, CgRecording recording) {
        int w = Math.max(1, (int) targetW), h = Math.max(1, (int) targetH);
        if (gbuffer == null || gbuffer.getWidth() != w || gbuffer.getHeight() != h) {
            gbuffer = CgGraphTexture.transientTexture("cg_world_lights.gbuffer", new CgTextureDesc(w, h, GBUFFER_FORMAT));
        }
        gbufferPass.applyProperties(gbufferValues);
        fullScreen(stage, recording, gbuffer, gbufferPass, GPU_GBUFFER);
    }

    /**
     * The lights casting shadows, lit as Unreal's MegaLights lights them (MegaLights.cpp's pass order): four samples a
     * pixel, or a downsampled one ({@link #DOWNSAMPLE}), picked by weighted reservoir from its cell's lights and traced across the screen then
     * through the occluders ({@code world_lights_sample}); every pixel shaded from a nearby downsampled pixel's
     * samples ({@code world_lights_shade}); accumulated over frames into the first firing's history
     * ({@code world_lights_temporal}); filtered across the screen where still noisy ({@code world_lights_denoise}). The
     * apply adds the result. False while a program is unbuilt, and the apply lights every light unshadowed.
     */
    private boolean recordShadows(CgStageFrame stage, CgRecording recording) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, ZONE_SHADOWS)) {
            if (sample == null) {
                sample = CgMaterial.newInstance(SAMPLE_SHADER);
                shade = CgMaterial.newInstance(SHADE_SHADER);
                temporal = CgMaterial.newInstance(TEMPORAL_SHADER);
                denoise = CgMaterial.newInstance(DENOISE_SHADER);
                tiles = CgMaterial.newInstance(TILES_SHADER);
                tileDepth = CgMaterial.newInstance(TILE_DEPTH_SHADER);
            }
            // Each started, whichever is not ready.
            boolean ready = sample.prepare() & shade.prepare() & temporal.prepare() & denoise.prepare() & tiles.prepare()
                    & tileDepth.prepare();
            if (!ready) return false;
            int mode = Math.round(DOWNSAMPLE.get());
            downX = mode > 0 ? 2 : 1;
            downY = mode > 1 ? 2 : 1;
            // GetNumSamplesPerPixel2d: a downsampled pixel's samples as a block of the samples texture, 1x1, 2x1 or 2x2.
            int count = SAMPLES.get();
            sampleX = count >= 2 ? 2 : 1;
            sampleY = count >= 4 ? 2 : 1;
            int w = Math.max(1, (int) targetW), h = Math.max(1, (int) targetH);
            int sw = (w + downX - 1) / downX * sampleX, sh = (h + downY - 1) / downY * sampleY;
            if (samples == null || samples.getWidth() != sw || samples.getHeight() != sh) {
                samples = CgGraphTexture.transientTexture("cg_world_lights.samples", new CgTextureDesc(sw, sh, SAMPLES_FORMAT));
            }
            // Shading, history and filter at the target's size, or half it each way under HALF; the samples keep theirs.
            lightScale = HALF.get() ? 2 : 1;
            int lw = (w + lightScale - 1) / lightScale, lh = (h + lightScale - 1) / lightScale;
            if (shaded == null || shaded.getWidth() != lw || shaded.getHeight() != lh) {
                shaded = CgGraphTexture.transientTexture("cg_world_lights.shaded", new CgTextureDesc(lw, lh, LIGHTING_FORMAT));
                filtered = CgGraphTexture.transientTexture("cg_world_lights.filtered", new CgTextureDesc(lw, lh, LIGHTING_FORMAT));
                scratchHistory = CgGraphTexture.transientTexture("cg_world_lights.accumulated", new CgTextureDesc(lw, lh, HISTORY_FORMAT));
            }
            // One history, the first firing's: a later firing of the frame (a second eye) accumulates nothing.
            if (stage.ordinal() == 0 && !CgRehearsal.active()) {
                if (history == null || history.getWidth() != lw || history.getHeight() != lh) {
                    if (history != null) recording.release(history);
                    history = CgGraphTexture.history("cg_world_lights.history", new CgTextureDesc(lw, lh, HISTORY_FORMAT));
                    historyMade = CgFrameRing.frame();
                }
                accumulated = history;
                freshHistory = historyMade == CgFrameRing.frame() || !stage.historyValid();
            } else {
                accumulated = scratchHistory;
                freshHistory = true;
            }
            eyeBlock[0] = (int) Math.floor(cameraX);
            eyeBlock[1] = (int) Math.floor(cameraY);
            eyeBlock[2] = (int) Math.floor(cameraZ);
            eyeFrac[0] = (float) (cameraX - eyeBlock[0]);
            eyeFrac[1] = (float) (cameraY - eyeBlock[1]);
            eyeFrac[2] = (float) (cameraZ - eyeBlock[2]);
            CgGraphBuffer none = zeroes(recording);
            // Past the screen: the window's blocks and the entities' capsules round the eye.
            CgWorldVoxelWindow.get().useDistance();
            CgWorldEntityBoxes.around(cameraX, cameraY, cameraZ, stage.host().partialTick());
            CgWorldEntityBoxes.upload(recording);
            CgGraphBuffer boxes = CgWorldEntityBoxes.buffer();
            entities = boxes != null ? CgWorldEntityBoxes.count() : 0;
            // Our meshes: the fields made since the last firing, then this firing's casters.
            meshFields.upload(recording);
            if (meshFields.atlas() == null) casterCount = 0;
            if (casterCount > 0) {
                casterTexture();
                casterStaging.clear();
                for (int i = 0; i < casterCount * CASTER_FLOATS; i++) casterStaging.putFloat(casterRows[i]);
                casterStaging.flip();
                recording.update(casters, 0, 0, 0, casterCount * CASTER_FLOATS / 4, 1, casterStaging);
            }
            CgTrace.counter(CgChannels.WORLD, "world.lights-casters", casterCount);
            sunMapped = sunOn && recordSun(stage, recording);
            if (entities > 0) {
                int tx = (w + ENTITY_TILE - 1) / ENTITY_TILE, ty = (h + ENTITY_TILE - 1) / ENTITY_TILE;
                if (entityTiles == null || entityTiles.getWidth() != tx || entityTiles.getHeight() != ty) {
                    entityTiles = CgGraphTexture.transientTexture("cg_world_lights.tiles", new CgTextureDesc(tx, ty, TILES_FORMAT));
                    tileRanges = CgGraphTexture.transientTexture("cg_world_lights.tile-ranges",
                            new CgTextureDesc(tx * (ENTITY_TILE / 4), ty * (ENTITY_TILE / 4), RANGES_FORMAT));
                }
                tileDepth.applyProperties(rangeValues);
                fullScreen(stage, recording, tileRanges, tileDepth, GPU_TILE_DEPTH);
                tiles.buffer("ENTITIES", boxes).applyProperties(tileValues);
                fullScreen(stage, recording, entityTiles, tiles, GPU_TILES);
            }
            sample.buffer("VIEWED", binned ? viewed : none).buffer("CELLS", binned ? cells : none)
                    .buffer("INDICES", binned ? indices : none).buffer("ENTITIES", boxes != null ? boxes : none)
                    .applyProperties(sampleValues);
            sample.applyProperties(windowValues);
            fullScreen(stage, recording, samples, sample, GPU_SAMPLE);
            shade.buffer("LIGHTS", binned ? list : none).applyProperties(shadeValues);
            fullScreen(stage, recording, shaded, shade, GPU_SHADE);
            temporal.applyProperties(temporalValues);
            fullScreen(stage, recording, accumulated, temporal, GPU_TEMPORAL);
            denoise.applyProperties(denoiseValues);
            fullScreen(stage, recording, filtered, denoise, GPU_DENOISE);
            return true;
        }
    }

    /**
     * The sun's cascaded shadow map, Unreal's for a directional light: each cascade a sphere round its slice of the view
     * (GetShadowSplitBoundsDepthRange, DirectionalLightComponent.cpp:818-925), grown into the next by the transition
     * (:947-969), its centre snapped to whole texels in absolute light space (ShadowSetup.cpp:1090-1100), and our opaque
     * casters within its box drawn depth-only under its orthographic light view. False while its program is unbuilt.
     */
    private boolean recordSun(CgStageFrame stage, CgRecording recording) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, ZONE_SUN)) {
            if (sunShadow == null) sunShadow = CgMaterial.newInstance(SUN_SHADOW_SHADER);
            if (!sunShadow.prepare()) return false;
            CgPipeline pipeline = sunShadow.pipeline(CgInstanceKind.OBJECT);
            if (pipeline == null) return false;
            int res = SUN_RESOLUTION.get();
            casterTexture();
            if (sunMap == null || sunMap.getWidth() != 2 * res) {
                sunMap = CgGraphTexture.transientTexture("cg_world_lights.sun", new CgTextureDesc(2 * res, 2 * res, SUN_MAP_FORMAT));
            }
            // The pass places each vertex itself, from its cascade's rows: only its depth convention matters.
            stage.defaults(sunConstants).resolution(2 * res, 2 * res);
            sunConstants.view.identity();
            sunConstants.projection.identity();
            CgRasterPass pass = recording.raster(sunMap, SUN_CLEAR, sunConstants, null, CgOrder.SORTED).timed(GPU_SUN);
            CgChunkBuilder chunks = recording.chunks().begin();
            float length = (float) Math.sqrt(sunDirection[0] * sunDirection[0] + sunDirection[1] * sunDirection[1]
                    + sunDirection[2] * sunDirection[2]);
            float sx = sunDirection[0] / length, sy = sunDirection[1] / length, sz = sunDirection[2] / length;
            // Any up not along the sun: Minecraft's sun crosses the x-y plane, so z.
            boolean alongZ = Math.abs(sz) > 0.9f;
            sunRotation.setLookAlong(-sx, -sy, -sz, alongZ ? 1f : 0f, 0f, alongZ ? 0f : 1f);
            toView.invertAffine(viewToEye);
            float distance = SUN_DISTANCE.get(), back = SUN_TRACE.get();
            float tanX = (rays[2] - rays[0]) * 0.5f, tanY = (rays[3] - rays[1]) * 0.5f;
            int binding = sunShadow.captureBindings(recording.bindings());
            float[] rows = sunRowData;
            for (int k = 0; k < CASCADES; k++) {
                float splitNear = SUN_NEAR + accumulatedScale(k) * (distance - SUN_NEAR);
                float splitFar = SUN_NEAR + accumulatedScale(k + 1) * (distance - SUN_NEAR);
                float fade = (splitFar - splitNear) * CASCADE_TRANSITION, fadeStart, fadeEnd;
                if (k < CASCADES - 1) {
                    fadeStart = splitFar;
                    splitFar += fade;
                    fadeEnd = splitFar;
                } else {
                    fadeStart = splitFar - fade;
                    fadeEnd = splitFar;
                }
                float span = splitFar - splitNear;
                float diagonalFar = (tanX * splitFar) * (tanX * splitFar) + (tanY * splitFar) * (tanY * splitFar);
                float diagonalNear = (tanX * splitNear) * (tanX * splitNear) + (tanY * splitNear) * (tanY * splitNear);
                float centreZ = splitFar - ((diagonalNear - diagonalFar) / (2f * span) + span * 0.5f);
                centreZ = Math.max(splitNear, Math.min(splitFar, centreZ));
                float radius = 0f;
                for (int c = 0; c < 8; c++) {
                    float z = (c & 1) == 0 ? splitNear : splitFar;
                    float x = ((c & 2) == 0 ? rays[0] : rays[2]) * z, y = ((c & 4) == 0 ? rays[1] : rays[3]) * z;
                    radius = Math.max(radius, x * x + y * y + (z - centreZ) * (z - centreZ));
                }
                radius = Math.max((float) Math.sqrt(radius), 1f);
                viewToEye.transformPosition(sunCentre.set(0f, 0f, -centreZ));
                // Snapped in absolute light space: a sub-texel move of the eye leaves the map's texels where they were.
                double ax = cameraX + sunCentre.x, ay = cameraY + sunCentre.y, az = cameraZ + sunCentre.z;
                Matrix4f r = sunRotation;
                double lx = r.m00() * ax + r.m10() * ay + r.m20() * az, ly = r.m01() * ax + r.m11() * ay + r.m21() * az;
                double texel = 2.0 * radius / res;
                float dlx = (float) (Math.floor(lx / texel) * texel - lx), dly = (float) (Math.floor(ly / texel) * texel - ly);
                sunCentre.add(r.m00() * dlx + r.m01() * dly, r.m10() * dlx + r.m11() * dly, r.m20() * dlx + r.m21() * dly);
                // The light's eye: past the cascade toward the sun by the casters' reach.
                float lift = radius + back, far = 2f * radius + back;
                float ex = sunCentre.x + sx * lift, ey = sunCentre.y + sy * lift, ez = sunCentre.z + sz * lift;
                // The receiver's rows: eye-relative world to the map's uv, then to depth from the light.
                float inv = 0.5f / radius;
                int o = k * 16;
                rows[o] = r.m00() * inv; rows[o + 1] = r.m10() * inv; rows[o + 2] = r.m20() * inv;
                rows[o + 3] = 0.5f - (r.m00() * ex + r.m10() * ey + r.m20() * ez) * inv;
                rows[o + 4] = r.m01() * inv; rows[o + 5] = r.m11() * inv; rows[o + 6] = r.m21() * inv;
                rows[o + 7] = 0.5f - (r.m01() * ex + r.m11() * ey + r.m21() * ez) * inv;
                rows[o + 8] = -r.m02(); rows[o + 9] = -r.m12(); rows[o + 10] = -r.m22();
                rows[o + 11] = r.m02() * ex + r.m12() * ey + r.m22() * ez;
                // Unreal's r.Shadow.CSMDepthBias of 10 times the cascade's world texel scale, its radius over its
                // resolution (ShadowRendering.cpp:1985-1995): the casters' bias, and the receivers' soft transition.
                rows[o + 12] = splitNear; rows[o + 13] = fadeStart; rows[o + 14] = fadeEnd; rows[o + 15] = 10f * radius / res;
                float qx = (k & 1) == 0 ? -0.5f : 0.5f, qy = (k & 2) == 0 ? -0.5f : 0.5f;
                for (int i = 0; i < sunCasterCount; i++) {
                    float bx = sunBounds[i * 4] - ex, by = sunBounds[i * 4 + 1] - ey, bz = sunBounds[i * 4 + 2] - ez;
                    float br = sunBounds[i * 4 + 3];
                    float vx = r.m00() * bx + r.m10() * by + r.m20() * bz, vy = r.m01() * bx + r.m11() * by + r.m21() * bz;
                    float depth = -(r.m02() * bx + r.m12() * by + r.m22() * bz);
                    if (Math.abs(vx) > radius + br || Math.abs(vy) > radius + br || depth + br < 0f || depth - br > far) continue;
                    chunks.draw(pipeline, binding, sunMeshes[i]);
                    if (sunRanges[i * 3] >= 0) chunks.range(sunRanges[i * 3], sunRanges[i * 3 + 1], sunRanges[i * 3 + 2]);
                    int at = chunks.instance();
                    float[] data = chunks.data();
                    System.arraycopy(sunModels, i * 16, data, at, 16);
                    // CUSTOM0 to 3: the uv rows as -1 to 1 across the cascade, the depth row, the quadrant, 1 / far, the bias.
                    for (int c = 0; c < 4; c++) {
                        data[at + 32 + c] = 2f * rows[o + c] - (c == 3 ? 1f : 0f);
                        data[at + 36 + c] = 2f * rows[o + 4 + c] - (c == 3 ? 1f : 0f);
                        data[at + 40 + c] = rows[o + 8 + c];
                    }
                    data[at + 44] = qx;
                    data[at + 45] = qy;
                    data[at + 46] = 1f / far;
                    data[at + 47] = rows[o + 15];
                }
            }
            pass.add(chunks.end());
            pass.end();
            sunRowStaging.clear();
            for (float v : rows) sunRowStaging.putFloat(v);
            sunRowStaging.flip();
            recording.update(casters, 0, SUN_ROWS_AT, 0, CASCADES * 4, 1, sunRowStaging);
            Arrays.fill(sunMeshes, 0, sunCasterCount, null);
            return true;
        }
    }

    /** The casters' rows, then the sun's cascades' ({@link #SUN_ROWS_AT}): made once. */
    private void casterTexture() {
        if (casters != null) return;
        casters = CgGraphTexture.requested("cg_world_lights.casters", new CgTextureDesc(SUN_ROWS_AT + CASCADES * 4, 1,
                CgFrameBufferFormat.builder("cg_world_lights.casters").color(0, CgTextureType.RGBA32F).build()));
        casterStaging = ByteBuffer.allocateDirect(MOST_CASTERS * CASTER_FLOATS * 4).order(ByteOrder.nativeOrder());
        sunRowStaging = ByteBuffer.allocateDirect(CASCADES * 16 * 4).order(ByteOrder.nativeOrder());
    }

    /** The world's lights on transparent surfaces this firing, for {@code LOCAL_LIGHTS_TEXTURE_UNIT}; null for none. */
    @Nullable
    CgGraphTexture localLights() {
        return localLights;
    }

    /**
     * Unreal's translucency lighting volume (TranslucentLighting.cpp:270-370): each cascade the sphere round its slice of
     * a fixed 45-degree, square view (r.TranslucencyLightingVolume.AdaptToPerspective 0), its centre weighted to the far
     * end and snapped to whole cells in absolute space; then light_volume.compute lights every cell, after the light
     * grid and the shadows' inputs are this firing's.
     */
    private void recordVolume(CgStageFrame stage, CgRecording recording) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, ZONE_VOLUME)) {
            if (fill == null) {
                fill = CgCompute.load(VOLUME_KERNELS).kernel("Fill");
                fill.prepare();
            }
            if (volume == null) {
                volume = CgGraphTexture.transientTexture("cg_world_lights.volume",
                        CgTextureDesc.volume(VOLUME_CELLS + 1, VOLUME_CELLS, 2 * VOLUME_CELLS, VOLUME_FORMAT));
            }
            // The view's forward in eye-relative world: the view's third row turned round.
            float fx = -toView.m02(), fy = -toView.m12(), fz = -toView.m22();
            float norm = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
            fx /= norm;
            fy /= norm;
            fz /= norm;
            float inner = VOLUME_INNER.get(), outer = Math.max(VOLUME_OUTER.get(), inner + 1f);
            for (int c = 0; c < 2; c++) {
                float start = c == 0 ? 0f : inner, end = c == 0 ? inner : outer;
                // A 45-degree square view's corners are their distance out each way; the far four weigh 1 / 4.4 each and the
                // near 1 / 44 (FarVertexWeightScale 10).
                float along = start / 11f + end / 1.1f;
                float radius = (float) Math.max(Math.sqrt(2.0 * end * end + (end - along) * (end - along)),
                        Math.sqrt(2.0 * start * start + (along - start) * (along - start)));
                double cell = 2.0 * radius / VOLUME_CELLS;
                cascades[c * 4] = (float) (Math.floor((cameraX + fx * along) / cell) * cell - cameraX) - radius;
                cascades[c * 4 + 1] = (float) (Math.floor((cameraY + fy * along) / cell) * cell - cameraY) - radius;
                cascades[c * 4 + 2] = (float) (Math.floor((cameraZ + fz * along) / cell) * cell - cameraZ) - radius;
                cascades[c * 4 + 3] = (float) cell;
            }
            CgTexture lightmap = stage.host().textures().lightmapTexture();
            CgComputePass pass = recording.compute("world.lightsVolume", stage.constants()).timed(GPU_VOLUME);
            traceInputs(pass.dispatch(fill, VOLUME_CELLS + 1, VOLUME_CELLS, 2 * VOLUME_CELLS).image("LOCAL_LIGHTS", volume)
                    .set("_VolumeShadowRays", VOLUME_RAYS.get())
                    .set("_Cascade0", cascades[0], cascades[1], cascades[2], cascades[3])
                    .set("_Cascade1", cascades[4], cascades[5], cascades[6], cascades[7])
                    .set("_EyeOffset", eye.x, eye.y, eye.z, 0f), lightmap != null ? lightmap : CgFallbackTextures.WHITE_1x1,
                    zeroes(recording));
            pass.end();
            localLights = volume;
        }
    }

    /**
     * The air lit, after the media's injectors and before their integration: each froxel holding extinction lit by its
     * light-grid cell's lights through light_volume.compute's {@code Scatter} (Unreal's LightScatteringCS), into a
     * froxel volume of the light scattered toward the eye. Null with no light binned this firing.
     */
    @Nullable
    CgGraphTexture scatter(CgRecording recording, CgComputePass pass, CgWorldMedia media, CgGraphTexture extinction) {
        if (!binned || !AIR.get()) return null;
        if (scatterKernel == null) {
            scatterKernel = CgCompute.load(VOLUME_KERNELS).kernel("Scatter");
            scatterKernel.prepare();
        }
        if (air[0] == null || air[0].getWidth() != media.width() || air[0].getHeight() != media.height()) {
            for (int k = 0; k < 2; k++) {
                if (air[k] != null) recording.release(air[k]);
                air[k] = CgGraphTexture.requested("cg_world_lights.air" + k,
                        CgTextureDesc.volume(media.width(), media.height(), CgWorldMedia.SLICES, VOLUME_FORMAT));
            }
            airFiring = -2;
        }
        // Unreal's VolumetricFogTemporalRandom (VolumetricFog.cpp:270-278): Halton 2, 3 and 5 of the frame, or the middle.
        boolean temporal = AIR_HISTORY.get();
        Matrix4fc reproject = media.reproject();
        boolean kept = temporal && reproject != null && airFiring == media.firing() - 1;
        CgGraphTexture into = air[1 - airNewest], last = air[airNewest];
        CgDispatch d = media.rays(pass.dispatch(scatterKernel, media.width(), media.height(), CgWorldMedia.SLICES))
                .image("MEDIA_SCATTER", into).texture("_Media", extinction).set("_PhaseG", AIR_PHASE)
                .set("_AirShadowRays", AIR_RAYS.get()).set("_Frame", (int) (media.firing() & 1023))
                .set("_Jittered", temporal ? 1 : 0)
                .set("_HistoryWeight", kept ? AIR_HISTORY_WEIGHT.get() : 0f).set("_Samples", AIR_SAMPLES.get())
                .texture("_History", kept ? last : CgFallbackTextures.BLACK_VOLUME);
        if (kept) {
            d.set("_Reproject0", reproject.m00(), reproject.m10(), reproject.m20(), reproject.m30())
                    .set("_Reproject1", reproject.m01(), reproject.m11(), reproject.m21(), reproject.m31())
                    .set("_Reproject2", reproject.m02(), reproject.m12(), reproject.m22(), reproject.m32())
                    .set("_Reproject3", reproject.m03(), reproject.m13(), reproject.m23(), reproject.m33());
        }
        traceInputs(d, CgFallbackTextures.WHITE_1x1, zeroes(recording));
        airNewest = 1 - airNewest;
        airFiring = media.firing();
        return into;
    }


    /** What both of light_volume.compute's kernels read: the lights, their grid, and the shadows' inputs this firing. */
    private void traceInputs(CgDispatch d, CgTexture lightmap, CgGraphBuffer none) {
        CgGraphBuffer boxes = CgWorldEntityBoxes.buffer();
        boolean traced = shadowsOn, meshes = traced && casterCount > 0;
        d.bind("LIGHTS", list).bind("CELLS", cells).bind("INDICES", indices)
                .bind("ENTITIES", traced && boxes != null ? boxes : none)
                .set("_Grid", gridX, gridY, SLICES, 0f).set("_Tile", tileU, tileV, 0f, 0f)
                .set("_ZParams", zParams[0], zParams[1], zParams[2], 0f).set("_Rays", rays[0], rays[1], rays[2], rays[3])
                .set("_ToView0", toView.m00(), toView.m10(), toView.m20(), toView.m30())
                .set("_ToView1", toView.m01(), toView.m11(), toView.m21(), toView.m31())
                .set("_ToView2", toView.m02(), toView.m12(), toView.m22(), toView.m32())
                .set("_DayFade", DAY_FADE.get()).set("_Shadows", traced ? 1f : 0f)
                .texture("_Lightmap", lightmap)
                .set("_Skip", SAMPLE_SKIP.get()).set("_EyeX", eyeBlock[0]).set("_EyeY", eyeBlock[1]).set("_EyeZ", eyeBlock[2])
                .set("_EyeFrac", eyeFrac[0], eyeFrac[1], eyeFrac[2], 0f)
                .set("_Entities", traced && boxes != null ? entities : 0).set("_Casters", meshes ? casterCount : 0)
                .texture("_CasterRows", meshes ? casters : CgFallbackTextures.BLACK_1x1)
                .texture("_MeshFields", meshes ? meshFields.atlas() : CgFallbackTextures.BLACK_1x1);
        CgWorldVoxelWindow.get().bind(d);
    }

    /** Unreal's ComputeAccumulatedScale: where split {@code index} of {@link #CASCADES} falls, 0 to 1. */
    private static float accumulatedScale(int index) {
        float scale = 1f, total = 0f, at = 0f;
        for (int i = 0; i < CASCADES; i++) {
            if (i < index) at += scale;
            total += scale;
            scale *= CASCADE_EXPONENT;
        }
        return at / total;
    }

    /**
     * The host's depth as it stands before our opaque draws, kept for this frame's transparent firing: a pixel nearer than
     * it then is ours. The world renderer's opaque firing calls it before its passes, where it draws anything opaque.
     */
    void snapshotHostDepth(CgStageFrame stage, CgRecording recording) {
        if (hostDepthCopy == null) hostDepthCopy = CgMaterial.newInstance(HOST_DEPTH_SHADER);
        if (!hostDepthCopy.prepare()) return;
        int w = Math.max(1, stage.host().width()), h = Math.max(1, stage.host().height());
        if (hostDepth == null || hostDepth.getWidth() != w || hostDepth.getHeight() != h) {
            if (hostDepth != null) recording.release(hostDepth);
            hostDepth = CgGraphTexture.requested("cg_world_lights.host-depth", new CgTextureDesc(w, h, HOST_DEPTH_FORMAT));
        }
        fullScreen(stage, recording, hostDepth, hostDepthCopy, GPU_HOST_DEPTH);
        hostDepthFrame = CgFrameRing.frame();
    }

    /** Whether the world renderer should write our surfaces' normals: the shadow passes read them. */
    boolean wantsNormals() {
        return SHADOWS.get();
    }

    /** This frame's normal target, from the world renderer's opaque firing. */
    void normals(CgGraphTexture target) {
        normals = target;
        normalsFrame = CgFrameRing.frame();
    }

    private boolean normalsNow() {
        return normalsFrame == CgFrameRing.frame() && normals != null;
    }

    /** Whether this frame's opaque firing drew something of ours: the host's depth before it was kept. */
    private boolean ownPixels() {
        return hostDepthFrame == CgFrameRing.frame() && hostDepth != null;
    }

    /** Whether the sun's shadows run this firing: something of ours drawn, under a sun above the horizon. */
    private boolean sunWanted(CgStageFrame stage) {
        if (!SHADOWS.get() || !SUN.get() || SUN_SHARE.get() <= 0f || !ownPixels()) return false;
        stage.host().environment().sunDirection(sunDirection);
        return sunDirection[1] > 0f;
    }

    /** A kilobyte of zeros, made once: no lights, no cells, no entities. */
    private CgGraphBuffer zeroes(CgRecording recording) {
        if (zeroes == null) {
            zeroes = CgGraphBuffer.persistent("cg_world_lights.zeroes", CgBufferDesc.of(1024, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
            recording.fill(zeroes, 0);
        }
        return zeroes;
    }

    /** One full-screen draw of {@code material} into {@code target}, reading the stage's depth and the lightmap. */
    private static void fullScreen(CgStageFrame stage, CgRecording recording, CgGraphTexture target, CgMaterial material,
                                   int zone) {
        CgPipeline pipeline = material.pipeline(CgInstanceKind.OBJECT);
        if (pipeline == null) return;
        CgRasterPass pass = recording.raster(target, CLEAR, stage.constants(), null, CgOrder.SORTED)
                .sceneDepth(CgBindingPoints.DEPTH_TEXTURE_UNIT, stage.target())
                .texture(CgBindingPoints.LIGHTMAP_TEXTURE_UNIT, stage.host().textures().lightmapTexture()).timed(zone);
        CgChunkBuilder chunks = recording.chunks().begin();
        chunks.draw(pipeline, material.captureBindings(recording.bindings()), FULLSCREEN);
        chunks.instance();
        pass.add(chunks.end());
        pass.end();
    }
}
