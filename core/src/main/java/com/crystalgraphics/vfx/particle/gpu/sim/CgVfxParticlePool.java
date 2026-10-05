package com.crystalgraphics.vfx.particle.gpu.sim;

import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWords;

import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Every particle of every playing emitter instance whose definition has one {@link CgVfxShape}: one simulation on the
 * GPU, stepped by one dispatch per step however many effects play it (vfx-gpu §4.4, §13). An instance owns a slot
 * while it plays; its definition's numbers are a row of the pool's parameter table, shared by every instance of it.
 * The CPU decides every step what spawns and where each instance is, and queues it here; the pool records the queued
 * steps once a frame.
 *
 * <pre>{@code
 * CgVfxParticlePool pool = CgVfxParticlePool.of(EMBERS);
 * int slot = pool.open(EMBERS, 400);                 // at play: the most this instance can have alive
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

    /** Every pool, by shape key. Render thread. */
    private static final Map<String, CgVfxParticlePool> POOLS = new HashMap<>();

    private final CgVfxShape shape;
    private final CgVfxWords words = new CgVfxWords();
    private final int paramWords, instanceWords;

    /** Each definition playing here: its row, and how many open slots use it. */
    private final IdentityHashMap<CgVfxGpuEmitter, int[]> rows = new IdentityHashMap<>();
    private int[] params = new int[0];
    private int rowCount;
    private int[] freeRows = new int[0];
    private int freeRowCount;
    private boolean paramsChanged;

    private CgVfxGpuEmitter[] slotEmitter = new CgVfxGpuEmitter[0];
    private int[] slotCapacity = new int[0], slotRow = new int[0], slotBase = new int[0];
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

    private CgVfxParticlePool(CgVfxShape shape) {
        this.shape = shape;
        this.paramWords = shape.paramRowVectors() * 4;
        this.instanceWords = shape.instanceRowVectors() * 4;
    }

    /** The pool every definition of {@code emitter}'s shape shares, made the first time. Render thread. */
    public static CgVfxParticlePool of(CgVfxGpuEmitter emitter) {
        CgVfxShape shape = CgVfxShape.of(emitter);
        CgVfxParticlePool pool = POOLS.get(shape.key());
        if (pool == null) POOLS.put(shape.key(), pool = new CgVfxParticlePool(shape));
        return pool;
    }

    /** Forgets every pool. Tests, and context teardown once pools hold GPU storage. */
    static void forgetAll() {
        POOLS.clear();
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
