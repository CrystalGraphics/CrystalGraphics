package com.crystalgraphics.render.mesh;

import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.platform.gl.CgGLImports;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;

import java.util.EnumSet;
import java.util.Set;

/**
 * The GPU storage of one vertex format: one vertex buffer and one 32-bit index buffer, each with an allocator over the
 * format's whole ceiling, so every mesh of the format is drawn from the same two buffers and joins any other. A buffer
 * holds only up to the highest range allocated in it: when a placement passes its end, the next upload makes it a power
 * of two larger and copies the old one in (Unreal's {@code ResizeBufferIfNeeded}, Bevy's growing slabs), and a placed
 * range never moves. Render thread; {@link CgMeshStore} is the only caller.
 *
 * <pre>{@code
 * CgMeshPool pool = new CgMeshPool(CgVertexFormat.SPATIAL, 0);
 * if (pool.place(vertexCount, indexCount, nodes)) {          // nodes[0] vertices, nodes[1] indices; -1 for none
 *     int baseVertex = pool.vertices.offset(nodes[0]);
 * }
 * pool.grow(copies);                                         // at the upload, before anything is copied into it
 * }</pre>
 *
 * <ul>
 *   <li>A buffer a growth replaced is released once its copy is recorded: the device destroys it when the frames that
 *       drew from it have retired.</li>
 *   <li>A format past its ceiling (1 GiB of vertices or of indices) is refused: a second buffer would split its joins.</li>
 * </ul>
 */
final class CgMeshPool {

    /** The most a format holds, in bytes, of vertices and of indices alike. */
    static final long CEILING_BYTES = 1L << 30;
    /** A buffer's first size, in vertices and indices. */
    static final int FIRST_VERTICES = 64 * 1024, FIRST_INDICES = 256 * 1024;

    final CgVertexFormat format;
    /** Its place in the store's list of pools, by which a recorded copy names it. */
    final int index;
    final CgOffsetAllocator vertices, indices;
    /** The buffers, null before the first upload that needs one, and how many vertices and indices each holds. */
    CgGpuBuffer vertexBuffer, indexBuffer;
    int vertexCapacity, indexCapacity;
    /** The highest vertex and index any placement reaches: what the buffers must hold after the next upload. */
    private int vertexEnd, indexEnd;

    CgMeshPool(CgVertexFormat format, int index) {
        this.format = format;
        this.index = index;
        int stride = format.getStride();
        vertices = new CgOffsetAllocator(stride > 0 ? (int) Math.min(CEILING_BYTES / stride, Integer.MAX_VALUE) : 1);
        indices = new CgOffsetAllocator((int) (CEILING_BYTES / 4));
    }

    /**
     * Ranges for {@code vertexCount} vertices and {@code indexCount} indices, their allocator nodes into {@code nodes}
     * (-1 for a count of 0, or for vertices of a format with none). Throws where the format's ceiling is reached.
     */
    void place(int vertexCount, int indexCount, int[] nodes) {
        boolean hasVertices = vertexCount > 0 && format.getStride() > 0;
        int v = hasVertices ? vertices.allocate(vertexCount) : -1;
        int x = indexCount > 0 ? indices.allocate(indexCount) : -1;
        if (hasVertices && v == CgOffsetAllocator.NO_SPACE || indexCount > 0 && x == CgOffsetAllocator.NO_SPACE) {
            if (v >= 0) vertices.free(v);
            if (x >= 0) indices.free(x);
            throw new IllegalStateException(format.getKey() + " holds " + (CEILING_BYTES >> 20) + " MB of vertices and of "
                    + "indices at most: no room for " + vertexCount + " vertices and " + indexCount + " indices");
        }
        if (v >= 0) vertexEnd = Math.max(vertexEnd, vertices.offset(v) + vertexCount);
        if (x >= 0) indexEnd = Math.max(indexEnd, indices.offset(x) + indexCount);
        nodes[0] = v;
        nodes[1] = x;
    }

    /**
     * Makes each buffer large enough for every range placed, adding to {@code grown} the copy of each old buffer into its
     * successor; the old one is the store's to release once that copy is recorded.
     */
    void grow(GrowthCopies grown) {
        int stride = format.getStride();
        if (stride > 0 && vertexEnd > vertexCapacity) {
            int capacity = capacity(vertexCapacity, vertexEnd, FIRST_VERTICES, vertices.size());
            CgGpuBuffer old = vertexBuffer;
            vertexBuffer = buffer("cg_mesh_vertices", (long) capacity * stride, VERTEX_USAGE);
            if (old != null) grown.add(old, vertexBuffer, (long) vertexCapacity * stride);
            vertexCapacity = capacity;
        }
        if (indexEnd > indexCapacity) {
            int capacity = capacity(indexCapacity, indexEnd, FIRST_INDICES, indices.size());
            CgGpuBuffer old = indexBuffer;
            indexBuffer = buffer("cg_mesh_indices", (long) capacity * 4, INDEX_USAGE);
            if (old != null) grown.add(old, indexBuffer, (long) indexCapacity * 4);
            indexCapacity = capacity;
        }
    }

    /** Vertices are pulled as words by the vertex stage; both are written only by copies. */
    static final Set<CgGpuBuffer.Usage> VERTEX_USAGE = EnumSet.of(CgGpuBuffer.Usage.STORAGE,
            CgGpuBuffer.Usage.COPY_SRC, CgGpuBuffer.Usage.COPY_DST);
    static final Set<CgGpuBuffer.Usage> INDEX_USAGE = EnumSet.of(CgGpuBuffer.Usage.INDEX,
            CgGpuBuffer.Usage.COPY_SRC, CgGpuBuffer.Usage.COPY_DST);

    /** The power of two at or past {@code end}, at least {@code first} and double {@code had}, within {@code ceiling}. */
    private static int capacity(int had, int end, int first, int ceiling) {
        long want = Math.max(Math.max(first, (long) had * 2), Long.highestOneBit(Math.max(end - 1, 1)) << 1);
        return (int) Math.min(want, ceiling);
    }

    /** The bytes its buffers hold. */
    long bytes() {
        return (long) vertexCapacity * format.getStride() + (long) indexCapacity * 4;
    }

    void delete() {
        if (vertexBuffer != null) release(vertexBuffer);
        if (indexBuffer != null) release(indexBuffer);
        vertexBuffer = indexBuffer = null;
        vertexCapacity = indexCapacity = 0;
    }

    /** A device-local buffer of {@code bytes}, its contents undefined. */
    static CgGpuBuffer buffer(String label, long bytes, Set<CgGpuBuffer.Usage> usage) {
        return CgRHI.get().createBuffer(new CgGpuBuffer.Desc(label, bytes, usage, false));
    }

    /** Gives {@code buffer} back, destroyed once the frames that may read it have retired. */
    static void release(CgGpuBuffer buffer) {
        if (CgGLImports.installed()) CgGLImports.get().forget(buffer);
        CgRHI.get().release(buffer);
    }

    /** Where {@link #grow} records its copies: the store's list, which it copies before anything else. */
    interface GrowthCopies {
        void add(CgGpuBuffer from, CgGpuBuffer to, long bytes);
    }
}
