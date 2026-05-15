package com.crystalgraphics.gl.buffer.staging;

import com.crystalgraphics.gl.render.CgBatchRenderer;

import java.util.Arrays;

/**
 * CPU-side float staging buffer: a growable {@code float[]} with a write cursor.
 *
 * <p>Pure data structure — no GL dependencies, no topology awareness, no semantic knowledge.
 * Values are written by {@link CgVertexWriter} / {@link CgInstanceWriter} / {@link CgBufferWriter}
 * and read by {@link CgBatchRenderer} or a shader-buffer upload path.</p>
 *
 * <h3>Two construction modes</h3>
 * <dl>
 *   <dt>Vertex/record mode — {@link #CgStagingBuffer(int, int)}</dt>
 *   <dd>Has a fixed stride ({@code floatsPerVertex}). {@link #ensureRoomForNextVertex()} and
 *       {@link #ensureRoomForStride(int)} grow in stride-sized increments. Used by vertex and
 *       instance writers, and by SSBO/TBO shader buffers.</dd>
 *   <dt>Flat mode — {@link #CgStagingBuffer(int)}</dt>
 *   <dd>No stride concept. {@link #putFloat} auto-grows the array whenever the cursor reaches
 *       the end. Used by UBO writers that write an arbitrary sequence of floats with no fixed
 *       record boundary.</dd>
 * </dl>
 *
 * <h3>Growth strategy</h3>
 * <p>All growth paths use a {@code max(current * 1.5, requested)} strategy — 1.5× amortises
 * reallocations while capping worst-case overshoot.</p>
 *
 * <h3>Color packing</h3>
 * <p>{@link #putIntBits(int)} stores an ABGR-packed int as a float slot via
 * {@link Float#intBitsToFloat(int)}. This matches the GPU layout when the attribute is
 * {@code GL_UNSIGNED_BYTE × 4} with normalisation enabled.</p>
 */
public final class CgStagingBuffer implements CgVertexOutput {

    private float[] data;
    private int cursor;

    // The stride used by ensureRoomForNextVertex(). In flat mode this is set to 1
    // but is never meaningfully used — putFloat auto-grows instead.
    private final int floatsPerVertex;

    // ── Constructors ──────────────────────────────────────────────────────────

    /**
     * Vertex/record-mode constructor.
     * Initial array size = {@code initialCapacityQuads * 4 * floatsPerVertex}.
     *
     * @param floatsPerVertex    floats per vertex (= record stride for shader buffers)
     * @param initialCapacityQuads number of quads to pre-allocate capacity for
     */
    public CgStagingBuffer(int floatsPerVertex, int initialCapacityQuads) {
        this.floatsPerVertex = floatsPerVertex;
        this.data = new float[initialCapacityQuads * 4 * floatsPerVertex];
    }

    /**
     * Flat-mode constructor for buffers with no fixed record stride (e.g. UBO writers).
     * {@link #putFloat} auto-grows the backing array; {@link #ensureRoomForNextVertex()} is
     * not meaningful in this mode.
     *
     * @param initialCapacityFloats initial size of the backing {@code float[]}
     */
    public CgStagingBuffer(int initialCapacityFloats) {
        this.floatsPerVertex = 1; // unused in flat mode; putFloat handles growth
        this.data = new float[initialCapacityFloats];
    }

    // ── Write primitives ──────────────────────────────────────────────────────

    /**
     * Writes one float at the current cursor, auto-growing the array if full.
     * Safe to call in both vertex/record mode and flat mode.
     */
    @Override
    public void putFloat(float v) {
        ensureRoomForFloat();
        data[cursor++] = v;
    }

    /**
     * Writes an int reinterpreted as a float (via {@link Float#intBitsToFloat}).
     * Used for color attributes and general int packing; the GPU reads the four bytes as normalised RGBA.
     * Auto-grows the backing array if full.
     */
    @Override
    public void putIntBits(int i) {
        ensureRoomForFloat();
        data[cursor++] = Float.intBitsToFloat(i);
    }

    /**
     * Writes {@code v} directly at absolute index {@code absIndex} without advancing
     * the cursor. The index must be within the already-reserved range
     * ({@code 0 <= absIndex < rawCursor()}).
     *
     * <p>Used by named field writes in {@link CgBufferWriter} to scatter float values
     * into specific offsets within a pre-reserved record.</p>
     *
     * @param absIndex zero-based index into the backing array
     * @param v        the value to write
     * @throws IndexOutOfBoundsException if {@code absIndex} is outside the reserved range
     */
    public void setFloatAt(int absIndex, float v) {
        if (absIndex < 0 || absIndex >= cursor) {
            throw new IndexOutOfBoundsException(
                    "setFloatAt: index " + absIndex + " is out of reserved range [0, " + cursor + ")");
        }
        data[absIndex] = v;
    }

