package com.crystalgraphics.vfx.fluid;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.compute.ops.CgGpuCount;
import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.gl.lifecycle.CgLifecycleListener;
import com.crystalgraphics.gl.texture.CgEngineTextures;
import com.crystalgraphics.noise.CgWaveletNoise;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.CgGpuBudget;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgDispatch;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgTextureDesc;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.render.world.CgWorldMedia;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.settings.CgGraphicsSettings;
import com.crystalgraphics.settings.CgQuality;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.sparse.CgVfxTiles;
import com.crystalgraphics.render.world.CgWorldVoxelWindow;
import com.crystalgraphics.world.CgEntityQueries;
import com.crystalgraphics.vfx.scalability.CgVfxBudget;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * Every fluid's simulation: NVIDIA Flow's grid step (BSD-3-Clause, {@code flow/source/nvflowext/Grid.cpp}
 * {@code simulateStep}) over sparse tiles, each fluid a grid of {@link #SPAN} cells a side about where it was placed,
 * holding cells only where it has smoke, in {@link #TILE}-cell tiles from one pool ({@link CgVfxTiles}). Recorded once a
 * frame on {@link CgRenderStage#WORLD_OPAQUE} after the voxel window: each fluid's fixed steps on its own clock
 * ({@link CgVfxTimeStepper}), then its density carried on by the time left over (Flow's {@code interpolateTimeSteps}),
 * lit, and laid out for its layers. {@link CgVfxFluid} is what an effect uses; this is what a renderer or a check reads.
 *
 * <pre>{@code
 * CgVfxFluidAtlas atlas = CgVfxFluidAtlas.get();
 * CgGraphTexture shade = atlas.shade();     // smoke, temperature, sun, sky: slot s's tile at (s % 8, s / 8 % 8, s / 64) * 20
 * CgGraphTexture restA = atlas.restA();     // Mantaflow's first UV grid, cells moved since its reset; restB() its second
 * CgGraphTexture flow = atlas.flow();       // velocity and temperature, as shade lays them out
 * CgGraphTexture pages = atlas.pages();     // fluid f's tile (x, y, z) at texel (x, y, z + PAGES * f): its slot + 1
 * }</pre>
 *
 * <ul>
 *   <li>The pool holds {@link #tiles(CgQuality)} tiles, its cells each fluid's {@code CELL} times
 *       {@link #cellScale(CgQuality)}: changing tier starts every fluid again empty.</li>
 *   <li>At most {@link #CAPACITY} fluids at once; one asking past that waits, drawing nothing.</li>
 *   <li>A tile is given out where a fluid is active (Flow's {@code SummaryCS}: smoke past 0.02, or smoke moving faster
 *       than a block a second), on the side of each half it is active in, from what the GPU found a frame or two
 *       before.</li>
 *   <li>Its passes are charged to {@link #GPU_BUDGET}: under it, Jacobi's iterations drop.</li>
 *   <li>Render thread.</li>
 * </ul>
 */
public final class CgVfxFluidAtlas {

    /** The most fluids at once. */
    public static final int CAPACITY = 16;
    public static final int TILE = CgVfxTiles.TILE, PAGES = CgVfxTiles.PAGES, SPAN = CgVfxTiles.SPAN, LAYER = CgVfxTiles.LAYER;
    /** A tile's side in the pools the renderer reads: two cells of apron round it, a cubic read's reach. */
    public static final int SHADE_TILE = CgVfxTiles.APRON_TILE;
    /** Flow's PressureJacobiCS on one level takes 40; the fewest the budget drops to. */
    public static final int ITERATIONS = 40, MIN_ITERATIONS = 12;
    /** Where it records on {@code WORLD_OPAQUE}: after the voxel window, which it reads. */
    public static final int ORDER = CgWorldRenderer.ORDER - 89;
    public static final CgGpuBudget GPU_BUDGET = CgGpuBudget.define("vfx.fluid", 2f);
    /** What a fluid layer's shader declares, and the frame binds, in this order. */
    public static final String SHADE_SAMPLER = "_FluidShade", REST_A_SAMPLER = "_FluidRestA", REST_B_SAMPLER = "_FluidRestB",
            PAGES_SAMPLER = "_FluidPages";
    /** A fluid's rows in {@code FLUIDS}: {@code fluid.compute}'s F_ROWS, laid out where it reads them. */
    public static final int ROWS = 16;
    /** Floats an emitter row takes in {@code EMITTERS}: six vec4s. */
    public static final int EMITTER = 24;
    /** A couple rate so high it reaches its target in one step: what a blast's divergence and carve take. */
    public static final float INSTANT = 1.0e6f;
    /** A smoke's cells on the GPU: a cube this many half blocks a side, a fluid each. */
    public static final int FILL = 64;
    /** What a Step kernel taking {@code GAS} declares, all of which {@link #bindFlow} sets. */
    public static final String FLOW_PROPERTIES = """
                _GasVel   ("The fluids' velocity, as the last frame left it", sampler3D) = "black"
                _GasPages ("Each fluid's page table",                         sampler3D) = "black"
                _GasLive  ("1 once there is a fluid",                         int)       = 0
            """;
    /**
     * What a particle step pouring into a fluid appends: where in its cells and which; the smoke and heat poured; its
     * velocity and the rate the fluid's is coupled to it (Flow's point emitter).
     */
    public static final String DEPOSIT_GLSL = "struct FxDeposit { vec4 cell; vec4 amounts; vec4 velocity; };";
    public static final int DEPOSITS = 32768;
    static final int DEPOSIT_CELL = 2, DEPOSIT_CELLS = (TILE / DEPOSIT_CELL) * (TILE / DEPOSIT_CELL) * (TILE / DEPOSIT_CELL);
    /** A deposit node's floats: smoke, heat, then Flow's point sums: weighted velocity, weighted couple rate, weight. */
    static final int DEPOSIT_WORDS = 7, DEPOSIT_BYTES = 48;
    /** The most entities that push the fluids a frame, as box emitters coupling the velocity to theirs. */
    public static final int MAX_ENTITIES = 16;
    /** How fast an entity drags the fluid in its box to its velocity, a second. */
    private static final float ENTITY_COUPLE = 12f;
    /** Milliseconds a fluid's effect may go unticked before its tiles are given back (VFX.md: two seconds). */
    static final int STALE_MILLIS = 2000;
    /** Flow's divergence and burn channels' damping and fade a second (its defaults: 0.01 and 1 cm, 0 and 0). */
    private static final float DIVERGENCE_DAMPING = 0.01f, DIVERGENCE_FADE = 0.01f;

    private static final String KERNELS = "crystalgraphics:shaders/env/compute/vfx/fluid.compute";
    private static final String MEDIA_KERNELS = "crystalgraphics:shaders/env/compute/vfx/media.compute";
    private static final int GPU_ZONE = CgGpuTrace.name("vfx.fluid");
    private static final CgFrameBufferFormat RGBA16F = format("vfx.fluid.rgba16f", CgTextureType.RGBA16F),
            RG16F = format("vfx.fluid.rg16f", CgTextureType.RG16F), R8 = format("vfx.fluid.r8", CgTextureType.R8);
    private static final CgVfxFluidAtlas INSTANCE = new CgVfxFluidAtlas();

    private final CgVfxFluid[] tenants = new CgVfxFluid[CAPACITY];
    private final CgVfxTiles tiles = new CgVfxTiles("vfx.fluid", CAPACITY);
    /** Each fluid's steps this frame and the time past them. */
    private final int[] steps = new int[CAPACITY];
    private final float[] drawSeconds = new float[CAPACITY];
    private int[] rows = new int[0];
    private float[] emitters = new float[0];
    private int rowWords, emitterCount;
    private ByteBuffer staging = ByteBuffer.allocate(0);
    private final CgPassConstants constants = new CgPassConstants();
    private final float[] box = new float[6];

    @Nullable
    private CgQuality quality;
    /** Milliseconds since {@link #ORIGIN} as this frame's record began: the tiles' clock, and what a tick stamps. */
    private int now;
    private static final long ORIGIN = System.nanoTime();
    /** Which of each pair is current: velocity, density, pressure, UV grids. */
    private int vel, den, pressure, rest;
    @Nullable
    private CgGraphTexture shade, flow, restA, restB;
    private final CgGraphTexture[] velocity = new CgGraphTexture[2], density = new CgGraphTexture[2],
            pressures = new CgGraphTexture[2], restAs = new CgGraphTexture[2], restBs = new CgGraphTexture[2];
    private CgGraphTexture predict, curl, lowpass, solid, interpolated, fills;
    @Nullable
    private CgGraphBuffer deposits, depositCount, depositGrid;
    private CgGraphBuffer fluidRows, emitterBuffer, carveBuffer;
    /** What {@link #bindFlow} and {@link #bindDeposits} give a kernel before the first fluid: the smallest, never read. */
    private final CgGraphTexture noFlow = CgGraphTexture.requested("vfx.fluid.none", CgTextureDesc.volume(1, 1, 2, RGBA16F)),
            noPages = CgGraphTexture.requested("vfx.fluid.nopages", CgTextureDesc.volume(1, 1, 2, R8));
    private final CgGraphBuffer noDeposits = CgGraphBuffer.persistent("vfx.fluid.nodeposits",
            CgBufferDesc.of(256, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
    /** Each fluid's smoke cells as last uploaded ({@link #FILL} cubed, stacked along z), and its open carves. */
    private final CgVfxSmokeFill[] filled = new CgVfxSmokeFill[CAPACITY];
    private final byte[] fillBytes = new byte[FILL * FILL * FILL];
    private final ByteBuffer fillUpload = ByteBuffer.allocate(FILL * FILL * FILL);
    private float[] carves = new float[0];
    private int carveCount;
    private final double[] pose = new double[CgEntityQuery.POSE_LENGTH];
    private final int[] lastIds = new int[MAX_ENTITIES];
    private final double[] lastPlaces = new double[MAX_ENTITIES * 3];
    private double lastSeconds = Double.NaN;
    private final EntityGather entityGather = new EntityGather();
    private CgKernel solids, advectDensity1, advectDensity2, emitDensity, advectVelocity1, advectVelocity2, emitVelocity,
            vorticity1, vorticity2, divergence, jacobi, subtract, restKernel, shadeKernel, detailKernel, occupancyKernel,
            depositKernel;
    @Nullable
    private CgKernel mediaKernel;
    private CgRenderStage.Registration registration;
    /** Each fluid's two rows in the world's media ({@code GASES}), camera-relative, and how many are live. */
    private final ByteBuffer mediaUpload = ByteBuffer.allocate(CAPACITY * 32).order(ByteOrder.nativeOrder());
    private int mediaCount;
    @Nullable
    private CgGraphBuffer mediaRows;
    private final CgWorldMedia.Injector media = new CgWorldMedia.Injector() {
        @Override
        public void upload(CgRecording recording, CgWorldMedia into) {
            uploadMedia(recording, into);
        }

        @Override
        public boolean inject(CgComputePass pass, CgWorldMedia into) {
            return injectMedia(pass, into);
        }
    };

    private CgVfxFluidAtlas() {
        Arrays.fill(lastIds, -1);
    }

    public static CgVfxFluidAtlas get() {
        return INSTANCE;
    }

    private static CgFrameBufferFormat format(String name, CgTextureType type) {
        return CgFrameBufferFormat.builder(name).color(0, type).build();
    }

    /** Tiles in the pool at {@code quality}: 1024 at Ultra, 512 at High, 256 at Medium, 128 at Low. */
    public static int tiles(CgQuality quality) {
        return switch (quality) {
            case LOW -> 128;
            case MEDIUM -> 256;
            case HIGH -> 512;
            default -> 1024;
        };
    }

    /** What a fluid's {@code CELL} is multiplied by at {@code quality}: 2 at Low, 1.5 at Medium, else 1. */
    public static float cellScale(CgQuality quality) {
        return switch (quality) {
            case LOW -> 2f;
            case MEDIUM -> 1.5f;
            default -> 1f;
        };
    }

    /** Smoke, temperature, light from the sun and from the sky, with two cells of apron a tile; null before the first fluid. */
    @Nullable
    public CgGraphTexture shade() {
        return shade;
    }

    /** Velocity in blocks a second and temperature, laid out as {@link #shade()}; null before the first fluid. */
    @Nullable
    public CgGraphTexture flow() {
        return flow;
    }

    /** Mantaflow's UV grids, cells each cell's texture has moved since its grid's reset, laid out as {@link #shade()}. */
    @Nullable
    public CgGraphTexture restA() {
        return restA;
    }

    @Nullable
    public CgGraphTexture restB() {
        return restB;
    }

    /** Each fluid's page table, {@link #PAGES} cubed texels a fluid stacked along z: a tile's slot + 1, 0 for none. */
    @Nullable
    public CgGraphTexture pages() {
        return tiles.pages();
    }

    /**
     * The fluids' velocity as the last frame left it, and the page tables, on a dispatch of a kernel declaring
     * {@link #FLOW_PROPERTIES}: {@link #flow()}, which this frame's steps do not write until their end.
     */
    public CgDispatch bindFlow(CgDispatch d) {
        if (flow == null) return d.texture("_GasVel", noFlow).texture("_GasPages", noPages).set("_GasLive", 0);
        return d.texture("_GasVel", flow).texture("_GasPages", tiles.pages()).set("_GasLive", 1);
    }

    /**
     * Where a particle step pouring into a fluid appends ({@code GAS_DEPOSITS}, the Step of a shape taking
     * {@code GAS_FEED}): taken by the fluids' next step, then emptied.
     */
    public CgDispatch bindDeposits(CgDispatch d) {
        CgGraphBuffer into = deposits != null ? deposits : noDeposits;
        return d.bind("GAS_DEPOSITS", into).counter("GAS_DEPOSITS", depositCount != null ? depositCount : noDeposits, 0);
    }

    public int tenants() {
        int n = 0;
        for (CgVfxFluid t : tenants) if (t != null) n++;
        return n;
    }

    public int tilesInUse() {
        return tiles.tilesInUse();
    }

    int acquire(CgVfxFluid fluid) {
        for (int b = 0; b < CAPACITY; b++) {
            if (tenants[b] != null) continue;
            tenants[b] = fluid;
            fluid.ticked = now;
            fluid.cell = fluid.cellSetting() * cellScale(CgGraphicsSettings.QUALITY.get());
            fluid.stepper.reset();
            fluid.owed = 0f;
            if (registration == null) {
                registration = CgRenderStage.WORLD_OPAQUE.registerOncePerFrame(ORDER, CgVfxBudget.charged(this::record));
                CgGraphicsLifecycle.addListener(context);
                CgWorldRenderer.get().media().add(media);
                prepare();
            }
            return b;
        }
        return -1;
    }

    void release(int b) {
        if (b < 0 || b >= CAPACITY) return;
        tenants[b] = null;
        filled[b] = null;
        tiles.freeAll(b);
    }

    /** The atlas's time in milliseconds: what a fluid's tick stamps, so one left unticked is found. */
    int now() {
        return now;
    }

    void wantSphere(int b, float x, float y, float z, float radius, int now) {
        tiles.wantSphere(b, x, y, z, radius, tenants[b].cell, now);
    }

    /**
     * Each fluid's rows for the world's media, from this firing's eye: its grid's low corner and its extinction a unit,
     * then its cell. A fluid with none is written with no extinction, which the kernel skips.
     */
    private void uploadMedia(CgRecording recording, CgWorldMedia into) {
        mediaCount = 0;
        if (shade == null) return;
        mediaUpload.clear();
        for (int b = 0; b < CAPACITY; b++) {
            CgVfxFluid fluid = tenants[b];
            boolean live = fluid != null && fluid.index() == b && fluid.cellSize() > 0f;
            float half = live ? SPAN * 0.5f * fluid.cellSize() : 0f;
            mediaUpload.putFloat(live ? (float) (fluid.x() - into.cameraX()) - half : 0f)
                    .putFloat(live ? (float) (fluid.y() - into.cameraY()) - half : 0f)
                    .putFloat(live ? (float) (fluid.z() - into.cameraZ()) - half : 0f)
                    .putFloat(live ? fluid.value(CgVfxFluid.ATTENUATION) : 0f)
                    .putFloat(live ? fluid.cellSize() : 1f).putFloat(0f).putFloat(0f).putFloat(0f);
            if (live) mediaCount = b + 1;
        }
        if (mediaCount == 0) return;
        mediaRows = CgVfxTiles.fit(recording, mediaRows, "vfx.fluid.media", CAPACITY * 8);
        mediaUpload.flip();
        recording.update(mediaRows, 0, mediaUpload);
    }

    /** Every fluid's smoke in the world's media, so what is drawn behind it is dimmed by it. */
    private boolean injectMedia(CgComputePass pass, CgWorldMedia into) {
        if (mediaCount == 0 || shade == null) return false;
        if (mediaKernel == null) mediaKernel = CgCompute.load(MEDIA_KERNELS).kernel("Gas");
        into.dispatch(pass, mediaKernel).texture("_GasShade", shade).texture("_GasPages", tiles.pages())
                .bind("GASES", mediaRows).set("_Gases", mediaCount);
        return true;
    }

    private final CgLifecycleListener context = new CgLifecycleListener() {
        @Override
        public void onDestroy() {
            shade = flow = restA = restB = predict = curl = lowpass = solid = interpolated = fills = null;
            deposits = depositCount = depositGrid = fluidRows = emitterBuffer = carveBuffer = mediaRows = null;
            Arrays.fill(velocity, null);
            Arrays.fill(density, null);
            Arrays.fill(pressures, null);
            Arrays.fill(restAs, null);
            Arrays.fill(restBs, null);
            Arrays.fill(filled, null);
            quality = null;
            for (int b = 0; b < CAPACITY; b++) tiles.freeAll(b);
            tiles.lost();
        }
    };

    private void prepare() {
        CgCompute file = CgCompute.load(KERNELS);
        solids = file.kernel("Solids");
        advectDensity1 = file.kernel("AdvectDensity1");
        advectDensity2 = file.kernel("AdvectDensity2");
        emitDensity = file.kernel("EmitDensity");
        advectVelocity1 = file.kernel("AdvectVelocity1");
        advectVelocity2 = file.kernel("AdvectVelocity2");
        emitVelocity = file.kernel("EmitVelocity");
        vorticity1 = file.kernel("Vorticity1");
        vorticity2 = file.kernel("Vorticity2");
        divergence = file.kernel("Divergence");
        jacobi = file.kernel("Jacobi");
        subtract = file.kernel("Subtract");
        restKernel = file.kernel("Rest");
        shadeKernel = file.kernel("Shade");
        detailKernel = file.kernel("Detail");
        occupancyKernel = file.kernel("Occupancy");
        depositKernel = file.kernel("Deposit");
        for (CgKernel k : new CgKernel[] {solids, advectDensity1, advectDensity2, emitDensity, advectVelocity1, advectVelocity2,
                emitVelocity, vorticity1, vorticity2, divergence, jacobi, subtract, restKernel, shadeKernel, detailKernel,
                occupancyKernel, depositKernel}) {
            k.prepare();
        }
    }

    private void record(CgStageFrame stage) {
        now = (int) ((System.nanoTime() - ORIGIN) / 1_000_000L);
        int live = 0;
        for (int b = 0; b < CAPACITY; b++) {
            CgVfxFluid fluid = tenants[b];
            if (fluid == null) continue;
            if (fluid.effect().state() == CgVfxEffect.State.DEAD || now - fluid.ticked > STALE_MILLIS) {
                fluid.lost();
                tenants[b] = null;
                filled[b] = null;
                tiles.freeAll(b);
                continue;
            }
            live = b + 1;
        }
        if (live == 0) return;
        CgRecording recording = stage.recording();
        CgQuality want = CgGraphicsSettings.QUALITY.get();
        if (want != quality || shade == null) remake(recording, want);
        grow(live);
        if (tiles.usedLayers() == 0) return;
        int most = 0;
        for (int b = 0; b < live; b++) {
            CgVfxFluid fluid = tenants[b];
            steps[b] = fluid == null ? 0 : fluid.stepper.update(fluid.owed);
            drawSeconds[b] = fluid == null ? 0f : fluid.stepper.timeError();
            if (fluid != null) fluid.owed = 0f;
            most = Math.max(most, steps[b]);
        }
        writeRows(most, live);
        writeEmitters(live);
        writeCarves(live);
        upload(recording);
        uploadFills(recording, live);
        CgWorldVoxelWindow window = CgWorldVoxelWindow.get().use();
        int iterations = Math.max(MIN_ITERATIONS, Math.round(ITERATIONS * GPU_BUDGET.scale()));
        if (most > 0) recording.fill(tiles.occupancy(), 0);
        CgComputePass pass = recording.compute("vfx.fluid", stage.defaults(constants)).timed(GPU_ZONE).timed(GPU_BUDGET).charged(CgVfxBudget.FRAME)
                .async();
        window.bind(bind(pass.dispatch(solids, LAYER * TILE, LAYER * TILE, tiles.usedLayers() * TILE), 0, 0))
                .image("SOLID_OUT", solid);
        if (most > 0) {
            CgGpuOps.fill(pass, depositGrid, 0, CgGpuCount.of(tiles.layers() * LAYER * LAYER * DEPOSIT_CELLS * DEPOSIT_WORDS));
            bind(pass.dispatch(depositKernel, DEPOSITS), 0, 0).bind("DEPOSITS", deposits).bind("DEPOSIT_COUNT", depositCount)
                    .bind("DEPOSIT_GRID", depositGrid);
        }
        for (int s = 0; s < most; s++) step(pass, s * CAPACITY * ROWS, iterations, s == 0 ? 1 : 0);
        // Flow's interpolateTimeSteps: what is drawn is the density carried on by the time past the last step.
        int drawBase = most * CAPACITY * ROWS, fresh = most == 0 ? 1 : 0;
        dispatch(pass, advectDensity1, drawBase, fresh).texture("_Density", density[den]).texture("_Vel", velocity[vel])
                .image("PREDICT_OUT", predict);
        dispatch(pass, advectDensity2, drawBase, fresh).texture("_Density", density[den]).texture("_Vel", velocity[vel])
                .texture("_Predict", predict).image("DENSITY_OUT", interpolated);
        window.bind(bind(pass.dispatch(shadeKernel, LAYER * SHADE_TILE, LAYER * SHADE_TILE, tiles.usedLayers() * SHADE_TILE),
                drawBase, fresh)).texture("_Density", interpolated).texture("_Vel", velocity[vel]).texture("_Solid", solid)
                .image("OUT_A", shade).image("OUT_B", flow);
        bind(pass.dispatch(detailKernel, LAYER * SHADE_TILE, LAYER * SHADE_TILE, tiles.usedLayers() * SHADE_TILE), drawBase, fresh)
                .texture("_RestA", restAs[rest]).texture("_RestB", restBs[rest]).image("OUT_A", restA)
                .image("OUT_B", restB);
        if (most > 0) {
            dispatch(pass, occupancyKernel, drawBase, 0).texture("_Density", density[den]).texture("_Vel", velocity[vel])
                    .bind("OCCUPIED", tiles.occupancy());
            CgGpuOps.fill(pass, depositCount, 0, CgGpuCount.of(1));
        }
        pass.end();
        if (most == 0) return;
        tiles.readOccupancy(recording);
        tiles.stepped();
        for (int b = 0; b < live; b++) {
            CgVfxFluid fluid = tenants[b];
            if (fluid != null) fluid.clock += steps[b] * fluid.stepper.fixedDt();
        }
    }

    /**
     * One step, Flow's {@code simulateStep}: density advected and burnt, emitted into; velocity advected and lifted,
     * emitted into, confined, made divergence-free; then the UV grids carried.
     */
    private void step(CgComputePass pass, int rowBase, int iterations, int fresh) {
        dispatch(pass, advectDensity1, rowBase, fresh).texture("_Density", density[den]).texture("_Vel", velocity[vel])
                .image("PREDICT_OUT", predict);
        dispatch(pass, advectDensity2, rowBase, fresh).texture("_Density", density[den]).texture("_Vel", velocity[vel])
                .texture("_Predict", predict).image("DENSITY_OUT", density[1 - den]);
        den = 1 - den;
        dispatch(pass, emitDensity, rowBase, fresh).texture("_Density", density[den]).texture("_Fill", fills)
                .texture("_Wavelet", CgEngineTextures.resolve(CgWaveletNoise.NAME)).bind("EMITTERS", emitterBuffer)
                .set("_Emitters", emitterCount).bind("CARVES", carveBuffer).set("_Carves", carveCount)
                .bind("DEPOSIT_GRID", depositGrid).set("_Deposit", fresh).image("DENSITY_OUT", density[1 - den]);
        den = 1 - den;
        dispatch(pass, advectVelocity1, rowBase, fresh).texture("_Vel", velocity[vel]).image("PREDICT_OUT", predict);
        dispatch(pass, advectVelocity2, rowBase, fresh).texture("_Vel", velocity[vel]).texture("_Predict", predict)
                .texture("_Density", density[den]).image("VEL_OUT", velocity[1 - vel]);
        vel = 1 - vel;
        dispatch(pass, emitVelocity, rowBase, fresh).texture("_Vel", velocity[vel]).bind("EMITTERS", emitterBuffer)
                .set("_Emitters", emitterCount).bind("DEPOSIT_GRID", depositGrid).image("VEL_OUT", velocity[1 - vel]);
        vel = 1 - vel;
        dispatch(pass, vorticity1, rowBase, fresh).texture("_Vel", velocity[vel]).image("OUT_A", curl)
                .image("OUT_B", lowpass);
        dispatch(pass, vorticity2, rowBase, fresh).texture("_Vel", velocity[vel]).texture("_Curl", curl)
                .texture("_Lowpass", lowpass).texture("_Density", density[den]).image("VEL_OUT", velocity[1 - vel]);
        vel = 1 - vel;
        dispatch(pass, divergence, rowBase, fresh).texture("_Vel", velocity[vel]).image("PRESSURE_OUT", pressures[pressure]);
        for (int i = 0; i < iterations; i++) {
            dispatch(pass, jacobi, rowBase, fresh).texture("_Pressure", pressures[pressure])
                    .image("PRESSURE_OUT", pressures[1 - pressure]);
            pressure = 1 - pressure;
        }
        dispatch(pass, subtract, rowBase, fresh).texture("_Vel", velocity[vel]).texture("_Pressure", pressures[pressure])
                .image("VEL_OUT", velocity[1 - vel]);
        vel = 1 - vel;
        dispatch(pass, restKernel, rowBase, fresh).texture("_Vel", velocity[vel]).texture("_RestA", restAs[rest])
                .texture("_RestB", restBs[rest]).image("OUT_A", restAs[1 - rest]).image("OUT_B", restBs[1 - rest]);
        rest = 1 - rest;
    }

    /** A dispatch of {@code kernel} over the pool's used layers, a texel a cell, reading Solids' cells. */
    private CgDispatch dispatch(CgComputePass pass, CgKernel kernel, int rowBase, int fresh) {
        return bind(pass.dispatch(kernel, LAYER * TILE, LAYER * TILE, tiles.usedLayers() * TILE), rowBase, fresh)
                .texture("_Solid", solid);
    }

    private CgDispatch bind(CgDispatch d, int rowBase, int fresh) {
        return d.bind("FLUIDS", fluidRows).bind("TILES", tiles.tileRows()).texture("_Pages", tiles.pages())
                .set("_RowBase", rowBase).set("_Fresh", fresh);
    }

    /** Each fluid's tiles for this frame, then its box, which its layers draw. */
    private void grow(int live) {
        int alive = 0;
        for (int b = 0; b < live; b++) if (tenants[b] != null) alive |= 1 << b;
        tiles.live(alive);
        for (int b = 0; b < live; b++) {
            CgVfxFluid fluid = tenants[b];
            if (fluid != null) {
                float half = SPAN * 0.5f, c = fluid.cell;
                int floor = (int) Math.ceil(((fluid.floor() - c) / c + half) / TILE - 1);
                tiles.bounds(b, CgVfxTiles.tile(half - fluid.domainX / c),
                        Math.max(floor, CgVfxTiles.tile(half - fluid.domainY / c)), CgVfxTiles.tile(half - fluid.domainZ / c),
                        CgVfxTiles.tile(half + fluid.domainX / c), CgVfxTiles.tile(half + fluid.domainY / c),
                        CgVfxTiles.tile(half + fluid.domainZ / c));
            }
        }
        tiles.grow(now);
        for (int b = 0; b < live; b++) {
            CgVfxFluid fluid = tenants[b];
            if (fluid != null) fluid.wantTiles(this, b, now);
        }
        tiles.finish(now);
        for (int b = 0; b < live; b++) {
            CgVfxFluid fluid = tenants[b];
            if (fluid != null) fluid.box(tiles.bounds(b, fluid.cell, box) ? box : null);
        }
    }

    private void remake(CgRecording recording, CgQuality want) {
        if (shade != null) {
            for (CgGraphTexture t : new CgGraphTexture[] {shade, flow, restA, restB, fills}) recording.release(t);
            for (int i = 0; i < 2; i++) {
                recording.release(velocity[i]);
                recording.release(density[i]);
                recording.release(pressures[i]);
                recording.release(restAs[i]);
                recording.release(restBs[i]);
            }
        }
        quality = want;
        tiles.pool(recording, tiles(want));
        int layers = tiles.layers();
        for (CgVfxFluid fluid : tenants) {
            if (fluid == null) continue;
            fluid.cell = fluid.cellSetting() * cellScale(want);
            fluid.stepper.reset();
        }
        int w = LAYER * TILE, d = layers * TILE, sw = LAYER * SHADE_TILE, sd = layers * SHADE_TILE;
        shade = CgGraphTexture.requested("vfx.fluid.shade", CgTextureDesc.volume(sw, sw, sd, RGBA16F));
        flow = CgGraphTexture.requested("vfx.fluid.flow", CgTextureDesc.volume(sw, sw, sd, RGBA16F));
        restA = CgGraphTexture.requested("vfx.fluid.restA", CgTextureDesc.volume(sw, sw, sd, RGBA16F));
        restB = CgGraphTexture.requested("vfx.fluid.restB", CgTextureDesc.volume(sw, sw, sd, RGBA16F));
        fills = CgGraphTexture.requested("vfx.fluid.fills", CgTextureDesc.volume(FILL, FILL, FILL * CAPACITY, R8));
        Arrays.fill(filled, null);
        if (depositGrid != null) recording.release(depositGrid);
        depositGrid = CgGraphBuffer.persistent("vfx.fluid.depositGrid",
                CgBufferDesc.of((long) layers * LAYER * LAYER * DEPOSIT_CELLS * DEPOSIT_WORDS * 4, CgBufferUsage.STORAGE,
                        CgBufferUsage.COPY));
        if (deposits == null) {
            deposits = CgGraphBuffer.persistent("vfx.fluid.deposits",
                    CgBufferDesc.of((long) DEPOSITS * DEPOSIT_BYTES, CgBufferUsage.STORAGE));
            depositCount = CgGraphBuffer.persistent("vfx.fluid.depositCount",
                    CgBufferDesc.of(256, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
            recording.fill(depositCount, 0);
        }
        if (carveBuffer == null) carveBuffer = CgVfxTiles.fit(recording, null, "vfx.fluid.carves", 12);
        for (int i = 0; i < 2; i++) {
            velocity[i] = CgGraphTexture.requested("vfx.fluid.velocity" + i, CgTextureDesc.volume(w, w, d, RGBA16F));
            density[i] = CgGraphTexture.requested("vfx.fluid.density" + i, CgTextureDesc.volume(w, w, d, RGBA16F));
            pressures[i] = CgGraphTexture.requested("vfx.fluid.pressure" + i, CgTextureDesc.volume(w, w, d, RG16F));
            restAs[i] = CgGraphTexture.requested("vfx.fluid.restA" + i, CgTextureDesc.volume(w, w, d, RGBA16F));
            restBs[i] = CgGraphTexture.requested("vfx.fluid.restB" + i, CgTextureDesc.volume(w, w, d, RGBA16F));
        }
        predict = CgGraphTexture.transientTexture("vfx.fluid.predict", CgTextureDesc.volume(w, w, d, RGBA16F));
        curl = CgGraphTexture.transientTexture("vfx.fluid.curl", CgTextureDesc.volume(w, w, d, RGBA16F));
        lowpass = CgGraphTexture.transientTexture("vfx.fluid.lowpass", CgTextureDesc.volume(w, w, d, RGBA16F));
        interpolated = CgGraphTexture.transientTexture("vfx.fluid.interpolated", CgTextureDesc.volume(w, w, d, RGBA16F));
        solid = CgGraphTexture.transientTexture("vfx.fluid.solid", CgTextureDesc.volume(w, w, d, R8));
    }

    /**
     * {@code most} blocks of every fluid's rows, a step each (a fluid with fewer steps steps by 0 in the rest), then one
     * for what is drawn, its seconds the time past the fluid's last step.
     */
    private void writeRows(int most, int live) {
        rowWords = (most + 1) * CAPACITY * ROWS * 4;
        if (rows.length < rowWords) rows = new int[rowWords];
        Arrays.fill(rows, 0, rowWords, 0);
        for (int b = 0; b < live; b++) {
            CgVfxFluid fluid = tenants[b];
            if (fluid == null) continue;
            float clock = fluid.clock, dt = fluid.stepper.fixedDt(), period = fluid.value(CgVfxFluid.DETAIL_PERIOD);
            for (int s = 0; s <= most; s++) {
                boolean drawing = s == most;
                float seconds = drawing ? drawSeconds[b] : s < steps[b] ? dt : 0f;
                writeFluid(((s * CAPACITY + b) * ROWS) * 4, fluid, seconds, clock, period);
                clock += drawing ? 0f : seconds;
            }
        }
    }

    private void writeFluid(int at, CgVfxFluid f, float seconds, float clock, float period) {
        putf(at, 1f, seconds, f.cell, 0f);
        rows[at + 4] = (int) Math.floor(f.worldX);
        rows[at + 5] = (int) Math.floor(f.worldY);
        rows[at + 6] = (int) Math.floor(f.worldZ);
        rows[at + 7] = Float.floatToRawIntBits(f.floor());
        putf(at + 8, frac(f.worldX), frac(f.worldY), frac(f.worldZ), clock);
        putf(at + 12, f.value(CgVfxFluid.IGNITION_TEMP), f.value(CgVfxFluid.BURN_PER_TEMP), f.value(CgVfxFluid.FUEL_PER_BURN),
                f.value(CgVfxFluid.TEMP_PER_BURN));
        putf(at + 16, f.value(CgVfxFluid.SMOKE_PER_BURN), f.value(CgVfxFluid.DIVERGENCE_PER_BURN), f.value(CgVfxFluid.BUOYANCY_PER_TEMP),
                f.value(CgVfxFluid.BUOYANCY_PER_SMOKE));
        putf(at + 20, f.value(CgVfxFluid.BUOYANCY_MAX_SMOKE), f.value(CgVfxFluid.COOLING_RATE), f.value(CgVfxFluid.GRAVITY),
                f.value(CgVfxFluid.COMBUSTION) > 0.5f ? 1f : 0f);
        putf(at + 24, f.value(CgVfxFluid.VELOCITY_DAMPING), f.value(CgVfxFluid.VELOCITY_FADE), DIVERGENCE_DAMPING, DIVERGENCE_FADE);
        putf(at + 28, f.value(CgVfxFluid.TEMPERATURE_DAMPING), f.value(CgVfxFluid.TEMPERATURE_FADE), f.value(CgVfxFluid.FUEL_DAMPING),
                f.value(CgVfxFluid.FUEL_FADE));
        putf(at + 32, 0f, 0f, f.value(CgVfxFluid.SMOKE_DAMPING), f.value(CgVfxFluid.SMOKE_FADE));
        putf(at + 36, f.value(CgVfxFluid.VORTICITY), f.value(CgVfxFluid.VORTICITY_VELOCITY_MASK),
                f.value(CgVfxFluid.VORTICITY_VELOCITY_LOG_SCALE), f.value(CgVfxFluid.VORTICITY_CONSTANT_MASK));
        putf(at + 40, f.value(CgVfxFluid.VORTICITY_VELOCITY_LINEAR_MASK), 0f, f.value(CgVfxFluid.VORTICITY_TEMPERATURE_MASK),
                f.value(CgVfxFluid.VORTICITY_FUEL_MASK));
        putf(at + 44, f.value(CgVfxFluid.VORTICITY_BURN_MASK), f.value(CgVfxFluid.VORTICITY_SMOKE_MASK), f.value(CgVfxFluid.TURBULENCE),
                f.value(CgVfxFluid.TURBULENCE_SIZE));
        // Rows 12 and 13, a smoke's cells: its fluid's middle from its fill window's low corner in cells and the smoke its
        // cells hold; the cost revealed, how much is left and how fast smoke is pulled to its cells.
        CgVfxSmoke smoke = f.smoke;
        if (smoke != null && smoke.fill() != null) {
            CgVfxSmokeFill fill = smoke.fill();
            double c = CgVfxSmokeFill.CELL, low = FILL / 2;
            putf(at + 48, (float) (f.worldX / c - (fill.originX() - low)), (float) (f.worldY / c - (fill.originY() - low)),
                    (float) (f.worldZ / c - (fill.originZ() - low)), smoke.density());
            putf(at + 52, smoke.revealed(), smoke.presence(), smoke.hold(), 0f);
        }
        // Row 14: Mantaflow's updateUvWeight, a grid reset as its phase wraps.
        boolean resetA = seconds > 0f && CgVfxFluid.phase(clock + seconds, period) < CgVfxFluid.phase(clock, period);
        float half = period * 0.5f;
        boolean resetB = seconds > 0f && CgVfxFluid.phase(clock + seconds + half, period) < CgVfxFluid.phase(clock + half, period);
        putf(at + 56, resetA ? 1f : 0f, resetB ? 1f : 0f, 0f, 0f);
        putf(at + 60, f.value(CgVfxFluid.SHADOW_ATTENUATION), f.value(CgVfxFluid.SHADOW_MIN_INTENSITY), 0f, 0f);
    }

    private void writeEmitters(int live) {
        int n = 0;
        for (int b = 0; b < live; b++) if (tenants[b] != null) n += tenants[b].emitterRows();
        n += MAX_ENTITIES;
        if (emitters.length < n * EMITTER) emitters = new float[n * EMITTER * 2];
        int at = 0;
        for (int b = 0; b < live; b++) if (tenants[b] != null) at = tenants[b].writeEmitters(emitters, at, b);
        at = gatherEntities(live, at);
        emitterCount = at / EMITTER;
    }

    /** Every smoke's open carves, three vec4s each ({@code fluid.compute}'s CARVES). */
    private void writeCarves(int live) {
        int n = 0;
        for (int b = 0; b < live; b++) if (tenants[b] != null && tenants[b].smoke != null) n += tenants[b].smoke.carveRows();
        if (carves.length < Math.max(1, n) * 12) carves = new float[Math.max(1, n) * 24];
        int at = 0;
        for (int b = 0; b < live; b++) {
            CgVfxFluid f = tenants[b];
            if (f == null || f.smoke == null) continue;
            at = f.writeCarves(carves, at, b);
        }
        carveCount = at / 12;
    }

    /** Each smoke's cells into its slab of {@code fills}, once each time its fill changes. */
    private void uploadFills(CgRecording recording, int live) {
        for (int b = 0; b < live; b++) {
            CgVfxSmoke smoke = tenants[b] == null ? null : tenants[b].smoke;
            CgVfxSmokeFill fill = smoke == null ? null : smoke.fill();
            if (fill == null || fill == filled[b]) continue;
            filled[b] = fill;
            fill.window(fillBytes, FILL);
            fillUpload.clear();
            fillUpload.put(fillBytes).flip();
            recording.update(fills, 0, 0, 0, b * FILL, FILL, FILL, FILL, fillUpload);
        }
    }

    private void upload(CgRecording recording) {
        int floats = Math.max(EMITTER, emitterCount * EMITTER);
        fluidRows = CgVfxTiles.fit(recording, fluidRows, "vfx.fluid.rows", rowWords);
        emitterBuffer = CgVfxTiles.fit(recording, emitterBuffer, "vfx.fluid.emitters", floats);
        tiles.upload(recording);
        int bytes = Math.max(rowWords, floats) * 4;
        if (staging.capacity() < bytes) staging = ByteBuffer.allocate(bytes * 2).order(ByteOrder.nativeOrder());
        staging.clear();
        for (int i = 0; i < rowWords; i++) staging.putInt(i * 4, rows[i]);
        staging.limit(rowWords * 4);
        recording.update(fluidRows, 0, staging);
        staging.clear();
        for (int i = 0; i < floats; i++) staging.putFloat(i * 4, i < emitterCount * EMITTER ? emitters[i] : 0f);
        staging.limit(floats * 4);
        recording.update(emitterBuffer, 0, staging);
        int carveFloats = Math.max(12, carveCount * 12);
        carveBuffer = CgVfxTiles.fit(recording, carveBuffer, "vfx.fluid.carves", carveFloats);
        if (staging.capacity() < carveFloats * 4) staging = ByteBuffer.allocate(carveFloats * 8).order(ByteOrder.nativeOrder());
        staging.clear();
        for (int i = 0; i < carveFloats; i++) staging.putFloat(i * 4, i < carveCount * 12 ? carves[i] : 0f);
        staging.limit(carveFloats * 4);
        recording.update(carveBuffer, 0, staging);
    }

    /**
     * One emitter row of {@link #EMITTER} floats into {@code out} at {@code at}: fluid {@code b}'s centre in cells, its
     * extent (a sphere's radius in x, a box's half extents, a cylinder's half axis) and shape (0 sphere, 1 box, 2
     * cylinder), its targets and couple rates, its inflow noise's offset (below 0 for none) and a cylinder's radius.
     * Answers the float after.
     */
    static int emitterRow(float[] out, int at, int b, float cx, float cy, float cz, float ex, float ey, float ez, float shape,
                          float vx, float vy, float vz, float divergence, float temperature, float fuel, float burn, float smoke,
                          float coupleVelocity, float coupleDivergence, float coupleTemperature, float coupleFuel,
                          float coupleBurn, float coupleSmoke, float noise, float cylinderRadius) {
        out[at] = b;
        out[at + 1] = cx;
        out[at + 2] = cy;
        out[at + 3] = cz;
        out[at + 4] = ex;
        out[at + 5] = ey;
        out[at + 6] = ez;
        out[at + 7] = shape;
        out[at + 8] = vx;
        out[at + 9] = vy;
        out[at + 10] = vz;
        out[at + 11] = divergence;
        out[at + 12] = temperature;
        out[at + 13] = fuel;
        out[at + 14] = burn;
        out[at + 15] = smoke;
        out[at + 16] = coupleVelocity;
        out[at + 17] = coupleDivergence;
        out[at + 18] = coupleTemperature;
        out[at + 19] = coupleFuel;
        out[at + 20] = coupleBurn;
        out[at + 21] = coupleSmoke;
        out[at + 22] = noise;
        out[at + 23] = cylinderRadius;
        return at + EMITTER;
    }

    /**
     * The living entities in each fluid's box, the first {@link #MAX_ENTITIES} found, as box emitters coupling the
     * fluid in their box to their velocity, from where each was last frame: Flow's box emitter with
     * {@code physicsVelocityScale}. None on a host with no level.
     */
    private int gatherEntities(int live, int at) {
        double now = CgFrameClock.seconds();
        entityGather.dt = Double.isNaN(lastSeconds) ? 0f : (float) Math.max(1e-3, now - lastSeconds);
        lastSeconds = now;
        entityGather.count = 0;
        entityGather.at = at;
        CgEntityQuery entities = CgEntityQueries.client();
        entityGather.query = entities;
        for (int b = 0; b < live && entityGather.count < MAX_ENTITIES; b++) {
            CgVfxFluid fluid = tenants[b];
            if (fluid == null || !fluid.bounds(box)) continue;
            entityGather.fluid = b;
            entities.within(fluid.worldX + box[0], fluid.worldY + box[1], fluid.worldZ + box[2],
                    fluid.worldX + box[3], fluid.worldY + box[4], fluid.worldZ + box[5], entityGather);
        }
        entityGather.query = null;
        for (int k = 0; k < MAX_ENTITIES; k++) lastIds[k] = k < entityGather.count ? entityGather.ids[k] : -1;
        System.arraycopy(entityGather.places, 0, lastPlaces, 0, MAX_ENTITIES * 3);
        return entityGather.at;
    }

    private final class EntityGather implements IntConsumer {
        CgEntityQuery query;
        float dt;
        int fluid, count, at;
        final int[] ids = new int[MAX_ENTITIES];
        final double[] places = new double[MAX_ENTITIES * 3];

        @Override
        public void accept(int id) {
            if (count >= MAX_ENTITIES || (query.flags(id) & CgEntityQuery.ALIVE) == 0) return;
            int kind = query.kind(id);
            if (kind == CgEntityQuery.KIND_ITEM || kind == CgEntityQuery.KIND_PROJECTILE) return;
            if (!query.pose(id, 1f, pose)) return;
            CgVfxFluid f = tenants[fluid];
            double x = pose[CgEntityQuery.X], y = pose[CgEntityQuery.Y], z = pose[CgEntityQuery.Z];
            float vx = 0f, vy = 0f, vz = 0f;
            for (int k = 0; k < MAX_ENTITIES; k++) {
                if (lastIds[k] != id || dt <= 0f) continue;
                vx = clampSpeed((float) (x - lastPlaces[k * 3]) / dt);
                vy = clampSpeed((float) (y - lastPlaces[k * 3 + 1]) / dt);
                vz = clampSpeed((float) (z - lastPlaces[k * 3 + 2]) / dt);
                break;
            }
            ids[count] = id;
            places[count * 3] = x;
            places[count * 3 + 1] = y;
            places[count * 3 + 2] = z;
            float cell = f.cell, half = SPAN * 0.5f, w = (float) (pose[CgEntityQuery.WIDTH] * 0.5) / cell;
            float h = (float) (pose[CgEntityQuery.HEIGHT] * 0.5) / cell;
            float cx = (float) (x - f.worldX) / cell + half, cy = (float) (y - f.worldY) / cell + half + h;
            float cz = (float) (z - f.worldZ) / cell + half;
            at = emitterRow(emitters, at, fluid, cx, cy, cz, w, h, w, 1f, vx, vy, vz, 0f, 0f, 0f, 0f, 0f,
                    ENTITY_COUPLE, 0f, 0f, 0f, 0f, 0f, -1f, 0f);
            count++;
        }
    }

    /** An entity's speed along an axis, held under 20 blocks a second: a teleport is no gust. */
    private static float clampSpeed(float v) {
        return Math.max(-20f, Math.min(20f, v));
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
}
