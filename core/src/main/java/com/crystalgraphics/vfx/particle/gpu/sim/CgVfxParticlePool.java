package com.crystalgraphics.vfx.particle.gpu.sim;

import com.crystalgraphics.render.world.CgWorldEntityBoxes;
import com.crystalgraphics.vfx.particle.form.CgVfxCurveBlock;
import com.crystalgraphics.vfx.particle.form.CgVfxFormInput;
import com.crystalgraphics.vfx.particle.form.CgVfxSpawnForm;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.compute.ops.CgGpuCount;
import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.gl.lifecycle.CgLifecycleListener;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgDispatch;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.vfx.CgVfxTrace;
import com.crystalgraphics.vfx.grid.CgVfxGrid3D;
import com.crystalgraphics.vfx.grid.CgVfxGridAtlas;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxLifeBind;
import com.crystalgraphics.vfx.particle.CgVfxLifeRoll;
import com.crystalgraphics.vfx.particle.CgVfxMeshRotation;
import com.crystalgraphics.vfx.particle.CgVfxSockets;
import com.crystalgraphics.vfx.particle.spawn.CgVfxSpawnFrom;
import com.crystalgraphics.vfx.particle.gpu.CgVfxCurveDomain;
import com.crystalgraphics.vfx.particle.gpu.CgVfxDataInterface;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEventListener;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEventRows;
import com.crystalgraphics.vfx.particle.gpu.CgVfxDrawnChannel;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuModule;
import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;
import com.crystalgraphics.vfx.particle.gpu.CgVfxUserBinding;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWords;
import com.crystalgraphics.vfx.sequence.CgVfxClipAtlas;
import com.crystalgraphics.vfx.sequence.CgVfxNodeClip;
import com.crystalgraphics.render.world.CgWorldVoxelWindow;
import com.crystalgraphics.vfx.particle.gpu.CgVfxDrawLayout;
import org.joml.Matrix4f;

