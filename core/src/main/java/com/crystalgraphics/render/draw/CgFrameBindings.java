package com.crystalgraphics.render.draw;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.platform.rhi.CgDeviceNames;
import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.gpu.resource.CgStreamBuffer;
import com.crystalgraphics.gpu.resource.CgShaderBuffer;
import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.command.CgCommandList;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.render.mesh.CgOffsetAllocator;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import javax.annotation.Nullable;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.Objects;
import com.crystalgraphics.render.shader.CgBindingModel;

/**
 * A frame's bindings, as its executor reads them by id: each {@link CgMaterialBindings} version its draws name, entered
 * once by identity, and its passes' own parameters (a pass's constants and view textures, a dispatch's values), copied
 * as they are packed. At execution every copied block is uploaded in one ring write and bound by range, a block equal to
 * the last one uploaded at its binding sharing its place; a version's blocks are written once into persistent memory
 * and bound from there by every frame after. Every entry is keyed by its slot ({@link CgBindingModel}, packed).
 *
 * <pre>{@code
 * int drawn = frame.bindings.add(chunk.binding(d));         // a draw's version: one id however many draws name it
 * int constants = CgPassConstants.blocks(frame.bindings.begin(), pass.constants, 0, reversed, scratch)
 *         .texture(CgBindingModel.LIGHTMAP, lightmap)
 *         .end();                                              // equal content answers the same id
 *
 * // at execution, on the render thread, once, then per batch:
 * frame.bindings.upload(ring);
 * CgBindingSet set = deviceSets.set(rhi, frame.bindings, drawn, layout, CgBindingModel.MATERIAL, null);
 * }</pre>
 *
 * <ul>
 *   <li>The frame builder's and the executor's alone: a draw names a version, never an id here.</li>
 *   <li>{@link #upload} after the last entry and before the first set is made: a block's place in the ring is only known
 *       once every block is.</li>
 *   <li>A block in a {@link CgMaterialArena} binds as the arena: its entry's draws carry its {@link #slotOf slot} in their
 *       ids, and entries equal but for which version of one arena they hold have one {@link #share}.</li>
 * </ul>
 */
public final class CgFrameBindings {

    /** An entry's caps: a version's ({@link CgMaterialBindings}). */
    static final int MAX_TEXTURES = CgMaterialBindings.MAX_TEXTURES;
    static final int MAX_BLOCKS = CgMaterialBindings.MAX_BLOCKS;
    static final int MAX_BUFFERS = CgMaterialBindings.MAX_BUFFERS;
    static final int MAX_STORAGE = CgMaterialBindings.MAX_STORAGE;

    /** Per entry: texture, block, buffer and storage counts, a unit per texture, (binding, offset, floats) per block, a
     * slot per storage handle. */
    private static final int HEADER = 4;
    private static final int BLOCK_INTS = 3;
    private static final int BLOCKS_AT = HEADER + MAX_TEXTURES;
    private static final int STORAGE_AT = BLOCKS_AT + MAX_BLOCKS * BLOCK_INTS;
    private static final int ENTRY_INTS = STORAGE_AT + MAX_STORAGE;

    /** Per entry: the textures, the buffers, the storage handles, each texture's sampler, then each block's version. */
    private static final int STORAGE_REFS = MAX_TEXTURES + MAX_BUFFERS;
    private static final int SAMPLER_REFS = STORAGE_REFS + MAX_STORAGE;
    private static final int BLOCK_REFS = SAMPLER_REFS + MAX_TEXTURES;
    private static final int ENTRY_REFS = BLOCK_REFS + MAX_BLOCKS;

    /** {@code GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT} is at most 256 on every GL and Vulkan device we run on. */
    private static final int BLOCK_ALIGNMENT = 256;
    private static final int BLOCKS_SHARED = CgTrace.name("bindings.blocks-shared");

    /** Per block slot, during an {@link #upload}: the last block written there, and where it landed. */
    private int[] lastBlock = new int[512], lastOffset = new int[512];
    /** During an {@link #upload}: the versions it writes, and where each is staged in the ring. */
    private CgBlockVersion[] writing = new CgBlockVersion[16];
    private int[] writingAt = new int[16];

    private int[] entries = new int[ENTRY_INTS * 64];
    private Object[] refs = new Object[ENTRY_REFS * 64];
    private int[] hashes = new int[64];
    private int count;

    private float[] floats = new float[4096];
    private int floatCursor;

    /** Open addressing over entry ids, stored +1 so 0 is empty. Kept under half full. */
    private int[] slots = new int[128];

    private int building = -1;
    private int buildFloats;

