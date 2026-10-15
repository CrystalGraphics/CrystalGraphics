package com.crystalgraphics.vfx.grid;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.compute.ops.CgGpuCount;
import com.crystalgraphics.compute.ops.CgGpuOps;
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
import com.crystalgraphics.vfx.CgVfxEffect;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Every grid's cells on the GPU ({@link CgVfxGrid3D}): a brick of {@link #BRICK} cells a side each, four channels a cell,
 * stepped once a frame by {@code env/compute/vfx/grid.compute} on {@link CgRenderStage#WORLD_OPAQUE}, after the
 * particles that splat into it and the gases. A grid's layers and the particles reading it take {@link #view()}; its
 * light (the sun's transmittance and the sky's light through channel x) is {@link #light()}.
 *
 * <pre>{@code
 * CgVfxGridAtlas atlas = CgVfxGridAtlas.get();
 * CgGraphTexture view = atlas.view();     // a sampler3D: grid b's cell (i, j, k) at texel (i, j, k + BRICK * b)
 * CgGraphTexture light = atlas.light();   // laid out as view: x the sun's transmittance, y the sky's light
 * }</pre>
 *
 * <ul>
 *   <li>At most {@link #CAPACITY} grids at once; one asking past that waits, drawing nothing.</li>
 *   <li>What particles splat lands the frame they splat it: the particle pools record before this.</li>
 *   <li>At most {@link #SPLATS} splats a frame, every grid's together; the rest are dropped.</li>
 *   <li>Its passes are charged to {@link #GPU_BUDGET}. Render thread.</li>
 * </ul>
 */
public final class CgVfxGridAtlas {

    /** The most grids at once. */
    public static final int CAPACITY = 4;
    /** Cells a brick side: the most cells a grid takes a side. */
    public static final int BRICK = 48;
    /** The most splats a frame, every grid's together. */
    public static final int SPLATS = 131072;
    /** Where it records on {@code WORLD_OPAQUE}: after the particle pools, which splat into it, and the gases. */
    public static final int ORDER = CgWorldRenderer.ORDER - 88;
    /** The GPU's frame the grids may take. */
    public static final CgGpuBudget GPU_BUDGET = CgGpuBudget.define("vfx.grid", 1f);
    /** What a grid layer's shader declares, and the frame binds, in this order: {@link #view()}, {@link #light()}. */
    public static final String VIEW_SAMPLER = "_GridView", LIGHT_SAMPLER = "_GridLight";
    /** What a Step kernel taking {@code GRID} declares, all of which {@link #bindView} sets. */
    public static final String VIEW_PROPERTIES = """
                _GridView ("Every grid's channels, as the last frame left them", sampler3D) = "black"
                _GridLive ("1 once there is a grid",                             int)       = 0
            """;
    /** What a particle step splatting into a grid appends ({@code GRID_SPLATS}): which cell and grid, then the amounts. */
    public static final String SPLAT_GLSL = "struct FxSplat { vec4 cell; vec4 amounts; };";
    /** A grid's rows in {@code GRIDS}: its state, what each channel keeps, what each spreads, its light. */
    static final int ROW_STATE = 0, ROW_KEEP = 1, ROW_SPREAD = 2, ROW_LIGHT = 3, ROWS = 4;
    /** Frames a grid's effect may go unticked before its brick is given back: asleep, cleared or dropped. */
    static final int STALE_FRAMES = 120;
    /** The longest a frame steps it: a hitch fades it no further than this. */
    private static final float LONGEST = 0.1f;
    /** The most a channel spreads in a frame: explicit diffusion past a sixth of its difference a neighbour rings. */
    private static final float MOST_SPREAD = 0.16f;

    private static final String KERNELS = "crystalgraphics:shaders/env/compute/vfx/grid.compute";
    private static final int GPU_ZONE = CgGpuTrace.name("vfx.grid");
    private static final CgFrameBufferFormat RGBA16F = CgFrameBufferFormat.builder("vfx.grid.rgba16f")
            .color(0, CgTextureType.RGBA16F).build();
    private static final CgVfxGridAtlas INSTANCE = new CgVfxGridAtlas();

    private final CgVfxGrid3D[] tenants = new CgVfxGrid3D[CAPACITY];
    private final float[] rows = new float[CAPACITY * ROWS * 4];
    private final ByteBuffer staging = ByteBuffer.allocate(CAPACITY * ROWS * 16).order(ByteOrder.nativeOrder());
    private final CgPassConstants constants = new CgPassConstants();
    @Nullable
    private CgQuality quality;
    private long frame;
    @Nullable
    private CgGraphTexture view, light, next;
    @Nullable
    private CgGraphBuffer gridRows, splats, splatCount, splatSum;
    /** What {@link #bindView} and {@link #bindSplats} give a kernel before the first grid: never read, never kept. */
    private final CgGraphTexture noView = CgGraphTexture.requested("vfx.grid.none", CgTextureDesc.volume(1, 1, 2, RGBA16F));
    private final CgGraphBuffer noSplats = CgGraphBuffer.persistent("vfx.grid.nosplats",
            CgBufferDesc.of(256, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
    private CgKernel splat, step, shade;
    private CgRenderStage.Registration registration;

    private CgVfxGridAtlas() {
    }

    public static CgVfxGridAtlas get() {
        return INSTANCE;
    }

    /** Cells a side a grid asking for {@code cells} takes at {@code quality}: three quarters at Medium, half at Low. */
    public static int cells(float cells, CgQuality quality) {
        float scale = switch (quality) {
            case LOW -> 0.5f;
            case MEDIUM -> 0.75f;
            default -> 1f;
        };
        return Math.max(4, Math.min(BRICK, Math.round(cells * scale)));
    }

    /** Every grid's channels as the last frame left them; null before the first grid. */
    @Nullable
    public CgGraphTexture view() {
        return view;
    }

    /** The sun's transmittance and the sky's light through each grid's channel x, laid out as {@link #view()}. */
    @Nullable
    public CgGraphTexture light() {
        return light;
    }

    /** The grids' channels on a dispatch of a kernel declaring {@link #VIEW_PROPERTIES}: the last frame's. */
    public CgDispatch bindView(CgDispatch d) {
        if (view == null) return d.texture("_GridView", noView).set("_GridLive", 0);
        return d.texture("_GridView", view).set("_GridLive", 1);
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

    /** A place for {@code grid}, the lowest free one, or -1 with none free; the first registers the recording. */
    int acquire(CgVfxGrid3D grid) {
        for (int b = 0; b < CAPACITY; b++) {
            if (tenants[b] != null) continue;
            tenants[b] = grid;
            size(grid, CgGraphicsSettings.QUALITY.get());
            grid.ticked = frame;
            grid.fresh = true;
            if (registration == null) {
                registration = CgRenderStage.WORLD_OPAQUE.registerOncePerFrame(ORDER, this::record);
                CgGraphicsLifecycle.addListener(context);
                CgCompute file = CgCompute.load(KERNELS);
                splat = file.kernel("Splat");
                step = file.kernel("Step");
                shade = file.kernel("Shade");
                splat.prepare();
                step.prepare();
                shade.prepare();
            }
            return b;
        }
        return -1;
    }

    void release(int b) {
        if (b >= 0 && b < CAPACITY) tenants[b] = null;
    }

    /** The frame the atlas is on: what a grid's tick stamps, so one left unticked is found. */
    long frame() {
        return frame;
    }

    private static void size(CgVfxGrid3D grid, CgQuality quality) {
        float extent = grid.extent();
        grid.cells = cells(grid.cellsSetting(), quality);
        grid.cell = extent / grid.cells;
    }

    /** A dying context frees the volumes: every grid starts again empty in the next one's. */
    private final CgLifecycleListener context = new CgLifecycleListener() {
        @Override
        public void onDestroy() {
            view = light = next = null;
            gridRows = splats = splatCount = splatSum = null;
            for (CgVfxGrid3D grid : tenants) if (grid != null) grid.fresh = true;
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
                continue;
            }
            live = b + 1;
        }
        if (live == 0) return;
        CgRecording recording = stage.recording();
        CgQuality want = CgGraphicsSettings.QUALITY.get();
        if (view == null) make(recording);
        if (want != quality) {
            quality = want;
            for (CgVfxGrid3D grid : tenants) {
                if (grid == null) continue;
                size(grid, want);
                grid.fresh = true;
            }
        }
        writeRows(live);
        staging.clear();
        for (int i = 0; i < live * ROWS * 4; i++) staging.putFloat(i * 4, rows[i]);
        staging.limit(live * ROWS * 16);
        recording.update(gridRows, 0, staging);
        int depth = live * BRICK;
        CgComputePass pass = recording.compute("vfx.grid", stage.defaults(constants)).timed(GPU_ZONE).timed(GPU_BUDGET)
                .async();
        CgGpuOps.fill(pass, splatSum, 0, CgGpuCount.of(live * BRICK * BRICK * BRICK * 4));
        pass.dispatch(splat, SPLATS).bind("GRIDS", gridRows).bind("SPLATS", splats).bind("SPLAT_COUNT", splatCount)
                .bind("SPLAT_SUM", splatSum);
        pass.dispatch(step, BRICK, BRICK, depth).bind("GRIDS", gridRows).bind("SPLAT_SUM", splatSum)
                .texture("_View", view).image("NEXT_OUT", next);
        pass.dispatch(shade, BRICK, BRICK, depth).bind("GRIDS", gridRows).texture("_Next", next)
                .image("VIEW_OUT", view).image("LIGHT_OUT", light);
        CgGpuOps.fill(pass, splatCount, 0, CgGpuCount.of(1));
        pass.end();
        for (int b = 0; b < live; b++) {
            CgVfxGrid3D grid = tenants[b];
            if (grid == null) continue;
            grid.fresh = false;
            grid.owed = 0f;
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
            put(at + ROW_STATE * 4, 1f, grid.fresh ? 1f : 0f, grid.cells, cell);
            for (int c = 0; c < 4; c++) {
                rows[at + ROW_KEEP * 4 + c] = (float) Math.exp(-grid.fade[c] * dt);
                rows[at + ROW_SPREAD * 4 + c] = Math.min(MOST_SPREAD, grid.spread[c] * dt / (cell * cell));
            }
            put(at + ROW_LIGHT * 4, grid.extinction(), grid.lights() ? 1f : 0f, 0f, 0f);
        }
    }

    private void put(int at, float x, float y, float z, float w) {
        rows[at] = x;
        rows[at + 1] = y;
        rows[at + 2] = z;
        rows[at + 3] = w;
    }

    private void make(CgRecording recording) {
        int depth = CAPACITY * BRICK;
        view = CgGraphTexture.requested("vfx.grid.view", CgTextureDesc.volume(BRICK, BRICK, depth, RGBA16F));
        light = CgGraphTexture.requested("vfx.grid.light", CgTextureDesc.volume(BRICK, BRICK, depth, RGBA16F));
        next = CgGraphTexture.transientTexture("vfx.grid.next", CgTextureDesc.volume(BRICK, BRICK, depth, RGBA16F));
        gridRows = CgGraphBuffer.persistent("vfx.grid.rows",
                CgBufferDesc.of(CAPACITY * ROWS * 16L, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
        splats = CgGraphBuffer.persistent("vfx.grid.splats", CgBufferDesc.of(SPLATS * 32L, CgBufferUsage.STORAGE));
        splatCount = CgGraphBuffer.persistent("vfx.grid.splatCount", CgBufferDesc.of(256, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
        splatSum = CgGraphBuffer.persistent("vfx.grid.splatSum",
                CgBufferDesc.of((long) depth * BRICK * BRICK * 4 * 4, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
        recording.fill(splatCount, 0);
        for (CgVfxGrid3D grid : tenants) if (grid != null) grid.fresh = true;
    }
}
