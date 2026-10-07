package com.crystalgraphics.vfx.particle.gpu.sim;

import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.compute.ops.CgGpuCount;
import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWords;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

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
 * // each particle step, after the emitters' ticks, for every pool
 * pool.beginStep(dt, wind.x, wind.y, wind.z);
 * pool.instance(slot, seedBits, share, groundY, instance);   // every open slot, once
 * pool.spawn(slot, firstSpawn, candidates);                  // only a slot that spawns this step
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
 */
public final class CgVfxParticlePool {

    /**
     * An instance row's own vec4s ahead of its modules' lanes: {@code uvec4(param row, seed bits, slot, 0)},
     * {@code vec4(source xyz, time)}, {@code vec4(share, ground height or NaN, 0, 0)}.
     */
    public static final int INSTANCE_HEADER = 3;

    /** A curve row's words: {@link CgVfxGpuEmitter#CURVE_TEXELS} pairs of size and opacity multipliers. */
    public static final int CURVE_WORDS = 2 * CgVfxGpuEmitter.CURVE_TEXELS;

    /** Where the pools record their steps on {@link CgRenderStage#WORLD_OPAQUE}: ahead of the world renderer and Range. */
    public static final int ORDER = CgWorldRenderer.ORDER - 100;

    /** Every pool, by owner and shape key, and in the order made; and those released since the last recording. */
    private static final Map<Object, Map<String, CgVfxParticlePool>> POOLS = new IdentityHashMap<>();
    private static final List<CgVfxParticlePool> ALL = new ArrayList<>(), RELEASED = new ArrayList<>();
    private static CgRenderStage.Registration recording;

    private static final int GPU_STEP = CgGpuTrace.name("vfx.pool.step");
    private static final CgGpuCount ONE = CgGpuCount.of(1);
    private static final int MIN_STORAGE = 1024;

    private final CgVfxShape shape;
    private final CgVfxWords words = new CgVfxWords();
    private final int paramWords, instanceWords;
    private final String passName, recordsName, paramsName, curvesName, instancesName, spawnsName;
    private final String[] countNames;

    /** Each definition playing here: its row, and how many open slots use it. */
    private final IdentityHashMap<CgVfxGpuEmitter, int[]> rows = new IdentityHashMap<>();
    private int[] params = new int[0];
    private int rowCount;
    private int[] freeRows = new int[0];
    private int freeRowCount;
    private boolean paramsChanged;
    /** Per row: its curve row's words ({@link #CURVE_WORDS}), and the largest size multiplier on it. */
    private int[] curves = new int[0];
    private float[] rowSizeMax = new float[0];
    private final float[] curveScratch = new float[CURVE_WORDS];

    private CgVfxGpuEmitter[] slotEmitter = new CgVfxGpuEmitter[0];
    private int[] slotCapacity = new int[0], slotRow = new int[0], slotBase = new int[0];
    /** Per slot: its origin as its last step gave it (3), and how far past its particles' size a look reaches. */
    private double[] slotOrigin = new double[0];
    private float[] slotScale = new float[0];
    private int slotCount, openSlots, capacity;
    private boolean basesStale;

    /** The steps queued since the last {@link #takeSteps()}: four floats each (dt, wind). */
    private int steps;
    private boolean inStep;
    private float[] stepBlock = new float[0];
    /** Per step: where its instance rows start in {@link #instances}, how many slots they cover, and its spawn rows. */
    private int[] stepInstanceAt = new int[0], stepSlots = new int[0], stepSpawnAt = new int[0], stepSpawnRows = new int[0],
            stepSpawned = new int[0];
    private int[] instances = new int[0];
    private int instanceEnd;
    /** Which step last wrote each slot's row, as 1 + its index. */
    private int[] written = new int[0];
    /** Four words a spawn row: slot, first spawn index, candidates, first element (after the step's live particles). */
    private int[] spawns = new int[0];
    private int spawnEnd;