    /** Where each entry's blocks landed in the ring, after {@link #upload}. */
    private int[] ringOffsets = new int[MAX_BLOCKS * 64];
    /** The buffer they landed in: a ring that grows later in the frame moves to a new one. */
    @Nullable
    private CgGpuBuffer uploadedBuffer;
    private boolean uploaded;
    /** The ring frame it was uploaded in: a later frame's region is somewhere else. */
    private long uploadedFrame = -1;

    /** Per entry: its arena slot once asked ({@link #slotOf}), -1 before; whether the ask holds a pin. */
    private int[] arenaSlots = new int[0];
    private boolean[] arenaPinned = new boolean[0];
    /** Per entry: its {@link #share}, made at the first ask after a change. Open addressing over shares, stored +1. */
    private int[] shares = new int[64];
    private boolean sharesMade;
    private int[] shareSlots = new int[128];
    /** During an {@link #upload}: the arenas its blocks are in. */
    private final List<CgMaterialArena> arenas = new ArrayList<>();

    /** Starts an entry. */
    public CgFrameBindings begin() {
        if (building >= 0) throw new IllegalStateException("begin() inside an entry: end() the last one first");
        if (count == hashes.length) {
            entries = Arrays.copyOf(entries, entries.length * 2);
            refs = Arrays.copyOf(refs, refs.length * 2);
            hashes = Arrays.copyOf(hashes, hashes.length * 2);
        }
        building = count;
        buildFloats = floatCursor;
        int e = count * ENTRY_INTS;
        entries[e] = 0;
        entries[e + 1] = 0;
        entries[e + 2] = 0;
        entries[e + 3] = 0;
        return this;
    }

    /** {@code texture} as the material's texture {@code index}, as it is when the draw executes, sampled as its own. */
    public CgFrameBindings texture(int index, CgTexture texture) {
        return put(CgBindingModel.packedTexture(index), texture, null);
    }

    /** {@code texture} as the material's texture {@code index}, sampled by {@code sampler}; null for its own sampling. */
    public CgFrameBindings texture(int index, CgTexture texture, @Nullable CgSampler sampler) {
        return put(CgBindingModel.packedTexture(index), texture, sampler);
    }

    /** {@code texture} at {@code slot}: a view texture ({@link CgBindingModel#LIGHTMAP}, scene depth and colour). */
    public CgFrameBindings texture(CgBindingModel.Slot slot, CgTexture texture) {
        return put(slot.packed(), texture, null);
    }

    private CgFrameBindings put(int packed, CgTexture texture, @Nullable CgSampler sampler) {
        int e = building * ENTRY_INTS;
        int n = entries[e];
        if (n == MAX_TEXTURES) throw new IllegalStateException("more than " + MAX_TEXTURES + " textures in one entry");
        entries[e + HEADER + n] = packed;
        refs[building * ENTRY_REFS + n] = texture;
        refs[building * ENTRY_REFS + SAMPLER_REFS + n] = sampler;
        entries[e] = n + 1;
        return this;
    }

    /** A uniform block at {@code slot}, copied from {@code data[offset, offset + floatCount)}. */
    public CgFrameBindings block(CgBindingModel.Slot slot, float[] data, int offset, int floatCount) {
        return block(slot.packed(), data, offset, floatCount);
    }

    private CgFrameBindings block(int binding, float[] data, int offset, int floatCount) {
        int e = building * ENTRY_INTS;
        int n = entries[e + 1];
        if (n == MAX_BLOCKS) throw new IllegalStateException("more than " + MAX_BLOCKS + " blocks in one entry");
        if (floatCursor + floatCount > floats.length) {
            floats = Arrays.copyOf(floats, Math.max(floats.length * 2, floatCursor + floatCount));
        }
        System.arraycopy(data, offset, floats, floatCursor, floatCount);
        int b = e + BLOCKS_AT + n * BLOCK_INTS;
        entries[b] = binding;
        entries[b + 1] = floatCursor;
        entries[b + 2] = floatCount;
        refs[building * ENTRY_REFS + BLOCK_REFS + n] = null;
        floatCursor += floatCount;
        entries[e + 1] = n + 1;
        return this;
    }

    private CgFrameBindings block(int binding, CgBlockVersion version) {
        int e = building * ENTRY_INTS;
        int n = entries[e + 1];
        if (n == MAX_BLOCKS) throw new IllegalStateException("more than " + MAX_BLOCKS + " blocks in one entry");
        int b = e + BLOCKS_AT + n * BLOCK_INTS;
        entries[b] = binding;
        entries[b + 1] = -1;
        entries[b + 2] = version.floats.length;
        refs[building * ENTRY_REFS + BLOCK_REFS + n] = version;
        entries[e + 1] = n + 1;
        return this;
    }