import javax.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * Every particle of every playing emitter instance whose definition has one {@link CgVfxShape}: one simulation on the
 * GPU, stepped by one dispatch per step however many effects play it (vfx-gpu §4.4, §13). An instance owns a slot
 * while it plays; its definition's numbers are a row of the pool's parameter table, shared by every instance of it.
 * The CPU decides every step what spawns and where each instance is, and queues it here; the pool records the queued
 * steps once a host frame on {@link CgRenderStage#WORLD_OPAQUE} at {@link #ORDER}, a dispatch of its shape's Step
 * kernel ({@link CgVfxEmitterCompiler}) each, and a draw reads what they left: {@link #records()} and {@link #live()}.
 *
 * <pre>{@code
 * CgVfxParticlePool pool = CgVfxParticlePool.of(system, EMBERS);
 * int slot = pool.open(EMBERS, EMBERS.peakAlive());  // at play: the most this instance can have alive
 *
 * // once a frame, over the frame's particle time, for every pool
 * pool.beginStep(dt, wind.x, wind.y, wind.z);
 * pool.instance(slot, seedBits, share, groundY, instance);   // every open slot, once
 * pool.spawn(slot, first, candidates, bursts, bornFrom, TICK, fx, fy, fz, tx, ty, tz, loopFrom, loopTo);   // a row per CPU tick that spawned
 * pool.endStep();
 *
 * pool.close(slot);                                  // once its last particle has died
 * }</pre>
 *
 * <ul>
 *   <li>Render thread, the step's caller: never from the emitters' workers.</li>
 *   <li>{@link #open} and {@link #close} only between steps; {@link #endStep} throws naming a slot that got no
 *       {@link #instance} call.</li>
 *   <li>A spawn's candidates are its spawn indices {@code firstSpawn} to {@code firstSpawn + candidates - 1}: the
 *       kernel thins them by the instance's share as {@code CgVfxEmitterInstance.spawnOne} does.</li>
 *   <li>Close a slot only once its particles have all died: a reopened slot's particles would be its new owner's.</li>
 *   <li>Steps allocate nothing once the tables have grown to what the effects need.</li>
 * </ul>
 *
 * <p>Events ({@link CgVfxEvent}): a slot of a definition whose events spawn children feeds a child slot opened for each
 * of them, in the child's pool; each step, the pools whose events spawn step first, and their children spawn in the
 * same step. Rows marked for the CPU reach {@link #listen}'s listeners a few frames later.</p>
 *
 * <pre>{@code
 * int parent = debris.open(DEBRIS, n), child = dust.open(DUST, dustCapacity);
 * dust.feed(child, debris, parent, 0);              // DEBRIS.events().get(0) spawns DUST
 * // each step both get instance rows; the child's seed bits are the parent's
 * }</pre>
 */
public final class CgVfxParticlePool {

    /**
     * An instance row's own vec4s ahead of its modules' lanes: {@code uvec4(param row, seed bits, slot, 0)},
     * {@code vec4(source xyz, time)}, {@code vec4(share, ground height or NaN, step scale, 1 to kill)}, {@code ivec4(origin's whole
     * blocks, 0)}, {@code vec4(origin within its block, 0)}, {@code vec4(the followed entity's box's centre from the source, its loop's
     * seconds)},
     * {@code vec4(its half extents, 0)}, {@code vec4(its rotation, a quaternion)}, {@code vec4(its rotation's change since
     * the last step, the identity unless local)}: the fourth and fifth place a particle in the world's blocks; spawns turn
     * by the eighth, and a local slot's live particles by the ninth.
     */
    public static final int INSTANCE_HEADER = 9;
    /**
     * A spawn row's words: {@code uvec4(slot, first spawn, candidates, first element)}, {@code vec4(born from, born to,
     * bursts, tick seconds)}, {@code vec4(source at the tick's start, its loop's seconds then)}, {@code vec4(source at its
     * end, its loop's seconds then)}. The born window is seconds into the step: a row is one CPU tick's spawns, its bursts
     * born at its start and the rest spread across it.
     */
    public static final int SPAWN_ROW_WORDS = 16;
    /** Seconds of particle time between two trail points: trails sample by time, whatever the frame rate. */
    public static final float TRAIL_INTERVAL = 1f / 60f;

    /**
     * An event row, as a Step kernel appends it and a child's reads it: where it fired within its slot's origin's block,
     * its velocity and age, its normal, that block, and {@code (parent id, pool << 16 | slot, event, 0)}.
     */
    public static final String EVENT_GLSL =
            "struct FxEvent { vec4 position; vec4 velocityAge; vec4 normal; ivec4 block; uvec4 ids; };";
    public static final int EVENT_BYTES = 80;

    /** A curve row's vec4s: what {@link CgVfxGpuEmitter#writeCurves} writes, then its {@link CgVfxCurveDomain}'s speeds. */
    public static final int CURVE_VECTORS = 2 * CgVfxGpuEmitter.CURVE_TEXELS + CgVfxGpuEmitter.CURVE_STARTS + 1;
    /** A curve row's words; its speeds are (-1, -1) over life. A pool's rows add size by speed and drawn slots: {@link #rowWords()}. */
    public static final int CURVE_WORDS = 4 * CURVE_VECTORS;

    /** Where the pools record their steps on {@link CgRenderStage#WORLD_OPAQUE}: ahead of the world renderer and Range. */
    public static final int ORDER = CgWorldRenderer.ORDER - 100;

    /** Every pool, by owner and shape key, and in the order made; and those released since the last recording. */
    private static final Map<Object, Map<String, List<CgVfxParticlePool>>> POOLS = new IdentityHashMap<>();
    /**
     * The textures a pool keeps per kind sampling them: a slot names its entry, so emitters differing only in their
     * textures share a pool, up to this many distinct ones; past it a pool of the same shape is made beside it.
     */
    public static final int TEXTURE_ENTRIES = 4;
    private static final List<CgVfxParticlePool> RELEASED = new ArrayList<>();
    /** The real frame's pools, and a rehearsal's hidden owners'; {@link #ALL} is whichever records now. */
    private static final List<CgVfxParticlePool> REAL = new ArrayList<>(), REHEARSED = new ArrayList<>();
    private static List<CgVfxParticlePool> ALL = REAL;
    /** Owners whose pools only a rehearsal records ({@link #hide}), by identity. */
    private static final Set<Object> HIDDEN = Collections.newSetFromMap(new IdentityHashMap<>());
    private static CgRenderStage.Registration recording;
    private static final List<CgVfxEventListener> LISTENERS = new ArrayList<>();
    /** The pools stepping in this recording, and the event streams between them: shared, as one step crosses pools. */
    private static final List<CgVfxParticlePool> STEPPING = new ArrayList<>();
    /** Each spawning tier's rows this step, which the next tier's children spawn from, and their counts. */
    private static final CgGraphBuffer[] SPAWN_STREAMS = new CgGraphBuffer[CgVfxShape.TIERS - 1],
            SPAWN_COUNTS = new CgGraphBuffer[CgVfxShape.TIERS - 1], SPAWN_REGIONS = new CgGraphBuffer[CgVfxShape.TIERS - 1],
            SPAWN_KEYS = new CgGraphBuffer[CgVfxShape.TIERS - 1], SPAWN_ORDER = new CgGraphBuffer[CgVfxShape.TIERS - 1],
            SPAWN_RUNS = new CgGraphBuffer[CgVfxShape.TIERS - 1], SPAWN_SORTED = new CgGraphBuffer[CgVfxShape.TIERS - 1];
    /**
     * Per tier: its spawn stream's rows, and its regions, each spawning event of each pool one (Unreal's
     * {@code MaxEventsPerFrame} per generator): its rows' place in the tier's capped rows and its cap, as
     * {@code uvec4(first capped row, cap, 0, 0)}. After the tier steps its rows are sorted by region
     * ({@code event_cap.compute}), and a child reads each region's first rows up to its cap.
     */
    /**
     * Per tier: its publishing pools' live particles this step, appended as event rows keyed by their slot's place among
     * the tier's publishing slots, then sorted by it as spawn events are, each slot's run marked; the tier after's
     * readers are born on them. Rows for every publishing pool's capacity, a key for each of their slots.
     */
    private static final CgGraphBuffer[] BIN_STREAMS = new CgGraphBuffer[CgVfxShape.TIERS - 1],
            BIN_COUNT = new CgGraphBuffer[CgVfxShape.TIERS - 1], BIN_KEYS = new CgGraphBuffer[CgVfxShape.TIERS - 1],
            BIN_ORDER = new CgGraphBuffer[CgVfxShape.TIERS - 1], BIN_SORTED = new CgGraphBuffer[CgVfxShape.TIERS - 1],
            BIN_RUNS = new CgGraphBuffer[CgVfxShape.TIERS - 1];
    private static final int[] BIN_ROWS = new int[CgVfxShape.TIERS - 1], BIN_SLOTS = new int[CgVfxShape.TIERS - 1];
    private static final CgGpuCount[] BIN_RUN_FILLS = new CgGpuCount[CgVfxShape.TIERS - 1];
    private static final int[] SPAWN_ROWS = new int[CgVfxShape.TIERS - 1], REGION_COUNT = new int[CgVfxShape.TIERS - 1],
            CAPPED_ROWS = new int[CgVfxShape.TIERS - 1];
    private static final int[][] REGION_WORDS = new int[CgVfxShape.TIERS - 1][64];
    private static final CgGpuCount[] RUN_FILLS = new CgGpuCount[CgVfxShape.TIERS - 1];
    private static final String EVENT_CAP_KERNELS = "crystalgraphics:shaders/env/compute/vfx/event_cap.compute";
    private static CgKernel capKeys, capRuns, capGather;
    private static CgGraphBuffer reportStream, reportCounts, unfed;
    private static final ArrayDeque<Report> REPORTS = new ArrayDeque<>();
    private static int nextId;
    /** What the next recording's steps see of the scene's depth: none without a pyramid. */
    private static final CgHostView DEPTH_VIEW = new CgHostView();
    private static final Matrix4f DEPTH_CLIP = new Matrix4f(), DEPTH_INVERSE = new Matrix4f();
    private static @Nullable CgGraphTexture depthPyramid;

    private static final int GPU_STEP = CgGpuTrace.name("vfx.pool.step");
    private static final CgGpuCount ONE = CgGpuCount.of(1);
    private static final int MIN_STORAGE = 1024;
    private static final String TRAIL_KERNELS = "crystalgraphics:shaders/env/compute/vfx/trail.compute";
    /** The Step's last stage: each live particle's drawn part, written into its record. */
    private static final String LOOK_KERNELS = "crystalgraphics:shaders/env/compute/vfx/look.compute";
    private static final String NEIGHBOUR_KERNELS = "crystalgraphics:shaders/env/compute/vfx/neighbours.compute";
    /** A neighbour grid's hashed cells: twice the records, a power of two, within these. */
    private static final int MIN_CELLS = 64, MAX_CELLS = 1 << 20;

    private final CgVfxShape shape;
    /** What event rows name it by: below 2^13, as a feed's key takes it. */
    private final int id = nextId++ & 0x1FFF;
    private final CgVfxWords words = new CgVfxWords();
    private final int paramWords, instanceWords;
    private final String passName, recordsName, drawnName, paramsName, curvesName, instancesName, spawnsName, historyName, ringsName,
            gridKeysName, gridValuesName, gridRunsName;
    private final String[] countNames;

    /** Each definition playing here: its row, and how many open slots use it. */
    private final IdentityHashMap<CgVfxGpuEmitter, int[]> rows = new IdentityHashMap<>();
    private int[] params = new int[0];
    private int rowCount;
    private int[] freeRows = new int[0];
    private int freeRowCount;
    /** The {@link #rows} entries given back, reused: a replay takes its row again without allocating. */
    private int[][] spareEntries = new int[0][];
    private int spareCount;
    private boolean paramsChanged;
    /** Per row: its curve row's words ({@link #CURVE_WORDS}), and the largest size multiplier on it. */
    private int[] curves = new int[0];
    private float[] rowSizeMax = new float[0];
    /** Each slot's largest bound size multiplier this step ({@code CgVfxLifeBind}), which its cull radius takes. */
    private float[] slotBindScale = new float[0];
    private final float[] bindScratch = new float[CgVfxLifeBind.LANES];
    private float[] curveScratch;
    /** A skeleton point an instance view answers, for its row. */
    private final float[] socketPoint = new float[3];

    private CgVfxGpuEmitter[] slotEmitter = new CgVfxGpuEmitter[0];
    /** Per slot: what played it ({@link #open(CgVfxGpuEmitter, int, Object)}), handed back with its event rows. */
    private Object[] slotOwner = new Object[0];
    private int[] slotCapacity = new int[0], slotRow = new int[0], slotBase = new int[0];
    /** Per slot: its origin as its last step gave it (3), and how far past its particles' size a look reaches. */
    private double[] slotOrigin = new double[0];
    /** Per slot: its instance's rotation as its last step gave it, a quaternion: what a SEQUENCE particle's clip turns by. */
    private float[] slotTurn = new float[0];
    private float[] slotScale = new float[0];
    /** Each open slot's opening, numbered from 1 in the pool's order; 0 once closed. */
    private long[] slotOpened = new long[0];
    private long openings;
    /** Per slot: its ground as its last step gave it, relative to its origin; NaN for none. */
    private float[] slotGround = new float[0];
    /** Each slot's skeleton points in the world last step, and 1 where answered: their velocity's other end. */
    private double[] socketPrev = new double[0];
    /** Per slot: the radius it is culled as one sphere of about its origin, or 0 to cull each particle by its size. */
    private float[] slotSourceReach = new float[0];
    /** Per slot: the grid its particles are drawn into each frame ({@code CgVfxEmitter.Builder.rasterize}), or null. */
    private CgVfxGrid3D[] slotGrid = new CgVfxGrid3D[0];
    /** {@link #rasterRows}: three vec4s a slot, uploaded each frame. */
    @Nullable
    private CgGraphBuffer rasterBuffer;
    private ByteBuffer rasterBytes = ByteBuffer.allocate(0).order(ByteOrder.nativeOrder());
    /** Per slot: the parent slot and event it is fed by, and the parent's definition then; null pool for none. */
    private CgVfxParticlePool[] feedPool = new CgVfxParticlePool[0];
    /** Per slot: the source pool and slot its particles are born on ({@link #readFrom}); null pool for none. */
    private CgVfxParticlePool[] readPool = new CgVfxParticlePool[0];
    private int[] readSlot = new int[0];
    private CgVfxGpuEmitter[] readSource = new CgVfxGpuEmitter[0];
    /** This recording's first publishing slot in its tier, and its readers' sources. */
    private int binKeyBase;
    private int[] readWords = new int[0];
    private CgGraphBuffer readsBuffer;
    private CgVfxGpuEmitter[] feedParent = new CgVfxGpuEmitter[0];
    private int[] feedSlot = new int[0], feedEvent = new int[0];
    /** This recording's feeds, four words a slot fed, by key; how many, and the most children an event spawns. */
    private int[] feedWords = new int[0];
    private int feeds, childMax;
    /** This recording's first region in its tier: its spawning events' are the next ones in order. */
    private int regionBase;
    private CgGraphBuffer feedBuffer;
    private int slotCount, openSlots, capacity;
    private boolean basesStale;
    /**
     * Per slot: where its trail rings start in {@link #history()}, a ring a particle of its capacity, and points a ring
     * (0: it keeps none). Its region is its own while it is open, so a ring's points never move.
     */
    private int[] ringFirst = new int[0], ringPoints = new int[0];
    /** Points the history has given out, its free runs as (start, length) pairs, and the slots keeping trails. */
    private int ringEnd, ringFreeCount, ringSlots;
    private int[] ringFree = new int[0];
    /** Runs given out since the last recording, cleared there so a new slot reads no old slot's tags: (start, length). */
    private int[] ringClears = new int[0];
    private int ringClearCount;
    /** Each slot's ring words, four a slot: what {@code trail.compute} reads as RINGS. */
    private int[] ringWords = new int[0];
    /** Trail points this pool has stored: each point's place in every ring. */
    private int stepsStored;
    /** Particle seconds since the newest trail point, once the queued steps record; queued steps that store one. */
    private float trailSince;
    private int pendingStores;
    /** Whether the history is new since the last recording; the one it replaced, and any between, to release there. */
    private boolean historyFresh;
    private CgGraphBuffer historyFrom;
    private final List<CgGraphBuffer> historyDrops = new ArrayList<>();

    /** The steps queued since the last {@link #takeSteps()}: four floats each (dt, wind). */
    private int steps;
    private boolean inStep;
    private float[] stepBlock = new float[0];
    /**
     * Per step: where its instance rows start in {@link #instances}, how many slots they cover, its spawn rows, and the
     * open slots' capacity as it began, which its live particles never exceed: what it dispatches over, besides spawns.
     */
    private int[] stepInstanceAt = new int[0], stepSlots = new int[0], stepSpawnAt = new int[0], stepSpawnRows = new int[0],
            stepSpawned = new int[0], stepCapacity = new int[0];
    /** Per step: whether it stores a trail point. */
    private boolean[] stepStores = new boolean[0];
    private int[] instances = new int[0];
    private int instanceEnd;
    /** Which step last wrote each slot's row, as 1 + its index. */
    private int[] written = new int[0];
    /** {@link #SPAWN_ROW_WORDS} a spawn row. */
    private int[] spawns = new int[0];
    private int spawnEnd;

    // The GPU half: made at the first recording, grown as slots open.
    private CgKernel step, look, store, gridKeys, gridRuns;
    /** Its beams' noise points, where its shape draws beams with noise. */
    @Nullable
    private final CgVfxBeams beams;
    private CgGraphBuffer records, drawn, paramBuffer, curveBuffer, instanceBuffer, spawnBuffer, history, ringTable;
    /** The neighbour grid (CgVfxDataInterfaces.NEIGHBOURS): each record's key and index, sorted; each hashed cell's run. */
    private CgGraphBuffer cellKeys, cellValues, cellRuns;
    /** Each slot's light row, CgVfxParticleLights's: fitted by the firing that uploads it. */
    private CgGraphBuffer lightSlots;
    private int cells;
    private float cellWidth = 1f;
    /** Ping-ponged append counts: {@code counts[current]} is how many records the newest version holds. */
    private final CgGraphBuffer[] counts = new CgGraphBuffer[2];
    private int current, storage;
    private ByteBuffer staging = ByteBuffer.allocate(0);
    private boolean released;
    /** Per textured module, its table: each entry's textures and the open slots naming it; each slot's entry per module. */
    private final CgTexture[][][] textureEntries;
    private final int[][] textureUses;
    private int[] slotTexture = new int[0];

    private CgVfxParticlePool(CgVfxShape shape, int group) {
        this.shape = shape;
        this.paramWords = shape.paramRowVectors() * 4;
        this.instanceWords = shape.instanceRowVectors() * 4;
        textureEntries = new CgTexture[shape.modules()][][];
        textureUses = new int[shape.modules()][];
        for (int i = 0; i < shape.modules(); i++) {
            if (shape.textureCount(i) == 0) continue;
            textureEntries[i] = new CgTexture[TEXTURE_ENTRIES][];
            textureUses[i] = new int[TEXTURE_ENTRIES];
        }
        String name = "vfx.pool[" + shape.key() + "]" + (group > 0 ? "#" + group : "");
        passName = name + ".step";
        recordsName = name + ".records";
        drawnName = name + ".drawn";
        paramsName = name + ".params";
        curvesName = name + ".curves";
        instancesName = name + ".instances";
        spawnsName = name + ".spawns";
        countNames = new String[]{name + ".live0", name + ".live1"};
        historyName = name + ".trails";
        ringsName = name + ".rings";
        gridKeysName = name + ".grid-keys";
        gridValuesName = name + ".grid-values";
        gridRunsName = name + ".grid-runs";
        beams = shape.beams() ? new CgVfxBeams(this, name) : null;
    }

    /**
     * The pool {@code owner}'s definitions of {@code emitter}'s shape share, made the first time: one simulation steps
     * its own pools, since a step moves every particle of one. The first pool made registers the pools' recording on
     * {@link CgRenderStage#WORLD_OPAQUE}. Render thread.
     *
     * <pre>{@code
     * CgVfxParticlePool pool = CgVfxParticlePool.of(system, EMBERS);   // a system's own, by identity
     * CgVfxParticlePool.release(system);                               // when the system is discarded
     * }</pre>
     */
    public static CgVfxParticlePool of(Object owner, CgVfxGpuEmitter emitter) {
        return of(owner, emitter, 0);
    }

    /**
     * As {@link #of(Object, CgVfxGpuEmitter)}, for {@code emitter} spawned at feed {@code tier} ({@link CgVfxShape#of(
     * CgVfxGpuEmitter, int)}): a child's slot is opened in its tier's pool, which steps after its parents'.
     */
    public static CgVfxParticlePool of(Object owner, CgVfxGpuEmitter emitter, int tier) {
        CgVfxShape shape = CgVfxShape.of(emitter, tier);
        Map<String, List<CgVfxParticlePool>> owned = POOLS.get(owner);
        if (owned == null) POOLS.put(owner, owned = new HashMap<>());
        List<CgVfxParticlePool> group = owned.get(shape.key());
        if (group == null) owned.put(shape.key(), group = new ArrayList<>(1));
        CgVfxParticlePool pool = null;
        for (int i = 0; i < group.size() && pool == null; i++) if (group.get(i).takesTextures(shape)) pool = group.get(i);
        if (pool == null) {
            group.add(pool = new CgVfxParticlePool(shape, group.size()));
            pool.hidden = HIDDEN.contains(owner);
            (pool.hidden ? REHEARSED : REAL).add(pool);
            if (recording == null) {
                recording = CgRenderStage.WORLD_OPAQUE.registerOncePerFrame(ORDER, CgVfxParticlePool::recordStage);
                CgGraphicsLifecycle.addListener(CONTEXT);
            }
        }
        return pool;
    }

    /**
     * Starts the programs a pool of {@code emitter}'s shape steps with, ahead of its first step: below compute its Step
     * kernel is several lowered programs, otherwise built on the frame the first burst plays. Render thread.
     *
     * <pre>{@code
     * CgVfxParticlePool.prepare(EMBERS);      // as a look is made, or on a loading screen
     * CgVfxParticlePool.prepare(SPARKS, 1);   // EMBERS' events' children, at their tier
     * CgVfxRange.prepare();                   // and what draws it
     * }</pre>
     */
    public static void prepare(CgVfxGpuEmitter emitter) {
        prepare(emitter, 0);
    }

    /**
     * {@link #prepare(CgVfxGpuEmitter, int)}'s CPU half: the Step kernel generated and parsed, the shared kernel files
     * read, nothing built. Any thread: a worker, so the prepare that follows takes them from the cache.
     */
    public static void loadAhead(CgVfxGpuEmitter emitter, int tier) {
        CgKernel.ahead(() -> prepare(emitter, tier));
    }

    /** As {@link #prepare(CgVfxGpuEmitter)}, for {@code emitter} spawned at feed {@code tier}. */
    public static void prepare(CgVfxGpuEmitter emitter, int tier) {
        CgVfxShape shape = CgVfxShape.of(emitter, tier);
        CgVfxEmitterCompiler.compile(shape).kernel("Step").prepare();
        CgCompute.load(LOOK_KERNELS).kernel("Look").prepare();
        CgGpuOps.prepareFill();
        if (emitter.trailPoints() > 0) CgCompute.load(TRAIL_KERNELS).kernel("Store").prepare();
        if (shape.spawns() || shape.publishes()) {
            CgCompute.load(EVENT_CAP_KERNELS).kernel("Keys").prepare();
            CgCompute.load(EVENT_CAP_KERNELS).kernel("Runs").prepare();
            CgCompute.load(EVENT_CAP_KERNELS).kernel("Gather").prepare();
            CgGpuOps.prepareSort(CgGpuOps.Element.UINT, CgGpuOps.Order.ASCENDING);
        }
        List<CgVfxDataInterface> interfaces = shape.dataInterfaces();
        for (int d = 0; d < interfaces.size(); d++) interfaces.get(d).prepare();
    }

    /** The neighbour grid's kernels, for {@code CgVfxDataInterfaces.NEIGHBOURS}' {@code prepare}. */
    static void prepareGrid() {
        CgCompute.load(NEIGHBOUR_KERNELS).kernel("Keys").prepare();
        CgCompute.load(NEIGHBOUR_KERNELS).kernel("Runs").prepare();
        CgGpuOps.prepareSort(CgGpuOps.Element.UINT, CgGpuOps.Order.ASCENDING);
    }

    /**
     * Hears the rows of every pool's events marked {@link CgVfxEvent#readback}, a few frames after their step. Render
     * thread.
     *
     * <pre>{@code
     * CgVfxParticlePool.listen(hisses);
     * CgVfxParticlePool.stopListening(hisses);
     * }</pre>
     */
    public static void listen(CgVfxEventListener listener) {
        LISTENERS.add(listener);
    }

    public static void stopListening(CgVfxEventListener listener) {
        LISTENERS.remove(listener);
    }

    /**
     * Drops {@code owner}'s pools; their GPU storage is released at the next recording. Render thread.
     */
    public static void release(Object owner) {
        HIDDEN.remove(owner);
        Map<String, List<CgVfxParticlePool>> owned = POOLS.remove(owner);
        if (owned == null) return;
        for (List<CgVfxParticlePool> group : owned.values()) for (CgVfxParticlePool pool : group) {
            (pool.hidden ? REHEARSED : REAL).remove(pool);
            pool.released = true;
            releases++;
            RELEASED.add(pool);
        }
    }

    /**
     * Makes {@code owner}'s pools a rehearsal's: real frames never record them, and while {@link #rehearsing} they are
     * the only pools ranged and drawn. Before its first pool. Render thread.
     *
     * <pre>{@code
     * CgVfxParticlePool.hide(hiddenSteps);
     * CgVfxParticlePool.rehearsing(true);
     * try { ... CgVfxParticlePool.recordRehearsed(recording); ... } finally { CgVfxParticlePool.rehearsing(false); }
     * }</pre>
     */
    public static void hide(Object owner) {
        if (POOLS.containsKey(owner)) throw new IllegalStateException(owner + " already has pools: hide it before its first");
        HIDDEN.add(owner);
    }

    /** Swaps the hidden owners' pools in for the real ones, or back; restore it in a {@code finally}. Render thread. */
    public static void rehearsing(boolean on) {
        ALL = on ? REHEARSED : REAL;
    }

    /** Records the hidden owners' queued steps into {@code recording}: a rehearsal's pass. Render thread. */
    public static void recordRehearsed(CgRecording recording) {
        if (ALL != REHEARSED) throw new IllegalStateException("record the hidden pools while rehearsing");
        recordPools(recording, REHEARSED);
    }

    /** Whether only a rehearsal records it. */
    public boolean hidden() {
        return hidden;
    }

    private boolean hidden;

    /** A dying context frees every pool's storage (CgExecutor.destroyAll): each makes it again, empty, at its next step. */
    private static final CgLifecycleListener CONTEXT = new CgLifecycleListener() {
        @Override
        public void onDestroy() {
            for (int i = 0; i < REAL.size(); i++) REAL.get(i).forgetStorage();
            for (int i = 0; i < REHEARSED.size(); i++) REHEARSED.get(i).forgetStorage();
            RELEASED.clear();
            unfed = reportStream = reportCounts = null;
            Arrays.fill(SPAWN_STREAMS, null);
            Arrays.fill(SPAWN_COUNTS, null);
            Arrays.fill(SPAWN_REGIONS, null);
            Arrays.fill(SPAWN_KEYS, null);
            Arrays.fill(SPAWN_ORDER, null);
            Arrays.fill(SPAWN_SORTED, null);
            Arrays.fill(SPAWN_RUNS, null);
            for (CgVfxDataInterface di : CgVfxDataInterfaces.all()) di.forget();
            CgVfxClipAtlas.forget();
        }
    };

    private void forgetStorage() {
        records = drawn = paramBuffer = curveBuffer = instanceBuffer = spawnBuffer = feedBuffer = counts[0] = counts[1] = null;
        history = ringTable = historyFrom = lightSlots = null;
        cellKeys = cellValues = cellRuns = null;
        if (beams != null) beams.forget();
        historyDrops.clear();
        historyFresh = false;
        current = storage = 0;
        paramsChanged = true;
    }

    /** Forgets every pool and stops recording. Tests. */
    static void forgetAll() {
        POOLS.clear();
        REAL.clear();
        REHEARSED.clear();
        HIDDEN.clear();
        ALL = REAL;
        RELEASED.clear();
        if (recording != null) {
            recording.close();
            recording = null;
        }
    }

    /**
     * The scene's depth the next {@link #record} steps against, for kinds taking {@link CgVfxDataInterfaces#DEPTH}: the
     * camera's view and {@code pyramid}, {@link CgGpuOps#depthPyramid}'s, in that recording. The stage does this
     * itself, with its own pyramid; a check scene recording its pools by hand calls it first. Render thread.
     *
     * <pre>{@code
     * CgVfxParticlePool.depth(view, pyramid);   // pyramid recorded into rec already
     * CgVfxParticlePool.record(rec, pools);     // reads it, then forgets it
     * }</pre>
     */
    public static void depth(CgHostView view, @Nullable CgGraphTexture pyramid) {
        DEPTH_VIEW.set(view);
        depthPyramid = pyramid;
    }

    /**
     * What the next {@link #record} gathers the entity boxes round, for kinds taking {@link CgVfxDataInterfaces#ENTITIES}:
     * the camera, and the partial tick to pose them at. The stage does this itself; a check scene recording its pools by
     * hand calls it first, or its kinds see no entities. Render thread.
     */
    public static void entities(double x, double y, double z, float partialTick) {
        CgWorldEntityBoxes.around(x, y, z, partialTick);
    }

    /** The stage's recording: each data interface a stepping shape takes gathers what it reads first, once. */
    private static void recordStage(CgStageFrame frame) {
        TAKEN.clear();
        for (int i = 0; i < ALL.size(); i++) {
            CgVfxParticlePool pool = ALL.get(i);
            if (pool.steps > 0) take(pool.shape);
        }
        for (int d = 0; d < TAKEN.size(); d++) TAKEN.get(d).beforeFrame(frame);
        recordAll(frame.recording());
    }

    /** The data interfaces the stepping pools take, each once: a frame's and a recording's scratch. */
    private static final List<CgVfxDataInterface> TAKEN = new ArrayList<>();

    /** {@code shape}'s data interfaces into {@link #TAKEN}, each once: a short list, searched rather than hashed. */
    private static void take(CgVfxShape shape) {
        List<CgVfxDataInterface> interfaces = shape.dataInterfaces();
        for (int d = 0; d < interfaces.size(); d++) if (!TAKEN.contains(interfaces.get(d))) TAKEN.add(interfaces.get(d));
    }

    /** Records every pool's queued steps into {@code recording}, once a host frame. Render thread. */
    static void recordAll(CgRecording recording) {
        for (int i = 0; i < RELEASED.size(); i++) RELEASED.get(i).releaseStorage(recording);
        RELEASED.clear();
        recordPools(recording, ALL);
    }

    /**
     * Records {@code pools}' queued steps step by step, in one compute pass: each step, the pools tier by tier, each
     * tier's child slots spawning from the rows the tier before appended in a stream of its own. Then each step's rows
     * for the CPU are read back.
     */
    private static void recordPools(CgRecording recording, List<CgVfxParticlePool> pools) {
        STEPPING.clear();
        int maxSteps = 0, reportRows = 0;
        Arrays.fill(SPAWN_ROWS, 0);
        Arrays.fill(REGION_COUNT, 0);
        Arrays.fill(CAPPED_ROWS, 0);
        Arrays.fill(BIN_ROWS, 0);
        Arrays.fill(BIN_SLOTS, 0);
        for (int i = 0; i < pools.size(); i++) {
            CgVfxParticlePool pool = pools.get(i);
            if (!pool.begin(recording)) continue;
            STEPPING.add(pool);
            maxSteps = Math.max(maxSteps, pool.steps);
            if (pool.shape.spawns()) pool.claimRegions();
            if (pool.shape.publishes()) pool.claimBins();
            reportRows += pool.reportRows();
        }
        if (STEPPING.isEmpty()) {
            depthPyramid = null;
            return;
        }
        if (depthPyramid != null) {
            DEPTH_CLIP.set(DEPTH_VIEW.projection()).mul(DEPTH_VIEW.view());
            DEPTH_VIEW.view().invert(DEPTH_INVERSE);
        }
        if (unfed == null) {
            unfed = CgGraphBuffer.persistent("vfx.pool.unfed", CgBufferDesc.of(256, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
            recording.fill(unfed, 0);
        }
        for (int t = 0; t < SPAWN_ROWS.length; t++) {
            if (SPAWN_ROWS[t] == 0) continue;
            SPAWN_STREAMS[t] = fitRows(recording, SPAWN_STREAMS[t], "vfx.pool.spawn-events" + t, SPAWN_ROWS[t]);
            SPAWN_COUNTS[t] = fit(recording, SPAWN_COUNTS[t], "vfx.pool.spawn-count" + t, 1);
            SPAWN_REGIONS[t] = fit(recording, SPAWN_REGIONS[t], "vfx.pool.spawn-regions" + t, REGION_COUNT[t] * 4);
            recording.update(SPAWN_REGIONS[t], 0, REGION_WORDS[t], 0, REGION_COUNT[t] * 4);
            SPAWN_KEYS[t] = fit(recording, SPAWN_KEYS[t], "vfx.pool.spawn-keys" + t, SPAWN_ROWS[t]);
            SPAWN_ORDER[t] = fit(recording, SPAWN_ORDER[t], "vfx.pool.spawn-order" + t, SPAWN_ROWS[t]);
            SPAWN_SORTED[t] = fitRows(recording, SPAWN_SORTED[t], "vfx.pool.spawn-sorted" + t, SPAWN_ROWS[t]);
            SPAWN_RUNS[t] = fit(recording, SPAWN_RUNS[t], "vfx.pool.spawn-runs" + t, REGION_COUNT[t] * 2);
            RUN_FILLS[t] = CgGpuCount.of(REGION_COUNT[t] * 2);
            loadCapKernels();
        }
        if (reportRows > 0) {
            reportStream = fitRows(recording, reportStream, "vfx.pool.report-events", reportRows * maxSteps);
            reportCounts = fit(recording, reportCounts, "vfx.pool.report-counts", maxSteps);
        }
        for (int t = 0; t < BIN_ROWS.length; t++) {
            if (BIN_SLOTS[t] == 0) continue;
            BIN_STREAMS[t] = fitRows(recording, BIN_STREAMS[t], "vfx.pool.published" + t, BIN_ROWS[t]);
            BIN_COUNT[t] = fit(recording, BIN_COUNT[t], "vfx.pool.published-count" + t, 1);
            BIN_KEYS[t] = fit(recording, BIN_KEYS[t], "vfx.pool.published-keys" + t, BIN_ROWS[t]);
            BIN_ORDER[t] = fit(recording, BIN_ORDER[t], "vfx.pool.published-order" + t, BIN_ROWS[t]);
            BIN_SORTED[t] = fitRows(recording, BIN_SORTED[t], "vfx.pool.published-sorted" + t, BIN_ROWS[t]);
            BIN_RUNS[t] = fit(recording, BIN_RUNS[t], "vfx.pool.published-runs" + t, BIN_SLOTS[t] * 2);
            BIN_RUN_FILLS[t] = CgGpuCount.of(BIN_SLOTS[t] * 2);
            loadCapKernels();
        }
        for (int i = 0; i < STEPPING.size(); i++) {
            CgVfxParticlePool pool = STEPPING.get(i);
            pool.uploadFeeds(recording);
            if (pool.shape.spawnFrom() != null) pool.uploadReads(recording);
        }
        TAKEN.clear();
        for (int i = 0; i < STEPPING.size(); i++) take(STEPPING.get(i).shape);
        for (int d = 0; d < TAKEN.size(); d++) TAKEN.get(d).upload(recording);
        for (int i = 0; i < STEPPING.size(); i++) {
            CgVfxShape s = STEPPING.get(i).shape;
            if (s.readsClips() || s.drawLayout() == CgVfxDrawLayout.SEQUENCE) {
                CgVfxClipAtlas.upload(recording);
                break;
            }
        }
        CgComputePass pass = recording.compute("vfx.pool.step").timed(GPU_STEP).async();
        if (reportRows > 0) CgGpuOps.fill(pass, reportCounts, 0, CgGpuCount.of(maxSteps));
        for (int s = 0; s < maxSteps; s++) {
            // A tier's stream holds only this step's rows: cleared where a pool of that tier spawns this step.
            int parents = 0;
            for (int i = 0; i < STEPPING.size(); i++) {
                CgVfxParticlePool pool = STEPPING.get(i);
                if (pool.shape.spawns() && s < pool.steps) parents |= 1 << pool.shape.tier();
            }
            for (int t = 0; t < SPAWN_ROWS.length; t++) {
                if ((parents >> t & 1) != 0 && SPAWN_ROWS[t] > 0) CgGpuOps.fill(pass, SPAWN_COUNTS[t], 0, ONE);
            }
            for (int tier = 0; tier < CgVfxShape.TIERS; tier++) {
                int childRows = tier > 0 && (parents >> (tier - 1) & 1) != 0 ? CAPPED_ROWS[tier - 1] : 0;
                // Stage by stage across the tier's pools, as Niagara groups each instance's i-th dispatch with every
                // other's (NiagaraGpuComputeDispatch.cpp, PrepareTicksForProxy): pools are independent, so a stage's
                // dispatches share one barrier and a kernel's binding, not one each.
                for (int i = 0; i < STEPPING.size(); i++) {
                    CgVfxParticlePool pool = STEPPING.get(i);
                    if (pool.shape.tier() == tier && s < pool.steps) pool.clearNext(pass);
                }
                // A tier's published rows are this step's alone: cleared before it publishes again.
                boolean publishing = tier < BIN_SLOTS.length && BIN_SLOTS[tier] > 0;
                if (publishing) CgGpuOps.fill(pass, BIN_COUNT[tier], 0, ONE);
                for (int i = 0; i < STEPPING.size(); i++) {
                    CgVfxParticlePool pool = STEPPING.get(i);
                    if (pool.shape.tier() == tier && s < pool.steps) pool.dispatchStep(pass, s, childRows, reportRows);
                }
                for (int i = 0; i < STEPPING.size(); i++) {
                    CgVfxParticlePool pool = STEPPING.get(i);
                    if (pool.shape.tier() == tier && s < pool.steps) pool.finishStep(pass, s);
                }
                if (tier < SPAWN_ROWS.length && (parents >> tier & 1) != 0 && SPAWN_ROWS[tier] > 0) {
                    sortByKey(pass, SPAWN_ROWS[tier], REGION_COUNT[tier], SPAWN_STREAMS[tier], SPAWN_COUNTS[tier],
                            SPAWN_KEYS[tier], SPAWN_ORDER[tier], SPAWN_RUNS[tier], RUN_FILLS[tier], SPAWN_SORTED[tier]);
                }
                if (publishing) {
                    sortByKey(pass, BIN_ROWS[tier], BIN_SLOTS[tier], BIN_STREAMS[tier], BIN_COUNT[tier], BIN_KEYS[tier],
                            BIN_ORDER[tier], BIN_RUNS[tier], BIN_RUN_FILLS[tier], BIN_SORTED[tier]);
                }
            }
        }
        pass.end();
        if (reportRows > 0) {
            for (int s = 0; s < maxSteps; s++) {
                Report report = REPORTS.isEmpty() ? new Report() : REPORTS.poll();
                report.take(STEPPING);
                report.heard = pools != REHEARSED;
                CgGpuOps.readRows(recording, reportStream, (long) s * reportRows * EVENT_BYTES, EVENT_BYTES,
                        CgGpuCount.at(reportCounts, s, reportRows), report);
            }
        }
        for (int i = 0; i < STEPPING.size(); i++) STEPPING.get(i).takeSteps();
        STEPPING.clear();
        depthPyramid = null;
    }

    /** Readies this pool to step: its kernel, storage and rows. False, its steps taken, when it has nothing to step. */
    private boolean begin(CgRecording recording) {
        betweenSteps("record");
        if (steps == 0) return false;
        if (openSlots == 0) {
            // A slot closes only once its particles have died, so there is nothing to step.
            takeSteps();
            return false;
        }
        if (step == null) step = CgVfxEmitterCompiler.compile(shape).kernel("Step");
        if (look == null) look = CgCompute.load(LOOK_KERNELS).kernel("Look");
        reserve(recording, capacity);
        upload(recording);
        if (ringSlots > 0) reserveRings(recording);
        List<CgVfxDataInterface> interfaces = shape.dataInterfaces();
        for (int d = 0; d < interfaces.size(); d++) interfaces.get(d).reserve(recording, this);
        return true;
    }

    /**
     * The neighbour grid's buffers for this storage, its runs cleared when new, and its cells as wide as the widest
     * {@code neighbourRadius} of the open slots' kinds.
     */
    void reserveGrid(CgRecording recording) {
        if (gridKeys == null) {
            CgCompute file = CgCompute.load(NEIGHBOUR_KERNELS);
            gridKeys = file.kernel("Keys");
            gridRuns = file.kernel("Runs");
        }
        cells = Math.max(MIN_CELLS, Math.min(MAX_CELLS, Integer.highestOneBit(storage * 2 - 1) << 1));
        cellKeys = fit(recording, cellKeys, gridKeysName, storage);
        cellValues = fit(recording, cellValues, gridValuesName, storage);
        CgGraphBuffer runs = fit(recording, cellRuns, gridRunsName, cells * 2);
        if (runs != cellRuns) recording.fill(runs, 0);
        cellRuns = runs;
        float widest = 0f;
        for (int s = 0; s < slotCount; s++) {
            if (slotEmitter[s] == null) continue;
            List<? extends CgVfxGpuModule> modules = slotEmitter[s].modules();
            for (int m = 0; m < modules.size(); m++) widest = Math.max(widest, modules.get(m).neighbourRadius());
        }
        cellWidth = Math.max(widest, 0.05f);
    }

    /** After a step: the newest records' keys, sorted with their indices, and each hashed cell's run among them. */
    void buildGrid(CgComputePass pass) {
        CgGraphBuffer live = counts[current];
        pass.dispatch(gridKeys, storage).bind("RECORDS", records).bind("LIVE", live).bind("KEYS", cellKeys)
                .bind("VALUES", cellValues).set("_Cell", cellWidth).set("_Cells", cells)
                .set("_RecordVectors", shape.record().vectors());
        CgGpuOps.sort(pass, Integer.numberOfTrailingZeros(cells), CgGpuOps.Order.ASCENDING, cellKeys, cellValues,
                CgGpuCount.at(live, 0, storage));
        CgGpuOps.fill(pass, cellRuns, 0, CgGpuCount.of(cells * 2));
        pass.dispatch(gridRuns, storage).bind("LIVE", live).bind("KEYS", cellKeys).bind("RUNS", cellRuns);
    }

    /**
     * Fills a history {@link #history()} made since the last recording, the old one's points copied into it, clears runs
     * given out since, and writes each slot's rings.
     */
    private void reserveRings(CgRecording recording) {
        if (store == null) store = CgCompute.load(TRAIL_KERNELS).kernel("Store");
        CgGraphBuffer into = history();
        if (historyFresh) {
            recording.fill(into, 0);
            if (historyFrom != null) {
                recording.copy(historyFrom, 0, into, 0, historyFrom.size());
                recording.release(historyFrom);
                historyFrom = null;
            }
            for (int i = 0; i < historyDrops.size(); i++) recording.release(historyDrops.get(i));
            historyDrops.clear();
            historyFresh = false;
        }
        for (int i = 0; i < ringClearCount; i++) {
            recording.fill(history, ringClears[i * 2] * 16L, ringClears[i * 2 + 1] * 16L, 0);
        }
        ringClearCount = 0;
        if (ringWords.length < slotCount * 4) ringWords = new int[Math.max(16, slotCount * 8)];
        for (int s = 0; s < slotCount; s++) {
            boolean keeps = slotEmitter[s] != null && ringPoints[s] > 0;
            ringWords[s * 4] = keeps ? ringFirst[s] : 0;
            ringWords[s * 4 + 1] = keeps ? slotCapacity[s] : 1;
            ringWords[s * 4 + 2] = keeps ? ringPoints[s] : 0;
            ringWords[s * 4 + 3] = 0;
        }
        ringTable = fit(recording, ringTable, ringsName, slotCount * 4);
        put(recording, ringTable, ringWords, slotCount * 4);
    }

    /** A run of {@code points} in the history: the first free run it fits, else past the end. */
    private int takeRing(int points) {
        for (int i = 0; i < ringFreeCount; i++) {
            int start = ringFree[i * 2], length = ringFree[i * 2 + 1];
            if (length < points) continue;
            if (length == points) {
                System.arraycopy(ringFree, (i + 1) * 2, ringFree, i * 2, (ringFreeCount - i - 1) * 2);
                ringFreeCount--;
            } else {
                ringFree[i * 2] = start + points;
                ringFree[i * 2 + 1] = length - points;
            }
            return clearLater(start, points);
        }
        int start = ringEnd;
        ringEnd += points;
        return clearLater(start, points);
    }

    private int clearLater(int start, int points) {
        if (ringClears.length < (ringClearCount + 1) * 2) ringClears = Arrays.copyOf(ringClears, Math.max(8, ringClearCount * 4));
        ringClears[ringClearCount * 2] = start;
        ringClears[ringClearCount * 2 + 1] = points;
        ringClearCount++;
        return start;
    }

    /** Gives back a run, joined to the free runs it touches, and the end drawn in past a run that reaches it. */
    private void giveRing(int start, int points) {
        int at = 0;
        while (at < ringFreeCount && ringFree[at * 2] < start) at++;
        if (ringFree.length < (ringFreeCount + 1) * 2) ringFree = Arrays.copyOf(ringFree, Math.max(8, ringFreeCount * 4));
        System.arraycopy(ringFree, at * 2, ringFree, (at + 1) * 2, (ringFreeCount - at) * 2);
        ringFree[at * 2] = start;
        ringFree[at * 2 + 1] = points;
        ringFreeCount++;
        for (int i = ringFreeCount - 2; i >= 0; i--) {
            if (ringFree[i * 2] + ringFree[i * 2 + 1] == ringFree[(i + 1) * 2]) {
                ringFree[i * 2 + 1] += ringFree[(i + 1) * 2 + 1];
                System.arraycopy(ringFree, (i + 2) * 2, ringFree, (i + 1) * 2, (ringFreeCount - i - 2) * 2);
                ringFreeCount--;
            }
        }
        if (ringFreeCount > 0 && ringFree[(ringFreeCount - 1) * 2] + ringFree[(ringFreeCount - 1) * 2 + 1] == ringEnd) {
            ringEnd = ringFree[(ringFreeCount - 1) * 2];
            ringFreeCount--;
        }
    }

    private static void loadCapKernels() {
        if (capKeys != null) return;
        CgCompute file = CgCompute.load(EVENT_CAP_KERNELS);
        capKeys = file.kernel("Keys");
        capRuns = file.kernel("Runs");
        capGather = file.kernel("Gather");
    }

    /**
     * A tier's rows this step, {@code count} of at most {@code rows}, sorted by the key in each one's position.w and each
     * of the {@code keys} keys' run among them marked: a spawn event's region, what its children read; a published
     * particle's slot, what its readers read.
     */
    private static void sortByKey(CgComputePass pass, int rows, int keys, CgGraphBuffer stream, CgGraphBuffer count,
                                  CgGraphBuffer keyed, CgGraphBuffer order, CgGraphBuffer runs, CgGpuCount runWords,
                                  CgGraphBuffer sorted) {
        pass.dispatch(capKeys, rows).bind("EVENTS", stream).bind("COUNT", count).bind("KEYS", keyed).bind("VALUES", order);
        int bits = Math.max(1, 32 - Integer.numberOfLeadingZeros(keys - 1));
        CgGpuOps.sort(pass, bits, CgGpuOps.Order.ASCENDING, keyed, order, CgGpuCount.at(count, 0, rows));
        CgGpuOps.fill(pass, runs, 0, runWords);
        pass.dispatch(capRuns, rows).bind("COUNT", count).bind("KEYS", keyed).bind("RUNS", runs);
        pass.dispatch(capGather, rows).bind("EVENTS", stream).bind("VALUES", order).bind("SORTED", sorted);
    }

    /** The rows one step of this pool can report: its capacity once for each event that reports. */
    private int reportRows() {
        if (!shape.reports()) return 0;
        int events = 0;
        for (int e = 0; e < shape.events(); e++) if (shape.eventReports(e)) events++;
        return capacity * events;
    }

    /**
     * Claims this recording's regions of its tier, one each spawning event, in their order: the stream's room for its
     * capacity's firings, and a cap of its open slots' definitions' {@code maxPerFrame} together, never more than its
     * capacity, since a particle fires an event once a step.
     */
    private void claimRegions() {
        int t = shape.tier();
        regionBase = REGION_COUNT[t];
        for (int e = 0; e < shape.events(); e++) {
            if (!shape.eventSpawns(e)) continue;
            long most = 0;
            for (int s = 0; s < slotCount; s++) if (slotEmitter[s] != null) most += slotEmitter[s].events().get(e).maxPerFrame();
            int cap = (int) Math.max(1, Math.min(capacity, most));
            int region = REGION_COUNT[t]++;
            if (REGION_WORDS[t].length < REGION_COUNT[t] * 4) REGION_WORDS[t] = Arrays.copyOf(REGION_WORDS[t], REGION_COUNT[t] * 8);
            REGION_WORDS[t][region * 4] = CAPPED_ROWS[t];
            REGION_WORDS[t][region * 4 + 1] = cap;
            CAPPED_ROWS[t] += cap;
            SPAWN_ROWS[t] += capacity;
        }
    }

    /** This recording's feeds, sorted by key, of slots whose parent is stepping: what {@code step_child} searches. */
    private void uploadFeeds(CgRecording recording) {
        feeds = childMax = 0;
        if (shape.tier() == 0 || SPAWN_ROWS[shape.tier() - 1] == 0) return;
        for (int slot = 0; slot < slotCount; slot++) {
            CgVfxParticlePool parent = feedPool[slot];
            if (parent == null || !STEPPING.contains(parent) || !parent.isOpen(feedSlot[slot])
                    || parent.slotEmitter[feedSlot[slot]] != feedParent[slot]) {
                continue;
            }
            CgVfxEvent event = feedParent[slot].events().get(feedEvent[slot]);
            if (feedWords.length < (feeds + 1) * 4) feedWords = Arrays.copyOf(feedWords, Math.max(16, feedWords.length * 2));
            int at = feeds * 4;
            feedWords[at] = parent.id << 19 | feedSlot[slot] << 3 | feedEvent[slot];
            feedWords[at + 1] = slot;
            feedWords[at + 2] = event.countMin() | event.count() << 16;
            feedWords[at + 3] = Float.floatToRawIntBits(event.inherit());
            childMax = Math.max(childMax, event.count());
            // Insertion by unsigned key: a handful of slots.
            for (int i = feeds; i > 0 && Integer.compareUnsigned(feedWords[(i - 1) * 4], feedWords[i * 4]) > 0; i--) {
                for (int w = 0; w < 4; w++) {
                    int t = feedWords[(i - 1) * 4 + w];
                    feedWords[(i - 1) * 4 + w] = feedWords[i * 4 + w];
                    feedWords[i * 4 + w] = t;
                }
            }
            feeds++;
        }
        if (feeds == 0) return;
        feedBuffer = fit(recording, feedBuffer, name() + ".feeds", feeds * 4);
        put(recording, feedBuffer, feedWords, feeds * 4);
    }

    /** The count the next step appends to, cleared: a step's first stage. */
    private void clearNext(CgComputePass pass) {
        CgGpuOps.fill(pass, counts[1 - current], 0, ONE);
    }

    /** Step {@code s}'s dispatch, its children read from {@code spawnRows} rows of the tier before when its parents stepped. */
    private void dispatchStep(CgComputePass pass, int s, int spawnRows, int reportRows) {
        CgGraphBuffer next = counts[1 - current];
        boolean children = feeds > 0 && spawnRows > 0;
        int live = Math.min(storage, stepCapacity[s]);
        CgDispatch stepping = pass.dispatch(step, live + stepSpawned[s] + (children ? spawnRows * childMax : 0))
                .bind("IN", records).bind("OUT", records).counter("OUT", next, 0).bind("LIVE", counts[current])
                .bind("PARAMS", paramBuffer).bind("INSTANCES", instanceBuffer).bind("SPAWNS", spawnBuffer)
                .set("_Step", stepBlock[s * 4], stepBlock[s * 4 + 1], stepBlock[s * 4 + 2], stepBlock[s * 4 + 3])
                .set("_InstanceAt", stepInstanceAt[s] / 4).set("_SpawnAt", stepSpawnAt[s] / 4)
                .set("_SpawnRows", stepSpawnRows[s]).set("_Spawned", stepSpawned[s]).set("_PoolId", id)
                .set("_ChildRows", children ? spawnRows : 0).set("_ChildMax", children ? childMax : 0)
                .set("_Feeds", children ? feeds : 0);
        int tier = shape.tier();
        if (shape.spawns()) {
            stepping.bind("SPAWN_EVENTS", SPAWN_STREAMS[tier]).counter("SPAWN_EVENTS", SPAWN_COUNTS[tier], 0)
                    .set("_RegionBase", regionBase);
        }
        if (shape.reports()) {
            stepping.bind("REPORT_EVENTS", reportStream, (long) s * reportRows * EVENT_BYTES, (long) reportRows * EVENT_BYTES)
                    .counter("REPORT_EVENTS", reportCounts, s * 4L);
        }
        if (tier > 0) {
            stepping.bind("PARENT_EVENTS", children ? SPAWN_SORTED[tier - 1] : unfed)
                    .bind("PARENT_RUNS", children ? SPAWN_RUNS[tier - 1] : unfed)
                    .bind("PARENT_REGIONS", children ? SPAWN_REGIONS[tier - 1] : unfed)
                    .set("_ParentRegions", children ? REGION_COUNT[tier - 1] : 0).bind("FEEDS", children ? feedBuffer : unfed);
        }
        if (shape.publishes()) {
            stepping.bind("PUBLISHED", BIN_STREAMS[tier]).counter("PUBLISHED", BIN_COUNT[tier], 0)
                    .set("_BinKeyBase", binKeyBase);
        }
        if (shape.spawnFrom() != null) {
            boolean published = BIN_SLOTS[tier - 1] > 0;
            stepping.bind("SOURCE_ROWS", published ? BIN_SORTED[tier - 1] : unfed)
                    .bind("SOURCE_RUNS", published ? BIN_RUNS[tier - 1] : unfed).bind("READS", readsBuffer);
        }
        if (shape.readsClips()) CgVfxClipAtlas.bind(stepping);
        List<CgVfxDataInterface> interfaces = shape.dataInterfaces();
        for (int d = 0; d < interfaces.size(); d++) interfaces.get(d).bind(stepping, this);
        for (int i = 0; i < shape.modules(); i++) {
            if (textureEntries[i] == null) continue;
            CgTexture[] any = shape.textures(i);
            for (int e = 0; e < TEXTURE_ENTRIES; e++) if (textureEntries[i][e] != null) any = textureEntries[i][e];
            for (int e = 0; e < TEXTURE_ENTRIES; e++) {
                CgTexture[] entry = textureEntries[i][e] != null ? textureEntries[i][e] : any;   // an empty entry: one bound
                for (int t = 0; t < entry.length; t++) stepping.texture(CgVfxEmitterCompiler.texture(i, t, e), entry[t]);
            }
        }
        current = 1 - current;
    }

    /** Step {@code s}'s last stages, after {@link #dispatchStep}: the drawn record, the neighbour grid, the history. */
    private void finishStep(CgComputePass pass, int s) {
        // What draws read, written into the drawn buffer at each live record's index.
        CgVfxRecord record = shape.record();
        CgWorldVoxelWindow.get().bindBlocks(pass.dispatch(look, storage * shape.drawnStride()).bind("IN", records).bind("DRAWN", drawn)
                .set("_DrawnStride", shape.drawnStride()).set("_Drawn", shape.drawnMask()).set("_RowVectors", shape.rowVectors())
                .set("_LifeRollAt", shape.lifeRollAt()).set("_LifeRolls", shape.lifeRolls()).set("_LifeBindAt", shape.lifeBindAt())
                .set("_DrawnBindAt", shape.drawnBindAt()).set("_LightAt", shape.lightAt()).set("_LightBindAt", shape.lightBindAt())
                .set("_RecordVectors", record.vectors()).set("_PreviousAt", record.word(CgVfxRecord.Tail.PREVIOUS))
                .set("_ColorAt", record.word(CgVfxRecord.Tail.COLOR)).set("_ScaleAt", record.word(CgVfxRecord.Tail.SCALE))
                .set("_TurnAt", record.word(CgVfxRecord.Tail.TURN)).set("_TurnRateAt", record.word(CgVfxRecord.Tail.TURN_RATE))
                .set("_TurnStateAt", record.word(CgVfxRecord.Tail.TURN_STATE)).set("_OrbitAt", record.word(CgVfxRecord.Tail.ORBIT))
                .bind("LIVE", counts[current]).bind("INSTANCES", instanceBuffer).bind("CURVES", curveBuffer)
                .set("_InstanceAt", stepInstanceAt[s] / 4).set("_InstanceRow", shape.instanceRowVectors())
                .set("_Floors", shape.floors() ? 1 : 0).set("_Texels", CgVfxGpuEmitter.CURVE_TEXELS));
        List<CgVfxDataInterface> interfaces = shape.dataInterfaces();
        for (int d = 0; d < interfaces.size(); d++) interfaces.get(d).afterStep(pass, this);
        if (stepStores[s]) {
            if (ringSlots > 0) {
                pass.dispatch(store, storage).bind("RECORDS", records).bind("LIVE", counts[current])
                        .bind("RINGS", ringTable).bind("HISTORY", history).set("_Step", stepsStored)
                        .set("_RecordVectors", record.vectors());
            }
            stepsStored++;
            pendingStores--;
        }
    }

    /** The neighbour grid the last step built, for {@code CgVfxDataInterfaces.NEIGHBOURS}' binding. */
    void bindGrid(CgDispatch d) {
        d.bind("SORTED", cellValues).bind("CELL_RUNS", cellRuns).set("_NeighbourCell", cellWidth).set("_NeighbourCells", cells);
    }

    /** The scene's depth as {@code fx_depth_at.glsl} reads it, or none. */
    static void bindDepth(CgDispatch d) {
        if (depthPyramid == null) {
            d.set("_DepthSize", 0f, 0f, 0f, 0f);
            return;
        }
        Matrix4f c = DEPTH_CLIP, v = DEPTH_INVERSE;
        double x = DEPTH_VIEW.x(), y = DEPTH_VIEW.y(), z = DEPTH_VIEW.z();
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        d.texture("_DepthPyramid", depthPyramid)
                .set("_DepthSize", depthPyramid.getWidth(), depthPyramid.getHeight(), 0f, 0f)
                .set("_DepthClipX", c.m00(), c.m10(), c.m20(), c.m30())
                .set("_DepthClipY", c.m01(), c.m11(), c.m21(), c.m31())
                .set("_DepthClipW", c.m03(), c.m13(), c.m23(), c.m33())
                .set("_DepthLens", DEPTH_VIEW.projection().m00(), DEPTH_VIEW.projection().m11(),
                        DEPTH_VIEW.projection().m20(), DEPTH_VIEW.projection().m21())
                .set("_DepthViewX", v.m00(), v.m01(), v.m02(), 0f)
                .set("_DepthViewY", v.m10(), v.m11(), v.m12(), 0f)
                .set("_DepthViewZ", v.m20(), v.m21(), v.m22(), 0f)
                .set("_DepthViewW", v.m30(), v.m31(), v.m32(), 1f)
                .set("_DepthEyeX", bx).set("_DepthEyeY", by).set("_DepthEyeZ", bz)
                .set("_DepthEyeFrac", (float) (x - bx), (float) (y - by), (float) (z - bz), 0f);
    }

    private String name() {
        return passName.substring(0, passName.length() - ".step".length());
    }

    private static CgGraphBuffer fitRows(CgRecording recording, CgGraphBuffer buffer, String name, int rows) {
        return fit(recording, buffer, name, rows * (EVENT_BYTES / 4));
    }

    /**
     * One step's rows for the CPU on their way back: which definition each stepping pool's slots held then, so a row
     * finds its event's definition and cap however the slots have changed since. Reused once delivered.
     */
    private static final class Report implements CgGpuOps.Rows {
        private final IdentityHashMap<CgVfxParticlePool, CgVfxGpuEmitter[]> slots = new IdentityHashMap<>();
        private final IdentityHashMap<CgVfxParticlePool, Object[]> owners = new IdentityHashMap<>();
        private final IdentityHashMap<CgVfxGpuEmitter, CgVfxEventRows[]> groups = new IdentityHashMap<>();
        private CgVfxParticlePool[] byId = new CgVfxParticlePool[0];
        /** False for a rehearsal's: its rows are read and reach no listener. */
        private boolean heard = true;

        void take(List<CgVfxParticlePool> pools) {
            for (int i = 0; i < pools.size(); i++) {
                CgVfxParticlePool pool = pools.get(i);
                if (!pool.shape.reports()) continue;
                CgVfxGpuEmitter[] held = slots.get(pool);
                if (held == null || held.length < pool.slotCount) slots.put(pool, held = new CgVfxGpuEmitter[pool.slotEmitter.length]);
                System.arraycopy(pool.slotEmitter, 0, held, 0, pool.slotCount);
                Arrays.fill(held, pool.slotCount, held.length, null);
                Object[] by = owners.get(pool);
                if (by == null || by.length < held.length) owners.put(pool, by = new Object[held.length]);
                System.arraycopy(pool.slotOwner, 0, by, 0, pool.slotCount);
                Arrays.fill(by, pool.slotCount, by.length, null);
                if (byId.length <= pool.id) byId = Arrays.copyOf(byId, Math.max(pool.id + 1, byId.length * 2));
                byId[pool.id] = pool;
            }
        }

        @Override
        public void accept(int count, ByteBuffer rows) {
            for (CgVfxEventRows[] g : groups.values()) for (CgVfxEventRows r : g) if (r != null) r.clear();
            for (int at = 0; at < rows.limit(); at += EVENT_BYTES) {
                int key = rows.getInt(at + 68), event = rows.getInt(at + 72);
                int poolId = key >>> 16, slot = key & 0xFFFF;
                CgVfxParticlePool pool = poolId < byId.length ? byId[poolId] : null;
                CgVfxGpuEmitter[] held = pool == null ? null : slots.get(pool);
                CgVfxGpuEmitter definition = held == null || slot >= held.length ? null : held[slot];
                if (definition == null || event >= definition.events().size()) continue;
                CgVfxEventRows[] group = groups.get(definition);
                if (group == null) groups.put(definition, group = new CgVfxEventRows[CgVfxEvent.MAX_EVENTS]);
                CgVfxEventRows into = group[event];
                if (into == null) into = group[event] = new CgVfxEventRows();
                if (into.count() >= definition.events().get(event).readback()) {
                    into.drop(1);
                    continue;
                }
                into.add(slot, rows.getInt(at + 64),
                        rows.getInt(at + 48) + (double) rows.getFloat(at), rows.getInt(at + 52) + (double) rows.getFloat(at + 4),
                        rows.getInt(at + 56) + (double) rows.getFloat(at + 8), rows.getFloat(at + 16), rows.getFloat(at + 20),
                        rows.getFloat(at + 24), rows.getFloat(at + 32), rows.getFloat(at + 36), rows.getFloat(at + 40),
                        rows.getInt(at + 76));
                // The normal's w is what a collision met (fx_hit's code); the block's w its entity, for an entity's.
                Object[] by = owners.get(pool);
                into.tag(by == null || slot >= by.length ? null : by[slot], (int) rows.getFloat(at + 44), rows.getInt(at + 60));
            }
            for (Map.Entry<CgVfxGpuEmitter, CgVfxEventRows[]> e : groups.entrySet()) {
                CgVfxEventRows[] group = e.getValue();
                for (int event = 0; event < group.length; event++) {
                    CgVfxEventRows r = group[event];
                    if (r == null || r.count() == 0 && r.dropped() == 0 || !heard) continue;
                    // From the end, so a listener may stop listening as it hears.
                    for (int l = LISTENERS.size() - 1; l >= 0; l--) {
                        if (l < LISTENERS.size()) LISTENERS.get(l).events(e.getKey(), event, r);
                    }
                }
            }
            done();
        }

        @Override
        public void failed(String reason) {
            done();
        }

        private void done() {
            slots.clear();
            owners.clear();
            Arrays.fill(byId, null);
            REPORTS.add(this);
        }
    }

    /** Whether {@link #release} dropped it: what holds it lets it go. */
    public boolean isReleased() {
        return released;
    }

    /** How many pools {@link #release} has dropped: what holds pools looks for released ones only when it moved. */
    public static int releases() {
        return releases;
    }

    private static int releases;

    private void releaseStorage(CgRecording recording) {
        CgGraphBuffer[] owned = {records, drawn, counts[0], counts[1], paramBuffer, curveBuffer, instanceBuffer, spawnBuffer,
                history, ringTable, historyFrom, cellKeys, cellValues, cellRuns, lightSlots};
        for (CgGraphBuffer buffer : owned) if (buffer != null) recording.release(buffer);
        for (int i = 0; i < historyDrops.size(); i++) recording.release(historyDrops.get(i));
        records = drawn = paramBuffer = curveBuffer = instanceBuffer = spawnBuffer = counts[0] = counts[1] = null;
        history = ringTable = historyFrom = lightSlots = null;
        cellKeys = cellValues = cellRuns = null;
        if (beams != null) beams.release(recording);
        historyDrops.clear();
    }

    public CgVfxShape shape() {
        return shape;
    }

    /**
     * A slot for one playing instance of {@code emitter} that can have {@code capacity} particles alive at once; its
     * definition's parameter row is written the first time one of its instances opens here.
     *
     * @throws IllegalArgumentException if {@code emitter}'s shape is not this pool's
     */
    public int open(CgVfxGpuEmitter emitter, int capacity) {
        return open(emitter, capacity, null);
    }

    /** As {@link #open(CgVfxGpuEmitter, int)}, for {@code owner}: its event rows carry it ({@link CgVfxEventRows#owner}). */
    public int open(CgVfxGpuEmitter emitter, int capacity, @Nullable Object owner) {
        betweenSteps("open");
        if (capacity < 0) throw new IllegalArgumentException("capacity " + capacity);
        String key = CgVfxShape.of(emitter, shape.tier()).key();
        if (!key.equals(shape.key())) {
            throw new IllegalArgumentException(emitter.name() + " has shape " + key + ", not this pool's " + shape.key());
        }
        int slot = 0;
        while (slot < slotCount && slotEmitter[slot] != null) slot++;
        if (slot == slotCount) growSlots(slotCount + 1);
        CgVfxShape own = CgVfxShape.of(emitter, shape.tier());
        if (!takesTextures(own)) {
            throw new IllegalArgumentException(emitter.name() + "'s textures do not fit this pool's table: open it in the pool "
                    + "of(owner, emitter) answers");
        }
        slotEmitter[slot] = emitter;
        slotOwner[slot] = owner;
        slotCapacity[slot] = capacity;
        takeTextures(slot, own);
        slotRow[slot] = useRow(emitter);
        slotOpened[slot] = ++openings;
        slotScale[slot] = 1f;
        slotBindScale[slot] = 1f;
        Arrays.fill(socketPrev, slot * CgVfxSockets.MAX * 4, (slot + 1) * CgVfxSockets.MAX * 4, 0);
        slotSourceReach[slot] = 0f;
        ringPoints[slot] = emitter.trailPoints();
        if (beams != null) beams.open(slot, capacity);
        if (ringPoints[slot] > 0) {
            ringFirst[slot] = takeRing(capacity * ringPoints[slot]);
            ringSlots++;
        }
        if (slot == slotCount) slotCount++;
        openSlots++;
        this.capacity += capacity;
        basesStale = true;
        return slot;
    }

    /** Gives {@code slot} back, its particles all dead. */
    public void close(int slot) {
        betweenSteps("close");
        CgVfxGpuEmitter emitter = openSlot(slot, "close");
        releaseRow(emitter);
        giveTextures(slot);
        capacity -= slotCapacity[slot];
        if (beams != null) beams.close(slot);
        if (ringPoints[slot] > 0) {
            giveRing(ringFirst[slot], slotCapacity[slot] * ringPoints[slot]);
            ringPoints[slot] = 0;
            ringSlots--;
        }
        slotEmitter[slot] = null;
        slotOwner[slot] = null;
        slotCapacity[slot] = 0;
        slotGrid[slot] = null;
        feedPool[slot] = null;
        readPool[slot] = null;
        readSource[slot] = null;
        openSlots--;
        while (slotCount > 0 && slotEmitter[slotCount - 1] == null) slotCount--;
        basesStale = true;
    }

    /** The emitter open {@code slot} plays. */
    public CgVfxGpuEmitter emitter(int slot) {
        return openSlot(slot, "emitter");
    }

    /**
     * Moves open {@code slot} to {@code emitter}'s numbers: an instance that took a newer emitter of this pool's shape,
     * a hot reload's {@code VALUE} swap. Its particles go on under them from the next step. Render thread, between
     * steps.
     */
    public void rebind(int slot, CgVfxGpuEmitter emitter) {
        betweenSteps("rebind");
        CgVfxGpuEmitter was = openSlot(slot, "rebind");
        if (was == emitter) return;
        CgVfxShape own = CgVfxShape.of(emitter, shape.tier());
        giveTextures(slot);
        if (!takesTextures(own)) {
            takeTextures(slot, CgVfxShape.of(was, shape.tier()));
            throw new IllegalArgumentException(emitter.name() + "'s textures do not fit this pool's table: open it in a pool "
                    + "of its own");
        }
        takeTextures(slot, own);
        releaseRow(was);
        slotEmitter[slot] = emitter;
        slotRow[slot] = useRow(emitter);
    }

    /** Whether every texture {@code own}'s kinds sample is in this pool's table, or has a free entry to go in. */
    private boolean takesTextures(CgVfxShape own) {
        for (int i = 0; i < shape.modules(); i++) {
            if (textureEntries[i] == null) continue;
            if (entryOf(i, own) < 0 && freeEntry(i) < 0) return false;
        }
        return true;
    }

    /** Module {@code i}'s entry holding {@code own}'s textures, by identity; -1 for none. */
    private int entryOf(int i, CgVfxShape own) {
        CgTexture[][] entries = textureEntries[i];
        for (int e = 0; e < TEXTURE_ENTRIES; e++) {
            if (entries[e] == null) continue;
            boolean same = true;
            for (int t = 0; t < entries[e].length && same; t++) same = entries[e][t] == own.texture(i, t);
            if (same) return e;
        }
        return -1;
    }

    private int freeEntry(int i) {
        for (int e = 0; e < TEXTURE_ENTRIES; e++) if (textureEntries[i][e] == null) return e;
        return -1;
    }

    /** {@code slot}'s entries for {@code own}'s textures, taken or shared. */
    private void takeTextures(int slot, CgVfxShape own) {
        for (int i = 0; i < shape.modules(); i++) {
            if (textureEntries[i] == null) continue;
            int e = entryOf(i, own);
            if (e < 0) {
                e = freeEntry(i);
                textureEntries[i][e] = own.textures(i);
            }
            textureUses[i][e]++;
            slotTexture[slot * shape.modules() + i] = e;
        }
    }

    /** {@code slot}'s entries given back: one no slot uses is free again. */
    private void giveTextures(int slot) {
        for (int i = 0; i < shape.modules(); i++) {
            if (textureEntries[i] == null) continue;
            int e = slotTexture[slot * shape.modules() + i];
            if (--textureUses[i][e] == 0) textureEntries[i][e] = null;
        }
    }

    private void releaseRow(CgVfxGpuEmitter emitter) {
        int[] row = rows.get(emitter);
        if (--row[1] == 0) {
            rows.remove(emitter);
            if (freeRows.length == freeRowCount) freeRows = Arrays.copyOf(freeRows, Math.max(4, freeRowCount * 2));
            freeRows[freeRowCount++] = row[0];
            if (spareEntries.length == spareCount) spareEntries = Arrays.copyOf(spareEntries, Math.max(4, spareCount * 2));
            spareEntries[spareCount++] = row;
        }
    }

    /**
     * Makes {@code slot} the child slot of {@code parent}'s slot {@code parentSlot}'s event {@code event}: each step its
     * children spawn here, in the step the event fired. Closing either slot ends it. Render thread, between steps.
     *
     * @throws IllegalArgumentException if that event does not spawn this slot's definition, or this pool is not the
     *                                  feed tier after the parent's ({@link #of(Object, CgVfxGpuEmitter, int)})
     */
    public void feed(int slot, CgVfxParticlePool parent, int parentSlot, int event) {
        betweenSteps("feed");
        CgVfxGpuEmitter child = openSlot(slot, "feed");
        CgVfxGpuEmitter from = parent.openSlot(parentSlot, "feed's parent");
        if (shape.tier() != parent.shape.tier() + 1) {
            throw new IllegalArgumentException(child.name() + "'s pool is at feed tier " + shape.tier() + ", its parent "
                    + from.name() + "'s at " + parent.shape.tier() + ": a child spawns in the tier after its parent's");
        }
        List<CgVfxEvent> events = from.events();
        if (event < 0 || event >= events.size() || events.get(event).child() != child) {
            throw new IllegalArgumentException(from.name() + "'s event " + event + " does not spawn " + child.name());
        }
        feedPool[slot] = parent;
        feedParent[slot] = from;
        feedSlot[slot] = parentSlot;
        feedEvent[slot] = event;
    }

    /**
     * Births {@code slot}'s particles on {@code source}'s slot {@code sourceSlot}: its definition reads another emitter's
     * particles ({@code CgVfxSpawnFrom}) and that slot plays its source. The source steps a tier before, publishing its
     * live particles each step.
     *
     * <pre>{@code
     * int sparks = sparkPool.open(SPARKS, n), rocks = rockPool.open(ROCKS, m);
     * sparkPool.readFrom(sparks, rockPool, rocks);     // SPARKS is born on ROCKS' particles
     * }</pre>
     */
    public void readFrom(int slot, CgVfxParticlePool source, int sourceSlot) {
        betweenSteps("readFrom");
        CgVfxGpuEmitter reader = openSlot(slot, "readFrom");
        CgVfxGpuEmitter from = source.openSlot(sourceSlot, "readFrom's source");
        if (shape.spawnFrom() == null || !source.shape.publishes() || shape.tier() != source.shape.tier() + 1) {
            throw new IllegalArgumentException(reader.name() + " at feed tier " + shape.tier() + " is not born on " + from.name()
                    + " at " + source.shape.tier() + ": a reader steps the tier after a source that publishes");
        }
        readPool[slot] = source;
        readSlot[slot] = sourceSlot;
        readSource[slot] = from;
    }

    /** Claims this recording's room in its tier's published rows and keys: its capacity and its slots. */
    private void claimBins() {
        int t = shape.tier();
        binKeyBase = BIN_SLOTS[t];
        BIN_ROWS[t] += capacity;
        BIN_SLOTS[t] += slotCount;
    }

    /**
     * Each slot's source this recording: its slot's key among its tier's published rows, then the shares of velocity
     * and spin it inherits; a key of {@code 0xFFFFFFFF} where its source is not stepping, which reads as none published.
     */
    private void uploadReads(CgRecording recording) {
        if (readWords.length < slotCount * 4) readWords = new int[Math.max(16, slotCount * 8)];
        for (int slot = 0; slot < slotCount; slot++) {
            CgVfxParticlePool source = readPool[slot];
            int at = slot * 4;
            boolean live = source != null && STEPPING.contains(source) && source.isOpen(readSlot[slot])
                    && source.slotEmitter[readSlot[slot]] == readSource[slot];
            readWords[at] = live ? source.binKeyBase + readSlot[slot] : -1;
            readWords[at + 1] = 0;
            CgVfxSpawnFrom from = slotEmitter[slot] == null ? null : slotEmitter[slot].spawnFrom();
            readWords[at + 2] = Float.floatToRawIntBits(from == null ? 0f : from.inheritVelocity());
            readWords[at + 3] = Float.floatToRawIntBits(from == null ? 0f : from.inheritRotation());
        }
        readsBuffer = fit(recording, readsBuffer, name() + ".reads", Math.max(1, slotCount) * 4);
        put(recording, readsBuffer, readWords, slotCount * 4);
    }

    /**
     * Which opening of the pool {@code slot} now holds, numbered from 1; 0 for a slot that is closed or was never open.
     * Two readings that differ mean the slot was closed between them, whatever opened it since.
     */
    public long opening(int slot) {
        return slot < slotOpened.length && slotEmitter[slot] != null ? slotOpened[slot] : 0L;
    }

    /** The particles {@code slot} can have alive. */
    public int capacity(int slot) {
        openSlot(slot, "capacity");
        return slotCapacity[slot];
    }

    /** Every open slot's capacity together: what the pool's storage must hold. */
    public int capacity() {
        return capacity;
    }

    /** How many slots are open. */
    public int openSlots() {
        return openSlots;
    }

    /** The slots in use reach below this. */
    public int slotCount() {
        return slotCount;
    }

    /**
     * Where {@code slot}'s region starts in a list the pool's particles are laid out in by slot, each slot as many as
     * its capacity: the open slots before it, together. A {@code MESHES} renderer's object records sit there.
     */
    public int listBase(int slot) {
        openSlot(slot, "listBase");
        if (basesStale) {
            for (int s = 0, base = 0; s < slotCount; s++) {
                slotBase[s] = base;
                base += slotCapacity[s];
            }
            basesStale = false;
        }
        return slotBase[slot];
    }

    /** Begins a particle step of {@code dt} seconds in wind {@code (windX, windY, windZ)}, blocks a second. */
    public void beginStep(float dt, float windX, float windY, float windZ) {
        if (inStep) throw new IllegalStateException("beginStep before the last step's endStep");
        inStep = true;
        int s = steps;
        if (stepSlots.length == s) {
            int n = Math.max(4, s * 2);
            stepBlock = Arrays.copyOf(stepBlock, n * 4);
            stepInstanceAt = Arrays.copyOf(stepInstanceAt, n);
            stepSlots = Arrays.copyOf(stepSlots, n);
            stepSpawnAt = Arrays.copyOf(stepSpawnAt, n);
            stepSpawnRows = Arrays.copyOf(stepSpawnRows, n);
            stepSpawned = Arrays.copyOf(stepSpawned, n);
            stepCapacity = Arrays.copyOf(stepCapacity, n);
            stepStores = Arrays.copyOf(stepStores, n);
        }
        stepCapacity[s] = capacity;
        stepBlock[s * 4] = dt;
        stepBlock[s * 4 + 1] = windX;
        stepBlock[s * 4 + 2] = windY;
        stepBlock[s * 4 + 3] = windZ;
        stepInstanceAt[s] = instanceEnd;
        stepSlots[s] = slotCount;
        stepSpawnAt[s] = spawnEnd;
        stepSpawnRows[s] = 0;
        stepSpawned[s] = 0;
        int need = instanceEnd + slotCount * instanceWords;
        if (instances.length < need) instances = Arrays.copyOf(instances, Math.max(need, instances.length * 2));
        instanceEnd = need;
    }

    /**
     * Writes {@code slot}'s row for this step: its seed's bits, the share of its candidates that spawn, the fixed ground
     * height relative to its origin (NaN for none), and what {@code view} says of where and when it is, from which each
     * module with lanes writes its own.
     */
    public void instance(int slot, int seedBits, float share, float groundY, CgVfxInstanceView view) {
        instance(slot, seedBits, share, groundY, view, 1f, false);
    }

    /**
     * As {@link #instance(int, int, float, float, CgVfxInstanceView)}, its particles stepping by {@code stepScale} of the
     * step's time (0 holds them still, unaged, while another slot of the pool prewarms), or with {@code kill} all ending
     * in this step, firing no event: then close the slot after the step. Give a held or killed slot no spawns.
     */
    public void instance(int slot, int seedBits, float share, float groundY, CgVfxInstanceView view, float stepScale,
                         boolean kill) {
        inStep("instance");
        CgVfxGpuEmitter emitter = openSlot(slot, "instance");
        int s = steps;
        if (written[slot] == s + 1) throw new IllegalStateException("slot " + slot + " written twice in one step");
        written[slot] = s + 1;
        slotOrigin[slot * 3] = view.originX();
        slotOrigin[slot * 3 + 1] = view.originY();
        slotOrigin[slot * 3 + 2] = view.originZ();
        slotTurn[slot * 4] = view.rotationX();
        slotTurn[slot * 4 + 1] = view.rotationY();
        slotTurn[slot * 4 + 2] = view.rotationZ();
        slotTurn[slot * 4 + 3] = view.rotationW();
        slotGround[slot] = groundY;
        slotGrid[slot] = emitter.rasterized() != null ? view.grid() : null;
        int at = stepInstanceAt[s] + slot * instanceWords;
        String who = emitter.name();
        words.target(instances, at, INSTANCE_HEADER, who);
        double ox = view.originX(), oy = view.originY(), oz = view.originZ();
        double bx = Math.floor(ox), by = Math.floor(oy), bz = Math.floor(oz);
        words.uvec4(slotRow[slot], seedBits, slot, 0)
                .vec4(view.sourceX(), view.sourceY(), view.sourceZ(), view.time())
                .vec4(share, groundY, stepScale, kill ? 1f : 0f)
                .ivec4((int) bx, (int) by, (int) bz, 0)
                .vec4((float) (ox - bx), (float) (oy - by), (float) (oz - bz), 0f)
                .vec4(view.boxX(), view.boxY(), view.boxZ(), view.loopSeconds())
                .vec4(view.boxHalfX(), view.boxHalfY(), view.boxHalfZ(), 0f)
                .vec4(view.rotationX(), view.rotationY(), view.rotationZ(), view.rotationW())
                .vec4(view.turnX(), view.turnY(), view.turnZ(), view.turnW());
        words.finish("its instance row's header");
        for (int i = 0; i < shape.modules(); i++) {
            int lanes = shape.laneCount(i);
            if (lanes == 0) continue;
            words.target(instances, at + shape.lanesAt(i) * 4, lanes, who, shape.kind(i));
            emitter.modules().get(i).writeInstance(view, words);
            words.finish("its instance lanes");
        }
        int bound = shape.userBindings();
        if (bound > 0) {
            List<CgVfxUserBinding> bindings = emitter.userBindings();
            int base = at + shape.userAt() * 4, end = base + CgVfxShape.userLanes(bound) * 4;
            for (int b = 0; b < bound; b++) {
                CgVfxUserBinding u = bindings.get(b);
                instances[base + b] = Float.floatToRawIntBits(u.apply(view.user(u.param())));
            }
            for (int w = base + bound; w < end; w++) instances[w] = 0;
        }
        for (int b = 0; b < shape.formBindings(); b++) {
            CgVfxFormInput.Binding u = shape.formBinding(b);
            float[] fallback = u.fallback();
            int base = at + shape.formBindingLane(b) * 4;
            for (int c = 0; c < 4; c++) {
                int k = Math.min(c, fallback.length - 1);
                instances[base + c] = Float.floatToRawIntBits(u.apply(view.user(shape.formBindingName(b), k, fallback[k]), k));
            }
        }
        if (shape.drawnBindAt() >= 0) {
            // A drawn slot's bound channels: its user parameter remapped, a vec4 a slot.
            int base = at + shape.drawnBindAt() * 4;
            for (int ds = 0; ds < CgVfxDrawnChannel.SLOTS; ds++) {
                for (int c = 0; c < 4; c++) {
                    CgVfxDrawnChannel ch = emitter.drawn(ds, c);
                    float v = 0f;
                    if (ch != null && ch.bind() != null) {
                        CgVfxFormInput.Binding u = ch.bind();
                        float fallback = u.fallback()[0];
                        v = u.apply(view.user(u.path().substring("user.".length()), 0, fallback), 0);
                    }
                    instances[base + 4 * ds + c] = Float.floatToRawIntBits(v);
                }
            }
        }
        if (shape.lightBindAt() >= 0) {
            // Its light's bound channels: as a drawn slot's, one vec4.
            int base = at + shape.lightBindAt() * 4;
            for (int c = 0; c < 4; c++) {
                CgVfxDrawnChannel ch = emitter.light(c);
                float v = 0f;
                if (ch != null && ch.bind() != null) {
                    CgVfxFormInput.Binding u = ch.bind();
                    v = u.apply(view.user(u.path().substring("user.".length()), 0, u.fallback()[0]), 0);
                }
                instances[base + c] = Float.floatToRawIntBits(v);
            }
        }
        slotBindScale[slot] = 1f;
        if (shape.lifeBindAt() >= 0) {
            int base = at + shape.lifeBindAt() * 4;
            for (int w = 0; w < CgVfxLifeBind.LANES; w++) bindScratch[w] = 1f;
            List<CgVfxLifeBind> binds = emitter.lifeBinds();
            for (int b = 0; b < binds.size(); b++) {
                CgVfxLifeBind u = binds.get(b);
                float[] fallback = u.bind().fallback();
                for (int c = 0; c < u.target().lanes; c++) {
                    float raw = view.user(u.parameter(), c, fallback[Math.min(c, fallback.length - 1)]);
                    bindScratch[u.target().lane + c] *= u.bind().apply(raw, Math.min(c, fallback.length - 1));
                }
            }
            for (int w = 0; w < CgVfxLifeBind.LANES; w++) instances[base + w] = Float.floatToRawIntBits(bindScratch[w]);
            // A bound size widens its particles' cull radius.
            for (int w = 0; w < 4; w++) slotBindScale[slot] = Math.max(slotBindScale[slot], Math.abs(bindScratch[w]));
        }
        List<CgVfxDataInterface> interfaces = shape.dataInterfaces();
        for (int d = 0; d < interfaces.size(); d++) {
            CgVfxDataInterface di = interfaces.get(d);
            if (di.instanceLanes() == 0) continue;
            words.target(instances, at + shape.dataLanesAt(d) * 4, di.instanceLanes(), who, di.id());
            di.writeInstance(view, ox, oy, oz, words);
            words.finish("its data interface lanes");
        }
        float dt = stepDt(s);
        for (int i = 0, base = at + shape.socketsAt() * 4; i < shape.sockets(); i++, base += 4) {
            boolean answered = view.socket(i, socketPoint);
            instances[base] = Float.floatToRawIntBits(answered ? socketPoint[0] : 0f);
            instances[base + 1] = Float.floatToRawIntBits(answered ? socketPoint[1] : 0f);
            instances[base + 2] = Float.floatToRawIntBits(answered ? socketPoint[2] : 0f);
            instances[base + 3] = Float.floatToRawIntBits(answered ? 1f : 0f);
            if (!shape.socketsInherit()) continue;
            // Its velocity: its point in the world now against last step's (Cascade's bone velocity).
            int prev = (slot * CgVfxSockets.MAX + i) * 4, vel = base + shape.sockets() * 4;
            double wx = ox + socketPoint[0], wy = oy + socketPoint[1], wz = oz + socketPoint[2];
            boolean moved = answered && socketPrev[prev + 3] != 0 && dt > 0f;
            instances[vel] = Float.floatToRawIntBits(moved ? (float) ((wx - socketPrev[prev]) / dt) : 0f);
            instances[vel + 1] = Float.floatToRawIntBits(moved ? (float) ((wy - socketPrev[prev + 1]) / dt) : 0f);
            instances[vel + 2] = Float.floatToRawIntBits(moved ? (float) ((wz - socketPrev[prev + 2]) / dt) : 0f);
            instances[vel + 3] = 0;
            socketPrev[prev] = wx;
            socketPrev[prev + 1] = wy;
            socketPrev[prev + 2] = wz;
            socketPrev[prev + 3] = answered ? 1 : 0;
        }
    }

    /**
     * Queues {@code candidates} spawns for {@code slot} this step, spawn indices from {@code firstSpawn}, all bursts born
     * at the step's start where its instance row puts the source. After the slot's {@link #instance} row.
     */
    public void spawn(int slot, int firstSpawn, int candidates) {
        int at = stepInstanceAt[steps] + slot * instanceWords + 4;
        float x = Float.intBitsToFloat(instances[at]), y = Float.intBitsToFloat(instances[at + 1]),
                z = Float.intBitsToFloat(instances[at + 2]);
        spawn(slot, firstSpawn, candidates, candidates, 0f, 1f, x, y, z, x, y, z, 0f, 0f);
    }

    /**
     * Queues one CPU tick's spawns for {@code slot} this step: {@code candidates} spawn indices from {@code firstSpawn},
     * the first {@code bursts} born at {@code bornFrom} seconds into the step where the source was as the tick began
     * ({@code from}), the rest spread from there to {@code bornFrom + tickSeconds} and the source at its end ({@code to}),
     * each stepped for the rest of the step; its loop's seconds run from {@code loopFrom} to {@code loopTo} across it, what
     * a spawn form by the loop reads. After the slot's {@link #instance} row; a row per tick that spawned.
     *
     * <pre>{@code
     * pool.spawn(slot, b.stepFirstSpawn(), b.stepCandidates(), b.stepBursts(), tickStart, TICK,
     *         b.fromX(), b.fromY(), b.fromZ(), b.sourceX(), b.sourceY(), b.sourceZ(), b.stepLoopFrom(), b.stepLoopTo());
     * }</pre>
     */
    public void spawn(int slot, int firstSpawn, int candidates, int bursts, float bornFrom, float tickSeconds,
                      float fromX, float fromY, float fromZ, float toX, float toY, float toZ, float loopFrom, float loopTo) {
        inStep("spawn");
        openSlot(slot, "spawn");
        if (firstSpawn < 0 || candidates < 0 || bursts < 0 || bursts > candidates || !(tickSeconds > 0f) || !(bornFrom >= 0f)) {
            throw new IllegalArgumentException("spawn " + firstSpawn + " + " + candidates + ", " + bursts + " bursts at "
                    + bornFrom + " for " + tickSeconds + " s");
        }
        if (candidates == 0) return;
        int s = steps;
        if (written[slot] != s + 1) throw new IllegalStateException("slot " + slot + " spawned before its instance row");
        if (spawns.length < spawnEnd + SPAWN_ROW_WORDS) {
            spawns = Arrays.copyOf(spawns, Math.max(spawnEnd + SPAWN_ROW_WORDS, spawns.length * 2));
        }
        int[] w = spawns;
        int at = spawnEnd;
        w[at] = slot;
        w[at + 1] = firstSpawn;
        w[at + 2] = candidates;
        w[at + 3] = stepSpawned[s];
        w[at + 4] = Float.floatToRawIntBits(bornFrom);
        w[at + 5] = Float.floatToRawIntBits(bornFrom + tickSeconds);
        w[at + 6] = Float.floatToRawIntBits(bursts);
        w[at + 7] = Float.floatToRawIntBits(tickSeconds);
        w[at + 8] = Float.floatToRawIntBits(fromX);
        w[at + 9] = Float.floatToRawIntBits(fromY);
        w[at + 10] = Float.floatToRawIntBits(fromZ);
        w[at + 11] = Float.floatToRawIntBits(loopFrom);
        w[at + 12] = Float.floatToRawIntBits(toX);
        w[at + 13] = Float.floatToRawIntBits(toY);
        w[at + 14] = Float.floatToRawIntBits(toZ);
        w[at + 15] = Float.floatToRawIntBits(loopTo);
        spawnEnd += SPAWN_ROW_WORDS;
        stepSpawnRows[s]++;
        stepSpawned[s] += candidates;
    }

    /** Ends the step, throwing if an open slot got no {@link #instance} row. */
    public void endStep() {
        inStep("endStep");
        int s = steps;
        inStep = false;
        for (int slot = 0; slot < stepSlots[s]; slot++) {
            if (slotEmitter[slot] != null && written[slot] != s + 1) {
                // The step is dropped whole, so the queue holds only complete steps.
                instanceEnd = stepInstanceAt[s];
                spawnEnd = stepSpawnAt[s];
                for (int w = 0; w < slotCount; w++) if (written[w] == s + 1) written[w] = 0;
                throw new IllegalStateException(slotEmitter[slot].name() + "'s slot " + slot + " got no instance row this step");
            }
        }
        // A trail point each TRAIL_INTERVAL of particle time, at most one a step.
        trailSince += stepBlock[s * 4];
        stepStores[s] = trailSince >= TRAIL_INTERVAL;
        if (stepStores[s]) {
            trailSince = Math.min(trailSince - TRAIL_INTERVAL, TRAIL_INTERVAL);
            pendingStores++;
        }
        steps++;
    }

    /**
     * Drops the queued steps unrecorded, as if the world had recorded them: a headless system's stand-in for
     * {@link CgRenderStage#WORLD_OPAQUE}, which never fires there. Never on a client, whose slots would then hold
     * particles their books count as dead. Render thread, between steps.
     */
    public void discardQueued() {
        betweenSteps("discardQueued");
        while (pendingStores > 0) {
            stepsStored++;
            pendingStores--;
        }
        takeSteps();
    }

    /** Steps queued and not yet taken. */
    public int queuedSteps() {
        return steps;
    }

    /**
     * Every particle, in {@link #record()}'s layout: a history buffer whose newest version holds {@link #live()}'s count
     * of them. Null before the first step is recorded; a new handle after growth, so ask each frame.
     */
    public CgGraphBuffer records() {
        return records;
    }

    /**
     * What draws read of every particle, {@link CgVfxRecord#DRAWN_BYTES} each at its record's index
     * ({@code CgParticleBuffer}'s record): written by the step's last stage. Null and renewed as {@link #records()}.
     */
    public CgGraphBuffer drawn() {
        return drawn;
    }

    /** Its records' layout: its shape's ({@link CgVfxShape#record()}). */
    public CgVfxRecord record() {
        return shape.record();
    }

    /** How many records the newest version of {@link #records()} holds: a {@code uint} at word 0. */
    public CgGraphBuffer live() {
        return counts[current];
    }

    /** Each slot's parameter row: what its Step reads as {@code PARAMS}. */
    CgGraphBuffer params() {
        return paramBuffer;
    }

    /** Before a step of a shape taking {@code CgVfxDataInterfaces.BEAM}: its beams' runs. */
    void reserveBeams(CgRecording recording) {
        if (beams != null) beams.reserve(recording, slotCount);
    }

    /** After each step: its beams' runs, from the step's records. */
    void stepBeams(CgComputePass pass) {
        if (beams != null) beams.step(pass);
    }

    /**
     * Every beam's run as the newest step wrote it, vec4s each: a tag and its record's beam state
     * ({@code fx_beam.glsl}'s {@code FX_BEAM_*}), then {@code shape().beamPoints()} noise points, then as many it heads
     * for; null where its particles are no beams. A beam draw binds it at {@code CgBindingPoints.PARTICLE_BEAMS}.
     */
    @Nullable
    public CgGraphBuffer beamRuns() {
        return beams == null ? null : beams.runs();
    }

    /** Where {@code slot}'s beam runs start in {@link #beamRuns()}, in vec4s: a run a particle of its capacity. */
    public int beamFirst(int slot) {
        return beams == null ? 0 : beams.first(slot);
    }

    /** vec4s each beam's run takes: {@code fx_beam.glsl}'s header, then its noise points twice; 0 for no beams. */
    public int beamRunVectors() {
        return beams == null ? 0 : beams.per();
    }

    /** Each slot's beam runs, a uvec4 a slot: its first vec4, its beams, vec4s a beam, 0; null as {@link #beamRuns()}. */
    @Nullable
    public CgGraphBuffer beamTable() {
        return beams == null ? null : beams.table();
    }

    /**
     * Every trail ring's points, a {@code vec4} each: position from its slot's origin, and 1 + its particle's id (low 24
     * bits), 0 where none has stored. Slot {@code s}'s particle {@code id} keeps its ring at
     * {@code ringFirst(s) + (id % capacity(s)) * ringPoints(s)}, and step {@code k}'s point at {@code k % ringPoints(s)}
     * (where a capacity binds and the share thins spawns, two live ids can share a ring: the later shortens the earlier)
     * of it. A new handle when slots keeping trails grew it, so ask each frame; render thread, between steps.
     */
    public CgGraphBuffer history() {
        long need = Math.max(ringEnd, 1) * 16L;
        if (history == null || history.size() < need) {
            // A new handle now, so a draw submitted before the recording binds the buffer it will read; the recording
            // copies the old one's points into it.
            if (history != null) {
                if (historyFrom == null) historyFrom = history;
                else historyDrops.add(history);
            }
            history = CgGraphBuffer.persistent(historyName, CgBufferDesc.of(Math.max(256L, Long.highestOneBit(need - 1) << 1),
                    CgBufferUsage.STORAGE, CgBufferUsage.COPY));
            historyFresh = true;
        }
        return history;
    }

    /** Where {@code slot}'s trail rings start in {@link #history()}. */
    public int ringFirst(int slot) {
        openSlot(slot, "ringFirst");
        return ringFirst[slot];
    }

    /** Points each of {@code slot}'s rings holds: its definition's {@code trailPoints()}, 0 for none. */
    public int ringPoints(int slot) {
        openSlot(slot, "ringPoints");
        return ringPoints[slot];
    }

    /**
     * The trail point that is the newest in every ring once this frame's queued steps record: what a draw submitted now
     * reads as its head's last point. Points are {@link #TRAIL_INTERVAL} of particle time apart.
     */
    public int lastStep() {
        return stepsStored + pendingStores - 1;
    }

    /** Particle seconds from {@link #lastStep()}'s point to where the particles are once this frame's steps record. */
    public float trailSince() {
        return trailSince;
    }

    /** The records {@link #records()} has room for. */
    public int storage() {
        return storage;
    }

    /** Each parameter row's curves, {@link CgVfxShape#rowVectors()} vec4s a row; null before the first step is recorded. */
    public CgGraphBuffer curves() {
        return curveBuffer;
    }

    /** Every pool alive: what the grids rasterize from ({@link #rasterRows}). Render thread. */
    public static List<CgVfxParticlePool> pools() {
        return Collections.unmodifiableList(ALL);
    }

    /** Whether a slot draws its particles into a grid each frame, with records to draw. */
    public boolean rasterizes() {
        if (records == null) return false;
        for (int s = 0; s < slotCount; s++) if (slotGrid[s] != null && slotGrid[s].index() >= 0) return true;
        return false;
    }

    /**
     * Each slot's rasterizing this frame, uploaded into {@code recording}: three vec4s a slot, its origin from its grid's
     * low corner in blocks and the grid's place (-1 for none), then 0s, then the density its particles hold in each
     * channel. Read by {@code grid.compute}'s Rasterize as {@code RASTER}.
     */
    public CgGraphBuffer rasterRows(CgRecording recording) {
        int bytes = Math.max(1, slotCount) * 48;
        if (rasterBuffer == null || rasterBytes.capacity() < bytes) {
            int size = Math.max(bytes, rasterBytes.capacity() * 2);
            rasterBytes = ByteBuffer.allocate(size).order(ByteOrder.nativeOrder());
            rasterBuffer = CgGraphBuffer.persistent("vfx.pool.raster", CgBufferDesc.of(size, CgBufferUsage.STORAGE,
                    CgBufferUsage.COPY));
        }
        rasterBytes.clear();
        for (int s = 0; s < Math.max(1, slotCount); s++) {
            CgVfxGrid3D grid = s < slotCount ? slotGrid[s] : null;
            int at = s * 48;
            if (grid == null || grid.index() < 0 || grid.cellSize() <= 0f) {
                for (int k = 0; k < 12; k++) rasterBytes.putFloat(at + k * 4, k == 3 ? -1f : 0f);
                continue;
            }
            float half = CgVfxGridAtlas.SPAN * 0.5f * grid.cellSize();
            float[] amounts = slotEmitter[s].rasterized();
            rasterBytes.putFloat(at, (float) (slotOrigin[s * 3] - grid.x()) + half)
                    .putFloat(at + 4, (float) (slotOrigin[s * 3 + 1] - grid.y()) + half)
                    .putFloat(at + 8, (float) (slotOrigin[s * 3 + 2] - grid.z()) + half)
                    .putFloat(at + 12, grid.index());
            for (int k = 4; k < 8; k++) rasterBytes.putFloat(at + k * 4, 0f);
            for (int c = 0; c < 4; c++) rasterBytes.putFloat(at + 32 + c * 4, amounts[c]);
        }
        rasterBytes.limit(Math.max(1, slotCount) * 48);
        recording.update(rasterBuffer, 0, rasterBytes);
        return rasterBuffer;
    }

    /** Whether {@code slot} is open. */
    public boolean isOpen(int slot) {
        return slot >= 0 && slot < slotCount && slotEmitter[slot] != null;
    }

    /** {@code slot}'s parameter row: its definition's. */
    public int paramRow(int slot) {
        openSlot(slot, "paramRow");
        return slotRow[slot];
    }

    /** {@code slot}'s origin on {@code axis} (0 x, 1 y, 2 z) as its last step gave it, in absolute coordinates. */
    public double origin(int slot, int axis) {
        openSlot(slot, "origin");
        return slotOrigin[slot * 3 + axis];
    }

    /** Component {@code i} (x, y, z, w) of {@code slot}'s rotation as its last step gave it, a unit quaternion. */
    public float rotation(int slot, int i) {
        openSlot(slot, "rotation");
        return slotTurn[slot * 4 + i];
    }

    /** {@code slot}'s ground as its last step gave it, relative to its origin; NaN for none. */
    public float ground(int slot) {
        openSlot(slot, "ground");
        return slotGround[slot];
    }

    /**
     * How far past their size a look draws {@code slot}'s particles: a streak's stretch along its motion, a halo. 1
     * until set.
     */
    public void cullScale(int slot, float scale) {
        openSlot(slot, "cullScale");
        slotScale[slot] = scale;
    }

    /**
     * Culls {@code slot}'s particles together, as one sphere of {@code radius} blocks about its origin, rather than each
     * by its size: for a look drawn round its source that its particles' sizes do not bound, as an arc is. 0, the
     * default, culls each particle again. Each frame's sort still orders them by their own depth.
     */
    public void cullAbout(int slot, float radius) {
        openSlot(slot, "cullAbout");
        if (!(radius >= 0f)) throw new IllegalArgumentException("a radius of " + radius);
        slotSourceReach[slot] = radius;
    }

    /** {@link #cullAbout}'s radius for {@code slot}, or 0 when its particles are culled one by one. */
    public float cullSourceRadius(int slot) {
        openSlot(slot, "cullSourceRadius");
        return slotSourceReach[slot];
    }

    /** What a cull takes as the radius of each of {@code slot}'s particles, times its size at birth. */
    public float cullRadius(int slot) {
        openSlot(slot, "cullRadius");
        return rowSizeMax[slotRow[slot]] * slotScale[slot] * slotBindScale[slot];
    }

    /**
     * Replaces the pool's particles with the first {@code count} records of {@code data}, in {@link #record()}'s
     * layout and native order, ahead of the steps recorded after it: a check stepping particles it placed itself.
     * Render thread, between steps.
     *
     * <pre>{@code
     * CgVfxRecord layout = pool.record();
     * layout.putF(packed, r, CgVfxRecord.POSITION, x)...;   // each record's words, in its layout
     * pool.seed(recording, packed, n);
     * pool.beginStep(dt, windX, windY, windZ);    // a step, its instance rows and spawns
     * ...
     * pool.endStep();
     * pool.record(recording);
     * recording.readback(pool.records(), 0, (long) pool.storage() * layout.bytes(), data -> compare(data));
     * recording.readback(pool.live(), 0, 4, data -> alive = data.getInt(0));
     * }</pre>
     *
     * <ul>
     *   <li>The step appends survivors in any order: match records by {@link CgVfxRecord#ID} and slot.</li>
     *   <li>Ask {@link #records()} and {@link #live()} after {@link #record}: each step moves {@link #live()} to the
     *       other count, and growth makes {@link #records()} a new handle.</li>
     * </ul>
     */
    public void seed(CgRecording recording, ByteBuffer data, int count) {
        betweenSteps("seed");
        int bytesEach = shape.record().bytes();
        if (count < 0 || data.capacity() < (long) count * bytesEach) {
            throw new IllegalArgumentException(count + " records in " + data.capacity() + " bytes");
        }
        reserve(recording, count);
        if (count > 0) {
            ByteBuffer bytes = data.duplicate();
            bytes.position(0);
            bytes.limit(count * bytesEach);
            recording.update(records, 0, bytes);
        }
        put(recording, counts[current], new int[]{count}, 1);
    }

    /**
     * Records the queued steps into {@code recording}, one dispatch each in one compute pass, and takes them. The pools
     * record themselves once a host frame on {@link CgRenderStage#WORLD_OPAQUE}; call it only where that stage does
     * not fire, as a check scene. Render thread, between steps.
     */
    public void record(CgRecording recording) {
        recordPools(recording, Collections.singletonList(this));
    }

    /**
     * {@link #record} for several pools at once, in step order, so their events reach each other: a check scene's
     * parent and child pools. Render thread, between steps.
     */
    public static void record(CgRecording recording, List<CgVfxParticlePool> pools) {
        recordPools(recording, pools);
    }

    /** Makes the records and counts the first time, and grows the records to hold {@code need}. */
    private void reserve(CgRecording recording, int need) {
        need = Math.max(need, expectedCapacity);
        if (records == null) {
            storage = sizeClass(need);
            records = CgGraphBuffer.history(recordsName, recordsDesc(storage));
            drawn = CgGraphBuffer.persistent(drawnName, drawnDesc(storage * shape.drawnStride()));
            for (int c = 0; c < 2; c++) {
                counts[c] = CgGraphBuffer.persistent(countNames[c],
                        CgBufferDesc.of(16, CgBufferUsage.STORAGE, CgBufferUsage.COPY, CgBufferUsage.INDIRECT));
                recording.fill(counts[c], 0);
            }
        } else if (need > storage) {
            storage = sizeClass(need);
            records = recording.resize(records, recordsDesc(storage));
            // The step's last stage rewrites every live particle's drawn part before a draw reads it.
            recording.release(drawn);
            drawn = CgGraphBuffer.persistent(drawnName, drawnDesc(storage * shape.drawnStride()));
            CgVfxTrace.count(STORAGE_GROWS, 1);
        }
    }

    /** This frame's instance and spawn rows, and the parameter table when a row was written since the last. */
    private void upload(CgRecording recording) {
        int paramEnd = rowCount * paramWords, curveEnd = rowCount * rowWords();
        CgGraphBuffer params = fit(recording, paramBuffer, paramsName, paramEnd);
        CgGraphBuffer rowCurves = fit(recording, curveBuffer, curvesName, curveEnd);
        if (paramsChanged || params != paramBuffer) put(recording, params, this.params, paramEnd);
        if (paramsChanged || rowCurves != curveBuffer) put(recording, rowCurves, curves, curveEnd);
        paramBuffer = params;
        curveBuffer = rowCurves;
        paramsChanged = false;
        instanceBuffer = fit(recording, instanceBuffer, instancesName, instanceEnd);
        put(recording, instanceBuffer, instances, instanceEnd);
        spawnBuffer = fit(recording, spawnBuffer, spawnsName, spawnEnd);
        put(recording, spawnBuffer, spawns, spawnEnd);
    }

    /**
     * Each slot's light row ({@code CgVfxParticleLights}), at least {@code words} long: fitted by the world renderer's
     * firing that uploads it, released with the pool's storage.
     */
    public CgGraphBuffer lightSlots(CgRecording recording, int words) {
        lightSlots = fit(recording, lightSlots, "vfx.pool.lights", words);
        return lightSlots;
    }

    /** Each slot's light row as last fitted; null before. */
    public CgGraphBuffer lightSlots() {
        return lightSlots;
    }

    /** {@code buffer}, or one of the next size class in its place when it holds fewer than {@code words}. */
    private static CgGraphBuffer fit(CgRecording recording, CgGraphBuffer buffer, String name, int words) {
        long bytes = Math.max(256L, words * 4L);
        if (buffer != null && buffer.size() >= bytes) return buffer;
        if (buffer != null) recording.release(buffer);
        return CgGraphBuffer.persistent(name, CgBufferDesc.of(Long.highestOneBit(bytes - 1) << 1,
                CgBufferUsage.STORAGE, CgBufferUsage.COPY));
    }

    /** {@code n} words of {@code words}, read when the frame executes: the tables are not written again before then. */
    private void put(CgRecording recording, CgGraphBuffer buffer, int[] words, int n) {
        if (n == 0) return;
        recording.update(buffer, 0, words, 0, n);
    }

    private static int sizeClass(int records) {
        return Math.max(MIN_STORAGE, Integer.highestOneBit(Math.max(records, 1) - 1) << 1);
    }

    private CgBufferDesc recordsDesc(int storage) {
        return CgBufferDesc.elements(storage, shape.record().bytes(), CgBufferUsage.STORAGE, CgBufferUsage.COPY);
    }

    /** A parameter row's words in this pool: {@link CgVfxShape#rowVectors()}'s. */
    private int rowWords() {
        return 4 * shape.rowVectors();
    }

    /**
     * Drawn slot {@code slot}'s block of a row from {@code out[at]}: each channel's low end over life, its high end, then
     * how each rolls ({@link CgVfxDrawnChannel.Roll}'s ordinal), a channel the emitter leaves unset 0 throughout.
     */
    private static void writeDrawn(CgVfxGpuEmitter emitter, int slot, float[] out, int at) {
        writeBlock(c -> emitter.drawn(slot, c), out, at);
    }

    /** A drawn slot's or a light's block at word {@code at}: each channel's two curves, then their rolls. */
    private static void writeBlock(IntFunction<CgVfxDrawnChannel> channel, float[] out, int at) {
        int texels = CgVfxGpuEmitter.CURVE_TEXELS, high = at + 4 * texels, rolls = at + 8 * texels;
        for (int c = 0; c < 4; c++) {
            CgVfxDrawnChannel ch = channel.apply(c);
            for (int i = 0; i < texels; i++) {
                float progress = (float) i / (texels - 1);
                out[at + 4 * i + c] = ch == null ? 0f : ch.from().at(progress);
                out[high + 4 * i + c] = ch == null ? 0f : ch.to().at(progress);
            }
            // Its roll, plus 10 on the viewer's clock (Look reads both).
            out[rolls + c] = ch == null ? 0f : ch.roll().ordinal() + (ch.realTime() ? 10f : 0f);
        }
    }

    private static CgBufferDesc drawnDesc(int storage) {
        return CgBufferDesc.elements(storage, CgVfxRecord.DRAWN_BYTES, CgBufferUsage.STORAGE, CgBufferUsage.COPY);
    }

    // What the recording reads, step by step; then takeSteps() clears it.

    float stepDt(int step) {
        return stepBlock[step * 4];
    }

    float stepWind(int step, int axis) {
        return stepBlock[step * 4 + 1 + axis];
    }

    int[] instanceWords() {
        return instances;
    }

    int stepInstanceAt(int step) {
        return stepInstanceAt[step];
    }

    int stepSlots(int step) {
        return stepSlots[step];
    }

    int[] spawnWords() {
        return spawns;
    }

    int stepSpawnAt(int step) {
        return stepSpawnAt[step];
    }

    int stepSpawnRows(int step) {
        return stepSpawnRows[step];
    }

    /** Spawn candidates the step queued, every slot together. */
    int stepSpawned(int step) {
        return stepSpawned[step];
    }

    int[] paramWords() {
        return params;
    }

    int paramRows() {
        return rowCount;
    }

    boolean paramsChanged() {
        return paramsChanged;
    }

    void paramsTaken() {
        paramsChanged = false;
    }

    /** Clears the queued steps once recorded. */
    void takeSteps() {
        if (inStep) throw new IllegalStateException("recording mid-step");
        steps = 0;
        instanceEnd = 0;
        spawnEnd = 0;
        Arrays.fill(written, 0);
    }

    /**
     * Counts of a pool outgrowing what it was sized for: its record storage, its slot and row tables. Preparing sizes them
     * ahead, so a move playing never grows one; a nonzero count in a frame is a hitch named.
     */
    private static final int STORAGE_GROWS = CgTrace.name("vfx.pool.storage-grows"), TABLE_GROWS = CgTrace.name("vfx.pool.table-grows");
    /** What {@link #expect} sized it for: slots, records, and the definitions given rows. */
    private int expectedSlots, expectedCapacity;
    private final Set<CgVfxGpuEmitter> expectedRows = Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Sizes this pool for a slot of {@code emitter} at {@code capacity} that a move will open: its slot table, its row
     * tables and its record storage, so the move's first frame grows none of them. Called as a look is prepared, once
     * per slot it will hold; between steps.
     *
     * <pre>{@code
     * CgVfxParticlePool.of(steps, EMBERS, 0).expect(EMBERS, EMBERS.peakAlive());
     * }</pre>
     */
    public void expect(CgVfxGpuEmitter emitter, int capacity) {
        betweenSteps("expect");
        expectedSlots++;
        expectedCapacity += capacity;
        if (slotEmitter.length < expectedSlots) growSlots(expectedSlots);
        if (expectedRows.add(emitter)) {
            int rows = rowCount + expectedRows.size();
            if (params.length < rows * paramWords) params = Arrays.copyOf(params, rows * paramWords);
            if (curves.length < rows * rowWords()) {
                curves = Arrays.copyOf(curves, rows * rowWords());
                rowSizeMax = Arrays.copyOf(rowSizeMax, rows);
            }
        }
    }

    private int useRow(CgVfxGpuEmitter emitter) {
        int[] row = rows.get(emitter);
        if (row != null) {
            row[1]++;
            return row[0];
        }
        int index = freeRowCount > 0 ? freeRows[--freeRowCount] : rowCount++;
        if (params.length < (index + 1) * paramWords) {
            params = Arrays.copyOf(params, Math.max((index + 1) * paramWords, params.length * 2));
            CgVfxTrace.count(TABLE_GROWS, 1);
        }
        int at = index * paramWords;
        words.target(params, at, CgVfxGpuEmitter.SPAWN_VECTORS, emitter.name());
        emitter.writeSpawn(words);
        words.finish("its spawn numbers");
        int[] moduleWords = emitter.moduleWords();
        int last = shape.modules() - 1;
        int expected = last < 0 ? 0 : (shape.paramAt(last) + shape.paramVectors(last) - CgVfxGpuEmitter.SPAWN_VECTORS) * 4;
        if (moduleWords.length != expected) {
            throw new IllegalStateException(emitter.name() + "'s module words are " + moduleWords.length + " long; its modules'"
                    + " declarations lay out " + expected);
        }
        System.arraycopy(moduleWords, 0, params, at + CgVfxGpuEmitter.SPAWN_VECTORS * 4, expected);
        int rowWords = rowWords();
        if (curves.length < (index + 1) * rowWords) {
            curves = Arrays.copyOf(curves, Math.max((index + 1) * rowWords, curves.length * 2));
            rowSizeMax = Arrays.copyOf(rowSizeMax, curves.length / rowWords);
        }
        if (curveScratch == null) curveScratch = new float[rowWords];
        emitter.writeCurves(curveScratch, 0, CgVfxGpuEmitter.CURVE_TEXELS);
        CgVfxCurveDomain domain = emitter.curveDomain();
        curveScratch[CURVE_WORDS - 4] = domain.min();
        curveScratch[CURVE_WORDS - 3] = domain.max();
        curveScratch[CURVE_WORDS - 2] = 0f;
        curveScratch[CURVE_WORDS - 1] = 0f;
        System.arraycopy(emitter.sizeBySpeed(), 0, curveScratch, CURVE_WORDS, 4);
        int slotAt = CURVE_WORDS + 4;
        for (int slot = 0; slot < CgVfxDrawnChannel.SLOTS; slot++) {
            if ((shape.drawnMask() & 1 << slot) == 0) continue;
            writeDrawn(emitter, slot, curveScratch, slotAt);
            slotAt += 4 * (2 * CgVfxGpuEmitter.CURVE_TEXELS + 1);
        }
        CgVfxMeshRotation turn = emitter.meshRotation();
        if (turn != null) {
            // look.compute's: each vec4's w, the start's and the rate's rolls, inheriting for the start's end.
            orientation(curveScratch, slotAt, turn.from(), CgVfxMeshRotation.rollCode(turn.rolls()));
            orientation(curveScratch, slotAt + 4, turn.to(), turn.inheritParent() ? 1f : 0f);
            orientation(curveScratch, slotAt + 8, turn.rateFrom(), CgVfxMeshRotation.rollCode(turn.rateRolls()));
            orientation(curveScratch, slotAt + 12, turn.rateTo(), turn.steps().isEmpty() ? 0f : 1f);
            if (!turn.steps().isEmpty()) {
                int lives = slotAt + 4 * CgVfxShape.ORIENTATION_VECTORS;
                turn.integrals(curveScratch, lives, curveScratch, lives + 4 * CgVfxGpuEmitter.CURVE_TEXELS, CgVfxGpuEmitter.CURVE_TEXELS);
            }
        }
        if (shape.lights()) writeBlock(emitter::light, curveScratch, shape.lightAt() * 4);
        // look.compute's: each roll a header (each channel's roll, its target plus 3 where curved), then its ends.
        List<CgVfxLifeRoll> rolls = emitter.lifeRolls();
        float rolled = 1f;
        for (int i = 0, l = shape.lifeRollAt() * 4; i < rolls.size(); i++) {
            CgVfxLifeRoll roll = rolls.get(i);
            for (int a = 0; a < 3; a++) curveScratch[l + a] = roll.rolls()[a];
            curveScratch[l + 3] = roll.target().ordinal() + (roll.curved() ? 3 : 0) + (roll.picks() ? 6 : 0);
            if (roll.curved()) {
                for (int s = 0; s < CgVfxGpuEmitter.CURVE_TEXELS; s++) {
                    float t = s / (float) (CgVfxGpuEmitter.CURVE_TEXELS - 1);
                    for (int a = 0; a < 3; a++) {
                        curveScratch[l + 4 + 4 * s + a] = roll.fromCurves()[a].at(t);
                        curveScratch[l + 4 + 4 * (CgVfxGpuEmitter.CURVE_TEXELS + s) + a] = roll.toCurves()[a].at(t);
                    }
                }
            } else {
                for (int a = 0; a < 3; a++) {
                    curveScratch[l + 4 + a] = roll.from()[a];
                    curveScratch[l + 8 + a] = roll.to()[a];
                }
            }
            l += 4 * roll.vectors(CgVfxGpuEmitter.CURVE_TEXELS);
            if (roll.target() == CgVfxLifeRoll.Target.MESH_SCALE) rolled *= roll.most();
        }
        for (int w = 0; w < rowWords; w++) curves[index * rowWords + w] = Float.floatToRawIntBits(curveScratch[w]);
        // The largest a particle draws: its size times its widest axis, at its widest start scale.
        float most = 0f, start = 0f;
        for (int i = 0; i < CgVfxGpuEmitter.CURVE_TEXELS; i++) {
            float axis = Math.max(Math.abs(curveScratch[4 * i]), Math.max(Math.abs(curveScratch[4 * i + 1]), Math.abs(curveScratch[4 * i + 2])));
            most = Math.max(most, curveScratch[4 * i + 3] * axis);
        }
        int starts = 8 * CgVfxGpuEmitter.CURVE_TEXELS + 8;
        for (int w = starts; w < starts + 8; w++) if ((w & 3) != 3) start = Math.max(start, Math.abs(curveScratch[w]));
        rowSizeMax[index] = most * start * rolled;
        if (shape.events() > 0) writeEvents(emitter, at + shape.eventsAt() * 4);
        // Each roll's key, by its element's id and its input's name (core.md § Values): the kernel reads its word.
        int keys = at + shape.keysAt() * 4;
        System.arraycopy(emitter.rollKeys(), 0, params, keys, CgVfxGpuEmitter.ROLL_KEYS);
        for (int f = 0; f < shape.forms(); f++) writeForm(shape.layout(f), at, keys);
        for (int f = 0; f < shape.spawnForms(); f++) {
            CgVfxSpawnForm form = shape.spawnForm(f);
            CgVfxFormLayout layout = shape.spawnLayout(f);
            if (layout.node(layout.root()).top()) params[at + form.target().word()] = Float.floatToRawIntBits(form.firstEnd());
            writeForm(layout, at, keys);
        }
        CgVfxSockets points = emitter.sockets();
        if (points != null) {
            int s = at + shape.socketParamsAt() * 4;
            params[s] = points.names().size();
            params[s + 1] = points.sequential() ? 1 : 0;
            params[s + 2] = points.key();
            params[s + 3] = Float.floatToRawIntBits(points.inherit());
            for (int i = 0; i < points.names().size(); i++) vector(s + 4 * (1 + i), points.offsets()[i]);
        }
        // Its draws read the clip's rows from its first step's upload on.
        if (emitter.clip() != null) CgVfxClipAtlas.of(emitter.clip());
        int[] entry = spareCount > 0 ? spareEntries[--spareCount] : new int[2];
        entry[0] = index;
        entry[1] = 1;
        rows.put(emitter, entry);
        paramsChanged = true;
        return index;
    }

    /** A form's roll key: 0 for one that rolls nothing. */
    private static int rollKey(CgVfxFormInput c) {
        if (c instanceof CgVfxFormInput.Range r) return r.key();
        if (c instanceof CgVfxFormInput.Pick pick) return pick.key();
        if (c instanceof CgVfxFormInput.Curve curve) return curve.key();
        if (c instanceof CgVfxFormInput.Array array) return array.key();
        if (c instanceof CgVfxFormInput.Function f) return f.key();
        return 0;
    }

    /**
     * A form's nodes into the row starting at word {@code row}, each node's roll key into the key area at word
     * {@code keys}.
     */
    private void writeForm(CgVfxFormLayout layout, int row, int keys) {
        for (CgVfxFormLayout.Node n : layout.nodes()) {
            if (n.key() >= 0) params[keys + n.key()] = rollKey(n.form());
            writeNode(n.form(), n.top(), row + n.at() * 4);
        }
    }

    /**
     * A node's vec4s from word {@code at}: a constant; a range's or a pick's ends (the other alone at the top, whose
     * first sits in its row word); a curve's domain, then its texels; an array's length, interpolation and address,
     * then its values.
     */
    private void writeNode(CgVfxFormInput c, boolean top, int at) {
        if (c instanceof CgVfxFormInput.Constant k) {
            vector(at, k.values());
        } else if (c instanceof CgVfxFormInput.Range r) {
            if (!top) vector(at, r.min());
            vector(top ? at : at + 4, r.max());
        } else if (c instanceof CgVfxFormInput.Pick pick) {
            if (!top) vector(at, pick.a());
            vector(top ? at : at + 4, pick.b());
        } else if (c instanceof CgVfxFormInput.Array array) {
            int address = "wrap".equals(array.address()) ? 1 : "mirror".equals(array.address()) ? 2 : 0;
            vector(at, new float[]{array.values().length, array.interpolate() ? 1f : 0f, address, 0f});
            for (int i = 0; i < array.values().length; i++) vector(at + 4 * (1 + i), array.values()[i]);
        } else if (c instanceof CgVfxFormInput.Curve curve) {
            CgVfxCurveBlock block = curve.block();
            params[at] = Float.floatToRawIntBits(block.start(curve.slot()));
            params[at + 1] = Float.floatToRawIntBits(block.span(curve.slot()));
            params[at + 2] = params[at + 3] = 0;
            float[] texels = block.texels();
            int from = curve.slot() * CgVfxCurveBlock.TEXELS * 4;
            for (int w = 0; w < CgVfxCurveBlock.TEXELS * 4; w++) params[at + 4 + w] = Float.floatToRawIntBits(texels[from + w]);
        }
    }

    private static void orientation(float[] row, int at, float[] rotator, float w) {
        System.arraycopy(rotator, 0, row, at, 3);
        row[at + 3] = w;
    }

    /** {@code v}'s numbers into the vec4 at word {@code at}, zero past them. */
    private void vector(int at, float[] v) {
        for (int k = 0; k < 4; k++) params[at + k] = Float.floatToRawIntBits(k < v.length ? v[k] : 0f);
    }

    /** {@code emitter}'s event vec4s from word {@code at}, laid out as {@link CgVfxShape#EVENT_VECTORS} says. */
    private void writeEvents(CgVfxGpuEmitter emitter, int at) {
        List<CgVfxEvent> events = emitter.events();
        CgVfxNodeClip clip = emitter.clip();
        CgVfxClipAtlas.Section section = clip == null ? null : CgVfxClipAtlas.of(clip);
        int m = CgVfxEvent.MAX_EVENTS;
        for (int e = 0; e < m; e++) {
            CgVfxEvent event = e < events.size() ? events.get(e) : null;
            params[at + CgVfxShape.EVENT_AGES * 4 + e] = Float.floatToRawIntBits(event == null ? 0f : event.age());
            params[at + CgVfxShape.EVENT_FIRINGS * 4 + e] = Float.floatToRawIntBits(event == null ? 0f : event.firings());
            params[at + CgVfxShape.EVENT_FROM * 4 + e] = Float.floatToRawIntBits(event == null ? 0f : event.from());
            params[at + CgVfxShape.EVENT_UNTIL * 4 + e] = Float.floatToRawIntBits(event == null ? 0f : event.until());
            int word = 0;
            if (event != null && event.node() != CgVfxEvent.NO_NODE) {
                int base = section.base(event.node());
                boolean steps = (section.stepMask(event.node()) & 0b111) != 0;
                word = base | (steps ? 1 << 31 : 0);
            }
            params[at + CgVfxShape.EVENT_NODES * 4 + e] = word;
        }
        int c = at + CgVfxShape.EVENT_CLIP * 4;
        params[c] = Float.floatToRawIntBits(section == null ? 0f : section.rowLength());
        params[c + 1] = Float.floatToRawIntBits(clip == null ? 0f : CgVfxNodeClip.FRAMES_PER_SECOND * emitter.playRate());
        params[c + 2] = Float.floatToRawIntBits(clip == null ? 0f : clip.frames());
        params[c + 3] = 0;
    }

    private void growSlots(int n) {
        if (n <= slotEmitter.length) return;
        CgVfxTrace.count(TABLE_GROWS, 1);
        int size = Math.max(n, Math.max(4, slotEmitter.length * 2));
        slotEmitter = Arrays.copyOf(slotEmitter, size);
        slotOwner = Arrays.copyOf(slotOwner, size);
        slotCapacity = Arrays.copyOf(slotCapacity, size);
        slotRow = Arrays.copyOf(slotRow, size);
        slotBase = Arrays.copyOf(slotBase, size);
        written = Arrays.copyOf(written, size);
        slotOrigin = Arrays.copyOf(slotOrigin, size * 3);
        int was = slotTurn.length / 4;
        slotTurn = Arrays.copyOf(slotTurn, size * 4);
        for (int s = was; s < size; s++) slotTurn[s * 4 + 3] = 1f;
        slotScale = Arrays.copyOf(slotScale, size);
        slotBindScale = Arrays.copyOf(slotBindScale, size);
        slotOpened = Arrays.copyOf(slotOpened, size);
        slotGround = Arrays.copyOf(slotGround, size);
        socketPrev = Arrays.copyOf(socketPrev, size * CgVfxSockets.MAX * 4);
        slotSourceReach = Arrays.copyOf(slotSourceReach, size);
        slotGrid = Arrays.copyOf(slotGrid, size);
        ringFirst = Arrays.copyOf(ringFirst, size);
        ringPoints = Arrays.copyOf(ringPoints, size);
        feedPool = Arrays.copyOf(feedPool, size);
        readPool = Arrays.copyOf(readPool, size);
        readSlot = Arrays.copyOf(readSlot, size);
        readSource = Arrays.copyOf(readSource, size);
        feedParent = Arrays.copyOf(feedParent, size);
        feedSlot = Arrays.copyOf(feedSlot, size);
        feedEvent = Arrays.copyOf(feedEvent, size);
        slotTexture = Arrays.copyOf(slotTexture, size * shape.modules());
    }

    private CgVfxGpuEmitter openSlot(int slot, String call) {
        CgVfxGpuEmitter emitter = slot >= 0 && slot < slotCount ? slotEmitter[slot] : null;
        if (emitter == null) throw new IllegalArgumentException(call + " on slot " + slot + ", which is not open");
        return emitter;
    }

    private void betweenSteps(String call) {
        if (inStep) throw new IllegalStateException(call + " between beginStep and endStep");
    }

    private void inStep(String call) {
        if (!inStep) throw new IllegalStateException(call + " outside a step");
    }
}
