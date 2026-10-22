package com.crystalgraphics.render.world;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.shader.CgShaderBindings;
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
 * struct CgLight { vec4 positionRadius; vec4 colorExponent; vec4 spotCos; vec4 segmentCone; vec4 ambient; };
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
            "struct CgLight { vec4 positionRadius; vec4 colorExponent; vec4 spotCos; vec4 segmentCone; vec4 ambient; };";
    /**
     * Floats a light: position and radius, colour and exponent, a spot's direction and the cosine of its outer angle (-2
     * for none), a tube's length along its axis and a spot's 1 / (cos inner - cos outer), the block and sky light at
     * the light (-1 unknown) and its source radius.
     */
    public static final int FLOATS = 20;

    /** Adds lights from a kernel, each transparent firing. */
    public interface Appender {
        /** Before the firing's pass opens, its eye known: uploads what {@link #append} binds. */
        default void upload(CgRecording recording, CgWorldLights lights) {
        }

        /** Appends its lights: {@link #into} each dispatch that does; false when it appends none this firing. */
        boolean append(CgComputePass pass, CgWorldLights lights);
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
    /** What adds the lights onto the scene: a full-screen pass reading the grid. */
    static final String APPLY_SHADER = "crystalgraphics:shaders/world_lights.shader";
    private static final CgMesh FULLSCREEN = CgMesh.vertices(3, CgMeshTopology.TRIANGLES);
    private static final int GPU_APPLY = CgGpuTrace.name("world.lightsApply");
    /** The shadow passes, MegaLights': samples traced, shaded, accumulated over frames, filtered across the screen. */
    static final String SAMPLE_SHADER = "crystalgraphics:shaders/world_lights_sample.shader",
            SHADE_SHADER = "crystalgraphics:shaders/world_lights_shade.shader",
            TEMPORAL_SHADER = "crystalgraphics:shaders/world_lights_temporal.shader",
            DENOISE_SHADER = "crystalgraphics:shaders/world_lights_denoise.shader";
    private static final int GPU_SAMPLE = CgGpuTrace.name("world.lightsSample"), GPU_SHADE = CgGpuTrace.name("world.lightsShade"),
            GPU_TEMPORAL = CgGpuTrace.name("world.lightsTemporal"), GPU_DENOISE = CgGpuTrace.name("world.lightsDenoise");
    /** A sample's light index, exact as a float to 2^24, and its weight. */
    private static final CgFrameBufferFormat SAMPLES_FORMAT = CgFrameBufferFormat.builder("cg_world_lights.samples")
            .color(0, CgTextureType.RG32F).build();
    private static final CgFrameBufferFormat LIGHTING_FORMAT = CgFrameBufferFormat.builder("cg_world_lights.lighting")
            .color(0, CgTextureType.RGBA16F).build();
    /** The accumulated lighting and its frames; its luminance's moments, eye depth and confidence. */
    private static final CgFrameBufferFormat HISTORY_FORMAT = CgFrameBufferFormat.builder("cg_world_lights.history")
            .color(0, CgTextureType.RGBA16F).color(1, CgTextureType.RGBA16F).build();
    private static final CgLoad CLEAR = CgLoad.clear(0f, 0f, 0f, 0f);
    private static final int GPU_ZONE = CgGpuTrace.name("world.lights");
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
    private static final CgVar.Num SCREEN_TRACE = CgVar.number("crystalgraphics.world.lightsScreenTrace", 8f, 0f, 64f,
            CgVar.DEV, "Blocks a shadow ray is followed across the screen's depth before the world's blocks take over.");
    /** Unreal's r.MegaLights.Temporal.MaxFramesAccumulated. */
    private static final CgVar.Num MAX_FRAMES = CgVar.number("crystalgraphics.world.lightsShadowFrames", 12f, 1f, 32f,
            CgVar.DEV, "Frames the shadowed lighting accumulates over: fewer ghost less and shimmer more.");
    /** Unreal's r.MegaLights.Spatial.KernelRadius, in pixels; 0 filters nothing. */
    private static final CgVar.Num SPATIAL_RADIUS = CgVar.number("crystalgraphics.world.lightsShadowSpatial", 8f, 0f, 32f,
            CgVar.DEV, "Pixels the shadowed lighting is filtered across where it is still noisy; 0 for none.");
    /** Unreal's r.Forward.LightGridHZBCull. */
    private static final CgVar.Bool HZB = CgVar.bool("crystalgraphics.world.lightsHzb", true, CgVar.DEV,
            "The light grid skips cells behind the scene and pulls each cell's far edge in to it.");
    private CgKernel view, coarse, cell, countCells, writeCells;
    private float tileU, tileV;
    private CgMaterial apply, sample, shade, temporal, denoise;
    /** Lights added from the CPU this frame that cast shadows. */
    private int shadowedAdded;
    private float targetW, targetH;
    private CgGraphTexture samples, shaded, filtered, scratchHistory, history, accumulated;
    private long historyMade = -1;
    private boolean shadowsOn, freshHistory;
    private CgGraphBuffer noEntities;
    private final float[] eyeFrac = new float[3];
    private final int[] eyeBlock = new int[3];
    private final Consumer<CgShaderBindings> sampleValues = b -> this.gridValues.accept(b.vec4("_Screen", targetW, targetH, 0f, 0f)
            .vec4("_Trace", SCREEN_TRACE.get(), 24f, 0.02f, 0.005f).set1i("_EyeX", eyeBlock[0]).set1i("_EyeY", eyeBlock[1])
            .set1i("_EyeZ", eyeBlock[2]).vec4("_EyeFrac", eyeFrac[0], eyeFrac[1], eyeFrac[2], 0f)
            .set1i("_Entities", this.entities));
    private final Consumer<CgShaderBindings> windowValues = b -> CgWorldVoxelWindow.get().bind(b, 0);
    private int entities;
    private final Consumer<CgShaderBindings> shadeValues = b -> this.gridValues.accept(b.vec4("_Screen", targetW, targetH, 0f, 0f)
            .sampler("_Samples", 0, samples).set1f("_MaxWeight", 20f));
    private final Consumer<CgShaderBindings> temporalValues = b -> this.gridValues.accept(b.vec4("_Eye", eye.x, eye.y, eye.z, 0f)
            .sampler("_Now", 0, shaded).sampler("_Last", 1, freshHistory ? shaded : history.previous())
            .sampler("_LastMoments", 2, freshHistory ? shaded : history.previous().attachment(1))
            .set1f("_Fresh", freshHistory ? 1f : 0f)
            .vec4("_Frames", Math.min(4f, MAX_FRAMES.get()), MAX_FRAMES.get(), 1f, 0.03f));
    private final Consumer<CgShaderBindings> denoiseValues = b -> this.gridValues.accept(b.sampler("_Lighting", 0, accumulated)
            .sampler("_Moments", 1, accumulated.attachment(1)).vec4("_Spatial", SPATIAL_RADIUS.get(), 4f, 10000f, 2f)
            .vec4("_Spread", 2f, 0.15f, MAX_FRAMES.get(), 0f));
    private final Consumer<CgShaderBindings> applyValues = b -> this.gridValues.accept(b.set1f("_Shadows", shadowsOn ? 1f : 0f)
            .sampler("_Shadowed", 0, shadowsOn ? filtered : CgFallbackTextures.BLACK_1x1));
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
        CgRecording recording = frame.recording();
        CgPassConstants stage = frame.constants();
        int cpu = addedFrame == CgFrameRing.frame() ? added : 0;
        if (cpu == 0 && appenders.isEmpty()) return false;
        // The froxels' eye (CgWorldMedia's): the view's position plus where toWorld puts the eye.
        toWorld.transformPosition(eye.set(0f, 0f, 0f));
        cameraX = view.x() + eye.x;
        cameraY = view.y() + eye.y;
        cameraZ = view.z() + eye.z;
        if (list == null) {
            list = CgGraphBuffer.persistent("cg_world_lights",
                    CgBufferDesc.of((long) CAPACITY * FLOATS * 4, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
            count = CgGraphBuffer.persistent("cg_world_lights.count",
                    CgBufferDesc.of(16, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
            staging = ByteBuffer.allocateDirect(CAPACITY * FLOATS * 4).order(ByteOrder.nativeOrder());
            viewed = CgGraphBuffer.persistent("cg_world_lights.view",
                    CgBufferDesc.elements(CAPACITY, 32, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
            alloc = CgGraphBuffer.persistent("cg_world_lights.alloc",
                    CgBufferDesc.elements(4, 4, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
        }
        listed = Math.min(cpu, CAPACITY);
        shadowedAdded = 0;
        targetW = targetWidth;
        targetH = targetHeight;
        if (listed > 0) {
            staging.clear();
            for (int i = 0; i < listed; i++) {
                int p = i * 3, l = i * FLOATS;
                if (light[l + 19] > 0.5f) shadowedAdded++;
                staging.putFloat((float) (at[p] - cameraX)).putFloat((float) (at[p + 1] - cameraY))
                        .putFloat((float) (at[p + 2] - cameraZ));
                for (int w = 3; w < FLOATS; w++) staging.putFloat(light[l + w]);
            }
            staging.flip();
            recording.update(list, 0, staging);
        }
        start[0] = listed;
        recording.update(count, 0, start, 0, 4);
        recording.update(alloc, 0, zeros, 0, 4);
        boolean appended = false;
        for (Appender a : appenders) a.upload(recording, this);
        // The scene's farthest depth per tile, for Unreal's HZB cull: built before the passes that read it open.
        pyramid = frame.depthPyramid();
        CgComputePass pass = recording.compute("world.lights", stage).timed(GPU_ZONE);
        for (Appender a : appenders) appended |= a.append(pass, this);
        boolean binned = listed > 0 || appended;
        if (binned) bin(recording, stage, pass, view.projection(), toWorld, targetWidth, targetHeight);
        else pass.end();
        // Every firing: a surge (a flash, a burst) drops lights the frame it comes, and the budget grows for the next.
        if (binned) recording.readback(alloc, 4, 4, readAlloc);
        long cpuDropped = cpu - listed;
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
        return listed > 0 || appended;
    }

    /**
     * Each cell's lights, as Unreal 5 bins them (LightGridInjection.usf): the lights turned into the grid's space once, a
     * parent grid {@link #PARENT} cells a side culled first where compute runs (LightGridInjection.cpp:1818-1851), then
     * each cell from its parent's list. The grid is sized to the target, {@link #TILE} pixels a cell
     * (LightGridInjection.cpp:659-670).
     */
    private void bin(CgRecording recording, CgPassConstants stage, CgComputePass pass, Matrix4fc projection,
                     Matrix4fc toWorld, float targetWidth, float targetHeight) {
        int gx = Math.max(1, (int) Math.ceil(targetWidth / TILE)), gy = Math.max(1, (int) Math.ceil(targetHeight / TILE));
        gridX = gx;
        gridY = gy;
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
        if (cells == null || cells.size() < n * 8L || parentCells.size() < parents * 8L
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
            cells = CgGraphBuffer.persistent("cg_world_lights.cells",
                    CgBufferDesc.elements(n * 2, 4, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
            indices = CgGraphBuffer.persistent("cg_world_lights.indices",
                    CgBufferDesc.elements(budget, 4, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
            parentCells = CgGraphBuffer.persistent("cg_world_lights.parent-cells",
                    CgBufferDesc.elements(parents * 2, 4, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
            parentIndices = CgGraphBuffer.persistent("cg_world_lights.parent-indices",
                    CgBufferDesc.elements(parentBudget, 4, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
        }
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
        grid(pass.dispatch(view, CAPACITY).bind("LIGHTS", list).bind("COUNT", count).bind("VIEW", viewed)
                .set("_MinScreen", MIN_SCREEN.get()), gx, gy, 0, 0, 0, 0, budget, parentBudget);
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
                .set("_ToView2", toView.m02(), toView.m12(), toView.m22(), toView.m32());
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
    void apply(CgStageFrame stage, CgRecording recording) {
        if (apply == null) apply = CgMaterial.newInstance(APPLY_SHADER);
        if (!apply.prepare()) return;
        shadowsOn = shadowedAdded > 0 && SHADOWS.get() && recordShadows(stage, recording);
        apply.buffer("LIGHTS", list).buffer("CELLS", cells).buffer("INDICES", indices).applyProperties(applyValues);
        CgPipeline pipeline = apply.pipeline(CgInstanceKind.OBJECT);
        if (pipeline == null) return;
        CgRasterPass add = recording.raster(stage.target(), CgLoad.load(), stage.constants(), null, CgOrder.SORTED)
                .sceneDepth(CgBindingPoints.DEPTH_TEXTURE_UNIT)
                // What the scene was lit by, divided out to find its albedo: unbound, it reads white and divides by 1.
                .texture(CgBindingPoints.LIGHTMAP_TEXTURE_UNIT, stage.host().textures().lightmapTexture()).timed(GPU_APPLY);
        CgChunkBuilder chunks = recording.chunks().begin();
        chunks.draw(pipeline, apply.captureBindings(recording.bindings()), FULLSCREEN);
        chunks.instance();
        add.add(chunks.end());
        add.end();
    }

    /**
     * The lights casting shadows, lit as Unreal's MegaLights lights them (MegaLights.cpp's pass order): four samples a
     * half-resolution pixel, picked by weighted reservoir from its cell's lights and traced across the screen then
     * through the occluders ({@code world_lights_sample}); every pixel shaded from a nearby half-resolution pixel's
     * samples ({@code world_lights_shade}); accumulated over frames into the first firing's history
     * ({@code world_lights_temporal}); filtered across the screen where still noisy ({@code world_lights_denoise}). The
     * apply adds the result. False while a program is unbuilt, and the apply lights every light unshadowed.
     */
    private boolean recordShadows(CgStageFrame stage, CgRecording recording) {
        if (sample == null) {
            sample = CgMaterial.newInstance(SAMPLE_SHADER);
            shade = CgMaterial.newInstance(SHADE_SHADER);
            temporal = CgMaterial.newInstance(TEMPORAL_SHADER);
            denoise = CgMaterial.newInstance(DENOISE_SHADER);
        }
        // Each started, whichever is not ready.
        boolean ready = sample.prepare() & shade.prepare() & temporal.prepare() & denoise.prepare();
        if (!ready) return false;
        int w = Math.max(1, (int) targetW), h = Math.max(1, (int) targetH), sw = (w + 1) / 2 * 2, sh = (h + 1) / 2 * 2;
        if (samples == null || samples.getWidth() != sw || samples.getHeight() != sh) {
            samples = CgGraphTexture.transientTexture("cg_world_lights.samples", new CgTextureDesc(sw, sh, SAMPLES_FORMAT));
        }
        if (shaded == null || shaded.getWidth() != w || shaded.getHeight() != h) {
            shaded = CgGraphTexture.transientTexture("cg_world_lights.shaded", new CgTextureDesc(w, h, LIGHTING_FORMAT));
            filtered = CgGraphTexture.transientTexture("cg_world_lights.filtered", new CgTextureDesc(w, h, LIGHTING_FORMAT));
            scratchHistory = CgGraphTexture.transientTexture("cg_world_lights.accumulated", new CgTextureDesc(w, h, HISTORY_FORMAT));
        }
        // One history, the first firing's: a later firing of the frame (a second eye) accumulates nothing.
        if (stage.ordinal() == 0 && !CgRehearsal.active()) {
            if (history == null || history.getWidth() != w || history.getHeight() != h) {
                if (history != null) recording.release(history);
                history = CgGraphTexture.history("cg_world_lights.history", new CgTextureDesc(w, h, HISTORY_FORMAT));
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
        if (noEntities == null) {
            noEntities = CgGraphBuffer.persistent("cg_world_lights.no-entities",
                    CgBufferDesc.of(32, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
        }
        // Past the screen: the window's blocks and the entities' capsules round the eye.
        CgWorldVoxelWindow.get().useDistance();
        CgWorldEntityBoxes.around(cameraX, cameraY, cameraZ, stage.host().partialTick());
        CgWorldEntityBoxes.upload(recording);
        CgGraphBuffer boxes = CgWorldEntityBoxes.buffer();
        entities = boxes != null ? CgWorldEntityBoxes.count() : 0;
        sample.buffer("LIGHTS", list).buffer("CELLS", cells).buffer("INDICES", indices)
                .buffer("ENTITIES", boxes != null ? boxes : noEntities).applyProperties(sampleValues);
        sample.applyProperties(windowValues);
        fullScreen(stage, recording, samples, sample, GPU_SAMPLE);
        shade.buffer("LIGHTS", list).applyProperties(shadeValues);
        fullScreen(stage, recording, shaded, shade, GPU_SHADE);
        temporal.applyProperties(temporalValues);
        fullScreen(stage, recording, accumulated, temporal, GPU_TEMPORAL);
        denoise.applyProperties(denoiseValues);
        fullScreen(stage, recording, filtered, denoise, GPU_DENOISE);
        return true;
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