    /** An engine buffer the draw reads at its own slot, its range as it is when the draw executes. */
    private CgFrameBindings buffer(CgShaderBuffer buffer) {
        int e = building * ENTRY_INTS;
        int n = entries[e + 2];
        if (n == MAX_BUFFERS) throw new IllegalStateException("more than " + MAX_BUFFERS + " buffers in one entry");
        refs[building * ENTRY_REFS + MAX_TEXTURES + n] = buffer;
        entries[e + 2] = n + 1;
        return this;
    }

    private CgFrameBindings storage(int point, CgBufferHandle buffer) {
        int e = building * ENTRY_INTS;
        int n = entries[e + 3];
        if (n == MAX_STORAGE) throw new IllegalStateException("more than " + MAX_STORAGE + " storage handles in one entry");
        entries[e + STORAGE_AT + n] = point;
        refs[building * ENTRY_REFS + STORAGE_REFS + n] = buffer;
        entries[e + 3] = n + 1;
        return this;
    }

    /** Ends the entry and answers its id: an earlier entry's when the content is equal. */
    public int end() {
        int id = building;
        building = -1;
        int hash = hash(id);
        int mask = slots.length - 1;
        for (int s = hash & mask; ; s = (s + 1) & mask) {
            int found = slots[s] - 1;
            if (found < 0) {
                slots[s] = id + 1;
                hashes[id] = hash;
                count++;
                if (count * 2 > slots.length) rehash();
                uploaded = false;
                sharesMade = false;
                return id;
            }
            if (hashes[found] == hash && sameContent(found, id)) {
                floatCursor = buildFloats;   // the duplicate's floats were never needed
                Arrays.fill(refs, id * ENTRY_REFS, (id + 1) * ENTRY_REFS, null);
                return found;
            }
        }
    }

    /**
     * Ends the entry without interning it: an id of its own, though an equal entry may exist. For entries that
     * almost never repeat, where hashing every float costs more than the duplicate would: a dispatch's values.
     *
     * <pre>{@code
     * int values = table.begin().block(MATERIAL_PROPERTIES_UBO, dispatchValues, 0, n).endUnique();
     * }</pre>
     */
    public int endUnique() {
        int id = building;
        building = -1;
        hashes[id] = 0;
        count++;
        if (count * 2 > slots.length) rehash();
        uploaded = false;
        sharesMade = false;
        return id;
    }

    /**
     * Enters {@code bindings}, a version a draw names, answering its id: the same id for the same version until
     * {@link #reset}, however many draws name it.
     */
    public int add(CgMaterialBindings bindings) {
        int mask = versionSlots.length - 1;
        int s = System.identityHashCode(bindings) & mask;
        for (; versionSlots[s] != null; s = (s + 1) & mask) {
            if (versionSlots[s] == bindings) return versionIds[s];
        }
        begin();
        for (int t = 0; t < bindings.textures(); t++) put(bindings.textureSlot(t), bindings.texture(t), bindings.sampler(t));
        for (int k = 0; k < bindings.blocks(); k++) block(bindings.blockSlot(k), bindings.block(k));
        for (int b = 0; b < bindings.buffers(); b++) buffer(bindings.buffer(b));
        for (int k = 0; k < bindings.storages(); k++) storage(bindings.storageSlot(k), bindings.storage(k));
        int id = endUnique();
        versionSlots[s] = bindings;
        versionIds[s] = id;
        if (++versions * 2 > versionSlots.length) growVersions();
        return id;
    }

    /** {@link #add}'s versions by identity, open addressing, kept under half full; and their ids. */
    private CgMaterialBindings[] versionSlots = new CgMaterialBindings[128];
    private int[] versionIds = new int[128];
    private int versions;

    private void growVersions() {
        CgMaterialBindings[] was = versionSlots;
        int[] wasIds = versionIds;
        versionSlots = new CgMaterialBindings[was.length * 2];
        versionIds = new int[was.length * 2];
        int mask = versionSlots.length - 1;
        for (int i = 0; i < was.length; i++) {
            if (was[i] == null) continue;
            int s = System.identityHashCode(was[i]) & mask;
            while (versionSlots[s] != null) s = (s + 1) & mask;
            versionSlots[s] = was[i];
            versionIds[s] = wasIds[i];
        }
    }

    /** How many distinct entries it holds. */
    public int size() {
        return count;
    }

    /** How many textures entry {@code id} binds. */
    public int textures(int id) {
        return entries[id * ENTRY_INTS];
    }

    /** The slot ({@link CgBindingModel#pack packed}) entry {@code id}'s {@code i}th texture binds to. */
    public int textureUnit(int id, int i) {
        return entries[id * ENTRY_INTS + HEADER + i];
    }

