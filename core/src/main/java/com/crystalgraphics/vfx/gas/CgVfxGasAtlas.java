package com.crystalgraphics.vfx.gas;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.gl.lifecycle.CgLifecycleListener;
import com.crystalgraphics.render.CgGpuBudget;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgDispatch;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgTextureDesc;
import com.crystalgraphics.render.stage.CgHostEnvironment;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.settings.CgGraphicsSettings;
import com.crystalgraphics.settings.CgQuality;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.world.CgVfxVoxelWindow;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Every gas's simulation: a brick of cells each in one atlas of volumes, stepped together on the GPU at 60 Hz by
 * {@code env/compute/vfx/gas.compute} (stable fluids in GPU Gems 3 ch. 30's order, MacCormack scalars, vorticity
 * confinement, free slip at the voxel window's blocks), at every compute tier. Recorded once a frame on
 * {@link CgRenderStage#WORLD_OPAQUE} after the voxel window; a gas's layers read {@link #shade()}. {@link CgVfxGas} is
 * what an effect uses; this is what a renderer or a check reads.
 *
 * <pre>{@code
 * CgVfxGasAtlas atlas = CgVfxGasAtlas.get();
 * CgGraphTexture shade = atlas.shade();     // a sampler3D: density, temperature, sun, sky light
 * int cells = atlas.cells();                // cells a brick a side; brick b is texels [b * cells, (b + 1) * cells) along x
 * }</pre>
 *
 * <ul>
 *   <li>A brick is {@link #cells(CgQuality)} a side by the quality tier: changing tier starts every gas again empty.</li>
 *   <li>At most {@link #CAPACITY} gases at once; one asking past that waits for a brick, drawing nothing.</li>
 *   <li>Each gas steps on its own effect's clock, so pause and slow motion reach it; a brick owing no step this frame
 *       steps by 0, which keeps it.</li>
 *   <li>Its passes are charged to {@link #GPU_BUDGET}: under it, Jacobi's iterations and the steps a frame drop.</li>
 *   <li>Render thread.</li>
 * </ul>
 */
public final class CgVfxGasAtlas {

    /** Bricks in the atlas: the most gases at once. */
    public static final int CAPACITY = 4;
    /** Seconds a step, and the most a frame. */
    public static final float STEP = 1f / 60f;
    public static final int MAX_STEPS = 2;
    /** Jacobi's iterations a step at full budget, and the fewest it drops to. */
    public static final int ITERATIONS = 24, MIN_ITERATIONS = 8;
    /** Where it records on {@code WORLD_OPAQUE}: after the voxel window, which it reads. */
    public static final int ORDER = CgWorldRenderer.ORDER - 90;
    /** The GPU's frame the gases may take. */
    public static final CgGpuBudget GPU_BUDGET = CgGpuBudget.define("vfx.gas", 2f);
    /** What a gas layer's shader declares, and the frame binds: {@link #shade()}. */
    public static final String SHADE_SAMPLER = "_GasShade";
    /**
     * A brick's rows in {@code BRICKS}, a uvec4 each: its state (alive, step seconds, cell width in blocks, fresh), its
     * origin's whole blocks and its floor from the origin, the fraction within and its buoyancy, its forces (weight, vorticity, density and
     * temperature fades a second), and the rest (velocity damping a second, fuel burnt a second, heat a unit burnt
     * gives, extinction a block a unit of density).
     */
    public static final int ROW_STATE = 0, ROW_ORIGIN = 1, ROW_PLACE = 2, ROW_FORCES = 3, ROW_FADES = 4, ROWS = 5;
    /** Frames a gas's effect may go unticked before its brick is given back: asleep, cleared or dropped. */
    static final int STALE_FRAMES = 120;

    private static final String KERNELS = "crystalgraphics:shaders/env/compute/vfx/gas.compute";
    private static final int GPU_ZONE = CgGpuTrace.name("vfx.gas");
    private static final CgFrameBufferFormat RGBA16F = CgFrameBufferFormat.builder("vfx.gas.rgba16f")
            .color(0, CgTextureType.RGBA16F).build();
    private static final CgFrameBufferFormat R16F = CgFrameBufferFormat.builder("vfx.gas.r16f")
            .color(0, CgTextureType.R16F).build();
    private static final CgFrameBufferFormat R8 = CgFrameBufferFormat.builder("vfx.gas.r8")
            .color(0, CgTextureType.R8).build();
    private static final CgVfxGasAtlas INSTANCE = new CgVfxGasAtlas();

    private final CgVfxGas[] tenants = new CgVfxGas[CAPACITY];
    private final boolean[] fresh = new boolean[CAPACITY];
    /** Each brick's step seconds this frame, a step a column. */
    private final float[] stepSeconds = new float[CAPACITY * MAX_STEPS];
    private int[] rows = new int[0];
    private float[] sources = new float[0];
    /** This frame's words of rows, and sources: those on first, then the blasts, which only the first step takes. */
    private int rowWords, sourceCount, continuous;
    private ByteBuffer staging = ByteBuffer.allocate(0);
    private final float[] sun = new float[3];

    private int cells;
    private long frame;
    /** Which of each pair is current: velocity, scalars, pressure. */
    private int vel, scalars, pressure;
    private boolean reshade;
    @Nullable
    private CgGraphTexture shade;
    private final CgGraphTexture[] velocity = new CgGraphTexture[2], scalar = new CgGraphTexture[2],
            pressures = new CgGraphTexture[2];
    private CgGraphTexture ahead, back, divergence, curl, solid;
    private CgGraphBuffer bricks, sourceBuffer;
    private CgKernel solids, advect, curlKernel, forces, divergenceKernel, jacobi, project, carry, carryBack, settle, shadeKernel;
    private CgRenderStage.Registration registration;

    private CgVfxGasAtlas() {
    }

    public static CgVfxGasAtlas get() {
        return INSTANCE;
    }

    /** Cells a brick a side at {@code quality}: 64 at High and Ultra, 48 at Medium, 32 at Low. */
    public static int cells(CgQuality quality) {
        return switch (quality) {
            case LOW -> 32;
            case MEDIUM -> 48;
            default -> 64;
        };
    }

    /** Cells a brick a side now; 0 before the first gas. */
    public int cells() {
        return cells;
    }

    /** Density, temperature over ambient, transmittance toward the sun and the sky light, a texel a cell; null before the first gas. */
    @Nullable
    public CgGraphTexture shade() {
        return shade;
    }

    /** How many gases hold a brick. */
    public int tenants() {
        int n = 0;
        for (CgVfxGas t : tenants) if (t != null) n++;
        return n;
    }

    /** A brick for {@code gas}, the lowest free one, or -1 with none free; the first registers the recording. */
    int acquire(CgVfxGas gas) {
        for (int b = 0; b < CAPACITY; b++) {
            if (tenants[b] != null) continue;
            tenants[b] = gas;
            fresh[b] = true;
            gas.ticked = frame;
            if (registration == null) {
                registration = CgRenderStage.WORLD_OPAQUE.registerOncePerFrame(ORDER, this::record);
                CgGraphicsLifecycle.addListener(context);
                prepare();
            }
            return b;
        }
        return -1;
    }

    void release(int brick) {
        if (brick >= 0 && brick < CAPACITY) tenants[brick] = null;
    }

    /** The frame the atlas is on: what a gas's tick stamps, so one left unticked is found. */
    long frame() {
        return frame;
    }

    /** A dying context frees the volumes: every gas starts again empty in the next one's. */
    private final CgLifecycleListener context = new CgLifecycleListener() {
        @Override
        public void onDestroy() {
            shade = ahead = back = divergence = curl = solid = null;
            Arrays.fill(velocity, null);
            Arrays.fill(scalar, null);
            Arrays.fill(pressures, null);
            bricks = sourceBuffer = null;
            cells = 0;
            Arrays.fill(fresh, true);
        }
    };

    private void prepare() {
        CgCompute file = CgCompute.load(KERNELS);
        solids = file.kernel("Solids");
        advect = file.kernel("Advect");
        curlKernel = file.kernel("Curl");
        forces = file.kernel("Forces");
        divergenceKernel = file.kernel("Divergence");
        jacobi = file.kernel("Jacobi");
        project = file.kernel("Project");
        carry = file.kernel("Carry");
        carryBack = file.kernel("CarryBack");
        settle = file.kernel("Settle");
        shadeKernel = file.kernel("Shade");
        for (CgKernel k : new CgKernel[] {solids, advect, curlKernel, forces, divergenceKernel, jacobi, project, carry, carryBack,
                settle, shadeKernel}) {
            k.prepare();
        }
    }

    private void record(CgStageFrame stage) {
        frame++;
        int live = 0;
        for (int b = 0; b < CAPACITY; b++) {
            CgVfxGas gas = tenants[b];
            if (gas == null) continue;
            if (gas.effect().state() == CgVfxEffect.State.DEAD || frame - gas.ticked > STALE_FRAMES) {
                gas.lost();
                tenants[b] = null;
                continue;
            }
            live = b + 1;
        }
        if (live == 0) return;
        CgRecording recording = stage.recording();
        int want = cells(CgGraphicsSettings.QUALITY.get());
        if (want != cells || shade == null) remake(recording, want);
        float scale = GPU_BUDGET.scale();
        int most = scale < 0.5f ? 1 : MAX_STEPS;
        int steps = 0;
        for (int b = 0; b < live; b++) {
            CgVfxGas gas = tenants[b];
            if (gas == null) continue;
            gas.owed = Math.min(gas.owed, most * STEP);
            steps = Math.max(steps, (int) (gas.owed / STEP));
            for (int s = 0; s < MAX_STEPS; s++) {
                float dt = s < most && gas.owed >= STEP * (s + 1) ? STEP : 0f;
                stepSeconds[b * MAX_STEPS + s] = dt;
            }
        }
        if (steps == 0 && !reshade) return;
        for (int b = 0; b < live; b++) {
            if (tenants[b] != null) tenants[b].owed -= STEP * Math.min(steps, (int) (tenants[b].owed / STEP));
        }
        CgHostEnvironment world = stage.host().environment();
        boolean sky = !Float.isNaN(world.celestialAngle());
        if (sky) {
            world.sunDirection(sun);
            // The moon, opposite, while the sun is down: as cg_SunDirection.
            float sign = sun[1] < 0f ? -1f : 1f;
            for (int i = 0; i < 3; i++) sun[i] *= sign;
        }
        writeRows(steps, live);
        writeSources(live);
        upload(recording);
        CgVfxVoxelWindow window = CgVfxVoxelWindow.get().use();
        int iterations = Math.max(MIN_ITERATIONS, Math.round(ITERATIONS * scale));
        int width = live * cells;
        CgComputePass pass = recording.compute("vfx.gas").timed(GPU_ZONE).timed(GPU_BUDGET).async();
        dispatch(pass, window, solids, width, 0).image("SOLID_OUT", solid);
        for (int s = 0; s < steps; s++) {
            step(pass, window, width, s * CAPACITY * ROWS, s == 0 ? sourceCount : continuous, iterations);
        }
        dispatch(pass, window, shadeKernel, width, steps * CAPACITY * ROWS)
                .texture("_Scalars", scalar[scalars]).texture("_Solid", solid).image("SHADE_OUT", shade)
                .set("_Sun", sun[0], sun[1], sun[2], sky ? 1f : 0f);
        pass.end();
        reshade = false;
        if (steps == 0) return;
        for (int b = 0; b < live; b++) {
            fresh[b] = false;
            if (tenants[b] != null) tenants[b].stepped();
        }
    }

    /** One step: velocity carried, confined, forced and projected; then the scalars carried by MacCormack. */
    private void step(CgComputePass pass, CgVfxVoxelWindow window, int width, int rowBase, int count, int iterations) {
        CgGraphTexture v0 = velocity[vel], v1 = velocity[1 - vel];
        dispatch(pass, window, advect, width, rowBase).texture("_Vel", v0).image("VEL_OUT", v1);
        dispatch(pass, window, curlKernel, width, rowBase).texture("_Vel", v1).image("CURL_OUT", curl);
        dispatch(pass, window, forces, width, rowBase).texture("_Vel", v1).texture("_Scalars", scalar[scalars])
                .texture("_Curl", curl).bind("SOURCES", sourceBuffer).set("_Sources", count).image("VEL_OUT", v0);
        dispatch(pass, window, divergenceKernel, width, rowBase).texture("_Vel", v0).image("DIV_OUT", divergence);
        for (int i = 0; i < iterations; i++) {
            dispatch(pass, window, jacobi, width, rowBase).texture("_Pressure", pressures[pressure])
                    .texture("_Div", divergence).set("_Iter", i).image("PRESSURE_OUT", pressures[1 - pressure]);
            pressure = 1 - pressure;
        }
        dispatch(pass, window, project, width, rowBase).texture("_Vel", v0).texture("_Pressure", pressures[pressure])
                .image("VEL_OUT", v1);
        vel = 1 - vel;
        CgGraphTexture s0 = scalar[scalars], s1 = scalar[1 - scalars];
        dispatch(pass, window, carry, width, rowBase).texture("_Vel", v1).texture("_Scalars", s0).image("SCALARS_OUT", ahead);
        dispatch(pass, window, carryBack, width, rowBase).texture("_Vel", v1).texture("_Ahead", ahead)
                .image("SCALARS_OUT", back);
        dispatch(pass, window, settle, width, rowBase).texture("_Vel", v1).texture("_Scalars", s0).texture("_Ahead", ahead)
                .texture("_Back", back).bind("SOURCES", sourceBuffer).set("_Sources", count).image("SCALARS_OUT", s1);
        scalars = 1 - scalars;
    }

    private CgDispatch dispatch(CgComputePass pass, CgVfxVoxelWindow window, CgKernel kernel, int width, int rowBase) {
        CgDispatch d = pass.dispatch(kernel, width, cells, cells).bind("BRICKS", bricks)
                .set("_Brick", cells).set("_Bricks", CAPACITY).set("_RowBase", rowBase);
        if (kernel != solids) d.texture("_Solid", solid);
        return window.bind(d);
    }

    /** The volumes at {@code want} cells a brick, the old ones released: every gas starts again empty. */
    private void remake(CgRecording recording, int want) {
        if (shade != null) {
            recording.release(shade);
            for (int i = 0; i < 2; i++) {
                recording.release(velocity[i]);
                recording.release(scalar[i]);
                recording.release(pressures[i]);
            }
        }
        cells = want;
        int w = CAPACITY * want;
        shade = CgGraphTexture.requested("vfx.gas.shade", CgTextureDesc.volume(w, want, want, RGBA16F));
        for (int i = 0; i < 2; i++) {
            velocity[i] = CgGraphTexture.requested("vfx.gas.velocity" + i, CgTextureDesc.volume(w, want, want, RGBA16F));
            scalar[i] = CgGraphTexture.requested("vfx.gas.scalars" + i, CgTextureDesc.volume(w, want, want, RGBA16F));
            pressures[i] = CgGraphTexture.requested("vfx.gas.pressure" + i, CgTextureDesc.volume(w, want, want, R16F));
        }
        ahead = CgGraphTexture.transientTexture("vfx.gas.ahead", CgTextureDesc.volume(w, want, want, RGBA16F));
        back = CgGraphTexture.transientTexture("vfx.gas.back", CgTextureDesc.volume(w, want, want, RGBA16F));
        curl = CgGraphTexture.transientTexture("vfx.gas.curl", CgTextureDesc.volume(w, want, want, RGBA16F));
        divergence = CgGraphTexture.transientTexture("vfx.gas.divergence", CgTextureDesc.volume(w, want, want, R16F));
        solid = CgGraphTexture.transientTexture("vfx.gas.solid", CgTextureDesc.volume(w, want, want, R8));
        Arrays.fill(fresh, true);
        for (CgVfxGas gas : tenants) if (gas != null) gas.owed = Math.max(gas.owed, STEP);
        reshade = true;
    }

    /**
     * {@code steps} blocks of every brick's rows, then one for Shade, stepping nothing. A brick is fresh in the first
     * block alone: after a step it holds what that step wrote, and with none it holds nothing yet.
     */
    private void writeRows(int steps, int live) {
        rowWords = (steps + 1) * CAPACITY * ROWS * 4;
        if (rows.length < rowWords) rows = new int[rowWords];
        Arrays.fill(rows, 0, rowWords, 0);
        for (int s = 0; s <= steps; s++) {
            for (int b = 0; b < live; b++) {
                CgVfxGas gas = tenants[b];
                if (gas == null) continue;
                int at = ((s * CAPACITY + b) * ROWS) * 4;
                float cell = gas.extent() / cells;
                float dt = s < steps ? stepSeconds[b * MAX_STEPS + s] : 0f;
                putf(at, 1f, dt, cell, s == 0 && fresh[b] ? 1f : 0f);
                rows[at + 4] = (int) Math.floor(gas.worldX);
                rows[at + 5] = (int) Math.floor(gas.worldY);
                rows[at + 6] = (int) Math.floor(gas.worldZ);
                rows[at + 7] = Float.floatToRawIntBits(gas.floor());
                putf(at + 8, frac(gas.worldX), frac(gas.worldY), frac(gas.worldZ), gas.buoyancy());
                putf(at + 12, gas.weight(), gas.vorticity(), gas.dissipation(), gas.cooling());
                putf(at + 16, gas.damping(), gas.burn(), gas.heat(), gas.extinction());
            }
        }
    }

    /** Every gas's sources on, then its blasts. */
    private void writeSources(int live) {
        int n = 0;
        for (int b = 0; b < live; b++) if (tenants[b] != null) n += tenants[b].sourceRows();
        if (sources.length < Math.max(1, n) * 12) sources = new float[Math.max(12, n * 24)];
        int at = 0;
        for (int b = 0; b < live; b++) if (tenants[b] != null) at = tenants[b].writeSources(sources, at, b, cells, false);
        continuous = at / 12;
        for (int b = 0; b < live; b++) if (tenants[b] != null) at = tenants[b].writeSources(sources, at, b, cells, true);
        sourceCount = at / 12;
    }

    private void upload(CgRecording recording) {
        int words = rowWords, floats = Math.max(12, sourceCount * 12);
        bricks = fit(recording, bricks, "vfx.gas.bricks", words);
        sourceBuffer = fit(recording, sourceBuffer, "vfx.gas.sources", floats);
        int bytes = Math.max(words, floats) * 4;
        if (staging.capacity() < bytes) staging = ByteBuffer.allocate(bytes * 2).order(ByteOrder.nativeOrder());
        staging.clear();
        for (int i = 0; i < words; i++) staging.putInt(i * 4, rows[i]);
        staging.limit(words * 4);
        recording.update(bricks, 0, staging);
        staging.clear();
        for (int i = 0; i < floats; i++) staging.putFloat(i * 4, i < sourceCount * 12 ? sources[i] : 0f);
        staging.limit(floats * 4);
        recording.update(sourceBuffer, 0, staging);
    }

    private void putf(int at, float x, float y, float z, float w) {
        rows[at] = Float.floatToRawIntBits(x);
        rows[at + 1] = Float.floatToRawIntBits(y);
        rows[at + 2] = Float.floatToRawIntBits(z);
        rows[at + 3] = Float.floatToRawIntBits(w);
    }

    private static float frac(double v) {
        return (float) (v - Math.floor(v));
    }

    private static CgGraphBuffer fit(CgRecording recording, @Nullable CgGraphBuffer buffer, String name, int words) {
        long bytes = Math.max(256L, words * 4L);
        if (buffer != null && buffer.size() >= bytes) return buffer;
        if (buffer != null) recording.release(buffer);
        return CgGraphBuffer.persistent(name, CgBufferDesc.of(Long.highestOneBit(bytes - 1) << 1,
                CgBufferUsage.STORAGE, CgBufferUsage.COPY));
    }
}
