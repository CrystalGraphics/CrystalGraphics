package com.crystalgraphics.render.draw;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gl.texture.CgTextureMutable;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.gl.buffer.CgStreamBuffer;
import com.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import com.crystalgraphics.platform.gl.CgGL;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * A recording's binding snapshots: what a draw reads besides its instances, interned by content so two draws that
 * read the same things share an id, and a batch can hold both. Uniform blocks are copied when recorded; textures
 * and buffers are held as handles and resolved when bound. At execution every block is uploaded in one ring write
 * and bound by range. A {@link CgBufferHandle} — a frame graph's buffer — binds as a storage block at the point given,
 * and the graph orders the draw after whatever wrote it.
 *
 * <pre>{@code
 * int bindings = table.begin()
 *         .texture(0, atlasPage)
 *         .block(CgBindingPoints.MATERIAL_PROPERTIES_UBO, props, 0, propFloats)
 *         .end();                  // equal content answers the same id
 *
 * // at execution, on the render thread, once, then per batch:
 * table.upload(ring);              // the executor's frame ring
 * table.bind(bindings);
 * }</pre>
 *
 * <p>Usually filled by {@code CgMaterial.captureBindings(table)} rather than by hand.</p>
 *
 * <ul>
 *   <li>A block's floats are read at {@link #block}: a material changed after recording does not change what the
 *       draw reads. A texture is not: one reallocated before execution binds its new storage, which is what a
 *       growing glyph atlas needs. Its <em>content</em> is ordered by the frame graph's versions.</li>
 *   <li>{@link #upload} after the last {@link #end()} and before the first {@link #bind}: a block's place in the
 *       ring is only known once every block is.</li>
 *   <li>At most {@value #MAX_TEXTURES} textures, {@value #MAX_BLOCKS} blocks, {@value #MAX_BUFFERS} buffers and
 *       {@value #MAX_STORAGE} storage handles per snapshot.</li>
 *   <li>One thread at a time: the recorder fills it, and the render thread uploads and binds it once the
 *       recording is handed over. {@link #upload} and {@link #bind} touch GL; the table owns no GL object.</li>
 * </ul>
 */
public final class CgBindingTable {

    public static final int MAX_TEXTURES = 8;
    public static final int MAX_BLOCKS = 4;
    public static final int MAX_BUFFERS = 4;
    public static final int MAX_STORAGE = 4;

    /**
     * Per entry: texture, block, buffer and storage counts, a unit per texture, (binding, offset, floats) per block,
     * a binding point per storage handle.
     */
    private static final int HEADER = 4;
    private static final int BLOCK_INTS = 3;
    private static final int BLOCKS_AT = HEADER + MAX_TEXTURES;
    private static final int STORAGE_AT = BLOCKS_AT + MAX_BLOCKS * BLOCK_INTS;
    private static final int ENTRY_INTS = STORAGE_AT + MAX_STORAGE;

    /** Per entry: the textures, the buffers, then the storage handles. */
    private static final int STORAGE_REFS = MAX_TEXTURES + MAX_BUFFERS;
    private static final int ENTRY_REFS = STORAGE_REFS + MAX_STORAGE;

    /** {@code GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT} is at most 256 on every GL and Vulkan device we run on. */
    private static final int BLOCK_ALIGNMENT = 256;

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
    private int uploadedBuffer;
    private boolean uploaded;
    /** The ring frame it was uploaded in: a later frame's region is somewhere else. */
    private long uploadedFrame = -1;

    /** Starts a snapshot. */
    public CgBindingTable begin() {
        if (building >= 0) throw new IllegalStateException("begin() inside a snapshot: end() the last one first");
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

    /** {@code texture} bound to {@code unit} for the draw, as it is when the draw executes. */
    public CgBindingTable texture(int unit, CgTexture texture) {
        int e = building * ENTRY_INTS;
        int n = entries[e];
        if (n == MAX_TEXTURES) throw new IllegalStateException("more than " + MAX_TEXTURES + " textures in one snapshot");
        entries[e + HEADER + n] = unit;
        // A repointable view is kept as what it points at now: by execution it may point elsewhere.
        refs[building * ENTRY_REFS + n] = texture instanceof CgTextureMutable view ? view.current() : texture;
        entries[e] = n + 1;
        return this;
    }

    /** A uniform block bound at {@code binding}, copied from {@code data[offset, offset + floatCount)}. */
    public CgBindingTable block(int binding, float[] data, int offset, int floatCount) {
        int e = building * ENTRY_INTS;
        int n = entries[e + 1];
        if (n == MAX_BLOCKS) throw new IllegalStateException("more than " + MAX_BLOCKS + " blocks in one snapshot");
        if (floatCursor + floatCount > floats.length) {
            floats = Arrays.copyOf(floats, Math.max(floats.length * 2, floatCursor + floatCount));
        }
        System.arraycopy(data, offset, floats, floatCursor, floatCount);
        int b = e + BLOCKS_AT + n * BLOCK_INTS;
        entries[b] = binding;
        entries[b + 1] = floatCursor;
        entries[b + 2] = floatCount;
        floatCursor += floatCount;
        entries[e + 1] = n + 1;
        return this;
    }

    /** A buffer the draw reads, bound through {@link CgShaderBuffer#bind()} as it is when the draw executes. */
    public CgBindingTable buffer(CgShaderBuffer buffer) {
        int e = building * ENTRY_INTS;
        int n = entries[e + 2];
        if (n == MAX_BUFFERS) throw new IllegalStateException("more than " + MAX_BUFFERS + " buffers in one snapshot");
        refs[building * ENTRY_REFS + MAX_TEXTURES + n] = buffer;
        entries[e + 2] = n + 1;
        return this;
    }

    /** {@code buffer} bound as the storage block at {@code point} for the draw, resolved when the draw executes. */
    public CgBindingTable storage(int point, CgBufferHandle buffer) {
        int e = building * ENTRY_INTS;
        int n = entries[e + 3];
        if (n == MAX_STORAGE) throw new IllegalStateException("more than " + MAX_STORAGE + " storage handles in one snapshot");
        entries[e + STORAGE_AT + n] = point;
        refs[building * ENTRY_REFS + STORAGE_REFS + n] = buffer;
        entries[e + 3] = n + 1;
        return this;
    }

    /** Ends the snapshot and answers its id: an earlier snapshot's when the content is equal. */
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
     * Snapshot {@code id} of {@code from}, interned here: how a frame builder gathers the snapshots of every recording
     * it packs into one table. Equal content answers the id an equal snapshot already has.
     */
    public int copy(CgBindingTable from, int id) {
        begin();
        int e = id * ENTRY_INTS;
        int r = id * ENTRY_REFS;
        for (int t = 0; t < from.entries[e]; t++) texture(from.entries[e + HEADER + t], (CgTexture) from.refs[r + t]);
        for (int b = 0; b < from.entries[e + 1]; b++) {
            int block = e + BLOCKS_AT + b * BLOCK_INTS;
            block(from.entries[block], from.floats, from.entries[block + 1], from.entries[block + 2]);
        }
        for (int b = 0; b < from.entries[e + 2]; b++) buffer((CgShaderBuffer) from.refs[r + MAX_TEXTURES + b]);
        for (int b = 0; b < from.entries[e + 3]; b++) {
            storage(from.entries[e + STORAGE_AT + b], (CgBufferHandle) from.refs[r + STORAGE_REFS + b]);
        }
        return end();
    }

    /** Snapshot {@code id} with {@code texture} at {@code unit}, in place of whatever it bound there. */
    public int withTexture(int id, int unit, CgTexture texture) {
        begin();
        int e = id * ENTRY_INTS;
        int r = id * ENTRY_REFS;
        for (int t = 0; t < entries[e]; t++) {
            if (entries[e + HEADER + t] != unit) texture(entries[e + HEADER + t], (CgTexture) refs[r + t]);
        }
        texture(unit, texture);
        for (int b = 0; b < entries[e + 1]; b++) {
            int block = e + BLOCKS_AT + b * BLOCK_INTS;
            block(entries[block], floats, entries[block + 1], entries[block + 2]);
        }
        for (int b = 0; b < entries[e + 2]; b++) buffer((CgShaderBuffer) refs[r + MAX_TEXTURES + b]);
        for (int b = 0; b < entries[e + 3]; b++) storage(entries[e + STORAGE_AT + b], (CgBufferHandle) refs[r + STORAGE_REFS + b]);
        return end();
    }

    /** Whether snapshot {@code id} binds a texture at {@code unit}. */
    public boolean bindsUnit(int id, int unit) {
        int e = id * ENTRY_INTS;
        for (int t = 0; t < entries[e]; t++) if (entries[e + HEADER + t] == unit) return true;
        return false;
    }

    /** How many distinct snapshots it holds. */
    public int size() {
        return count;
    }

    /** How many textures snapshot {@code id} binds. */
    public int textures(int id) {
        return entries[id * ENTRY_INTS];
    }

    /** The unit snapshot {@code id}'s {@code i}th texture binds to. */
    public int textureUnit(int id, int i) {
        return entries[id * ENTRY_INTS + HEADER + i];
    }

    /** Snapshot {@code id}'s {@code i}th texture. */
    public CgTexture texture(int id, int i) {
        return (CgTexture) refs[id * ENTRY_REFS + i];
    }

    /** How many uniform blocks snapshot {@code id} binds. */
    public int blocks(int id) {
        return entries[id * ENTRY_INTS + 1];
    }

    /** The binding point of snapshot {@code id}'s {@code k}th block. */
    public int blockBinding(int id, int k) {
        return entries[id * ENTRY_INTS + BLOCKS_AT + k * BLOCK_INTS];
    }

    /** A copy of snapshot {@code id}'s {@code k}th block, as captured. */
    public float[] blockFloats(int id, int k) {
        int block = id * ENTRY_INTS + BLOCKS_AT + k * BLOCK_INTS;
        return Arrays.copyOfRange(floats, entries[block + 1], entries[block + 1] + entries[block + 2]);
    }

    /** How many storage handles snapshot {@code id} binds. */
    public int storages(int id) {
        return entries[id * ENTRY_INTS + 3];
    }

    /** Snapshot {@code id}'s {@code i}th storage handle. */
    public CgBufferHandle storage(int id, int i) {
        return (CgBufferHandle) refs[id * ENTRY_REFS + STORAGE_REFS + i];
    }

    /** The binding point snapshot {@code id}'s {@code i}th storage handle binds at. */
    public int storagePoint(int id, int i) {
        return entries[id * ENTRY_INTS + STORAGE_AT + i];
    }

    /** How many buffers snapshot {@code id} binds. */
    public int buffers(int id) {
        return entries[id * ENTRY_INTS + 2];
    }

    /** Forgets every snapshot; ids from before are invalid. */
    public void reset() {
        if (building >= 0) throw new IllegalStateException("reset() inside a snapshot");
        Arrays.fill(refs, 0, count * ENTRY_REFS, null);
        count = 0;
        floatCursor = 0;
        Arrays.fill(slots, 0);
        uploaded = false;
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
        if (ringOffsets.length < count * MAX_BLOCKS) ringOffsets = new int[Math.max(ringOffsets.length * 2, count * MAX_BLOCKS)];
        int bytes = 0;
        for (int id = 0; id < count; id++) {
            int e = id * ENTRY_INTS;
            for (int b = 0; b < entries[e + 1]; b++) bytes += align(entries[e + BLOCKS_AT + b * BLOCK_INTS + 2] * 4);
        }
        if (bytes == 0) return;
        ByteBuffer out = ring.map(bytes).order(ByteOrder.nativeOrder());
        FloatBuffer view = out.asFloatBuffer();
        int at = 0;
        for (int id = 0; id < count; id++) {
            int e = id * ENTRY_INTS;
            for (int b = 0; b < entries[e + 1]; b++) {
                int block = e + BLOCKS_AT + b * BLOCK_INTS;
                view.position(at >> 2);
                view.put(floats, entries[block + 1], entries[block + 2]);
                ringOffsets[id * MAX_BLOCKS + b] = at;
                at += align(entries[block + 2] * 4);
            }
        }
        int base = ring.commit(bytes);
        uploadedBuffer = ring.getGlBufferId();
        for (int i = 0; i < count * MAX_BLOCKS; i++) ringOffsets[i] += base;
    }

    /** Binds snapshot {@code id}'s textures, blocks and buffers. {@link #upload} first. */
    public void bind(int id) {
        if (!uploaded) throw new IllegalStateException("bind() before upload()");
        int e = id * ENTRY_INTS;
        int r = id * ENTRY_REFS;
        for (int t = 0; t < entries[e]; t++) ((CgTexture) refs[r + t]).bind(entries[e + HEADER + t]);
        if (entries[e] > 0) CgTexture.active(0);
        for (int b = 0; b < entries[e + 1]; b++) {
            int block = e + BLOCKS_AT + b * BLOCK_INTS;
            int size = Math.max(16, (entries[block + 2] * 4 + 15) & ~15);
            CgGL.glBindBufferRange(CgGL.GL_UNIFORM_BUFFER, entries[block], uploadedBuffer,
                    ringOffsets[id * MAX_BLOCKS + b], size);
        }
        for (int b = 0; b < entries[e + 2]; b++) ((CgShaderBuffer) refs[r + MAX_TEXTURES + b]).bind();
        for (int b = 0; b < entries[e + 3]; b++) {
            CgGL.glBindBufferBase(CgGL.GL_SHADER_STORAGE_BUFFER, entries[e + STORAGE_AT + b],
                    ((CgBufferHandle) refs[r + STORAGE_REFS + b]).bufferId());
        }
    }

    private static int align(int bytes) {
        return (bytes + BLOCK_ALIGNMENT - 1) & ~(BLOCK_ALIGNMENT - 1);
    }

    private int hash(int id) {
        int e = id * ENTRY_INTS;
        int r = id * ENTRY_REFS;
        int h = ((entries[e] * 31 + entries[e + 1]) * 31 + entries[e + 2]) * 31 + entries[e + 3];
        for (int t = 0; t < entries[e]; t++) h = (h * 31 + entries[e + HEADER + t]) * 31 + System.identityHashCode(refs[r + t]);
        for (int b = 0; b < entries[e + 1]; b++) {
            int block = e + BLOCKS_AT + b * BLOCK_INTS;
            h = h * 31 + entries[block];
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
        int ea = a * ENTRY_INTS, eb = b * ENTRY_INTS;
        int ra = a * ENTRY_REFS, rb = b * ENTRY_REFS;
        for (int i = 0; i < HEADER; i++) if (entries[ea + i] != entries[eb + i]) return false;
        for (int t = 0; t < entries[ea]; t++) {
            if (entries[ea + HEADER + t] != entries[eb + HEADER + t] || refs[ra + t] != refs[rb + t]) return false;
        }
        for (int k = 0; k < entries[ea + 1]; k++) {
            int ba = ea + BLOCKS_AT + k * BLOCK_INTS, bb = eb + BLOCKS_AT + k * BLOCK_INTS;
            if (entries[ba] != entries[bb] || entries[ba + 2] != entries[bb + 2]) return false;
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
            int s = hashes[id] & mask;
            while (slots[s] != 0) s = (s + 1) & mask;
            slots[s] = id + 1;
        }
    }
}