    /** Entry {@code id}'s {@code i}th texture. */
    public CgTexture texture(int id, int i) {
        return (CgTexture) refs[id * ENTRY_REFS + i];
    }

    /** The sampler entry {@code id}'s {@code i}th texture is sampled by; null for its own. */
    public CgSampler sampler(int id, int i) {
        return (CgSampler) refs[id * ENTRY_REFS + SAMPLER_REFS + i];
    }

    /** How many uniform blocks entry {@code id} binds. */
    public int blocks(int id) {
        return entries[id * ENTRY_INTS + 1];
    }

    /** The slot ({@link CgBindingModel#pack packed}) of entry {@code id}'s {@code k}th block. */
    public int blockBinding(int id, int k) {
        return entries[id * ENTRY_INTS + BLOCKS_AT + k * BLOCK_INTS];
    }

    /** A copy of entry {@code id}'s {@code k}th block, as captured. */
    public float[] blockFloats(int id, int k) {
        CgBlockVersion version = (CgBlockVersion) refs[id * ENTRY_REFS + BLOCK_REFS + k];
        if (version != null) return version.floats();
        int block = id * ENTRY_INTS + BLOCKS_AT + k * BLOCK_INTS;
        return Arrays.copyOfRange(floats, entries[block + 1], entries[block + 1] + entries[block + 2]);
    }

    /** How many storage handles entry {@code id} binds. */
    public int storages(int id) {
        return entries[id * ENTRY_INTS + 3];
    }

    /** Entry {@code id}'s {@code i}th storage handle. */
    public CgBufferHandle storage(int id, int i) {
        return (CgBufferHandle) refs[id * ENTRY_REFS + STORAGE_REFS + i];
    }

    /** The slot ({@link CgBindingModel#pack packed}) entry {@code id}'s {@code i}th storage handle binds at. */
    public int storagePoint(int id, int i) {
        return entries[id * ENTRY_INTS + STORAGE_AT + i];
    }

    /** How many buffers entry {@code id} binds. */
    public int buffers(int id) {
        return entries[id * ENTRY_INTS + 2];
    }

    /** Entry {@code id}'s {@code i}th buffer. */
    public CgShaderBuffer buffer(int id, int i) {
        return (CgShaderBuffer) refs[id * ENTRY_REFS + MAX_TEXTURES + i];
    }

    /** Entry {@code id}'s {@code k}th block's persistent version; null for one copied into the ring. */
    CgBlockVersion blockVersion(int id, int k) {
        return (CgBlockVersion) refs[id * ENTRY_REFS + BLOCK_REFS + k];
    }

    /** How many floats entry {@code id}'s {@code k}th block holds. */
    int blockFloatCount(int id, int k) {
        return entries[id * ENTRY_INTS + BLOCKS_AT + k * BLOCK_INTS + 2];
    }

    /** Where entry {@code id}'s {@code k}th ring block landed in {@link #uploadedBuffer()}; after {@link #upload}. */
    int ringOffset(int id, int k) {
        return ringOffsets[id * MAX_BLOCKS + k];
    }

    /** The ring buffer its blocks landed in at the last {@link #upload}; null before one wrote any. */
    @Nullable
    CgGpuBuffer uploadedBuffer() {
        return uploadedBuffer;
    }

    /** How many times it was {@link #reset}: an id held across an unchanged count is still valid. */
    public int resets() {
        return resets;
    }

    private int resets;

    /** Forgets every entry; ids from before are invalid. */
    public void reset() {
        if (building >= 0) throw new IllegalStateException("reset() inside an entry");
        resets++;
        for (int id = 0; id < Math.min(count, arenaSlots.length); id++) {
            if (arenaPinned[id]) {
                CgBlockVersion v = arenaVersion(id);
                v.arena.unpin(v);
                arenaPinned[id] = false;
            }
            arenaSlots[id] = -1;
        }
        Arrays.fill(refs, 0, count * ENTRY_REFS, null);
        if (versions > 0) {
            Arrays.fill(versionSlots, null);
            versions = 0;
        }
        count = 0;
        floatCursor = 0;
        Arrays.fill(slots, 0);
        uploaded = false;
        sharesMade = false;
    }

