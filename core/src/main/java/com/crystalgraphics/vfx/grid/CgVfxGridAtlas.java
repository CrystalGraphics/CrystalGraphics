package com.crystalgraphics.vfx.grid;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.compute.ops.CgGpuCount;
import com.crystalgraphics.compute.ops.CgGpuOps;
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
import com.crystalgraphics.render.world.CgWorldMedia;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.settings.CgGraphicsSettings;
import com.crystalgraphics.settings.CgQuality;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import com.crystalgraphics.vfx.sparse.CgVfxTiles;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Every grid's cells on the GPU ({@link CgVfxGrid3D}), sparse: each grid {@link #SPAN} cells a side about its middle,
 * holding cells only where something is, in tiles of {@link CgVfxTiles#TILE} drawn from one pool through its page table,
 * as the gases hold theirs. A tile is given out where particles splat and where what a grid holds spreads, and taken
 * back once it has been empty a while, so a grid reaches as far as its particles go. Stepped once a frame by
 * {@code env/compute/vfx/grid.compute} on {@link CgRenderStage#WORLD_OPAQUE}, after the particles that splat into it and
 * the gases: each pool's particles that rasterize are drawn into their grids where Range draws them this frame, then
 * every channel steps, a rebuilt one taking that frame's alone. A grid's layers and the particles reading it take {@link #view()} and {@link #pages()}; its light (the sun's
 * transmittance and the sky's light through channel x) is {@link #light()}.
 *
 * <pre>{@code
 * CgVfxGridAtlas atlas = CgVfxGridAtlas.get();
 * CgGraphTexture pages = atlas.pages();   // a sampler3D: grid b's tile (x, y, z) at texel (x, y, z + PAGES * b), slot + 1
 * CgGraphTexture view = atlas.view();     // the channels: slot s's tile at (s % 8, s / 8 % 8, s / 64) * 20, two cells of apron
 * CgGraphTexture light = atlas.light();   // laid out as view: x the sun's transmittance, y the sky's light
 * // read through fx_grid.glsl: fx_grid_read(_GridView, _GridPages, grid, cell)
 * }</pre>
 *
 * <ul>
 *   <li>At most {@link #CAPACITY} grids at once; one asking past that waits, drawing nothing.</li>
 *   <li>The pool holds {@link #tiles(CgQuality)} tiles, a grid's cells {@code CELL} times {@link #cellScale(CgQuality)}:
 *       changing tier starts every grid again empty. Each grid is kept an even share of a full pool
 *       ({@link CgVfxTiles}); one whose splats land where it was refused tiles, every frame for a second
 *       ({@link #STARVED_FRAMES}), coarsens, its cells twice as wide and each 2x2x2 of them one, up to
 *       {@link #MAX_LEVEL} times, so it reaches eight times as far on the same tiles, softer. Past that it grows no
 *       further, and what it holds fades out toward the tiles it lacks.</li>
 *   <li>A splat landing where its grid has no tile asks for one, given out by the frame after next: what lands there
 *       meanwhile is lost. Tiles round each instance splatting are asked for ahead of it.</li>
 *   <li>At most {@link #SPLATS} splats a frame, every grid's together; the rest are dropped.</li>
 *   <li>Its passes are charged to {@link #GPU_BUDGET}. Render thread.</li>
 * </ul>
 */
public final class CgVfxGridAtlas {

    /** The most grids at once. */
    public static final int CAPACITY = 4;
    /** Cells a grid's grid side, its middle in the middle: {@link CgVfxTiles}'. */
    public static final int SPAN = CgVfxTiles.SPAN;
    /** The most splats a frame, every grid's together. */
    public static final int SPLATS = 131072;
    /** Where it records on {@code WORLD_OPAQUE}: after the particle pools, which splat into it, and the gases. */
    public static final int ORDER = CgWorldRenderer.ORDER - 88;
    /** The GPU's frame the grids may take. */
    public static final CgGpuBudget GPU_BUDGET = CgGpuBudget.define("vfx.grid", 1f);
    /** What a grid layer's shader declares, and the frame binds, in this order: {@link #view()}, {@link #light()}, {@link #pages()}. */
    public static final String VIEW_SAMPLER = "_GridView", LIGHT_SAMPLER = "_GridLight", PAGES_SAMPLER = "_GridPages";
    /** What a Step kernel taking {@code GRID} declares, all of which {@link #bindView} sets. */
    public static final String VIEW_PROPERTIES = """
                _GridView  ("Every grid's channels, as the last frame left them", sampler3D) = "black"
                _GridPages ("Every grid's page table",                            sampler3D) = "black"
                _GridLive  ("1 once there is a grid",                             int)       = 0
            """;
    /** What a particle step splatting into a grid appends ({@code GRID_SPLATS}): which cell and grid, then the amounts. */
    public static final String SPLAT_GLSL = "struct FxSplat { vec4 cell; vec4 amounts; };";
    /** A grid's rows in {@code GRIDS}: its state, what each channel keeps, what each spreads, its light, which are rebuilt. */
    static final int ROW_STATE = 0, ROW_KEEP = 1, ROW_SPREAD = 2, ROW_LIGHT = 3, ROW_REBUILT = 4, ROWS = 5;
    /** Frames a grid's effect may go unticked before its tiles are given back: asleep, cleared or dropped. */
    static final int STALE_FRAMES = 120;
    /** The most pages missed splats ask for in a frame. */
    static final int WANTED = 256;
    /** The most times a grid coarsens: its cells up to {@code 2^MAX_LEVEL} times as wide. */
    public static final int MAX_LEVEL = 3;
    /** Frames in a row splats must land on tiles a grid was refused before it coarsens: a second's worth of loss. */
    static final int STARVED_FRAMES = 60;
    /** The longest a frame steps it: a hitch fades it no further than this. */
    private static final float LONGEST = 0.1f;
    /** The most a channel spreads in a frame: explicit diffusion past a sixth of its difference a neighbour rings. */
    private static final float MOST_SPREAD = 0.16f;

    private static final String KERNELS = "crystalgraphics:shaders/env/compute/vfx/grid.compute";
    private static final String MEDIA_KERNELS = "crystalgraphics:shaders/env/compute/vfx/media.compute";
    private static final int GPU_ZONE = CgGpuTrace.name("vfx.grid");
    private static final int TILE = CgVfxTiles.TILE, LAYER = CgVfxTiles.LAYER, APRON_TILE = CgVfxTiles.APRON_TILE,
            PAGE_TABLE = CgVfxTiles.PAGES * CgVfxTiles.PAGES * CgVfxTiles.PAGES;
    private static final CgFrameBufferFormat RGBA16F = CgFrameBufferFormat.builder("vfx.grid.rgba16f")
            .color(0, CgTextureType.RGBA16F).build();
    private static final CgFrameBufferFormat R32F = CgFrameBufferFormat.builder("vfx.grid.r32f")
            .color(0, CgTextureType.R32F).build();
    private static final Logger LOG = LogManager.getLogger("CgVfxGridAtlas");
    private static final CgVfxGridAtlas INSTANCE = new CgVfxGridAtlas();

    private final CgVfxGrid3D[] tenants = new CgVfxGrid3D[CAPACITY];
    /** Every grid's tiles in the one pool. */
    private final CgVfxTiles tiles = new CgVfxTiles("vfx.grid", CAPACITY);
    private final float[] rows = new float[CAPACITY * ROWS * 4];
    private final ByteBuffer staging = ByteBuffer.allocate(CAPACITY * ROWS * 16).order(ByteOrder.nativeOrder());
    private final CgPassConstants constants = new CgPassConstants();
    private final float[] box = new float[6];
    /** The pages missed splats asked for, as the last readback delivered them: grid * PAGES³ + page + 1, 0 for none. */
    private final int[] missed = new int[WANTED];
    /** The grid coarsened this frame, -1 for none, and its page table before, as uploaded. */
    private int coarsened = -1;
    /** Each grid's frames in a row its missed splats were refused tiles; whether this frame's were. */
    private final int[] starvedFor = new int[CAPACITY];
    private final boolean[] refused = new boolean[CAPACITY];
    private final ByteBuffer beforeBytes = ByteBuffer.allocate(PAGE_TABLE * 4).order(ByteOrder.nativeOrder());
    private final CgReadback.Sink wantedSink = data -> {
        int n = Math.min(WANTED, data.remaining() / 4);
        for (int i = 0; i < n; i++) missed[i] = data.getInt(i * 4);
        Arrays.fill(missed, n, WANTED, 0);
    };
    @Nullable
    private CgQuality quality;
    private long frame;
    /** The channels as each step leaves them, a pair; which is current. */
    private final CgGraphTexture[] state = new CgGraphTexture[2];
    private int current;
    @Nullable
    private CgGraphTexture view, light, before;
    @Nullable
    private CgGraphBuffer gridRows, splats, splatCount, splatSum, want, wanted, wantedCount;
    /** What {@link #bindView} and {@link #bindSplats} give a kernel before the first grid: never read, never kept. */
    private final CgGraphTexture noView = CgGraphTexture.requested("vfx.grid.none", CgTextureDesc.volume(1, 1, 2, RGBA16F)),
            noPages = CgGraphTexture.requested("vfx.grid.nopages", CgTextureDesc.volume(1, 1, 2, R32F));
    private final CgGraphBuffer noSplats = CgGraphBuffer.persistent("vfx.grid.nosplats",
            CgBufferDesc.of(256, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
    private CgKernel splat, rasterize, wantedKernel, step, shade, lightKernel, occupancyKernel, coarsenKernel;
    /** This frame's pools that rasterize into a grid, and the rows each uploaded. */
    private final List<CgVfxParticlePool> rasterizing = new ArrayList<>();
    private final List<CgGraphBuffer> rasterRows = new ArrayList<>();
    private CgRenderStage.Registration registration;
    /** Each grid's place in the world's media: its low corner from the camera and its extinction, its cell. */
    private final float[] mediaRows = new float[CAPACITY * 4], mediaCells = new float[CAPACITY];
    @Nullable
    private CgKernel mediaKernel;

    private CgVfxGridAtlas() {
    }

    public static CgVfxGridAtlas get() {
        return INSTANCE;
    }

    /** Tiles in the pool at {@code quality}: 512 at Ultra, 256 at High, 128 at Medium, 64 at Low. */
    public static int tiles(CgQuality quality) {
        return switch (quality) {
            case LOW -> 64;
            case MEDIUM -> 128;
            case HIGH -> 256;
            default -> 512;
        };
    }

    /** What a grid's {@code CELL} is multiplied by at {@code quality}: 2 at Low, 1.5 at Medium, else 1. */
    public static float cellScale(CgQuality quality) {
        return switch (quality) {
            case LOW -> 2f;
            case MEDIUM -> 1.5f;
            default -> 1f;
        };
    }

    /** Every grid's channels as the last frame left them, laid out as the gas's shade; null before the first grid. */
    @Nullable
    public CgGraphTexture view() {
        return view;
    }

    /** The sun's transmittance and the sky's light through each lit grid's channel x, laid out as {@link #view()}. */
    @Nullable
    public CgGraphTexture light() {
        return light;
    }

    /** Each grid's page table, {@code PAGES} cubed texels a grid stacked along z: a tile's slot + 1, 0 for none. */
    @Nullable
    public CgGraphTexture pages() {
        return tiles.pages();
    }

    /** The grids' channels and page tables on a dispatch of a kernel declaring {@link #VIEW_PROPERTIES}: the last frame's. */
    public CgDispatch bindView(CgDispatch d) {
        if (view == null) return d.texture("_GridView", noView).texture("_GridPages", noPages).set("_GridLive", 0);
        return d.texture("_GridView", view).texture("_GridPages", tiles.pages()).set("_GridLive", 1);
    }

    /** Where a particle step splatting into a grid appends ({@code GRID_SPLATS}): taken by the grids this frame. */
    public CgDispatch bindSplats(CgDispatch d) {
        CgGraphBuffer into = splats != null ? splats : noSplats;
        return d.bind("GRID_SPLATS", into).counter("GRID_SPLATS", splatCount != null ? splatCount : noSplats, 0);
    }

    /** How many grids hold a place. */
    public int tenants() {
        int n = 0;
        for (CgVfxGrid3D t : tenants) if (t != null) n++;
        return n;
    }

    /** Tiles given out now, every grid's. */
    public int tilesInUse() {
        return tiles.tilesInUse();
    }

    /** A place for {@code grid}, the lowest free one, or -1 with none free; the first registers the recording. */
    int acquire(CgVfxGrid3D grid) {
        for (int b = 0; b < CAPACITY; b++) {
            if (tenants[b] != null) continue;
            tenants[b] = grid;
            grid.level = 0;
            grid.cell = grid.cellSetting() * cellScale(CgGraphicsSettings.QUALITY.get());
            grid.ticked = frame;
            starvedFor[b] = 0;
            if (registration == null) {
                registration = CgRenderStage.WORLD_OPAQUE.registerOncePerFrame(ORDER, this::record);
                CgGraphicsLifecycle.addListener(context);
                CgWorldRenderer.get().media().add(this::inject);
                CgCompute file = CgCompute.load(KERNELS);
                splat = file.kernel("Splat");
                rasterize = file.kernel("Rasterize");
                wantedKernel = file.kernel("Wanted");
                step = file.kernel("Step");
                shade = file.kernel("Shade");
                lightKernel = file.kernel("Light");
                occupancyKernel = file.kernel("Occupancy");
                coarsenKernel = file.kernel("Coarsen");
                for (CgKernel k : new CgKernel[] {splat, rasterize, wantedKernel, step, shade, lightKernel, occupancyKernel,
                        coarsenKernel}) {
                    k.prepare();
                }
            }
            return b;
        }
        return -1;
    }

    void release(int b) {
        if (b < 0 || b >= CAPACITY) return;
        tenants[b] = null;
        tiles.freeAll(b);
    }

    /** Asks for grid {@code b}'s tiles round the sphere {@code radius} blocks about {@code (x, y, z)} from its middle. */
    void wantSphere(int b, float x, float y, float z, float radius, int now) {
        tiles.wantSphere(b, x, y, z, radius, tenants[b].cell, now);
    }

    /** Every lit grid's channel x in the world's media, so what is drawn behind its smoke is dimmed by it. */
    private boolean inject(CgComputePass pass, CgWorldMedia media) {
        if (view == null) return false;
        boolean any = false;
        for (int b = 0; b < CAPACITY; b++) {
            CgVfxGrid3D grid = tenants[b];
            boolean live = grid != null && grid.index == b && grid.cell > 0f && grid.lights();
            float half = live ? SPAN * 0.5f * grid.cell : 0f;
            mediaRows[b * 4] = live ? (float) (grid.worldX - media.cameraX()) - half : 0f;
            mediaRows[b * 4 + 1] = live ? (float) (grid.worldY - media.cameraY()) - half : 0f;
            mediaRows[b * 4 + 2] = live ? (float) (grid.worldZ - media.cameraZ()) - half : 0f;
            mediaRows[b * 4 + 3] = live ? grid.extinction() : 0f;
            mediaCells[b] = live ? grid.cell : 1f;
            any |= live;
        }
        if (!any) return false;
        if (mediaKernel == null) mediaKernel = CgCompute.load(MEDIA_KERNELS).kernel("Grid");
        float[] r = mediaRows;
        media.dispatch(pass, mediaKernel).texture(VIEW_SAMPLER, view).texture(PAGES_SAMPLER, tiles.pages())
                .set("_Grid0", r[0], r[1], r[2], r[3]).set("_Grid1", r[4], r[5], r[6], r[7])
                .set("_Grid2", r[8], r[9], r[10], r[11]).set("_Grid3", r[12], r[13], r[14], r[15])
                .set("_GridCells", mediaCells[0], mediaCells[1], mediaCells[2], mediaCells[3]);
        return true;
    }

    /** The frame the atlas is on: what a grid's tick stamps, so one left unticked is found. */
    long frame() {
        return frame;
    }

    /** A dying context frees the volumes: every grid starts again empty in the next one's. */
    private final CgLifecycleListener context = new CgLifecycleListener() {
        @Override
        public void onDestroy() {
            view = light = before = null;
            coarsened = -1;
            Arrays.fill(state, null);
            gridRows = splats = splatCount = splatSum = want = wanted = wantedCount = null;
            quality = null;
            for (int b = 0; b < CAPACITY; b++) tiles.freeAll(b);
            tiles.lost();
            Arrays.fill(missed, 0);
        }
    };

    private void record(CgStageFrame stage) {
        frame++;
        int live = 0;
        for (int b = 0; b < CAPACITY; b++) {
            CgVfxGrid3D grid = tenants[b];
            if (grid == null) continue;
            if (grid.effect().state() == CgVfxEffect.State.DEAD || frame - grid.ticked > STALE_FRAMES) {
                grid.lost();
                tenants[b] = null;
                tiles.freeAll(b);
                continue;
            }
            live = b + 1;
        }
        if (live == 0) return;
        CgRecording recording = stage.recording();
        CgQuality want = CgGraphicsSettings.QUALITY.get();
        if (want != quality || view == null) remake(recording, want);
        grow(live);
        if (tiles.usedLayers() == 0) {
            // Nothing held yet: what particles splatted this frame is dropped, and their next frame's count starts at 0.
            recording.fill(splatCount, 0);
            coarsened = -1;
            return;
        }
        boolean lit = false;
        for (int b = 0; b < live; b++) lit |= tenants[b] != null && tenants[b].lights();
        writeRows(live);
        staging.clear();
        for (int i = 0; i < live * ROWS * 4; i++) staging.putFloat(i * 4, rows[i]);
        staging.limit(live * ROWS * 16);
        recording.update(gridRows, 0, staging);
        tiles.upload(recording);
        recording.fill(tiles.occupancy(), 0);
        rasterizing.clear();
        rasterRows.clear();
        if (coarsened < 0) {
            List<CgVfxParticlePool> pools = CgVfxParticlePool.pools();
            for (int i = 0; i < pools.size(); i++) {
                CgVfxParticlePool p = pools.get(i);
                if (!p.rasterizes() || p.curves() == null) continue;
                rasterizing.add(p);
                rasterRows.add(p.rasterRows(recording));
            }
        }
        if (coarsened >= 0) {
            beforeBytes.clear();
            int[] table = tiles.previous();
            for (int i = 0; i < table.length; i++) beforeBytes.putFloat(i * 4, table[i]);
            beforeBytes.limit(table.length * 4);
            recording.update(before, 0, 0, 0, 0, CgVfxTiles.PAGES, CgVfxTiles.PAGES, CgVfxTiles.PAGES, beforeBytes);
        }
        int used = tiles.usedLayers(), w = LAYER * TILE, d = used * TILE, aw = LAYER * APRON_TILE, ad = used * APRON_TILE;
        CgComputePass pass = recording.compute("vfx.grid", stage.defaults(constants)).timed(GPU_ZONE).timed(GPU_BUDGET)
                .async();
        CgGpuOps.fill(pass, splatSum, 0, CgGpuCount.of(used * LAYER * LAYER * TILE * TILE * TILE * 4));
        CgGpuOps.fill(pass, this.want, 0, CgGpuCount.of(CAPACITY * PAGE_TABLE));
        CgGpuOps.fill(pass, wanted, 0, CgGpuCount.of(WANTED));
        CgGpuOps.fill(pass, wantedCount, 0, CgGpuCount.of(1));
        if (coarsened >= 0) {
            // Its tiles refilled on the wider cells before anything steps. This frame's splats were placed on the old
            // cells: dropped.
            bind(pass.dispatch(coarsenKernel, w, w, d)).texture("_State", state[current]).texture("_Before", before)
                    .set("_Coarsened", coarsened).image("STATE_OUT", state[1 - current]);
            current = 1 - current;
        } else {
            bind(pass.dispatch(splat, SPLATS)).bind("SPLATS", splats).bind("SPLAT_COUNT", splatCount)
                    .bind("SPLAT_SUM", splatSum).bind("WANT", this.want);
            // Each pool's particles where Range draws them this frame: the same records, the same alpha.
            for (int i = 0; i < rasterizing.size(); i++) {
                CgVfxParticlePool p = rasterizing.get(i);
                bind(pass.dispatch(rasterize, p.storage())).bind("RECORDS", p.records()).bind("LIVE", p.liveBuffer(), p.liveOffset(), 16)
                        .bind("CURVES", p.curves()).bind("RASTER", rasterRows.get(i)).bind("SPLAT_SUM", splatSum)
                        .bind("WANT", this.want).set("_Texels", CgVfxGpuEmitter.CURVE_TEXELS)
                        .set("_RecordVectors", p.record().vectors()).set("_RowVectors", p.shape().rowVectors());
            }
        }
        pass.dispatch(wantedKernel, CAPACITY * PAGE_TABLE).bind("WANT", this.want).bind("WANTED", wanted)
                .counter("WANTED", wantedCount, 0);
        bind(pass.dispatch(step, w, w, d)).bind("SPLAT_SUM", splatSum).texture("_State", state[current])
                .image("STATE_OUT", state[1 - current]);
        current = 1 - current;
        bind(pass.dispatch(shade, aw, aw, ad)).texture("_State", state[current]).image("VIEW_OUT", view);
        if (lit) bind(pass.dispatch(lightKernel, aw, aw, ad)).texture("_View", view).image("LIGHT_OUT", light);
        bind(pass.dispatch(occupancyKernel, w, w, d)).texture("_State", state[current]).bind("OCCUPIED", tiles.occupancy());
        CgGpuOps.fill(pass, splatCount, 0, CgGpuCount.of(1));
        pass.end();
        tiles.readOccupancy(recording);
        recording.readback(wanted, 0, WANTED * 4L, wantedSink);
        tiles.stepped();
        coarsened = -1;
        for (int b = 0; b < live; b++) if (tenants[b] != null) tenants[b].owed = 0f;
    }

    private CgDispatch bind(CgDispatch d) {
        return d.bind("GRIDS", gridRows).bind("TILES", tiles.tileRows()).texture("_Pages", tiles.pages());
    }

    /**
     * Each grid's tiles for this frame: kept where the GPU last found something, grown on the faces it found something
     * near, given out where splats missed and round every instance splatting; then each grid's box, which its layers draw.
     */
    private void grow(int live) {
        int now = (int) frame;
        int alive = 0;
        for (int b = 0; b < live; b++) if (tenants[b] != null) alive |= 1 << b;
        tiles.live(alive);
        tiles.grow(now);
        Arrays.fill(refused, false);
        for (int i = 0; i < WANTED; i++) {
            int m = missed[i] - 1;
            if (m < 0) continue;
            int b = m / PAGE_TABLE, page = m % PAGE_TABLE, pages = CgVfxTiles.PAGES;
            if (b < live && tenants[b] != null && !tiles.want(b, page % pages, page / pages % pages, page / (pages * pages), now)) {
                refused[b] = true;
            }
        }
        for (int b = 0; b < CAPACITY; b++) starvedFor[b] = refused[b] ? starvedFor[b] + 1 : 0;
        Arrays.fill(missed, 0);
        for (int b = 0; b < live; b++) {
            CgVfxGrid3D grid = tenants[b];
            if (grid != null) grid.wantTiles(this, b, now);
        }
        for (int b = 0; b < live && coarsened < 0; b++) {
            CgVfxGrid3D grid = tenants[b];
            if (grid == null || starvedFor[b] < STARVED_FRAMES || grid.level >= MAX_LEVEL) continue;
            // Its splats landing where it was refused tiles for a second: the same tiles over cells twice as wide.
            starvedFor[b] = 0;
            tiles.coarsen(b, now);
            grid.level++;
            grid.cell *= 2f;
            coarsened = b;
            LOG.warn("[vfx.grid] grid {} lost splats past its share of tiles for a second: its cells now {} blocks (level {})",
                    b, grid.cell, grid.level);
        }
        tiles.finish(now);
        for (int b = 0; b < live; b++) {
            CgVfxGrid3D grid = tenants[b];
            if (grid != null) grid.box(tiles.bounds(b, grid.cell, box) ? box : null);
        }
    }

    /** Each live grid's rows for this frame, its owed seconds stepped at once; a free place's rows say it is not live. */
    private void writeRows(int live) {
        Arrays.fill(rows, 0, live * ROWS * 4, 0f);
        for (int b = 0; b < live; b++) {
            CgVfxGrid3D grid = tenants[b];
            if (grid == null) continue;
            float dt = Math.min(grid.owed, LONGEST), cell = grid.cell;
            int at = b * ROWS * 4;
            put(at + ROW_STATE * 4, 1f, (float) Math.pow(0.125, grid.level), 0f, cell);
            for (int c = 0; c < 4; c++) {
                rows[at + ROW_KEEP * 4 + c] = (float) Math.exp(-grid.fade[c] * dt);
                rows[at + ROW_SPREAD * 4 + c] = Math.min(MOST_SPREAD, grid.spread[c] * dt / (cell * cell));
            }
            put(at + ROW_LIGHT * 4, grid.extinction(), grid.lights() ? 1f : 0f, 0f, 0f);
            for (int c = 0; c < 4; c++) rows[at + ROW_REBUILT * 4 + c] = grid.rebuilt[c] ? 1f : 0f;
        }
    }

    private void put(int at, float x, float y, float z, float w) {
        rows[at] = x;
        rows[at + 1] = y;
        rows[at + 2] = z;
        rows[at + 3] = w;
    }

    /** The volumes at {@code want}'s pool, the old ones released: every grid starts again empty. */
    private void remake(CgRecording recording, CgQuality want) {
        if (view != null) {
            recording.release(view);
            recording.release(light);
            recording.release(state[0]);
            recording.release(state[1]);
            recording.release(splatSum);
        }
        quality = want;
        tiles.pool(recording, tiles(want));
        for (CgVfxGrid3D grid : tenants) {
            if (grid == null) continue;
            grid.level = 0;
            grid.cell = grid.cellSetting() * cellScale(want);
        }
        int layers = tiles.layers(), w = LAYER * TILE, d = layers * TILE, aw = LAYER * APRON_TILE, ad = layers * APRON_TILE;
        for (int i = 0; i < 2; i++) {
            state[i] = CgGraphTexture.requested("vfx.grid.state" + i, CgTextureDesc.volume(w, w, d, RGBA16F));
        }
        view = CgGraphTexture.requested("vfx.grid.view", CgTextureDesc.volume(aw, aw, ad, RGBA16F));
        light = CgGraphTexture.requested("vfx.grid.light", CgTextureDesc.volume(aw, aw, ad, RGBA16F));
        if (before == null) {
            before = CgGraphTexture.requested("vfx.grid.before",
                    CgTextureDesc.volume(CgVfxTiles.PAGES, CgVfxTiles.PAGES, CgVfxTiles.PAGES, R32F));
        }
        splatSum = CgGraphBuffer.persistent("vfx.grid.splatSum",
                CgBufferDesc.of((long) layers * LAYER * LAYER * TILE * TILE * TILE * 4 * 4, CgBufferUsage.STORAGE,
                        CgBufferUsage.COPY));
        if (gridRows == null) {
            gridRows = CgGraphBuffer.persistent("vfx.grid.rows",
                    CgBufferDesc.of(CAPACITY * ROWS * 16L, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
            splats = CgGraphBuffer.persistent("vfx.grid.splats", CgBufferDesc.of(SPLATS * 32L, CgBufferUsage.STORAGE));
            splatCount = CgGraphBuffer.persistent("vfx.grid.splatCount",
                    CgBufferDesc.of(256, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
            this.want = CgGraphBuffer.persistent("vfx.grid.want",
                    CgBufferDesc.of(CAPACITY * PAGE_TABLE * 4L, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
            wanted = CgGraphBuffer.persistent("vfx.grid.wanted",
                    CgBufferDesc.of(WANTED * 4L, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
            wantedCount = CgGraphBuffer.persistent("vfx.grid.wantedCount",
                    CgBufferDesc.of(256, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
            recording.fill(splatCount, 0);
        }
        current = 0;
        coarsened = -1;
        Arrays.fill(missed, 0);
    }
}