    // The GPU half: made at the first recording, grown as slots open.
    private CgKernel step;
    private CgGraphBuffer records, paramBuffer, curveBuffer, instanceBuffer, spawnBuffer;
    /** Ping-ponged append counts: {@code counts[current]} is how many records the newest version holds. */
    private final CgGraphBuffer[] counts = new CgGraphBuffer[2];
    private int current, storage;
    private ByteBuffer staging = ByteBuffer.allocate(0);
    private boolean released;

    private CgVfxParticlePool(CgVfxShape shape) {
        this.shape = shape;
        this.paramWords = shape.paramRowVectors() * 4;
        this.instanceWords = shape.instanceRowVectors() * 4;
        String name = "vfx.pool[" + shape.key() + "]";
        passName = name + ".step";
        recordsName = name + ".records";
        paramsName = name + ".params";
        curvesName = name + ".curves";
        instancesName = name + ".instances";
        spawnsName = name + ".spawns";
        countNames = new String[]{name + ".live0", name + ".live1"};
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
        CgVfxShape shape = CgVfxShape.of(emitter);
        Map<String, CgVfxParticlePool> owned = POOLS.get(owner);
        if (owned == null) POOLS.put(owner, owned = new HashMap<>());
        CgVfxParticlePool pool = owned.get(shape.key());
        if (pool == null) {
            owned.put(shape.key(), pool = new CgVfxParticlePool(shape));
            ALL.add(pool);
            if (recording == null) {
                recording = CgRenderStage.WORLD_OPAQUE.registerOncePerFrame(ORDER, frame -> recordAll(frame.recording()));
            }
        }
        return pool;
    }

    /**
     * Starts the programs a pool of {@code emitter}'s shape steps with, ahead of its first step: below compute its Step
     * kernel is several lowered programs, otherwise built on the frame the first burst plays. Render thread.
     *
     * <pre>{@code
     * CgVfxParticlePool.prepare(EMBERS);   // as a look is made, or on a loading screen
     * CgVfxRange.prepare();                // and what draws it
     * }</pre>
     */
    public static void prepare(CgVfxGpuEmitter emitter) {
        CgVfxEmitterCompiler.compile(CgVfxShape.of(emitter)).kernel("Step").prepare();
        CgGpuOps.prepareFill();
    }

    /** Drops {@code owner}'s pools; their GPU storage is released at the next recording. Render thread. */
    public static void release(Object owner) {
        Map<String, CgVfxParticlePool> owned = POOLS.remove(owner);
        if (owned == null) return;
        for (CgVfxParticlePool pool : owned.values()) {
            ALL.remove(pool);
            pool.released = true;
            RELEASED.add(pool);
        }
    }

    /** Forgets every pool and stops recording. Tests, and context teardown. */
    static void forgetAll() {
        POOLS.clear();
        ALL.clear();
        RELEASED.clear();
        if (recording != null) {
            recording.close();
            recording = null;
        }
    }

    /** Records every pool's queued steps into {@code recording}, once a host frame. Render thread. */
    static void recordAll(CgRecording recording) {
        for (int i = 0; i < RELEASED.size(); i++) RELEASED.get(i).releaseStorage(recording);
        RELEASED.clear();
        for (int i = 0; i < ALL.size(); i++) ALL.get(i).record(recording);
    }

    /** Whether {@link #release} dropped it: what holds it lets it go. */
    public boolean isReleased() {
        return released;
    }

