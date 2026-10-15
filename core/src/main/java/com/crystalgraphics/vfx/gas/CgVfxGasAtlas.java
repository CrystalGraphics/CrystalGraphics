package com.crystalgraphics.vfx.gas;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.gl.lifecycle.CgLifecycleListener;
import com.crystalgraphics.compute.ops.CgGpuCount;
import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.world.CgEntityQueries;
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
import com.crystalgraphics.vfx.world.CgVfxVoxelWindow;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * Every gas's simulation, sparse: each gas a grid of {@link #SPAN} cells a side about where it was placed, holding cells
 * only where it has gas, in tiles of {@link #TILE} cells drawn from one pool, as OpenVDB, EmberGen and Houdini's sparse
 * Pyro hold theirs. A tile is given out where gas arrives and taken back once it has been empty a while, so smoke flows
 * as far as it goes. Stepped together on the GPU at 60 Hz by {@code env/compute/vfx/gas.compute} (stable fluids in Pyro's
 * order, MacCormack scalars, confinement, turbulence, expansion, free slip at the voxel window's blocks), at every
 * compute tier. Recorded once a frame on {@link CgRenderStage#WORLD_OPAQUE} after the voxel window; a gas's layers read
 * {@link #shade()}, {@link #flow()} and {@link #pages()}. {@link CgVfxGas} is what an effect uses; this is what a renderer
 * or a check reads.
 *
 * <pre>{@code
 * CgVfxGasAtlas atlas = CgVfxGasAtlas.get();
 * CgGraphTexture pages = atlas.pages();     // a sampler3D: gas g's tile (x, y, z) at texel (x, y, z + PAGES * g), slot + 1
 * CgGraphTexture shade = atlas.shade();     // density, flame, sun, sky: slot s's tile at (s % 8, s / 8 % 8, s / 64) * 20
 * CgGraphTexture flow = atlas.flow();       // velocity, temperature, laid out as shade, two cells of apron round each tile
 * }</pre>
 *
 * <ul>
 *   <li>The pool holds {@link #tiles(CgQuality)} tiles, its cells as fine as each gas's {@code CELL} times
 *       {@link #cellScale(CgQuality)}: changing tier starts every gas again empty.</li>
 *   <li>At most {@link #CAPACITY} gases at once; one asking past that waits, drawing nothing. A gas finding the pool
 *       full grows no further, and what flows past its tiles is lost there.</li>
 *   <li>A tile is given out on the face its gas is near, from what the GPU found a frame or two before: gas flowing
 *       faster than a tile's last cells in that time loses what runs ahead.</li>
 *   <li>Each gas steps on its own effect's clock, so pause and slow motion reach it; a gas owing no step this frame steps
 *       by 0, which keeps it.</li>
 *   <li>Its passes are charged to {@link #GPU_BUDGET}: under it, Jacobi's iterations and the steps a frame drop.</li>
 *   <li>Render thread.</li>
 * </ul>
 */
public final class CgVfxGasAtlas {

    /** The most gases at once. */
    public static final int CAPACITY = 4;
    /** Cells a tile side; tiles a gas's page table side; cells a gas's grid side, its origin in the middle: {@link CgVfxTiles}'. */
    public static final int TILE = CgVfxTiles.TILE, PAGES = CgVfxTiles.PAGES, SPAN = CgVfxTiles.SPAN;
    /** Pool tiles a layer side: slot s is pool tile (s % LAYER, s / LAYER % LAYER, s / LAYER²). */
    public static final int LAYER = CgVfxTiles.LAYER;
    /** A tile's side in {@link #shade()} and {@link #flow()}: two cells of apron round it, a cubic read's reach. */
    public static final int SHADE_TILE = CgVfxTiles.APRON_TILE;
    /** Seconds a step, and the most a frame. */
    public static final float STEP = 1f / 60f;
    public static final int MAX_STEPS = 2;
    /** Jacobi's iterations a step at full budget, and the fewest it drops to. */
    public static final int ITERATIONS = 24, MIN_ITERATIONS = 8;
    /** Where it records on {@code WORLD_OPAQUE}: after the voxel window, which it reads. */
    public static final int ORDER = CgWorldRenderer.ORDER - 90;
    /** The GPU's frame the gases may take. */
    public static final CgGpuBudget GPU_BUDGET = CgGpuBudget.define("vfx.gas", 2f);
    /** What a gas layer's shader declares, and the frame binds, in this order: {@link #shade()}, {@link #flow()}, {@link #pages()}. */
    public static final String SHADE_SAMPLER = "_GasShade", FLOW_SAMPLER = "_GasFlow", PAGES_SAMPLER = "_GasPages";
    /** What a Step kernel taking {@code GAS} declares, all of which {@link #bindFlow} sets. */
    public static final String FLOW_PROPERTIES = """
                _GasVel   ("The gas pool's flow, as the last frame left it", sampler3D) = "black"
                _GasPages ("Each gas's page table",                         sampler3D) = "black"
                _GasLive  ("1 once there is a gas",                         int)       = 0
            """;
    /**
     * A gas's rows in {@code GASES}, a uvec4 each: its state (alive, step seconds, cell width in blocks), its origin's
     * whole blocks and its floor from the origin, the fraction within and its buoyancy, its forces (weight, vorticity,
     * density and temperature fades a second), its fades (velocity damping a second, the share of fuel burnt a second,
     * the temperature it burns at, extinction a block a unit of density), its motion (expansion, turbulence, turbulence
     * size, the gas's clock), the rest (disturbance, its size, the smoke a unit of fuel leaves), and a smoke's cells
     * (its middle from its fill window's low corner in cells and the density its cells hold, 0 for none; then the cost
     * revealed, how much is left and how fast density is pulled to its cells).
     */
    public static final int ROW_STATE = 0, ROW_ORIGIN = 1, ROW_PLACE = 2, ROW_FORCES = 3, ROW_FADES = 4, ROW_MOTION = 5,
            ROW_DISTURB = 6, ROW_FILL = 7, ROW_REVEAL = 8, ROWS = 9;
    /** A smoke's cells on the GPU: a cube this many {@link CgVfxSmokeFill#CELL}s a side, centred on its first, a gas each. */
    public static final int FILL = 64;
    /**
     * What a particle step pouring into a gas appends ({@code GAS_DEPOSITS}, {@link #bindDeposits}): where in a gas's
     * cells and which gas, then the density and heat poured.
     */
    public static final String DEPOSIT_GLSL = "struct FxDeposit { vec4 cell; vec4 amounts; };";
    /** The most deposits particles make between two steps, every instance's together; the rest are dropped. */
    public static final int DEPOSITS = 32768;
    /** Deposits are spread over nodes this many of a gas's cells apart, two floats each: density, heat. */
    static final int DEPOSIT_CELL = 2, DEPOSIT_CELLS = (TILE / DEPOSIT_CELL) * (TILE / DEPOSIT_CELL) * (TILE / DEPOSIT_CELL);
    /** The most entities that push the gases a frame, every gas's together, the first found kept. */
    public static final int MAX_ENTITIES = 16;
    /** Frames a gas's effect may go unticked before its tiles are given back: asleep, cleared or dropped. */
    static final int STALE_FRAMES = 120;

    private static final String KERNELS = "crystalgraphics:shaders/env/compute/vfx/gas.compute";
    private static final int GPU_ZONE = CgGpuTrace.name("vfx.gas");
    private static final CgFrameBufferFormat RGBA16F = CgFrameBufferFormat.builder("vfx.gas.rgba16f")
            .color(0, CgTextureType.RGBA16F).build();
    private static final CgFrameBufferFormat R16F = CgFrameBufferFormat.builder("vfx.gas.r16f")
            .color(0, CgTextureType.R16F).build();
    private static final CgFrameBufferFormat R32F = CgFrameBufferFormat.builder("vfx.gas.r32f")
            .color(0, CgTextureType.R32F).build();
    private static final CgFrameBufferFormat R8 = CgFrameBufferFormat.builder("vfx.gas.r8")
            .color(0, CgTextureType.R8).build();
    private static final CgVfxGasAtlas INSTANCE = new CgVfxGasAtlas();

    private final CgVfxGas[] tenants = new CgVfxGas[CAPACITY];
    /** Every gas's tiles in the one pool. */
    private final CgVfxTiles tiles = new CgVfxTiles("vfx.gas", CAPACITY);
    /** Each gas's step seconds this frame, a step a column. */
    private final float[] stepSeconds = new float[CAPACITY * MAX_STEPS];
    private int[] rows = new int[0];
    private float[] sources = new float[0];
    /** This frame's words of rows, and sources: those on first, then the blasts, which only the first step takes. */
    private int rowWords, sourceCount, continuous;
    private ByteBuffer staging = ByteBuffer.allocate(0);
    /** The pass's frame block: its time, and the sun its layers light it by. */
    private final CgPassConstants constants = new CgPassConstants();
    private final float[] box = new float[6];

    @Nullable
    private CgQuality quality;
    private long frame;
    /** Which of each pair is current: velocity, scalars, pressure. */
    private int vel, scalars, pressure;
    private boolean reshade;
    @Nullable
    private CgGraphTexture shade, flow;
    private final CgGraphTexture[] velocity = new CgGraphTexture[2], scalar = new CgGraphTexture[2],
            pressures = new CgGraphTexture[2];
    private CgGraphTexture ahead, back, divergence, curl, solid;
    /** Each gas's smoke cells ({@link #FILL} cubed, stacked along z): cost + 1, 0 for none; and the fill each holds. */
    @Nullable
    private CgGraphTexture fills;
    private final CgVfxSmokeFill[] filled = new CgVfxSmokeFill[CAPACITY];
    private final byte[] fillBytes = new byte[FILL * FILL * FILL];
    private final ByteBuffer fillUpload = ByteBuffer.allocate(FILL * FILL * FILL);
    /** What particles poured ({@link #bindDeposits}), how many, and their sum per deposit cell; what binds before any gas. */
    @Nullable
    private CgGraphBuffer deposits, depositCount, depositGrid;
    private final CgGraphBuffer noDeposits = CgGraphBuffer.persistent("vfx.gas.nodeposits",
            CgBufferDesc.of(256, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
    /** The entities in the gases this frame, three vec4s each; and each one's last position, to tell its velocity. */
    private CgGraphBuffer entityRows;
    private final float[] entityWords = new float[MAX_ENTITIES * 12];
    private int entityCount;
    private final int[] lastIds = new int[MAX_ENTITIES];
    private final double[] lastPlaces = new double[MAX_ENTITIES * 3];
    private final double[] pose = new double[CgEntityQuery.POSE_LENGTH];
    private float lastSeconds = Float.NaN;
    private int gatheringGas;
    private final EntityGather entityGather = new EntityGather();
    /** What {@link #bindFlow} gives a kernel before the first gas: the smallest volumes, never read. */
    private final CgGraphTexture noFlow = CgGraphTexture.requested("vfx.gas.none", CgTextureDesc.volume(1, 1, 2, RGBA16F)),
            noPages = CgGraphTexture.requested("vfx.gas.nopages", CgTextureDesc.volume(1, 1, 2, R32F));
    private CgGraphBuffer gasRows, sourceBuffer;
    private CgKernel solids, advect, curlKernel, forces, divergenceKernel, jacobi, project, carry, carryBack, settle, shadeKernel,
            occupancyKernel, depositKernel;
    private CgRenderStage.Registration registration;
    /** Each gas's place in the world's media: its grid's low corner from the camera and its extinction, its cell. */
    private final float[] mediaRows = new float[CAPACITY * 4], mediaCells = new float[CAPACITY];
    @Nullable
    private CgKernel mediaKernel;
    private static final String MEDIA_KERNELS = "crystalgraphics:shaders/env/compute/vfx/media.compute";

    private CgVfxGasAtlas() {
        Arrays.fill(lastIds, -1);
    }

    public static CgVfxGasAtlas get() {
        return INSTANCE;
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

    /** What a gas's {@code CELL} is multiplied by at {@code quality}: 2 at Low, 1.5 at Medium, else 1. */
    public static float cellScale(CgQuality quality) {
        return switch (quality) {
            case LOW -> 2f;
            case MEDIUM -> 1.5f;
            default -> 1f;
        };
    }

    /** Density, flame (0 to 1), the transmittance toward the sun and the sky light the gas above leaves; null before the first gas. */
    @Nullable
    public CgGraphTexture shade() {
        return shade;
    }

    /** Velocity in blocks a second and temperature over the air's, laid out as {@link #shade()}; null before the first gas. */
    @Nullable
    public CgGraphTexture flow() {
        return flow;
    }

    /** Each gas's page table, {@link #PAGES} cubed texels a gas stacked along z: a tile's slot + 1, 0 for none. */
    @Nullable
    public CgGraphTexture pages() {
        return tiles.pages();
    }

    /**
     * The flow as the last frame left it, and the page tables, on a dispatch of a kernel declaring
     * {@link #FLOW_PROPERTIES}: {@link #flow()}, which this frame's steps do not write until their end.
     */
    public CgDispatch bindFlow(CgDispatch d) {
        if (flow == null) return d.texture("_GasVel", noFlow).texture("_GasPages", noPages).set("_GasLive", 0);
        return d.texture("_GasVel", flow).texture("_GasPages", tiles.pages()).set("_GasLive", 1);
    }

    /**
     * Where a particle step pouring into a gas appends ({@code GAS_DEPOSITS}, the Step of a shape taking
     * {@code GAS_FEED}): taken by the gases' next step, then emptied.
     */
    public CgDispatch bindDeposits(CgDispatch d) {
        CgGraphBuffer into = deposits != null ? deposits : noDeposits;
        return d.bind("GAS_DEPOSITS", into).counter("GAS_DEPOSITS", depositCount != null ? depositCount : noDeposits, 0);
    }

    /** How many gases hold a place. */
    public int tenants() {
        int n = 0;
        for (CgVfxGas t : tenants) if (t != null) n++;
        return n;
    }

    /** Tiles given out now, every gas's. */
    public int tilesInUse() {
        return tiles.tilesInUse();
    }

    /** A place for {@code gas}, the lowest free one, or -1 with none free; the first registers the recording. */
    int acquire(CgVfxGas gas) {
        for (int b = 0; b < CAPACITY; b++) {
            if (tenants[b] != null) continue;
            tenants[b] = gas;
            gas.ticked = frame;
            gas.cell = gas.cellSetting() * cellScale(CgGraphicsSettings.QUALITY.get());
            if (registration == null) {
                registration = CgRenderStage.WORLD_OPAQUE.registerOncePerFrame(ORDER, this::record);
                CgGraphicsLifecycle.addListener(context);
                CgWorldRenderer.get().media().add(this::inject);
                prepare();
            }
            return b;
        }
        return -1;
    }

    /** Every gas's density in the world's media, so what is drawn behind it is dimmed by it. */
    private boolean inject(CgComputePass pass, CgWorldMedia media) {
        if (shade == null) return false;
        boolean any = false;
        for (int b = 0; b < CAPACITY; b++) {
            CgVfxGas gas = tenants[b];
            boolean live = gas != null && gas.index() == b && gas.cellSize() > 0f;
            float half = live ? SPAN * 0.5f * gas.cellSize() : 0f;
            mediaRows[b * 4] = live ? (float) (gas.x() - media.cameraX()) - half : 0f;
            mediaRows[b * 4 + 1] = live ? (float) (gas.y() - media.cameraY()) - half : 0f;
            mediaRows[b * 4 + 2] = live ? (float) (gas.z() - media.cameraZ()) - half : 0f;
            mediaRows[b * 4 + 3] = live ? gas.extinction() : 0f;
            mediaCells[b] = live ? gas.cellSize() : 1f;
            any |= live;
        }
        if (!any) return false;
        if (mediaKernel == null) mediaKernel = CgCompute.load(MEDIA_KERNELS).kernel("Gas");
        float[] r = mediaRows;
        media.dispatch(pass, mediaKernel).texture(SHADE_SAMPLER, shade).texture(PAGES_SAMPLER, tiles.pages())
                .set("_Gas0", r[0], r[1], r[2], r[3]).set("_Gas1", r[4], r[5], r[6], r[7])
                .set("_Gas2", r[8], r[9], r[10], r[11]).set("_Gas3", r[12], r[13], r[14], r[15])
                .set("_GasCells", mediaCells[0], mediaCells[1], mediaCells[2], mediaCells[3]);
        return true;
    }

    void release(int b) {
        if (b < 0 || b >= CAPACITY) return;
        tenants[b] = null;
        freeAll(b);
    }

    /** The frame the atlas is on: what a gas's tick stamps, so one left unticked is found. */
    long frame() {
        return frame;
    }

    /** A dying context frees the volumes: every gas starts again empty in the next one's. */
    private final CgLifecycleListener context = new CgLifecycleListener() {
        @Override
        public void onDestroy() {
            shade = flow = ahead = back = divergence = curl = solid = fills = null;
            deposits = depositCount = depositGrid = entityRows = null;
            Arrays.fill(filled, null);
            Arrays.fill(velocity, null);
            Arrays.fill(scalar, null);
            Arrays.fill(pressures, null);
            gasRows = sourceBuffer = null;
            quality = null;
            for (int b = 0; b < CAPACITY; b++) freeAll(b);
            tiles.lost();
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
        depositKernel = file.kernel("Deposit");
        shadeKernel = file.kernel("Shade");
        occupancyKernel = file.kernel("Occupancy");
        for (CgKernel k : new CgKernel[] {solids, advect, curlKernel, forces, divergenceKernel, jacobi, project, carry, carryBack,
                settle, shadeKernel, occupancyKernel, depositKernel}) {
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
                freeAll(b);
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
        float scale = GPU_BUDGET.scale();
        int most = scale < 0.5f ? 1 : MAX_STEPS;
        int steps = 0;
        for (int b = 0; b < live; b++) {
            CgVfxGas gas = tenants[b];
            if (gas == null) continue;
            gas.owed = Math.min(gas.owed, most * STEP);
            steps = Math.max(steps, (int) (gas.owed / STEP));
            for (int s = 0; s < MAX_STEPS; s++) {
                stepSeconds[b * MAX_STEPS + s] = s < most && gas.owed >= STEP * (s + 1) ? STEP : 0f;
            }
        }
        if (steps == 0 && !reshade) return;
        for (int b = 0; b < live; b++) {
            if (tenants[b] != null) tenants[b].owed -= STEP * Math.min(steps, (int) (tenants[b].owed / STEP));
        }
        writeRows(steps, live);
        writeSources(live);
        upload(recording);
        CgVfxVoxelWindow window = CgVfxVoxelWindow.get().use();
        int iterations = Math.max(MIN_ITERATIONS, Math.round(ITERATIONS * scale));
        if (steps > 0) recording.fill(tiles.occupancy(), 0);
        gatherEntities(live, recording);
        CgComputePass pass = recording.compute("vfx.gas", stage.defaults(constants)).timed(GPU_ZONE).timed(GPU_BUDGET)
                .async();
        dispatch(pass, window, solids, 0, 0).image("SOLID_OUT", solid);
        if (steps > 0) {
            // What particles poured since the last step, summed per deposit cell for its first step to take.
            CgGpuOps.fill(pass, depositGrid, 0, CgGpuCount.of(tiles.layers() * LAYER * LAYER * DEPOSIT_CELLS * 2));
            bind(pass.dispatch(depositKernel, DEPOSITS), 0, 0).bind("DEPOSITS", deposits).bind("DEPOSIT_COUNT", depositCount)
                    .bind("DEPOSIT_GRID", depositGrid);
        }
        for (int s = 0; s < steps; s++) {
            step(pass, window, s * CAPACITY * ROWS, s == 0 ? sourceCount : continuous, iterations, s == 0 ? 1 : 0, s == 0);
        }
        int fresh = steps == 0 ? 1 : 0, rowBase = steps * CAPACITY * ROWS;
        window.bind(bind(pass.dispatch(shadeKernel, LAYER * SHADE_TILE, LAYER * SHADE_TILE, tiles.usedLayers() * SHADE_TILE),
                rowBase, fresh))
                .texture("_Scalars", scalar[scalars]).texture("_Vel", velocity[vel]).texture("_Solid", solid)
                .image("SHADE_OUT", shade).image("FLOW_OUT", flow);
        if (steps > 0) {
            dispatch(pass, window, occupancyKernel, rowBase, fresh).texture("_Scalars", scalar[scalars])
                    .bind("OCCUPIED", tiles.occupancy());
            CgGpuOps.fill(pass, depositCount, 0, CgGpuCount.of(1));
        }
        pass.end();
        reshade = false;
        if (steps == 0) return;
        tiles.readOccupancy(recording);
        tiles.stepped();
        for (int b = 0; b < live; b++) {
            CgVfxGas gas = tenants[b];
            if (gas == null) continue;
            for (int s = 0; s < steps; s++) gas.clock += stepSeconds[b * MAX_STEPS + s];
            gas.stepped();
        }
    }

    /**
     * Each gas's tiles for this frame: kept where the GPU last found gas, given out on each face it found gas near and
     * round every source and blast, and taken back once nothing asked for them for {@link #FREE_AFTER} frames. Then its
     * box, which its layers draw.
     */
    private void grow(int live) {
        int now = (int) frame;
        int alive = 0;
        for (int b = 0; b < live; b++) if (tenants[b] != null) alive |= 1 << b;
        tiles.live(alive);
        for (int b = 0; b < live; b++) {
            CgVfxGas gas = tenants[b];
            // Wholly under its floor, a tile holds nothing: refused.
            if (gas != null) tiles.floorTile(b, (int) Math.ceil(((gas.floor() - gas.cell) / gas.cell + SPAN * 0.5f) / TILE - 1));
        }
        tiles.grow(now);
        for (int b = 0; b < live; b++) {
            CgVfxGas gas = tenants[b];
            if (gas != null) gas.wantTiles(this, b, now);
        }
        tiles.finish(now);
        for (int b = 0; b < live; b++) {
            CgVfxGas gas = tenants[b];
            if (gas != null) gas.box(tiles.bounds(b, gas.cell, box) ? box : null);
        }
    }

    /**
     * Asks for gas {@code b}'s tiles round the sphere {@code radius} blocks about {@code (x, y, z)} from its middle,
     * this frame: a source's or a blast's.
     */
    void wantSphere(int b, float x, float y, float z, float radius, int now) {
        tiles.wantSphere(b, x, y, z, radius, tenants[b].cell, now);
    }

    private void freeAll(int b) {
        filled[b] = null;
        tiles.freeAll(b);
    }

    /**
     * One step: velocity carried, confined, forced (entities pushing it among the forces) and projected; then the scalars
     * carried by MacCormack, the frame's deposits added on its {@code first} step.
     */
    private void step(CgComputePass pass, CgVfxVoxelWindow window, int rowBase, int count, int iterations, int fresh,
                      boolean first) {
        CgGraphTexture v0 = velocity[vel], v1 = velocity[1 - vel];
        dispatch(pass, window, advect, rowBase, fresh).texture("_Vel", v0).image("VEL_OUT", v1);
        dispatch(pass, window, curlKernel, rowBase, fresh).texture("_Vel", v1).image("CURL_OUT", curl);
        dispatch(pass, window, forces, rowBase, fresh).texture("_Vel", v1).texture("_Scalars", scalar[scalars])
                .texture("_Curl", curl).bind("SOURCES", sourceBuffer).set("_Sources", count)
                .bind("ENTITY_ROWS", entityRows).set("_Entities", entityCount).image("VEL_OUT", v0);
        dispatch(pass, window, divergenceKernel, rowBase, fresh).texture("_Vel", v0).texture("_Scalars", scalar[scalars])
                .bind("SOURCES", sourceBuffer).set("_Sources", count).image("DIV_OUT", divergence);
        for (int i = 0; i < iterations; i++) {
            dispatch(pass, window, jacobi, rowBase, fresh).texture("_Pressure", pressures[pressure])
                    .texture("_Div", divergence).set("_Iter", i).image("PRESSURE_OUT", pressures[1 - pressure]);
            pressure = 1 - pressure;
        }
        dispatch(pass, window, project, rowBase, fresh).texture("_Vel", v0).texture("_Pressure", pressures[pressure])
                .image("VEL_OUT", v1);
        vel = 1 - vel;
        CgGraphTexture s0 = scalar[scalars], s1 = scalar[1 - scalars];
        dispatch(pass, window, carry, rowBase, fresh).texture("_Vel", v1).texture("_Scalars", s0).image("SCALARS_OUT", ahead);
        dispatch(pass, window, carryBack, rowBase, fresh).texture("_Vel", v1).texture("_Ahead", ahead)
                .image("SCALARS_OUT", back);
        dispatch(pass, window, settle, rowBase, fresh).texture("_Vel", v1).texture("_Scalars", s0).texture("_Ahead", ahead)
                .texture("_Back", back).texture("_Fill", fills).bind("SOURCES", sourceBuffer).set("_Sources", count)
                .bind("DEPOSIT_GRID", depositGrid).set("_Deposit", first ? 1 : 0).image("SCALARS_OUT", s1);
        scalars = 1 - scalars;
    }

    /**
     * A dispatch of {@code kernel} over the pool's used layers, a texel a cell. Only Solids reads the voxel window here:
     * the step's kernels read solids from Solids, which keeps the busiest of them (Settle) under a dispatch's eight
     * textures.
     */
    private CgDispatch dispatch(CgComputePass pass, CgVfxVoxelWindow window, CgKernel kernel, int rowBase, int fresh) {
        CgDispatch d = bind(pass.dispatch(kernel, LAYER * TILE, LAYER * TILE, tiles.usedLayers() * TILE), rowBase, fresh);
        if (kernel == solids) return window.bind(d);
        return d.texture("_Solid", solid);
    }

    private CgDispatch bind(CgDispatch d, int rowBase, int fresh) {
        return d.bind("GASES", gasRows).bind("TILES", tiles.tileRows()).texture("_Pages", tiles.pages()).set("_RowBase", rowBase)
                .set("_Fresh", fresh);
    }

    /** The volumes at {@code want}'s pool, the old ones released: every gas starts again empty. */
    private void remake(CgRecording recording, CgQuality want) {
        if (shade != null) {
            recording.release(shade);
            recording.release(flow);
            recording.release(fills);
            for (int i = 0; i < 2; i++) {
                recording.release(velocity[i]);
                recording.release(scalar[i]);
                recording.release(pressures[i]);
            }
        }
        quality = want;
        tiles.pool(recording, tiles(want));
        int layers = tiles.layers();
        for (int b = 0; b < CAPACITY; b++) {
            if (tenants[b] != null) tenants[b].cell = tenants[b].cellSetting() * cellScale(want);
        }
        int w = LAYER * TILE, d = layers * TILE, sw = LAYER * SHADE_TILE, sd = layers * SHADE_TILE;
        shade = CgGraphTexture.requested("vfx.gas.shade", CgTextureDesc.volume(sw, sw, sd, RGBA16F));
        flow = CgGraphTexture.requested("vfx.gas.flow", CgTextureDesc.volume(sw, sw, sd, RGBA16F));
        fills = CgGraphTexture.requested("vfx.gas.fills", CgTextureDesc.volume(FILL, FILL, FILL * CAPACITY, R8));
        Arrays.fill(filled, null);
        if (depositGrid != null) recording.release(depositGrid);
        depositGrid = CgGraphBuffer.persistent("vfx.gas.depositGrid",
                CgBufferDesc.of((long) layers * LAYER * LAYER * DEPOSIT_CELLS * 2 * 4, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
        if (deposits == null) {
            // Made, and their count zeroed, before any gas is live: a step pours only into a live one.
            deposits = CgGraphBuffer.persistent("vfx.gas.deposits", CgBufferDesc.of(DEPOSITS * 32L, CgBufferUsage.STORAGE));
            depositCount = CgGraphBuffer.persistent("vfx.gas.depositCount",
                    CgBufferDesc.of(256, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
            recording.fill(depositCount, 0);
        }
        if (entityRows == null) entityRows = CgVfxTiles.fit(recording, null, "vfx.gas.entities", MAX_ENTITIES * 12);
        for (int i = 0; i < 2; i++) {
            velocity[i] = CgGraphTexture.requested("vfx.gas.velocity" + i, CgTextureDesc.volume(w, w, d, RGBA16F));
            scalar[i] = CgGraphTexture.requested("vfx.gas.scalars" + i, CgTextureDesc.volume(w, w, d, RGBA16F));
            pressures[i] = CgGraphTexture.requested("vfx.gas.pressure" + i, CgTextureDesc.volume(w, w, d, R16F));
        }
        ahead = CgGraphTexture.transientTexture("vfx.gas.ahead", CgTextureDesc.volume(w, w, d, RGBA16F));
        back = CgGraphTexture.transientTexture("vfx.gas.back", CgTextureDesc.volume(w, w, d, RGBA16F));
        curl = CgGraphTexture.transientTexture("vfx.gas.curl", CgTextureDesc.volume(w, w, d, RGBA16F));
        divergence = CgGraphTexture.transientTexture("vfx.gas.divergence", CgTextureDesc.volume(w, w, d, R16F));
        solid = CgGraphTexture.transientTexture("vfx.gas.solid", CgTextureDesc.volume(w, w, d, R8));
        for (CgVfxGas gas : tenants) if (gas != null) gas.owed = Math.max(gas.owed, STEP);
        reshade = true;
    }

    /**
     * {@code steps} blocks of every gas's rows, then one for Shade, stepping nothing. A gas's clock in each block is where
     * that step starts.
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
                float dt = s < steps ? stepSeconds[b * MAX_STEPS + s] : 0f;
                float clock = gas.clock;
                for (int k = 0; k < s && k < steps; k++) clock += stepSeconds[b * MAX_STEPS + k];
                putf(at, 1f, dt, gas.cell, 0f);
                rows[at + 4] = (int) Math.floor(gas.worldX);
                rows[at + 5] = (int) Math.floor(gas.worldY);
                rows[at + 6] = (int) Math.floor(gas.worldZ);
                rows[at + 7] = Float.floatToRawIntBits(gas.floor());
                putf(at + 8, frac(gas.worldX), frac(gas.worldY), frac(gas.worldZ), gas.buoyancy());
                putf(at + 12, gas.weight(), gas.vorticity(), gas.dissipation(), gas.cooling());
                putf(at + 16, gas.damping(), gas.burn(), gas.heat(), gas.extinction());
                putf(at + 20, gas.expansion(), gas.turbulence(), gas.turbulenceSize(), clock);
                putf(at + 24, gas.disturbance(), gas.disturbanceSize(), gas.smokeAmount(), 0f);
                CgVfxSmoke smoke = gas.smoke;
                if (smoke != null && smoke.fill() != null) {
                    CgVfxSmokeFill fill = smoke.fill();
                    double c = CgVfxSmokeFill.CELL, low = FILL / 2;
                    putf(at + 28, (float) (gas.worldX / c - (fill.originX() - low)), (float) (gas.worldY / c - (fill.originY() - low)),
                            (float) (gas.worldZ / c - (fill.originZ() - low)), smoke.density());
                    putf(at + 32, smoke.revealed(), smoke.presence(), smoke.hold(), 0f);
                }
            }
        }
    }

    /** Every gas's sources on, then its blasts. */
    private void writeSources(int live) {
        int n = 0;
        for (int b = 0; b < live; b++) if (tenants[b] != null) n += tenants[b].sourceRows();
        if (sources.length < Math.max(1, n) * 12) sources = new float[Math.max(12, n * 24)];
        int at = 0;
        for (int b = 0; b < live; b++) if (tenants[b] != null) at = tenants[b].writeSources(sources, at, b, false);
        continuous = at / 12;
        for (int b = 0; b < live; b++) if (tenants[b] != null) at = tenants[b].writeSources(sources, at, b, true);
        sourceCount = at / 12;
    }

    private void upload(CgRecording recording) {
        int words = rowWords, floats = Math.max(12, sourceCount * 12);
        gasRows = CgVfxTiles.fit(recording, gasRows, "vfx.gas.rows", words);
        sourceBuffer = CgVfxTiles.fit(recording, sourceBuffer, "vfx.gas.sources", floats);
        tiles.upload(recording);
        int bytes = Math.max(words, floats) * 4;
        if (staging.capacity() < bytes) staging = ByteBuffer.allocate(bytes * 2).order(ByteOrder.nativeOrder());
        staging.clear();
        for (int i = 0; i < words; i++) staging.putInt(i * 4, rows[i]);
        staging.limit(words * 4);
        recording.update(gasRows, 0, staging);
        staging.clear();
        for (int i = 0; i < floats; i++) staging.putFloat(i * 4, i < sourceCount * 12 ? sources[i] : 0f);
        staging.limit(floats * 4);
        recording.update(sourceBuffer, 0, staging);
        for (int b = 0; b < CAPACITY; b++) {
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

    /**
     * The living entities in each gas's box, the first {@link #MAX_ENTITIES} found: their boxes in the gas's cells and
     * their velocity, from where each was last frame. None on a host with no level.
     */
    private void gatherEntities(int live, CgRecording recording) {
        float now = CgFrameClock.seconds();
        float frameDt = Float.isNaN(lastSeconds) ? 0f : Math.max(1e-3f, now - lastSeconds);
        lastSeconds = now;
        entityCount = 0;
        CgEntityQuery entities = CgEntityQueries.client();
        entityGather.query = entities;
        entityGather.dt = frameDt;
        for (int b = 0; b < live && entityCount < MAX_ENTITIES; b++) {
            CgVfxGas gas = tenants[b];
            if (gas == null || !gas.bounds(box)) continue;
            gatheringGas = b;
            entities.within(gas.worldX + box[0], gas.worldY + box[1], gas.worldZ + box[2],
                    gas.worldX + box[3], gas.worldY + box[4], gas.worldZ + box[5], entityGather);
        }
        entityGather.query = null;
        // Each entity's place, for its velocity next frame.
        for (int k = 0; k < MAX_ENTITIES; k++) lastIds[k] = k < entityCount ? entityGather.ids[k] : -1;
        System.arraycopy(entityGather.places, 0, lastPlaces, 0, MAX_ENTITIES * 3);
        if (staging.capacity() < MAX_ENTITIES * 12 * 4) staging = ByteBuffer.allocate(MAX_ENTITIES * 12 * 8).order(ByteOrder.nativeOrder());
        staging.clear();
        for (int i = 0; i < MAX_ENTITIES * 12; i++) staging.putFloat(i * 4, i < entityCount * 12 ? entityWords[i] : 0f);
        staging.limit(MAX_ENTITIES * 12 * 4);
        recording.update(entityRows, 0, staging);
    }

    /** What {@link #gatherEntities} hands {@code within}: one entity's rows. */
    private final class EntityGather implements IntConsumer {
        CgEntityQuery query;
        float dt;
        final int[] ids = new int[MAX_ENTITIES];
        final double[] places = new double[MAX_ENTITIES * 3];

        @Override
        public void accept(int id) {
            if (entityCount >= MAX_ENTITIES || (query.flags(id) & CgEntityQuery.ALIVE) == 0) return;
            int kind = query.kind(id);
            if (kind == CgEntityQuery.KIND_ITEM || kind == CgEntityQuery.KIND_PROJECTILE) return;
            if (!query.pose(id, 1f, pose)) return;
            CgVfxGas gas = tenants[gatheringGas];
            double x = pose[CgEntityQuery.X], y = pose[CgEntityQuery.Y], z = pose[CgEntityQuery.Z];
            float vx = 0f, vy = 0f, vz = 0f;
            for (int k = 0; k < MAX_ENTITIES; k++) {
                if (lastIds[k] != id || dt <= 0f) continue;
                vx = clampSpeed((float) (x - lastPlaces[k * 3]) / dt);
                vy = clampSpeed((float) (y - lastPlaces[k * 3 + 1]) / dt);
                vz = clampSpeed((float) (z - lastPlaces[k * 3 + 2]) / dt);
                break;
            }
            ids[entityCount] = id;
            places[entityCount * 3] = x;
            places[entityCount * 3 + 1] = y;
            places[entityCount * 3 + 2] = z;
            float cell = gas.cell, half = SPAN * 0.5f, w = (float) (pose[CgEntityQuery.WIDTH] * 0.5);
            float cx = (float) (x - gas.worldX) / cell + half, cy = (float) (y - gas.worldY) / cell + half;
            float cz = (float) (z - gas.worldZ) / cell + half, r = w / cell, h = (float) pose[CgEntityQuery.HEIGHT] / cell;
            int at = entityCount * 12;
            entityWords[at] = gatheringGas;
            entityWords[at + 1] = cx - r;
            entityWords[at + 2] = cy;
            entityWords[at + 3] = cz - r;
            entityWords[at + 4] = cx + r;
            entityWords[at + 5] = cy + h;
            entityWords[at + 6] = cz + r;
            entityWords[at + 7] = 0f;
            entityWords[at + 8] = vx;
            entityWords[at + 9] = vy;
            entityWords[at + 10] = vz;
            entityWords[at + 11] = 0f;
            entityCount++;
        }
    }

    /** An entity's speed along an axis, held under the gas's fastest: a teleport is no gust. */
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
