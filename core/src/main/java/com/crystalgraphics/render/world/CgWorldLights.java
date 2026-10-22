package com.crystalgraphics.render.world;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.shader.CgShaderBindings;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.gl.buffer.CgReadback;
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
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The point lights in the world this frame: Unreal's simple lights, a position, a radius, a colour and a falloff each,
 * which the world renderer bins into its froxels and adds onto the scene behind each pixel. An effect or a mod adds
 * its lights from the CPU; a kernel appends its own into the same list (a light per particle).
 *
 * <pre>{@code
 * // from the CPU, once a frame, before the world records (a tick function of the client world)
 * CgWorldLights lights = CgWorldRenderer.get().lights();
 * lights.add(x, y + 1.5, z, 6f, 0xFFFF9030, 4f, 0f);      // an orange glow, 6 blocks, inverse-squared
 * lights.add(x, y, z, 3f, 0xFF40A0FF, 2f, 8f);            // a blue one, falling off as Unreal's exponent 8
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
 * struct CgLight { vec4 positionRadius; vec4 colorExponent; };
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
 *   <li>Exponent 0 is inverse-squared falloff, faded to nothing at the radius; any other is Unreal's
 *       {@code (1 - d²/r²)^exponent}.</li>
 *   <li>A kernel's positions are from the firing's eye ({@link #cameraX()}, {@code _LightEye}), as the froxels are.</li>
 *   <li>Past {@link #CAPACITY} lights a frame the rest are dropped and counted ({@code world.lights-dropped}).</li>
 * </ul>
 */
public final class CgWorldLights {

    /** The most lights a frame, the CPU's and the kernels' together: {@code -Dcrystalgraphics.world.lights}. */
    public static final int CAPACITY = Integer.getInteger("crystalgraphics.world.lights", 16384);
    /** The append buffer {@link #into} binds, of {@code lights.glsl}'s {@code CgLight}. */
    public static final String BUFFER = "LIGHTS";
    /**
     * The light as a kernel declares it, in its own file before its {@code Buffers}: a buffer's element is laid out
     * from the kernel's file alone, not its includes.
     */
    public static final String STRUCT = "struct CgLight { vec4 positionRadius; vec4 colorExponent; };";
    /** Floats a light: position and radius, colour and exponent. */
    public static final int FLOATS = 8;

    /** Adds lights from a kernel, each transparent firing. */
    public interface Appender {
        /** Before the firing's pass opens, its eye known: uploads what {@link #append} binds. */
        default void upload(CgRecording recording, CgWorldLights lights) {
        }

        /** Appends its lights: {@link #into} each dispatch that does; false when it appends none this firing. */
        boolean append(CgComputePass pass, CgWorldLights lights);
    }

    /** The light grid, Unreal's (LightGridInjection.cpp:34-50): as {@code lights.glsl}'s {@code CG_LIGHT_*}. */
    public static final int TILE = 64, SLICES = 32, PER_CELL = 32;
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
    private static final int GPU_ZONE = CgGpuTrace.name("world.lights");
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
    private final Matrix4f unproject = new Matrix4f(), toView = new Matrix4f();
    private final Vector4f corner = new Vector4f();
    /** The grid this firing: cells across and up, unit-depth rays at the target's edges, the slices' B, O and S. */
    private int gridX, gridY;
    private final float[] rays = new float[4], zParams = new float[3];
    private CgGraphBuffer cells, indices;
    private CgKernel bin;
    private float tileU, tileV;
    private CgMaterial apply;
    /** The grid's values onto the apply material, read from the fields this firing set. */
    private final Consumer<CgShaderBindings> gridValues = b -> b.vec4("_Grid", gridX, gridY, SLICES, 0f)
            .vec4("_Tile", tileU, tileV, 0f, 0f).vec4("_ZParams", zParams[0], zParams[1], zParams[2], 0f)
            .vec4("_Rays", rays[0], rays[1], rays[2], rays[3])
            .vec4("_ToView0", toView.m00(), toView.m10(), toView.m20(), 0f)
            .vec4("_ToView1", toView.m01(), toView.m11(), toView.m21(), 0f)
            .vec4("_ToView2", toView.m02(), toView.m12(), toView.m22(), 0f);
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
        added++;
        return this;
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

    /** Each cell's light count as the last firing binned it, a {@code uint} a cell, x fastest, then y, then the slice. */
    public CgGraphBuffer cells() {
        return cells;
    }

    /** Each cell's light indices, {@link #PER_CELL} a cell. */
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
    boolean record(CgRecording recording, CgPassConstants stage, CgHostView view, Matrix4fc toWorld, float targetWidth,
                   float targetHeight) {
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
        }
        listed = Math.min(cpu, CAPACITY);
        if (listed > 0) {
            staging.clear();
            for (int i = 0; i < listed; i++) {
                int p = i * 3, l = i * FLOATS;
                staging.putFloat((float) (at[p] - cameraX)).putFloat((float) (at[p + 1] - cameraY))
                        .putFloat((float) (at[p + 2] - cameraZ)).putFloat(light[l + 3])
                        .putFloat(light[l + 4]).putFloat(light[l + 5]).putFloat(light[l + 6]).putFloat(light[l + 7]);
            }
            staging.flip();
            recording.update(list, 0, staging);
        }
        start[0] = listed;
        recording.update(count, 0, start, 0, 4);
        boolean appended = false;
        for (Appender a : appenders) a.upload(recording, this);
        CgComputePass pass = recording.compute("world.lights", stage).timed(GPU_ZONE);
        for (Appender a : appenders) appended |= a.append(pass, this);
        if (listed > 0 || appended) bin(recording, pass, view.projection(), toWorld, targetWidth, targetHeight);
        pass.end();
        long cpuDropped = cpu - listed;
        if (appended && firings++ % COUNT_EVERY == 0) {
            readCpuDropped = cpuDropped;
            recording.readback(count, 0, 4, readCount);
        } else if (!appended) {
            dropped = cpuDropped;
        }
        CgTrace.counter(CgChannels.WORLD, "world.lights", listed);
        CgTrace.counter(CgChannels.WORLD, "world.lights-dropped", dropped);
        return listed > 0 || appended;
    }

    /**
     * Each cell's lights (LightGridInjection.usf), a cell an element: the grid sized to the target, {@link #TILE}
     * pixels a cell, as Unreal sizes it (LightGridInjection.cpp:659-670).
     */
    private void bin(CgRecording recording, CgComputePass pass, Matrix4fc projection, Matrix4fc toWorld, float targetWidth,
                     float targetHeight) {
        int gx = Math.max(1, (int) Math.ceil(targetWidth / TILE)), gy = Math.max(1, (int) Math.ceil(targetHeight / TILE));
        gridX = gx;
        gridY = gy;
        // Persistent, grown only: what a later recording reads back is the last firing's grid.
        int n = gx * gy * SLICES;
        if (cells == null || cells.size() < n * 4L) {
            if (cells != null) {
                recording.release(cells);
                recording.release(indices);
            }
            cells = CgGraphBuffer.persistent("cg_world_lights.cells",
                    CgBufferDesc.elements(n, 4, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
            indices = CgGraphBuffer.persistent("cg_world_lights.indices",
                    CgBufferDesc.elements(n * PER_CELL, 4, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
        }
        // GetLightGridZParams: slice = log2(z * B + O) * S, with the last slice's start at GRID_FAR.
        double o = (GRID_FAR - GRID_NEAR * Math.pow(2, (SLICES - 1) / GRID_SPREAD)) / (GRID_FAR - GRID_NEAR);
        zParams[0] = (float) ((1 - o) / GRID_NEAR);
        zParams[1] = (float) o;
        zParams[2] = GRID_SPREAD;
        projection.invert(unproject);
        for (int c = 0; c < 2; c++) {
            float ndc = c == 0 ? -1f : 1f;
            unproject.transform(corner.set(ndc, ndc, 0.5f, 1f));
            float z = -corner.z / corner.w;
            rays[c * 2] = corner.x / corner.w / z;
            rays[c * 2 + 1] = corner.y / corner.w / z;
        }
        toWorld.invert(toView);
        tileU = TILE / targetWidth;
        tileV = TILE / targetHeight;
        if (bin == null) {
            bin = CgCompute.load(KERNELS).kernel("Bin");
            bin.prepare();
        }
        pass.dispatch(bin, gx * gy * SLICES).bind("LIGHTS", list).bind("COUNT", count).bind("CELLS", cells)
                .bind("INDICES", indices).set("_Grid", gx, gy, SLICES, CAPACITY)
                .set("_Tile", tileU, tileV, 0f, 0f)
                .set("_ZParams", zParams[0], zParams[1], zParams[2], 0f)
                .set("_Rays", rays[0], rays[1], rays[2], rays[3])
                .set("_ToView0", toView.m00(), toView.m10(), toView.m20(), 0f)
                .set("_ToView1", toView.m01(), toView.m11(), toView.m21(), 0f)
                .set("_ToView2", toView.m02(), toView.m12(), toView.m22(), 0f);
    }

    /**
     * Adds this firing's lights onto the scene behind each pixel, after {@link #record} answered true: one full-screen
     * pass over the stage's target, Unreal's simple lights through the grid. Skipped while its program is unbuilt.
     */
    void apply(CgStageFrame stage, CgRecording recording) {
        if (apply == null) apply = CgMaterial.newInstance(APPLY_SHADER);
        if (!apply.prepare()) return;
        apply.buffer("LIGHTS", list).buffer("CELLS", cells).buffer("INDICES", indices).applyProperties(gridValues);
        CgPipeline pipeline = apply.pipeline(CgInstanceKind.OBJECT);
        if (pipeline == null) return;
        CgRasterPass add = recording.raster(stage.target(), CgLoad.load(), stage.constants(), null, CgOrder.SORTED)
                .sceneDepth(CgBindingPoints.DEPTH_TEXTURE_UNIT).timed(GPU_APPLY);
        CgChunkBuilder chunks = recording.chunks().begin();
        chunks.draw(pipeline, apply.captureBindings(recording.bindings()), FULLSCREEN);
        chunks.instance();
        add.add(chunks.end());
        add.end();
    }
}