    private void releaseStorage(CgRecording recording) {
        CgGraphBuffer[] owned = {records, counts[0], counts[1], paramBuffer, curveBuffer, instanceBuffer, spawnBuffer};
        for (CgGraphBuffer buffer : owned) if (buffer != null) recording.release(buffer);
        records = paramBuffer = curveBuffer = instanceBuffer = spawnBuffer = counts[0] = counts[1] = null;
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
        betweenSteps("open");
        if (capacity < 0) throw new IllegalArgumentException("capacity " + capacity);
        String key = CgVfxShape.of(emitter).key();
        if (!key.equals(shape.key())) {
            throw new IllegalArgumentException(emitter.name() + " has shape " + key + ", not this pool's " + shape.key());
        }
        int slot = 0;
        while (slot < slotCount && slotEmitter[slot] != null) slot++;
        if (slot == slotCount) growSlots(slotCount + 1);
        slotEmitter[slot] = emitter;
        slotCapacity[slot] = capacity;
        slotRow[slot] = useRow(emitter);
        slotScale[slot] = 1f;
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
        int[] row = rows.get(emitter);
        if (--row[1] == 0) {
            rows.remove(emitter);
            if (freeRows.length == freeRowCount) freeRows = Arrays.copyOf(freeRows, Math.max(4, freeRowCount * 2));
            freeRows[freeRowCount++] = row[0];
        }
        capacity -= slotCapacity[slot];
        slotEmitter[slot] = null;
        slotCapacity[slot] = 0;
        openSlots--;
        while (slotCount > 0 && slotEmitter[slotCount - 1] == null) slotCount--;
        basesStale = true;
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
        }
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
        inStep("instance");
        CgVfxGpuEmitter emitter = openSlot(slot, "instance");
        int s = steps;
        if (written[slot] == s + 1) throw new IllegalStateException("slot " + slot + " written twice in one step");
        written[slot] = s + 1;
        slotOrigin[slot * 3] = view.originX();
        slotOrigin[slot * 3 + 1] = view.originY();
        slotOrigin[slot * 3 + 2] = view.originZ();
        int at = stepInstanceAt[s] + slot * instanceWords;
        String who = emitter.name();
        words.target(instances, at, INSTANCE_HEADER, who);
        words.uvec4(slotRow[slot], seedBits, slot, 0)
                .vec4(view.sourceX(), view.sourceY(), view.sourceZ(), view.time())
                .vec4(share, groundY, 0f, 0f);
        words.finish("its instance row's header");
        for (int i = 0; i < shape.modules(); i++) {
            int lanes = shape.laneCount(i);
            if (lanes == 0) continue;
            words.target(instances, at + shape.lanesAt(i) * 4, lanes, who + "'s fx_" + shape.kind(i));
            emitter.modules().get(i).writeInstance(view, words);
            words.finish("its instance lanes");
        }
    }

    /** Queues {@code candidates} spawns for {@code slot} this step, spawn indices from {@code firstSpawn}. */
    public void spawn(int slot, int firstSpawn, int candidates) {
        inStep("spawn");
        openSlot(slot, "spawn");
        if (firstSpawn < 0 || candidates < 0) throw new IllegalArgumentException("spawn " + firstSpawn + " + " + candidates);
        if (candidates == 0) return;
        int s = steps;
        if (spawns.length < spawnEnd + 4) spawns = Arrays.copyOf(spawns, Math.max(spawnEnd + 4, spawns.length * 2));
        spawns[spawnEnd] = slot;
        spawns[spawnEnd + 1] = firstSpawn;
        spawns[spawnEnd + 2] = candidates;
        spawns[spawnEnd + 3] = stepSpawned[s];
        spawnEnd += 4;
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
        steps++;
    }

    /** Steps queued and not yet taken. */
    public int queuedSteps() {
        return steps;
    }

    /**
     * Every particle, a {@link CgVfxRecord} each: a history buffer whose newest version holds {@link #live()}'s count
     * of them. Null before the first step is recorded; a new handle after growth, so ask each frame.
     */
    public CgGraphBuffer records() {
        return records;
    }

    /** How many records the newest version of {@link #records()} holds: a {@code uint} at word 0. */
    public CgGraphBuffer live() {
        return counts[current];
    }

    /** The records {@link #records()} has room for. */
    public int storage() {
        return storage;
    }

    /** Each parameter row's curves, {@link #CURVE_WORDS} floats a row; null before the first step is recorded. */
    public CgGraphBuffer curves() {
        return curveBuffer;
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

    /**
     * How far past their size a look draws {@code slot}'s particles: a streak's stretch along its motion, a halo. 1
     * until set.
     */
    public void cullScale(int slot, float scale) {
        openSlot(slot, "cullScale");
        slotScale[slot] = scale;
    }

    /** What a cull takes as the radius of each of {@code slot}'s particles, times its size at birth. */
    public float cullRadius(int slot) {
        openSlot(slot, "cullRadius");
        return rowSizeMax[slotRow[slot]] * slotScale[slot];
    }

    /**
     * Replaces the pool's particles with the first {@code count} records of {@code data}, in {@link CgVfxRecord}'s
     * layout and native order, ahead of the steps recorded after it: a check stepping the CPU's particles on the GPU.
     * Render thread, between steps.
     *
     * <pre>{@code
     * for (int i = 0; i < n; i++) CgVfxRecord.pack(packed, i, cpu.particles(), i, slot, paramRow);
     * pool.seed(recording, packed, n);
     * pool.beginStep(dt, windX, windY, windZ);    // the step the CPU took, its instance rows and spawns
     * ...
     * pool.endStep();
     * pool.record(recording);
     * recording.readback(pool.records(), 0, (long) pool.storage() * CgVfxRecord.BYTES, data -> compare(data));
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
        if (count < 0 || data.capacity() < (long) count * CgVfxRecord.BYTES) {
            throw new IllegalArgumentException(count + " records in " + data.capacity() + " bytes");
        }
        reserve(recording, count);
        if (count > 0) {
            ByteBuffer bytes = data.duplicate();
            bytes.position(0);
            bytes.limit(count * CgVfxRecord.BYTES);
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
        betweenSteps("record");
        if (steps == 0) return;
        if (openSlots == 0) {
            // A slot closes only once its particles have died, so there is nothing to step.
            takeSteps();
            return;
        }
        if (step == null) step = CgVfxEmitterCompiler.compile(shape).kernel("Step");
        reserve(recording, capacity);
        upload(recording);
        CgComputePass pass = recording.compute(passName).timed(GPU_STEP).async();
        for (int s = 0; s < steps; s++) {
            CgGraphBuffer next = counts[1 - current];
            CgGpuOps.fill(pass, next, 0, ONE);
            pass.dispatch(step, storage + stepSpawned[s])
                    .bind("IN", records).bind("OUT", records).counter("OUT", next, 0).bind("LIVE", counts[current])
                    .bind("PARAMS", paramBuffer).bind("INSTANCES", instanceBuffer).bind("SPAWNS", spawnBuffer)
                    .set("_Step", stepBlock[s * 4], stepBlock[s * 4 + 1], stepBlock[s * 4 + 2], stepBlock[s * 4 + 3])
                    .set("_InstanceAt", stepInstanceAt[s] / 4).set("_SpawnAt", stepSpawnAt[s] / 4)
                    .set("_SpawnRows", stepSpawnRows[s]).set("_Spawned", stepSpawned[s]);
            current = 1 - current;
        }
        pass.end();
        takeSteps();
    }

    /** Makes the records and counts the first time, and grows the records to hold {@code need}. */
    private void reserve(CgRecording recording, int need) {
        if (records == null) {
            storage = sizeClass(need);
            records = CgGraphBuffer.history(recordsName, recordsDesc(storage));
            for (int c = 0; c < 2; c++) {
                counts[c] = CgGraphBuffer.persistent(countNames[c],
                        CgBufferDesc.of(16, CgBufferUsage.STORAGE, CgBufferUsage.COPY, CgBufferUsage.INDIRECT));
                recording.fill(counts[c], 0);
            }
        } else if (need > storage) {
            storage = sizeClass(need);
            records = recording.resize(records, recordsDesc(storage));
        }
    }

    /** This frame's instance and spawn rows, and the parameter table when a row was written since the last. */
    private void upload(CgRecording recording) {
        int paramEnd = rowCount * paramWords, curveEnd = rowCount * CURVE_WORDS;
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

    /** {@code buffer}, or one of the next size class in its place when it holds fewer than {@code words}. */
    private static CgGraphBuffer fit(CgRecording recording, CgGraphBuffer buffer, String name, int words) {
        long bytes = Math.max(256L, words * 4L);
        if (buffer != null && buffer.size() >= bytes) return buffer;
        if (buffer != null) recording.release(buffer);
        return CgGraphBuffer.persistent(name, CgBufferDesc.of(Long.highestOneBit(bytes - 1) << 1,
                CgBufferUsage.STORAGE, CgBufferUsage.COPY));
    }

    private void put(CgRecording recording, CgGraphBuffer buffer, int[] words, int n) {
        if (n == 0) return;
        if (staging.capacity() < n * 4) staging = ByteBuffer.allocate(Math.max(n * 4, staging.capacity() * 2)).order(ByteOrder.nativeOrder());
        staging.clear();
        for (int i = 0; i < n; i++) staging.putInt(i * 4, words[i]);
        staging.limit(n * 4);
        recording.update(buffer, 0, staging);
    }

    private static int sizeClass(int records) {
        return Math.max(MIN_STORAGE, Integer.highestOneBit(Math.max(records, 1) - 1) << 1);
    }

    private static CgBufferDesc recordsDesc(int storage) {
        return CgBufferDesc.elements(storage, CgVfxRecord.BYTES, CgBufferUsage.STORAGE, CgBufferUsage.COPY);
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

    private int useRow(CgVfxGpuEmitter emitter) {
        int[] row = rows.get(emitter);
        if (row != null) {
            row[1]++;
            return row[0];
        }
        int index = freeRowCount > 0 ? freeRows[--freeRowCount] : rowCount++;
        if (params.length < (index + 1) * paramWords) params = Arrays.copyOf(params, Math.max((index + 1) * paramWords, params.length * 2));
        int at = index * paramWords;
        words.target(params, at, CgVfxGpuEmitter.SPAWN_VECTORS, emitter.name());
        emitter.writeSpawn(words);
        words.finish("its spawn numbers");
        for (int i = 0; i < shape.modules(); i++) {
            int vectors = shape.paramVectors(i);
            words.target(params, at + shape.paramAt(i) * 4, vectors, emitter.name() + "'s fx_" + shape.kind(i));
            emitter.modules().get(i).writeParams(words);
            words.finish("its numbers");
        }
        if (curves.length < (index + 1) * CURVE_WORDS) {
            curves = Arrays.copyOf(curves, Math.max((index + 1) * CURVE_WORDS, curves.length * 2));
            rowSizeMax = Arrays.copyOf(rowSizeMax, curves.length / CURVE_WORDS);
        }
        emitter.writeCurves(curveScratch, 0, CgVfxGpuEmitter.CURVE_TEXELS);
        float most = 0f;
        for (int w = 0; w < CURVE_WORDS; w++) {
            curves[index * CURVE_WORDS + w] = Float.floatToRawIntBits(curveScratch[w]);
            if ((w & 1) == 0) most = Math.max(most, curveScratch[w]);
        }
        rowSizeMax[index] = most;
        rows.put(emitter, new int[]{index, 1});
        paramsChanged = true;
        return index;
    }

    private void growSlots(int n) {
        int size = Math.max(n, Math.max(4, slotEmitter.length * 2));
        if (size <= slotEmitter.length) return;
        slotEmitter = Arrays.copyOf(slotEmitter, size);
        slotCapacity = Arrays.copyOf(slotCapacity, size);
        slotRow = Arrays.copyOf(slotRow, size);
        slotBase = Arrays.copyOf(slotBase, size);
        written = Arrays.copyOf(written, size);
        slotOrigin = Arrays.copyOf(slotOrigin, size * 3);
        slotScale = Arrays.copyOf(slotScale, size);
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