    /**
     * Writes integer bits at absolute index {@code absIndex} without advancing the cursor,
     * reinterpreted as a float via {@link Float#intBitsToFloat(int)}.
     *
     * <p>Used by named integer field writes in {@link CgBufferWriter} (INT, UINT, BOOL,
     * IVEC*, UVEC*, INT64, UINT64). The index must be within the already-reserved range.</p>
     *
     * @param absIndex zero-based index into the backing array
     * @param bits     the raw int bits to store
     * @throws IndexOutOfBoundsException if {@code absIndex} is outside the reserved range
     */
    public void setIntBitsAt(int absIndex, int bits) {
        if (absIndex < 0 || absIndex >= cursor) {
            throw new IndexOutOfBoundsException(
                    "setIntBitsAt: index " + absIndex + " is out of reserved range [0, " + cursor + ")");
        }
        data[absIndex] = Float.intBitsToFloat(bits);
    }

    // ── Capacity management ───────────────────────────────────────────────────

    /**
     * Ensures there is room for one more vertex ({@code floatsPerVertex} more floats).
     * Called by {@link CgVertexWriter#endVertex()} and {@link CgInstanceWriter#endInstance()}
     * after each record to pre-allocate the next slot.
     */
    public void ensureRoomForFloat() {
        if (cursor >= data.length) {
            data = Arrays.copyOf(data, Math.max(data.length * 3 / 2, cursor + 1));
        }
    }
    
    /**
     * Ensures there is room for one more vertex ({@code floatsPerVertex} more floats).
     * Called by {@link CgVertexWriter#endVertex()} and {@link CgInstanceWriter#endInstance()}
     * after each record to pre-allocate the next slot.
     */
    public void ensureRoomForNextVertex() {
        int needed = cursor + floatsPerVertex;
        if (needed > data.length) {
            data = Arrays.copyOf(data, Math.max(data.length * 3 / 2, needed));
        }
    }

    /**
     * Ensures there is room for {@code stride} more floats beyond the current cursor.
     * Called by {@link CgBufferWriter#endRecord} so the writer owns its stride rather
     * than relying on the staging buffer's internal {@code floatsPerVertex}.
     *
     * @param stride number of floats to reserve ahead of the current cursor
     */
    public void ensureRoomForStride(int stride) {
        int needed = cursor + stride;
        if (needed > data.length) {
            data = Arrays.copyOf(data, Math.max(data.length * 3 / 2, needed));
        }
    }

    /**
     * Ensures there is room for {@code vertices} more vertices
     * ({@code vertices * floatsPerVertex} floats).
     *
     * @param vertices number of vertices to pre-allocate
     */
    public void ensureRoomForVertices(int vertices) {
        int needed = cursor + vertices * floatsPerVertex;
        if (needed > data.length) {
            data = Arrays.copyOf(data, Math.max(data.length * 3 / 2, needed));
        }
    }

    /**
     * Convenience wrapper — equivalent to {@link #ensureRoomForVertices}{@code (quads * 4)}.
     *
     * @param quads number of quads (4 vertices each) to pre-allocate
     */
    public void ensureRoomForQuads(int quads) {
        ensureRoomForVertices(quads * 4);
    }

    // ── State ─────────────────────────────────────────────────────────────────

    /** Resets the write cursor to 0 without releasing the backing array. */
    public void reset() { cursor = 0; }

    /** Returns {@code true} if no floats have been written since the last {@link #reset()}. */
    public boolean isEmpty() { return cursor == 0; }

    /**
     * Returns the number of complete vertices written.
     * Only meaningful in vertex/record mode; in flat mode use {@link #rawCursor()} directly.
     */
    public int vertexCount() { return cursor / floatsPerVertex; }

    /** Returns the number of complete quads written (vertex count / 4). */
    public int quadCount() { return vertexCount() / 4; }

    /** Returns the raw write cursor (total floats written since last reset). */
    public int rawCursor() { return cursor; }

    /** Returns the backing float array. Valid until the next growth-triggering write. */
    public float[] rawData() { return data; }

    /** Returns the stride this buffer was constructed with ({@code floatsPerVertex}). */
    public int floatsPerVertex() { return floatsPerVertex; }

    /**
     * Reserves {@code floatCount} float slots at the current cursor, zero-fills them,
     * advances the cursor by {@code floatCount}, and returns the start index of the
     * reserved range.
     *
     * <p>Used by {@link CgBufferWriter#beginRecord()} in format-aware mode to pre-zero
     * an entire record so that unwritten fields default to zero. The returned start
     * index is later passed to {@link #setFloatAt(int, float)} for named field writes.</p>
     *
     * @param floatCount number of float slots to reserve and zero
     * @return the index of the first reserved slot (the pre-advance cursor position)
     */
    public int reserveAndZero(int floatCount) {
        ensureRoomForStride(floatCount);
        int start = cursor;
        // Zero-fill the reserved range
        for (int i = start; i < start + floatCount; i++) {
            data[i] = 0f;
        }
        cursor += floatCount;
        return start;
    }
}