    /**
     * Uploads every block in one write into {@code ring}, a frame-local uniform ring. After the last {@link #end()},
     * before the first {@link #bind}; a table that has not changed since is not uploaded again in the same frame.
     */
    public void upload(CgStreamBuffer ring) {
        long frame = CgFrameRing.frame();
        if (uploaded && uploadedFrame == frame) return;
        uploaded = true;
        uploadedFrame = frame;
        BlockStore store = BlockStore.STORE;
        store.retire();
        int writes = 0;
        int bytes = 0, shared = 0;
        // Arena blocks: each given its slot, the frame builder's pin released (retire has run), its arena fitted to its
        // slots, and staged below with the pages' new versions.
        arenas.clear();
        for (int id = 0; id < count; id++) {
            CgBlockVersion v = arenaVersion(id);
            if (v == null) continue;
            if (id < arenaPinned.length && arenaPinned[id]) {
                arenaPinned[id] = false;
                v.arena.unpin(v);
            } else if (v.slot < 0) {
                v.arena.slotOf(v);
                v.arena.unpin(v);
            }
            v.lastUse = frame;
            if (!arenas.contains(v.arena)) arenas.add(v.arena);
            if (v.written) continue;
            v.written = true;
            store.track(v);
            if (writes == writing.length) {
                writing = Arrays.copyOf(writing, writes * 2);
                writingAt = Arrays.copyOf(writingAt, writes * 2);
            }
            writing[writes] = v;
            writingAt[writes++] = bytes;
            bytes += BlockStore.bytes(v);
        }
        for (int a = 0; a < arenas.size(); a++) store.fit(arenas.get(a));
        if (ringOffsets.length < count * MAX_BLOCKS) ringOffsets = new int[Math.max(ringOffsets.length * 2, count * MAX_BLOCKS)];
        // Places first: a block equal to the last at its binding takes that one's, kept as -1 - offset until written.
        Arrays.fill(lastBlock, -1);
        for (int id = 0; id < count; id++) {
            int e = id * ENTRY_INTS;
            for (int b = 0; b < entries[e + 1]; b++) {
                int block = e + BLOCKS_AT + b * BLOCK_INTS;
                CgBlockVersion version = (CgBlockVersion) refs[id * ENTRY_REFS + BLOCK_REFS + b];
                if (version != null) {
                    if (version.arena == null && store.place(version)) {
                        if (writes == writing.length) {
                            writing = Arrays.copyOf(writing, writes * 2);
                            writingAt = Arrays.copyOf(writingAt, writes * 2);
                        }
                        writing[writes] = version;
                        writingAt[writes++] = bytes;
                        bytes += BlockStore.bytes(version);
                    }
                    continue;
                }
                // A block's slot as a small index: set and binding, the bindings a block takes all below 128
                int binding = CgBindingModel.setOf(entries[block]) * 128 + CgBindingModel.bindingOf(entries[block]);
                if (binding >= lastBlock.length) {
                    int was = lastBlock.length;
                    lastBlock = Arrays.copyOf(lastBlock, binding * 2);
                    lastOffset = Arrays.copyOf(lastOffset, binding * 2);
                    Arrays.fill(lastBlock, was, lastBlock.length, -1);
                }
                int last = lastBlock[binding];
                if (last >= 0 && sameFloats(last, block)) {
                    ringOffsets[id * MAX_BLOCKS + b] = -1 - lastOffset[binding];
                    shared++;
                    continue;
                }
                lastBlock[binding] = block;
                lastOffset[binding] = bytes;
                ringOffsets[id * MAX_BLOCKS + b] = bytes;
                bytes += align(entries[block + 2] * 4);
            }
        }
        CgTrace.add(CgChannels.GL, BLOCKS_SHARED, shared);
        if (bytes == 0) return;
        FloatBuffer view = ring.mapFloats(bytes);
        int from = view.position();
        for (int id = 0; id < count; id++) {
            int e = id * ENTRY_INTS;
            for (int b = 0; b < entries[e + 1]; b++) {
                if (refs[id * ENTRY_REFS + BLOCK_REFS + b] != null) continue;
                int at = ringOffsets[id * MAX_BLOCKS + b];
                if (at < 0) {
                    ringOffsets[id * MAX_BLOCKS + b] = -1 - at;
                    continue;
                }
                int block = e + BLOCKS_AT + b * BLOCK_INTS;
                view.position(from + (at >> 2));
                view.put(floats, entries[block + 1], entries[block + 2]);
            }
        }
        for (int w = 0; w < writes; w++) {
            view.position(from + (writingAt[w] >> 2));
            view.put(writing[w].floats);
        }
        int base = ring.commit(bytes);
        uploadedBuffer = ring.gpuBuffer();
        for (int i = 0; i < count * MAX_BLOCKS; i++) ringOffsets[i] += base;
        if (writes == 0) return;
        // Each new version copied from the ring into its page or its arena slot, as CgMeshStore writes its pools: outside
        // any pass.
        CgDeviceSection.begin();
        CgCommandList list = CgRHI.get().encoder();
        list.beginTransfer();
        try {
            for (int w = 0; w < writes; w++) {
                CgBlockVersion v = writing[w];
                CgMaterialArena arena = v.arena;
                list.copyBuffer(uploadedBuffer, base + writingAt[w], arena != null ? arena.buffer : v.buffer,
                        arena != null ? (long) v.slot * arena.stride() : v.offset, v.floats.length * 4L);
                writing[w] = null;
            }
        } finally {
            list.endTransfer();
            CgDeviceSection.end();
        }
    }

