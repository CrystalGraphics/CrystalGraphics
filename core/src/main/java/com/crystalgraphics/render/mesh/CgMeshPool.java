package com.crystalgraphics.render.mesh;

import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.vertex.CgVertexArray;
import com.crystalgraphics.platform.gl.CgGL;

import java.util.ArrayList;
import java.util.List;

/**
 * The GPU storage of one vertex format: slabs, each a vertex buffer and a 32-bit index buffer with an allocator for
 * each, and one vertex array over the pair. A slab that fills is joined by another, never resized, so a placed range
 * never moves; a mesh larger than a slab gets one sized to it. Render thread; {@link CgMeshStore} is the only caller.
 *
 * <pre>{@code
 * CgMeshPool pool = new CgMeshPool(CgVertexFormat.SPATIAL);
 * CgMeshPool.Slab slab = pool.place(vertexCount, indexCount, nodes);   // nodes[0] vertices, nodes[1] indices
 * int baseVertex = slab.vertices.offset(nodes[0]);
 * }</pre>
 */
final class CgMeshPool {

    /** A slab's size when no mesh needs more. */
    static final int SLAB_VERTICES = 64 * 1024, SLAB_INDICES = 256 * 1024;

    /** One vertex buffer and one index buffer, with the vertex array that binds both. */
    static final class Slab {
        final int vertexBuffer, indexBuffer, vao;
        final CgOffsetAllocator vertices, indices;
        final long bytes;

        Slab(CgVertexFormat format, int vertexCapacity, int indexCapacity) {
            vertices = new CgOffsetAllocator(vertexCapacity);
            indices = new CgOffsetAllocator(indexCapacity);
            bytes = (long) vertexCapacity * format.getStride() + (long) indexCapacity * 4;
            vao = CgVertexArray.createRawVaoId();
            CgVertexArray.bind(vao);
            vertexBuffer = CgGL.glGenBuffers();
            CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, vertexBuffer);
            CgGL.glBufferData(CgGL.GL_ARRAY_BUFFER, (long) vertexCapacity * format.getStride(), CgGL.GL_STATIC_DRAW);
            for (int i = 0; i < format.getAttributeCount(); i++) {
                CgVertexArray.pointer(i, format.getAttribute(i), format.getStride(), format.getAttribute(i).getOffset());
                CgGL.glEnableVertexAttribArray(i);
            }
            indexBuffer = CgGL.glGenBuffers();
            CgGL.glBindBuffer(CgGL.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);   // captured by the vertex array
            CgGL.glBufferData(CgGL.GL_ELEMENT_ARRAY_BUFFER, (long) indexCapacity * 4, CgGL.GL_STATIC_DRAW);
            CgVertexArray.bind(0);
            CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, 0);
        }

        void delete() {
            CgVertexArray.deleteRaw(vao);
            CgGL.glDeleteBuffers(vertexBuffer);
            CgGL.glDeleteBuffers(indexBuffer);
        }
    }

    final CgVertexFormat format;
    final List<Slab> slabs = new ArrayList<>();

    CgMeshPool(CgVertexFormat format) {
        this.format = format;
    }

    /**
     * A slab with room for both counts, made if none has it; the allocator nodes go into {@code nodes} (-1 for a
     * count of 0).
     */
    Slab place(int vertexCount, int indexCount, int[] nodes) {
        for (int i = 0; i < slabs.size(); i++) {
            Slab slab = slabs.get(i);
            if (tryPlace(slab, vertexCount, indexCount, nodes)) return slab;
        }
        Slab slab = new Slab(format, Math.max(SLAB_VERTICES, CgOffsetAllocator.fittingSize(Math.max(1, vertexCount))),
                Math.max(SLAB_INDICES, CgOffsetAllocator.fittingSize(Math.max(1, indexCount))));
        slabs.add(slab);
        if (!tryPlace(slab, vertexCount, indexCount, nodes)) throw new IllegalStateException("a new slab refused its mesh");
        return slab;
    }

    private static boolean tryPlace(Slab slab, int vertexCount, int indexCount, int[] nodes) {
        int v = vertexCount > 0 ? slab.vertices.allocate(vertexCount) : -1;
        if (vertexCount > 0 && v == CgOffsetAllocator.NO_SPACE) return false;
        int x = indexCount > 0 ? slab.indices.allocate(indexCount) : -1;
        if (indexCount > 0 && x == CgOffsetAllocator.NO_SPACE) {
            if (v >= 0) slab.vertices.free(v);
            return false;
        }
        nodes[0] = v;
        nodes[1] = x;
        return true;
    }

    void delete() {
        for (Slab slab : slabs) slab.delete();
        slabs.clear();
    }
}
