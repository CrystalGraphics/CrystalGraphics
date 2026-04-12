package io.github.somehussar.crystalgraphics.gl.buffer.staging;

import java.util.Arrays;

/**
 * CPU-side vertex staging buffer: a growable {@code float[]} with a write cursor.
 *
 * <p>This is a <strong>pure data structure</strong> with no GL dependencies, no topology
 * awareness, and no semantic knowledge. It stores raw float values written by
 * {@link CgVertexWriter} and is read by {@link CgBatchRenderer} during GPU upload.</p>
 *
 * <h3>Growth Strategy</h3>
 * <p>Two capacity-ensure paths are provided:</p>
 * <ul>
 *   <li>{@link #ensureRoomForNextVertex()} — grows by 1 vertex; called automatically
 *       by {@code CgVertexWriter.endVertex()} after each vertex is written.</li>
 *   <li>{@link #ensureRoomForQuads(int)} — bulk pre-allocation for known burst sizes;
 *       optional optimization that callers may use before submitting a batch of quads.</li>
 * </ul>
 * <p>Both use a 1.5× growth factor (with a minimum of the requested size).</p>
 *
 * <h3>Color Packing</h3>
 * <p>{@link #putColorPacked(int)} stores an ABGR-packed integer as a float via
 * {@link Float#intBitsToFloat(int)}. This matches the GPU's expected layout when
 * the attribute is declared as {@code GL_UNSIGNED_BYTE} with 4 components and
 * normalization enabled.</p>
 */
public final class CgStagingBuffer {

    private float[] data;
    private int cursor;
    private final int floatsPerVertex;

    public CgStagingBuffer(int floatsPerVertex, int initialCapacityQuads) {
        this.floatsPerVertex = floatsPerVertex;
        this.data = new float[initialCapacityQuads * 4 * floatsPerVertex];
    }

    public void putFloat(float v) {
        data[cursor++] = v;
    }

    public void putColorPacked(int abgr) {
        data[cursor++] = Float.intBitsToFloat(abgr);
    }

    public void ensureRoomForNextVertex() {
        int needed = cursor + floatsPerVertex;
        if (needed > data.length) {
            data = Arrays.copyOf(data, Math.max(data.length * 3 / 2, needed));
        }
    }

    public void ensureRoomForVertices(int vertices) {
        int needed = cursor + vertices * floatsPerVertex;
        if (needed > data.length) {
            data = Arrays.copyOf(data, Math.max(data.length * 3 / 2, needed));
        }
    }

    public void ensureRoomForQuads(int quads) {
        ensureRoomForVertices(quads * 4);
    }

    public void reset() { cursor = 0; }
    public boolean isEmpty() { return cursor == 0; }
    public int vertexCount() { return cursor / floatsPerVertex; }
    public int quadCount() { return vertexCount() / 4; }
    public int rawCursor() { return cursor; }
    public float[] rawData() { return data; }
    public int floatsPerVertex() { return floatsPerVertex; }
}