    /**
     * The arena slot entry {@code id}'s material block takes, given now if it holds none and kept until this table's
     * {@link #upload}: what the frame builder writes beside each id of the entry's draws. 0 for an entry holding no
     * arena block. The building thread's.
     */
    public int slotOf(int id) {
        if (arenaSlots.length < count) {
            int was = arenaSlots.length;
            arenaSlots = Arrays.copyOf(arenaSlots, Math.max(count, was * 2));
            arenaPinned = Arrays.copyOf(arenaPinned, arenaSlots.length);
            Arrays.fill(arenaSlots, was, arenaSlots.length, -1);
        }
        if (arenaSlots[id] >= 0) return arenaSlots[id];
        CgBlockVersion v = arenaVersion(id);
        if (v == null) return arenaSlots[id] = 0;
        arenaPinned[id] = true;
        return arenaSlots[id] = v.arena.slotOf(v);
    }

    /**
     * Entry {@code id}'s share: the first entry equal to it but for which version of one arena they hold, which
     * draws its with one set and in one multi-draw (Bevy's material slab key).
     */
    public int share(int id) {
        if (!sharesMade) share();
        return shares[id];
    }

    /** Entry {@code id}'s block in an arena; null for none. */
    @Nullable
    CgBlockVersion arenaVersion(int id) {
        int e = id * ENTRY_INTS;
        for (int b = 0; b < entries[e + 1]; b++) {
            CgBlockVersion v = (CgBlockVersion) refs[id * ENTRY_REFS + BLOCK_REFS + b];
            if (v != null && v.arena != null) return v;
        }
        return null;
    }

    /** Every entry's {@link #share}, interned by content with each arena block taken as its arena. */
    private void share() {
        sharesMade = true;
        if (shares.length < count) shares = new int[Math.max(count, shares.length * 2)];
        int size = Integer.highestOneBit(Math.max(count * 2, 2) - 1) << 1;
        if (shareSlots.length < size) shareSlots = new int[size];
        else Arrays.fill(shareSlots, 0);
        int mask = shareSlots.length - 1;
        for (int id = 0; id < count; id++) {
            if (arenaVersion(id) == null) {
                shares[id] = id;
                continue;
            }
            int h = hash(id, true);
            for (int s = h & mask; ; s = (s + 1) & mask) {
                int found = shareSlots[s] - 1;
                if (found < 0) {
                    shareSlots[s] = id + 1;
                    shares[id] = id;
                    break;
                }
                if (sameContent(found, id, true)) {
                    shares[id] = found;
                    break;
                }
            }
        }
    }

    /** Whether the blocks at entry offsets {@code a} and {@code b} hold the same floats. */
    private boolean sameFloats(int a, int b) {
        int n = entries[a + 2];
        if (entries[b + 2] != n) return false;
        int fa = entries[a + 1], fb = entries[b + 1];
        for (int f = 0; f < n; f++) {
            if (Float.floatToRawIntBits(floats[fa + f]) != Float.floatToRawIntBits(floats[fb + f])) return false;
        }
        return true;
    }

    private static int align(int bytes) {
        return (bytes + BLOCK_ALIGNMENT - 1) & ~(BLOCK_ALIGNMENT - 1);
    }

    private int hash(int id) {
        return hash(id, false);
    }

    /** {@code id}'s content hash; with {@code share}, an arena block by its arena. */
    private int hash(int id, boolean share) {
        int e = id * ENTRY_INTS;
        int r = id * ENTRY_REFS;
        int h = ((entries[e] * 31 + entries[e + 1]) * 31 + entries[e + 2]) * 31 + entries[e + 3];
        for (int t = 0; t < entries[e]; t++) {
            h = ((h * 31 + entries[e + HEADER + t]) * 31 + System.identityHashCode(refs[r + t])) * 31
                    + Objects.hashCode(refs[r + SAMPLER_REFS + t]);
        }
        for (int b = 0; b < entries[e + 1]; b++) {
            int block = e + BLOCKS_AT + b * BLOCK_INTS;
            h = h * 31 + entries[block];
            CgBlockVersion version = (CgBlockVersion) refs[r + BLOCK_REFS + b];
            if (version != null) {
                h = h * 31 + System.identityHashCode(share && version.arena != null ? version.arena : version);
                continue;
            }
            int from = entries[block + 1];
            for (int f = 0; f < entries[block + 2]; f++) h = h * 31 + Float.floatToRawIntBits(floats[from + f]);
        }
        for (int b = 0; b < entries[e + 2]; b++) h = h * 31 + System.identityHashCode(refs[r + MAX_TEXTURES + b]);
        for (int b = 0; b < entries[e + 3]; b++) {
            h = (h * 31 + entries[e + STORAGE_AT + b]) * 31 + System.identityHashCode(refs[r + STORAGE_REFS + b]);
        }
        return h ^ (h >>> 16);
    }

