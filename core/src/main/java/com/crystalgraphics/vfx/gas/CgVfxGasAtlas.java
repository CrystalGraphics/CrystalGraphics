package com.crystalgraphics.vfx.gas;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.gl.buffer.CgReadback;
import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.gl.lifecycle.CgLifecycleListener;
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
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.settings.CgGraphicsSettings;
import com.crystalgraphics.settings.CgQuality;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxTrace;
import com.crystalgraphics.vfx.world.CgVfxVoxelWindow;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

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
 * CgGraphTexture shade = atlas.shade();     // density, flame, sun, sky: slot s's tile at (s % 8, s / 8 % 8, s / 64) * 18
 * CgGraphTexture flow = atlas.flow();       // velocity, temperature, laid out as shade, a cell of apron round each tile
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
    /** Cells a tile side; tiles a gas's page table side; cells a gas's grid side, its origin in the middle. */
    public static final int TILE = 16, PAGES = 32, SPAN = TILE * PAGES;
    /** Pool tiles a layer side: slot s is pool tile (s % LAYER, s / LAYER % LAYER, s / LAYER²). */
    public static final int LAYER = 8;
    /** A tile's side in {@link #shade()} and {@link #flow()}: a cell of apron round it. */
    public static final int SHADE_TILE = TILE + 2;
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
     * size, the gas's clock) and the rest (disturbance, its size, the smoke a unit of fuel leaves).
     */
    public static final int ROW_STATE = 0, ROW_ORIGIN = 1, ROW_PLACE = 2, ROW_FORCES = 3, ROW_FADES = 4, ROW_MOTION = 5,
            ROW_DISTURB = 6, ROWS = 7;
    /** Frames a gas's effect may go unticked before its tiles are given back: asleep, cleared or dropped. */
    static final int STALE_FRAMES = 120;
    /** Frames a tile stays once nothing asks for it: gas thinning past the occupancy line keeps it a moment. */
    static final int FREE_AFTER = 30;
    /** Each slot's flags in {@code OCCUPIED}: gas in it, then gas near its -x, +x, -y, +y, -z, +z face. */
    private static final int FLAGS = 8;

    private static final Logger LOG = LogManager.getLogger("CgVfxGasAtlas");
    private static final String KERNELS = "crystalgraphics:shaders/env/compute/vfx/gas.compute";
    private static final int GPU_ZONE = CgGpuTrace.name("vfx.gas");
    private static final int TILES_COUNTER = CgTrace.name("vfx.gas.tiles"), FULL_COUNTER = CgTrace.name("vfx.gas.pool-full");
    private static final CgFrameBufferFormat RGBA16F = CgFrameBufferFormat.builder("vfx.gas.rgba16f")
            .color(0, CgTextureType.RGBA16F).build();
    private static final CgFrameBufferFormat R16F = CgFrameBufferFormat.builder("vfx.gas.r16f")
            .color(0, CgTextureType.R16F).build();
    private static final CgFrameBufferFormat R32F = CgFrameBufferFormat.builder("vfx.gas.r32f")
            .color(0, CgTextureType.R32F).build();
    private static final CgFrameBufferFormat R8 = CgFrameBufferFormat.builder("vfx.gas.r8")
            .color(0, CgTextureType.R8).build();
    private static final int MOST_TILES = tiles(CgQuality.ULTRA);
    private static final CgVfxGasAtlas INSTANCE = new CgVfxGasAtlas();

    private final CgVfxGas[] tenants = new CgVfxGas[CAPACITY];
    /** Each gas's page table: a tile's slot + 1, 0 for none; and whether it changed since its upload. */
    private final int[][] pageTable = new int[CAPACITY][PAGES * PAGES * PAGES];
    private final boolean[] pagesDirty = new boolean[CAPACITY];
    /** Each slot's gas (-1 free), its tile's place in that gas's page table, the frame last asked for, fresh. */
    private final int[] slotGas = new int[MOST_TILES], slotPage = new int[MOST_TILES], slotSeen = new int[MOST_TILES];
    private final boolean[] slotFresh = new boolean[MOST_TILES];
    /** What Occupancy found, FLAGS a slot, as the last readback delivered it. */
    private final int[] occupied = new int[MOST_TILES * FLAGS];
    /** Each gas's step seconds this frame, a step a column. */
    private final float[] stepSeconds = new float[CAPACITY * MAX_STEPS];
    private int[] rows = new int[0];
    private float[] sources = new float[0];
    /** This frame's words of rows, and sources: those on first, then the blasts, which only the first step takes. */
    private int rowWords, sourceCount, continuous;
    private ByteBuffer staging = ByteBuffer.allocate(0);
    private final ByteBuffer pageBytes = ByteBuffer.allocate(PAGES * PAGES * PAGES * 4).order(ByteOrder.nativeOrder());
    /** The pass's frame block: its time, and the sun its layers light it by. */
    private final CgPassConstants constants = new CgPassConstants();
    private final float[] box = new float[6];
    /** Occupancy's words as read back: the slots of the frame that asked, FLAGS each. */
    private int readSlots;
    private final CgReadback.Sink occupancySink = data -> {
        int n = Math.min(readSlots * FLAGS, data.remaining() / 4);
        for (int i = 0; i < n; i++) occupied[i] = data.getInt(i * 4);
        Arrays.fill(occupied, n, occupied.length, 0);
    };

    @Nullable
    private CgQuality quality;
    private int poolTiles, layers, usedLayers;
    private long frame;
    private int full;
    /** Which of each pair is current: velocity, scalars, pressure. */
    private int vel, scalars, pressure;
    private boolean reshade;
    @Nullable
    private CgGraphTexture shade, flow, pages;
    private final CgGraphTexture[] velocity = new CgGraphTexture[2], scalar = new CgGraphTexture[2],
            pressures = new CgGraphTexture[2];
    private CgGraphTexture ahead, back, divergence, curl, solid;
    /** What {@link #bindFlow} gives a kernel before the first gas: the smallest volumes, never read. */
    private final CgGraphTexture noFlow = CgGraphTexture.requested("vfx.gas.none", CgTextureDesc.volume(1, 1, 2, RGBA16F)),
            noPages = CgGraphTexture.requested("vfx.gas.nopages", CgTextureDesc.volume(1, 1, 2, R32F));
    private CgGraphBuffer gasRows, tileRows, sourceBuffer, occupancy;
    private CgKernel solids, advect, curlKernel, forces, divergenceKernel, jacobi, project, carry, carryBack, settle, shadeKernel,
            occupancyKernel;
    private CgRenderStage.Registration registration;

    private CgVfxGasAtlas() {
        Arrays.fill(slotGas, -1);
    }

    public static CgVfxGasAtlas get() {
        return INSTANCE;
    }

    /** Tiles in the pool at {@code quality}: 768 at Ultra, 384 at High, 256 at Medium, 128 at Low. */
    public static int tiles(CgQuality quality) {
        return switch (quality) {
            case LOW -> 128;
            case MEDIUM -> 256;
            case HIGH -> 384;
            default -> 768;
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
        return pages;
    }

    /**
     * The flow as the last frame left it, and the page tables, on a dispatch of a kernel declaring
     * {@link #FLOW_PROPERTIES}: {@link #flow()}, which this frame's steps do not write until their end.
     */
    public CgDispatch bindFlow(CgDispatch d) {
        if (flow == null) return d.texture("_GasVel", noFlow).texture("_GasPages", noPages).set("_GasLive", 0);
        return d.texture("_GasVel", flow).texture("_GasPages", pages).set("_GasLive", 1);
    }

    /** How many gases hold a place. */
    public int tenants() {
        int n = 0;
        for (CgVfxGas t : tenants) if (t != null) n++;
        return n;
    }

    /** Tiles given out now, every gas's. */
    public int tilesInUse() {
        int n = 0;
        for (int s = 0; s < poolTiles; s++) if (slotGas[s] >= 0) n++;
        return n;
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
                prepare();
            }
            return b;
        }
        return -1;
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
            shade = flow = pages = ahead = back = divergence = curl = solid = null;
            Arrays.fill(velocity, null);
            Arrays.fill(scalar, null);
            Arrays.fill(pressures, null);
            gasRows = tileRows = sourceBuffer = occupancy = null;
            quality = null;
            for (int b = 0; b < CAPACITY; b++) freeAll(b);
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
        occupancyKernel = file.kernel("Occupancy");
        for (CgKernel k : new CgKernel[] {solids, advect, curlKernel, forces, divergenceKernel, jacobi, project, carry, carryBack,
                settle, shadeKernel, occupancyKernel}) {
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
        if (usedLayers == 0) return;
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
        if (steps > 0) recording.fill(occupancy, 0);
        CgComputePass pass = recording.compute("vfx.gas", stage.defaults(constants)).timed(GPU_ZONE).timed(GPU_BUDGET)
                .async();
        dispatch(pass, window, solids, 0, 0).image("SOLID_OUT", solid);
        for (int s = 0; s < steps; s++) {
            step(pass, window, s * CAPACITY * ROWS, s == 0 ? sourceCount : continuous, iterations, s == 0 ? 1 : 0);
        }
        int fresh = steps == 0 ? 1 : 0, rowBase = steps * CAPACITY * ROWS;
        window.bind(bind(pass.dispatch(shadeKernel, LAYER * SHADE_TILE, LAYER * SHADE_TILE, usedLayers * SHADE_TILE), rowBase, fresh))
                .texture("_Scalars", scalar[scalars]).texture("_Vel", velocity[vel]).texture("_Solid", solid)
                .image("SHADE_OUT", shade).image("FLOW_OUT", flow);
        if (steps > 0) {
            dispatch(pass, window, occupancyKernel, rowBase, fresh).texture("_Scalars", scalar[scalars])
                    .bind("OCCUPIED", occupancy);
        }
        pass.end();
        reshade = false;
        if (steps == 0) return;
        readSlots = usedLayers * LAYER * LAYER;
        recording.readback(occupancy, 0, (long) readSlots * FLAGS * 4, occupancySink);
        for (int s = 0; s < poolTiles; s++) slotFresh[s] = false;
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
        for (int s = 0; s < poolTiles; s++) {
            int b = slotGas[s];
            if (b < 0 || occupied[s * FLAGS] == 0) continue;
            slotSeen[s] = now;
            int page = slotPage[s], tx = page % PAGES, ty = page / PAGES % PAGES, tz = page / (PAGES * PAGES);
            int at = s * FLAGS;
            int x0 = occupied[at + 1] != 0 ? -1 : 0, x1 = occupied[at + 2] != 0 ? 1 : 0;
            int y0 = occupied[at + 3] != 0 ? -1 : 0, y1 = occupied[at + 4] != 0 ? 1 : 0;
            int z0 = occupied[at + 5] != 0 ? -1 : 0, z1 = occupied[at + 6] != 0 ? 1 : 0;
            for (int dz = z0; dz <= z1; dz++) {
                for (int dy = y0; dy <= y1; dy++) {
                    for (int dx = x0; dx <= x1; dx++) {
                        if (dx != 0 || dy != 0 || dz != 0) want(b, tx + dx, ty + dy, tz + dz, now);
                    }
                }
            }
        }
        for (int b = 0; b < live; b++) {
            CgVfxGas gas = tenants[b];
            if (gas != null) gas.wantTiles(this, b, now);
        }
        int highest = -1;
        for (int s = 0; s < poolTiles; s++) {
            int b = slotGas[s];
            if (b < 0) continue;
            if (now - slotSeen[s] > FREE_AFTER) {
                free(s);
                continue;
            }
            highest = s;
        }
        usedLayers = highest < 0 ? 0 : highest / (LAYER * LAYER) + 1;
        for (int b = 0; b < live; b++) if (tenants[b] != null) bounds(b);
        CgVfxTrace.count(TILES_COUNTER, tilesInUse());
    }

    /**
     * Asks for gas {@code b}'s tiles round the sphere {@code radius} blocks about {@code (x, y, z)} from its middle,
     * this frame: a source's or a blast's.
     */
    void wantSphere(int b, float x, float y, float z, float radius, int now) {
        CgVfxGas gas = tenants[b];
        float cell = gas.cell, half = SPAN * 0.5f, r = radius + cell;
        int x0 = tile((x - r) / cell + half), x1 = tile((x + r) / cell + half);
        int y0 = tile((y - r) / cell + half), y1 = tile((y + r) / cell + half);
        int z0 = tile((z - r) / cell + half), z1 = tile((z + r) / cell + half);
        for (int tz = z0; tz <= z1; tz++) {
            for (int ty = y0; ty <= y1; ty++) {
                for (int tx = x0; tx <= x1; tx++) want(b, tx, ty, tz, now);
            }
        }
    }

    private static int tile(float cell) {
        return (int) Math.floor(cell / TILE);
    }

    /** Tile {@code (tx, ty, tz)} of gas {@code b} kept this frame, given a slot if it has none: none past its grid or wholly under its floor. */
    private void want(int b, int tx, int ty, int tz, int now) {
        if (tx < 0 || ty < 0 || tz < 0 || tx >= PAGES || ty >= PAGES || tz >= PAGES) return;
        CgVfxGas gas = tenants[b];
        if (((ty + 1) * TILE - SPAN * 0.5f) * gas.cell < gas.floor() - gas.cell) return;
        int page = tx + PAGES * (ty + PAGES * tz);
        int held = pageTable[b][page];
        if (held != 0) {
            slotSeen[held - 1] = now;
            return;
        }
        for (int s = 0; s < poolTiles; s++) {
            if (slotGas[s] >= 0) continue;
            slotGas[s] = b;
            slotPage[s] = page;
            slotSeen[s] = now;
            slotFresh[s] = true;
            pageTable[b][page] = s + 1;
            pagesDirty[b] = true;
            return;
        }
        CgVfxTrace.count(FULL_COUNTER, 1);
        if (full++ == 0) LOG.warn("[vfx.gas] the pool's {} tiles are all given out: gases grow no further", poolTiles);
    }

    private void free(int s) {
        int b = slotGas[s];
        if (b < 0) return;
        pageTable[b][slotPage[s]] = 0;
        pagesDirty[b] = true;
        slotGas[s] = -1;
        slotFresh[s] = false;
        for (int f = 0; f < FLAGS; f++) occupied[s * FLAGS + f] = 0;
    }

    private void freeAll(int b) {
        for (int s = 0; s < poolTiles; s++) if (slotGas[s] == b) free(s);
        Arrays.fill(pageTable[b], 0);
        pagesDirty[b] = true;
    }

    /** Gas {@code b}'s box: the tiles it holds, from its middle in blocks. */
    private void bounds(int b) {
        int lx = PAGES, ly = PAGES, lz = PAGES, hx = -1, hy = -1, hz = -1;
        for (int s = 0; s < poolTiles; s++) {
            if (slotGas[s] != b) continue;
            int page = slotPage[s], tx = page % PAGES, ty = page / PAGES % PAGES, tz = page / (PAGES * PAGES);
            lx = Math.min(lx, tx);
            ly = Math.min(ly, ty);
            lz = Math.min(lz, tz);
            hx = Math.max(hx, tx);
            hy = Math.max(hy, ty);
            hz = Math.max(hz, tz);
        }
        CgVfxGas gas = tenants[b];
        if (hx < 0) {
            gas.box(null);
            return;
        }
        float cell = gas.cell, half = SPAN * 0.5f;
        box[0] = (lx * TILE - half) * cell;
        box[1] = (ly * TILE - half) * cell;
        box[2] = (lz * TILE - half) * cell;
        box[3] = ((hx + 1) * TILE - half) * cell;
        box[4] = ((hy + 1) * TILE - half) * cell;
        box[5] = ((hz + 1) * TILE - half) * cell;
        gas.box(box);
    }

    /** One step: velocity carried, confined, forced and projected; then the scalars carried by MacCormack. */
    private void step(CgComputePass pass, CgVfxVoxelWindow window, int rowBase, int count, int iterations, int fresh) {
        CgGraphTexture v0 = velocity[vel], v1 = velocity[1 - vel];
        dispatch(pass, window, advect, rowBase, fresh).texture("_Vel", v0).image("VEL_OUT", v1);
        dispatch(pass, window, curlKernel, rowBase, fresh).texture("_Vel", v1).image("CURL_OUT", curl);
        dispatch(pass, window, forces, rowBase, fresh).texture("_Vel", v1).texture("_Scalars", scalar[scalars])
                .texture("_Curl", curl).bind("SOURCES", sourceBuffer).set("_Sources", count).image("VEL_OUT", v0);
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
                .texture("_Back", back).bind("SOURCES", sourceBuffer).set("_Sources", count).image("SCALARS_OUT", s1);
        scalars = 1 - scalars;
    }

    /**
     * A dispatch of {@code kernel} over the pool's used layers, a texel a cell. Only Solids reads the voxel window here:
     * the step's kernels read solids from Solids, which keeps the busiest of them (Settle) under a dispatch's eight
     * textures.
     */
    private CgDispatch dispatch(CgComputePass pass, CgVfxVoxelWindow window, CgKernel kernel, int rowBase, int fresh) {
        CgDispatch d = bind(pass.dispatch(kernel, LAYER * TILE, LAYER * TILE, usedLayers * TILE), rowBase, fresh);
        if (kernel == solids) return window.bind(d);
        return d.texture("_Solid", solid);
    }

    private CgDispatch bind(CgDispatch d, int rowBase, int fresh) {
        return d.bind("GASES", gasRows).bind("TILES", tileRows).texture("_Pages", pages).set("_RowBase", rowBase)
                .set("_Fresh", fresh);
    }

    /** The volumes at {@code want}'s pool, the old ones released: every gas starts again empty. */
    private void remake(CgRecording recording, CgQuality want) {
        if (shade != null) {
            recording.release(shade);
            recording.release(flow);
            recording.release(pages);
            for (int i = 0; i < 2; i++) {
                recording.release(velocity[i]);
                recording.release(scalar[i]);
                recording.release(pressures[i]);
            }
        }
        quality = want;
        poolTiles = tiles(want);
        layers = poolTiles / (LAYER * LAYER);
        for (int b = 0; b < CAPACITY; b++) {
            freeAll(b);
            if (tenants[b] != null) tenants[b].cell = tenants[b].cellSetting() * cellScale(want);
        }
        Arrays.fill(occupied, 0);
        int w = LAYER * TILE, d = layers * TILE, sw = LAYER * SHADE_TILE, sd = layers * SHADE_TILE;
        shade = CgGraphTexture.requested("vfx.gas.shade", CgTextureDesc.volume(sw, sw, sd, RGBA16F));
        flow = CgGraphTexture.requested("vfx.gas.flow", CgTextureDesc.volume(sw, sw, sd, RGBA16F));
        pages = CgGraphTexture.requested("vfx.gas.pages", CgTextureDesc.volume(PAGES, PAGES, PAGES * CAPACITY, R32F));
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
        int slots = Math.max(1, usedLayers) * LAYER * LAYER;
        int words = rowWords, floats = Math.max(12, sourceCount * 12), tileWords = slots * 4;
        gasRows = fit(recording, gasRows, "vfx.gas.rows", words);
        sourceBuffer = fit(recording, sourceBuffer, "vfx.gas.sources", floats);
        tileRows = fit(recording, tileRows, "vfx.gas.tiles", layers * LAYER * LAYER * 4);
        occupancy = fit(recording, occupancy, "vfx.gas.occupancy", layers * LAYER * LAYER * FLAGS);
        int bytes = Math.max(Math.max(words, floats), tileWords) * 4;
        if (staging.capacity() < bytes) staging = ByteBuffer.allocate(bytes * 2).order(ByteOrder.nativeOrder());
        staging.clear();
        for (int i = 0; i < words; i++) staging.putInt(i * 4, rows[i]);
        staging.limit(words * 4);
        recording.update(gasRows, 0, staging);
        staging.clear();
        for (int i = 0; i < floats; i++) staging.putFloat(i * 4, i < sourceCount * 12 ? sources[i] : 0f);
        staging.limit(floats * 4);
        recording.update(sourceBuffer, 0, staging);
        staging.clear();
        for (int s = 0; s < slots; s++) {
            int b = s < poolTiles ? slotGas[s] : -1, page = b < 0 ? 0 : slotPage[s];
            int tx = page % PAGES, ty = page / PAGES % PAGES, tz = page / (PAGES * PAGES);
            staging.putInt(s * 16, b + 1);
            staging.putInt(s * 16 + 4, tx | ty << 8 | tz << 16);
            staging.putInt(s * 16 + 8, b >= 0 && slotFresh[s] ? 1 : 0);
            staging.putInt(s * 16 + 12, 0);
        }
        staging.limit(tileWords * 4);
        recording.update(tileRows, 0, staging);
        for (int b = 0; b < CAPACITY; b++) {
            if (!pagesDirty[b]) continue;
            pagesDirty[b] = false;
            pageBytes.clear();
            int[] table = pageTable[b];
            for (int i = 0; i < table.length; i++) pageBytes.putFloat(i * 4, table[i]);
            pageBytes.limit(table.length * 4);
            recording.update(pages, 0, 0, 0, b * PAGES, PAGES, PAGES, PAGES, pageBytes);
        }
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
