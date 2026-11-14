package com.crystalgraphics.render.draw;

import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.render.shader.CgBindingModel;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * One raster pass's mesh draw commands, in the order they draw: Unreal's visible {@code FMeshDrawCommand}s of a pass,
 * as flat arrays. A command names its pipeline, its material's bindings, its mesh and range, and the objects it draws:
 * GPU scene records or this recording's frame records by id, ids a kernel wrote, or records of its own; drawn once per
 * object, or as an indirect count says. A raster pass takes the list whole ({@code CgRasterPass.add(commands)}); the
 * frame builder packs it straight into the encoder's batches, merging neighbours that differ only by their objects into
 * one instanced draw (Unreal's dynamic instancing).
 *
 * <pre>{@code
 * CgDrawCommands commands = new CgDrawCommands(recording.primitives());
 * commands.add(pipeline, bindings, rock).sceneRecord(rockId).sortKey(key);   // a GPU scene record
 * commands.add(pipeline, bindings, grass).ids(culled, first, capacity)        // ids a cull kernel kept
 *         .indirect(counts, word * 4L, CgIndirect.INSTANCES, 1);
 * commands.add(pipeline, bindings, spark).frameRecord(slot);                 // a record of the recording's table
 * commands.sort();
 * pass.add(commands);
 * }</pre>
 *
 * <ul>
 *   <li>Each setter applies to the command {@link #add} began last.</li>
 *   <li>Frame records are slots of the table it was made with, good until that table resets: its recording's.</li>
 *   <li>Reused: {@link #clear} keeps the arrays, so a warm frame allocates nothing. A pass reads the list when its
 *       recording is built; leave it alone until then.</li>
 * </ul>
 */
public final class CgDrawCommands {

    /** How a command's objects are named. */
    public static final byte NONE = 0, SCENE = 1, FRAME = 2, GPU_IDS = 3, RECORDS = 4;

    @Nullable
    private final CgPrimitiveTable table;
    private int size;
    private int[] pipelines = new int[32];
    private CgMaterialBindings[] bindings = new CgMaterialBindings[32];
    private CgMesh[] meshes = new CgMesh[32];
    /** Per command: submesh (-1 whole), first, count (-1 to the end). */
    private int[] ranges = new int[32 * 3];
    private byte[] objectKinds = new byte[32];
    /** Per command: where its ids start in {@link #ids} and how many, or a GPU buffer's first element and capacity. */
    private int[] objectFirst = new int[32], objectCount = new int[32];
    private CgBufferHandle[] objectBuffers = new CgBufferHandle[32];
    private CgBufferHandle[] counts = new CgBufferHandle[32];
    private long[] countOffsets = new long[32];
    private CgIndirect[] countModes = new CgIndirect[32];
    private int[] countFactors = new int[32];
    private CgBufferHandle[] buffers = new CgBufferHandle[32 * CgDrawChunk.BUFFERS];
    private CgBindingModel.Slot[] bufferAt = new CgBindingModel.Slot[32 * CgDrawChunk.BUFFERS];
    private long[] keys = new long[32];
    private int[] groups = new int[32];
    private int[] ids = new int[64];
    private int idCount;
    /** The drawing order {@link #sort} leaves, and its merge's scratch. */
    private int[] order = new int[32], scratch = new int[32];

    /** Commands naming frame records of {@code table} (the recording's {@code primitives()}), or none for null. */
    public CgDrawCommands(@Nullable CgPrimitiveTable table) {
        this.table = table;
    }

    /** Begins a command drawing {@code mesh} whole through {@code pipeline} with {@code binding}. */
    public CgDrawCommands add(CgPipeline pipeline, CgMaterialBindings binding, CgMesh mesh) {
        if (size == pipelines.length) grow();
        int c = size++;
        pipelines[c] = pipeline.id();
        bindings[c] = binding;
        meshes[c] = mesh;
        ranges[c * 3] = -1;
        ranges[c * 3 + 1] = 0;
        ranges[c * 3 + 2] = -1;
        objectKinds[c] = NONE;
        objectFirst[c] = 0;
        objectCount[c] = 0;
        objectBuffers[c] = null;
        counts[c] = null;
        countModes[c] = null;
        groups[c] = -1;
        keys[c] = 0L;
        Arrays.fill(buffers, c * CgDrawChunk.BUFFERS, (c + 1) * CgDrawChunk.BUFFERS, null);
        Arrays.fill(bufferAt, c * CgDrawChunk.BUFFERS, (c + 1) * CgDrawChunk.BUFFERS, null);
        order[c] = c;
        return this;
    }

    /** Its range of the mesh: a submesh (-1 for all), its first index or vertex and how many (-1 to the end). */
    public CgDrawCommands range(int submesh, int first, int count) {
        int c = (size - 1) * 3;
        ranges[c] = submesh;
        ranges[c + 1] = first;
        ranges[c + 2] = count;
        return this;
    }

    /** Draws GPU scene record {@code id} ({@code CgGpuScene.id}, its scene bit set); call again for more. */
    public CgDrawCommands sceneRecord(int id) {
        return id(SCENE, id);
    }

    /** Draws the record at {@code slot} of its table; call again for more. */
    public CgDrawCommands frameRecord(int slot) {
        if (table == null) throw new IllegalStateException("these commands were made with no table of frame records");
        return id(FRAME, slot);
    }

    private CgDrawCommands id(byte kind, int id) {
        int c = size - 1;
        if (objectKinds[c] == NONE) {
            objectKinds[c] = kind;
            objectFirst[c] = idCount;
        } else if (objectKinds[c] != kind || objectFirst[c] + objectCount[c] != idCount) {
            throw new IllegalStateException("a command draws objects of one kind, named in one run");
        }
        if (idCount == ids.length) ids = Arrays.copyOf(ids, idCount * 2);
        ids[idCount++] = id;
        objectCount[c]++;
        return this;
    }

    /**
     * Draws the objects a kernel named in {@code buffer}: {@code cg_ObjectIds} entries (record, 0) from element
     * {@code first}, at most {@code capacity}; how many is the {@link #indirect} count it wrote.
     */
    public CgDrawCommands ids(CgBufferHandle buffer, int first, int capacity) {
        return objects(GPU_IDS, buffer, first, capacity);
    }

    /** Draws {@code n} records of its own from {@code buffer} (48-float {@code cg_Objects}), from record {@code first}. */
    public CgDrawCommands records(CgBufferHandle buffer, int first, int n) {
        return objects(RECORDS, buffer, first, n);
    }

    private CgDrawCommands objects(byte kind, CgBufferHandle buffer, int first, int n) {
        int c = size - 1;
        if (objectKinds[c] != NONE) throw new IllegalStateException("a command draws objects of one kind");
        objectKinds[c] = kind;
        objectBuffers[c] = buffer;
        objectFirst[c] = first;
        objectCount[c] = n;
        return this;
    }

    /** How much it draws: the {@code uint} at {@code offset} of {@code buffer} times {@code factor}, read as {@code mode}. */
    public CgDrawCommands indirect(CgBufferHandle buffer, long offset, CgIndirect mode, int factor) {
        int c = size - 1;
        counts[c] = buffer;
        countOffsets[c] = offset;
        countModes[c] = mode;
        countFactors[c] = factor;
        return this;
    }

    /** Binds {@code buffer} at pass slot {@code at} for this command: a skinned mesh's palettes, a kernel's output. */
    public CgDrawCommands buffer(CgBindingModel.Slot at, CgBufferHandle buffer) {
        int base = (size - 1) * CgDrawChunk.BUFFERS;
        for (int s = base; s < base + CgDrawChunk.BUFFERS; s++) {
            if (bufferAt[s] == null || bufferAt[s] == at) {
                bufferAt[s] = at;
                buffers[s] = buffer;
                return this;
            }
        }
        throw new IllegalStateException("a command binds at most " + CgDrawChunk.BUFFERS + " buffers of its own");
    }

    /** Its place in {@link #sort}: a {@code CgSortKey}. */
    public CgDrawCommands sortKey(long key) {
        keys[size - 1] = key;
        return this;
    }

    /** The GPU group its time is charged to, a {@code CgGpuTrace} label; -1 for its material's. */
    public CgDrawCommands gpuGroup(int label) {
        groups[size - 1] = label;
        return this;
    }

    /** Orders the commands by their keys, unsigned, ties in the order added: a stable merge sort of indices. */
    public void sort() {
        if (scratch.length < size) scratch = new int[order.length];
        for (int c = 0; c < size; c++) order[c] = c;
        for (int width = 1; width < size; width *= 2) {
            for (int lo = 0; lo < size - width; lo += width * 2) {
                int mid = lo + width, hi = Math.min(lo + width * 2, size), a = lo, b = mid, o = lo;
                while (a < mid && b < hi) scratch[o++] = Long.compareUnsigned(keys[order[b]], keys[order[a]]) < 0 ? order[b++] : order[a++];
                while (a < mid) scratch[o++] = order[a++];
                while (b < hi) scratch[o++] = order[b++];
                System.arraycopy(scratch, lo, order, lo, hi - lo);
            }
        }
    }

    /**
     * Appends every command of {@code other}, made for the same table, as it was: what merges two pass types' commands
     * into one list drawn in key order (a glow pass's surfaces' covers among its glows). Sort after.
     *
     * <pre>{@code
     * merged.clear();
     * merged.append(passes.commands(EMISSIVE, rec, glows));
     * merged.append(passes.commands(TRANSLUCENT, rec, covers));
     * merged.sort();
     * }</pre>
     */
    public void append(CgDrawCommands other) {
        if (other.table != table && other.table != null) throw new IllegalArgumentException("commands of another table");
        for (int k = 0; k < other.size; k++) {
            if (size == pipelines.length) grow();
            int c = size++;
            pipelines[c] = other.pipelines[k];
            bindings[c] = other.bindings[k];
            meshes[c] = other.meshes[k];
            System.arraycopy(other.ranges, k * 3, ranges, c * 3, 3);
            byte kind = other.objectKinds[k];
            objectKinds[c] = kind;
            objectBuffers[c] = other.objectBuffers[k];
            objectCount[c] = other.objectCount[k];
            if (kind == SCENE || kind == FRAME) {
                int n = other.objectCount[k];
                if (idCount + n > ids.length) ids = Arrays.copyOf(ids, Math.max(idCount + n, ids.length * 2));
                System.arraycopy(other.ids, other.objectFirst[k], ids, idCount, n);
                objectFirst[c] = idCount;
                idCount += n;
            } else {
                objectFirst[c] = other.objectFirst[k];
            }
            counts[c] = other.counts[k];
            countOffsets[c] = other.countOffsets[k];
            countModes[c] = other.countModes[k];
            countFactors[c] = other.countFactors[k];
            System.arraycopy(other.buffers, k * CgDrawChunk.BUFFERS, buffers, c * CgDrawChunk.BUFFERS, CgDrawChunk.BUFFERS);
            System.arraycopy(other.bufferAt, k * CgDrawChunk.BUFFERS, bufferAt, c * CgDrawChunk.BUFFERS, CgDrawChunk.BUFFERS);
            keys[c] = other.keys[k];
            groups[c] = other.groups[k];
            order[c] = c;
        }
    }

    /** Forgets every command. */
    public void clear() {
        Arrays.fill(bindings, 0, size, null);
        Arrays.fill(meshes, 0, size, null);
        Arrays.fill(objectBuffers, 0, size, null);
        Arrays.fill(counts, 0, size, null);
        Arrays.fill(buffers, 0, size * CgDrawChunk.BUFFERS, null);
        Arrays.fill(bufferAt, 0, size * CgDrawChunk.BUFFERS, null);
        size = 0;
        idCount = 0;
    }

    public int size() {
        return size;
    }

    /** The table its frame records are slots of, or null. */
    @Nullable
    public CgPrimitiveTable table() {
        return table;
    }

    /** The command drawn {@code i}th: its index in the order {@link #sort} left, else the order added. */
    public int at(int i) {
        return order[i];
    }

    public int pipeline(int c) {
        return pipelines[c];
    }

    public CgMaterialBindings binding(int c) {
        return bindings[c];
    }

    public CgMesh mesh(int c) {
        return meshes[c];
    }

    public int rangeSubmesh(int c) {
        return ranges[c * 3];
    }

    public int rangeFirst(int c) {
        return ranges[c * 3 + 1];
    }

    public int rangeCount(int c) {
        return ranges[c * 3 + 2];
    }

    /** {@link #NONE}, {@link #SCENE}, {@link #FRAME}, {@link #GPU_IDS} or {@link #RECORDS}. */
    public byte objectKind(int c) {
        return objectKinds[c];
    }

    /** Its first id in {@link #ids()}, or its buffer's first element. */
    public int objectFirst(int c) {
        return objectFirst[c];
    }

    /** How many ids it names, or its buffer's capacity or record count. */
    public int objectCount(int c) {
        return objectCount[c];
    }

    @Nullable
    public CgBufferHandle objectBuffer(int c) {
        return objectBuffers[c];
    }

    /** Every command's ids, by {@link #objectFirst} and {@link #objectCount}. */
    public int[] ids() {
        return ids;
    }

    @Nullable
    public CgBufferHandle indirectCount(int c) {
        return counts[c];
    }

    public long indirectOffset(int c) {
        return countOffsets[c];
    }

    public CgIndirect indirectMode(int c) {
        return countModes[c];
    }

    public int indirectFactor(int c) {
        return countFactors[c];
    }

    @Nullable
    public CgBufferHandle buffer(int c, int slot) {
        return buffers[c * CgDrawChunk.BUFFERS + slot];
    }

    @Nullable
    public CgBindingModel.Slot bufferAt(int c, int slot) {
        return bufferAt[c * CgDrawChunk.BUFFERS + slot];
    }

    public long sortKey(int c) {
        return keys[c];
    }

    public int group(int c) {
        return groups[c];
    }

    /**
     * Whether commands {@code a} and {@code b} draw as one instanced draw: Unreal's {@code MatchesForDynamicInstancing},
     * every field alike but their ids, both naming ids of one kind and neither counted on the GPU.
     */
    public boolean merges(int a, int b) {
        if (pipelines[a] != pipelines[b] || bindings[a] != bindings[b] || meshes[a] != meshes[b]) return false;
        byte k = objectKinds[a];
        if ((k != SCENE && k != FRAME) || objectKinds[b] != k || counts[a] != null || counts[b] != null) return false;
        if (ranges[a * 3] != ranges[b * 3] || ranges[a * 3 + 1] != ranges[b * 3 + 1] || ranges[a * 3 + 2] != ranges[b * 3 + 2]) {
            return false;
        }
        if (groups[a] != groups[b]) return false;
        for (int s = 0; s < CgDrawChunk.BUFFERS; s++) {
            int i = a * CgDrawChunk.BUFFERS + s, j = b * CgDrawChunk.BUFFERS + s;
            if (buffers[i] != buffers[j] || bufferAt[i] != bufferAt[j]) return false;
        }
        return true;
    }

    private void grow() {
        int n = pipelines.length * 2;
        pipelines = Arrays.copyOf(pipelines, n);
        bindings = Arrays.copyOf(bindings, n);
        meshes = Arrays.copyOf(meshes, n);
        ranges = Arrays.copyOf(ranges, n * 3);
        objectKinds = Arrays.copyOf(objectKinds, n);
        objectFirst = Arrays.copyOf(objectFirst, n);
        objectCount = Arrays.copyOf(objectCount, n);
        objectBuffers = Arrays.copyOf(objectBuffers, n);
        counts = Arrays.copyOf(counts, n);
        countOffsets = Arrays.copyOf(countOffsets, n);
        countModes = Arrays.copyOf(countModes, n);
        countFactors = Arrays.copyOf(countFactors, n);
        buffers = Arrays.copyOf(buffers, n * CgDrawChunk.BUFFERS);
        bufferAt = Arrays.copyOf(bufferAt, n * CgDrawChunk.BUFFERS);
        keys = Arrays.copyOf(keys, n);
        groups = Arrays.copyOf(groups, n);
        order = Arrays.copyOf(order, n);
        scratch = new int[n];
    }
}