    private boolean sameContent(int a, int b) {
        return sameContent(a, b, false);
    }

    /** Whether entries {@code a} and {@code b} hold the same; with {@code share}, two blocks of one arena match. */
    private boolean sameContent(int a, int b, boolean share) {
        int ea = a * ENTRY_INTS, eb = b * ENTRY_INTS;
        int ra = a * ENTRY_REFS, rb = b * ENTRY_REFS;
        for (int i = 0; i < HEADER; i++) if (entries[ea + i] != entries[eb + i]) return false;
        for (int t = 0; t < entries[ea]; t++) {
            if (entries[ea + HEADER + t] != entries[eb + HEADER + t] || refs[ra + t] != refs[rb + t]
                    || !Objects.equals(refs[ra + SAMPLER_REFS + t], refs[rb + SAMPLER_REFS + t])) return false;
        }
        for (int k = 0; k < entries[ea + 1]; k++) {
            int ba = ea + BLOCKS_AT + k * BLOCK_INTS, bb = eb + BLOCKS_AT + k * BLOCK_INTS;
            if (entries[ba] != entries[bb] || entries[ba + 2] != entries[bb + 2]) return false;
            CgBlockVersion va = (CgBlockVersion) refs[ra + BLOCK_REFS + k], vb = (CgBlockVersion) refs[rb + BLOCK_REFS + k];
            if (va != vb && !(share && va != null && vb != null && va.arena != null && va.arena == vb.arena)) return false;
            if (va != null) continue;
            int fa = entries[ba + 1], fb = entries[bb + 1];
            for (int f = 0; f < entries[ba + 2]; f++) {
                if (Float.floatToRawIntBits(floats[fa + f]) != Float.floatToRawIntBits(floats[fb + f])) return false;
            }
        }
        for (int k = 0; k < entries[ea + 2]; k++) {
            if (refs[ra + MAX_TEXTURES + k] != refs[rb + MAX_TEXTURES + k]) return false;
        }
        for (int k = 0; k < entries[ea + 3]; k++) {
            if (entries[ea + STORAGE_AT + k] != entries[eb + STORAGE_AT + k]
                    || refs[ra + STORAGE_REFS + k] != refs[rb + STORAGE_REFS + k]) return false;
        }
        return true;
    }

    private void rehash() {
        slots = new int[slots.length * 2];
        int mask = slots.length - 1;
        for (int id = 0; id < count; id++) {
            if (hashes[id] == 0) continue;   // endUnique's, or an interned one hashing to 0: no longer shared
            int s = hashes[id] & mask;
            while (slots[s] != 0) s = (s + 1) & mask;
            slots[s] = id + 1;
        }
    }

    /**
     * Where {@link CgBlockVersion}s live once written: pages of uniform memory that outlive frames, a range a version, freed
     * once the version is superseded and the last frame that bound it has retired. {@link CgFrameBindings#upload} places and
     * writes the versions its entries hold; render thread only.
     */
    static final class BlockStore {

        static final BlockStore STORE = new BlockStore();

        /** {@code GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT} is at most 256 on every GL and Vulkan device we run on. */
        static final int ALIGNMENT = 256;
        private static final int PAGE_UNITS = (1 << 20) / ALIGNMENT;
        private static final int WRITTEN = CgTrace.name("blocks.persistent-written"), LIVE = CgTrace.name("blocks.persistent-live"),
                ARENA_SLOTS = CgTrace.name("material.arena-slots"), ARENA_GROWS = CgTrace.name("material.arena-grows");

        private static final Set<CgGpuBuffer.Usage> PAGE_USAGE = EnumSet.of(CgGpuBuffer.Usage.UNIFORM,
                CgGpuBuffer.Usage.COPY_DST);
        private static final Set<CgGpuBuffer.Usage> ARENA_USAGE = EnumSet.of(CgGpuBuffer.Usage.STORAGE,
                CgGpuBuffer.Usage.COPY_SRC, CgGpuBuffer.Usage.COPY_DST);

        static final class Page {
            final CgGpuBuffer buffer;
            final CgOffsetAllocator ranges;

            Page(int units) {
                buffer = CgRHI.get().createBuffer(new CgGpuBuffer.Desc("cg_blocks", (long) units * ALIGNMENT, PAGE_USAGE, false));
                ranges = new CgOffsetAllocator(units);
            }
        }

        private final List<Page> pages = new ArrayList<>();
        private final List<CgBlockVersion> live = new ArrayList<>();
        /** Every arena a version was written into. */
        private final List<CgMaterialArena> arenas = new ArrayList<>();

        /** Makes {@code a}'s buffer hold every slot given, copying what it held; outside any pass. */
        void fit(CgMaterialArena a) {
            int need = a.reach();
            if (need <= a.capacity) return;
            int slots = Math.max(CgMaterialArena.FIRST_SLOTS, Integer.highestOneBit(need - 1) << 1);
            CgGpuBuffer grown = CgRHI.get().createBuffer(new CgGpuBuffer.Desc("cg_material_arena",
                    (long) slots * a.stride(), ARENA_USAGE, false));
            if (a.buffer != null) {
                CgDeviceSection.begin();
                CgCommandList list = CgRHI.get().encoder();
                list.beginTransfer();
                try {
                    list.copyBuffer(a.buffer, 0, grown, 0, (long) a.capacity * a.stride());
                } finally {
                    list.endTransfer();
                    CgDeviceSection.end();
                }
                // Destroyed once the frames that drew from it retire
                CgDeviceNames.forget(a.buffer);
                CgRHI.get().release(a.buffer);
                CgTrace.add(CgChannels.GL, ARENA_GROWS, 1);
            }
            a.buffer = grown;
            a.capacity = slots;
        }

        private long retiredScan = -1;

        /** The bytes {@code v} takes, aligned: what a write stages for it. A block of no values still takes one unit, as a range cannot be empty. */
        static int bytes(CgBlockVersion v) {
            return Math.max(ALIGNMENT, (v.floats.length * 4 + ALIGNMENT - 1) / ALIGNMENT * ALIGNMENT);
        }

        /** Gives {@code v} its range if it has none, answering whether it must be written now. */
        boolean place(CgBlockVersion v) {
            if (v.buffer != null) return false;
            int units = bytes(v) / ALIGNMENT;
            for (int i = 0; i < pages.size(); i++) {
                if (take(pages.get(i), v, units)) return true;
            }
            Page page = new Page(Math.max(PAGE_UNITS, CgOffsetAllocator.fittingSize(units)));
            pages.add(page);
            if (!take(page, v, units)) throw new IllegalStateException("a new page holds no block of " + units * ALIGNMENT + " bytes");
            return true;
        }

        private boolean take(Page page, CgBlockVersion v, int units) {
            int node = page.ranges.allocate(units);
            if (node == CgOffsetAllocator.NO_SPACE) return false;
            v.page = page;
            v.node = node;
            v.offset = page.ranges.offset(node) * ALIGNMENT;
            v.buffer = page.buffer;
            live.add(v);
            CgTrace.add(CgChannels.GL, WRITTEN, 1);
            return true;
        }

        /** Keeps {@code v}, an arena version just written, for {@link #retire} to free its slot. Render thread. */
        void track(CgBlockVersion v) {
            live.add(v);
            CgTrace.add(CgChannels.GL, WRITTEN, 1);
            if (!arenas.contains(v.arena)) arenas.add(v.arena);
        }

        /** Frees the ranges and slots of versions superseded whose frames have retired: once a frame, at its first upload. */
        void retire() {
            long now = CgFrameRing.frame();
            if (now == retiredScan) return;
            retiredScan = now;
            long safe = now - CgFrameRing.FRAMES;
            for (int i = live.size() - 1; i >= 0; i--) {
                CgBlockVersion v = live.get(i);
                if (v.arena != null) {
                    // A replay naming it after takes a new slot: the frame builder writes slots, no recording holds one
                    if (v.arena.free(v, safe)) {
                        live.set(i, live.get(live.size() - 1));
                        live.remove(live.size() - 1);
                    }
                    continue;
                }
                if (!v.superseded || v.lastUse > safe) continue;
                CgFrameRing.awaitRetired(v.lastUse);
                v.page.ranges.free(v.node);
                // Unwritten again: a recording executed again places and writes it anew.
                v.page = null;
                v.node = -1;
                v.buffer = null;
                live.set(i, live.get(live.size() - 1));
                live.remove(live.size() - 1);
            }
            CgTrace.counter(CgChannels.GL, LIVE, live.size());
            int slots = 0;
            for (int a = 0; a < arenas.size(); a++) slots += arenas.get(a).live();
            CgTrace.counter(CgChannels.GL, ARENA_SLOTS, slots);
        }
    }
}
