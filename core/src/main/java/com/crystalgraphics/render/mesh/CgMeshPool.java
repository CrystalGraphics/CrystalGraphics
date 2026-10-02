package com.crystalgraphics.render.mesh;

import com.crystalgraphics.api.vertex.CgVertexAttribute;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.platform.gl.CgGL;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    private static final Logger LOGGER = LogManager.getLogger("CgMeshPool");

    /** Vertex array names this process holds. */
    private static final Set<Integer> LIVE_VERTEX_ARRAYS = new HashSet<>();

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
            vao = genVertexArray();
            CgGL.glBindVertexArray(vao);
            if (format.getStride() > 0) {
                vertexBuffer = CgGL.glGenBuffers();
                CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, vertexBuffer);
                CgGL.glBufferData(CgGL.GL_ARRAY_BUFFER, (long) vertexCapacity * format.getStride(), CgGL.GL_STATIC_DRAW);
                for (int i = 0; i < format.getAttributeCount(); i++) {
                    pointer(i, format.getAttribute(i), format.getStride(), format.getAttribute(i).getOffset());
                    CgGL.glEnableVertexAttribArray(i);
                }
            } else {
                vertexBuffer = 0;   // CgVertexFormat.NONE: the shader places every vertex
            }
            indexBuffer = CgGL.glGenBuffers();
            CgGL.glBindBuffer(CgGL.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);   // captured by the vertex array
            CgGL.glBufferData(CgGL.GL_ELEMENT_ARRAY_BUFFER, (long) indexCapacity * 4, CgGL.GL_STATIC_DRAW);
            CgGL.glBindVertexArray(0);
            CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, 0);
        }

        void delete() {
            LIVE_VERTEX_ARRAYS.remove(vao);
            CgGL.glDeleteVertexArrays(vao);
            if (vertexBuffer != 0) CgGL.glDeleteBuffers(vertexBuffer);
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

    private boolean tryPlace(Slab slab, int vertexCount, int indexCount, int[] nodes) {
        // A mesh with no vertex bytes takes no vertex range: its base vertex is 0.
        boolean vertices = vertexCount > 0 && format.getStride() > 0;
        int v = vertices ? slab.vertices.allocate(vertexCount) : -1;
        if (vertices && v == CgOffsetAllocator.NO_SPACE) return false;
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

    /** Forgets every vertex array name: the context that owned them is gone. */
    static void forgetVertexArrays() {
        LIVE_VERTEX_ARRAYS.clear();
    }

    private static int genVertexArray() {
        int id = CgGL.glGenVertexArrays();
        if (!LIVE_VERTEX_ARRAYS.add(id)) {
            // A name this process still holds means a second context: vertex arrays are not shared between contexts,
            // so the second owner reconfigures the first's, whose draws then rasterise nothing and raise no error.
            // On 1.7.10 the second context is FML's splash screen, which is why no GL work may run during mod loading.
            LOGGER.warn("[cg-vao] glGenVertexArrays returned {}, which this process already owns. "
                    + "Two contexts are in play and one vertex array now has two owners.", id);
        }
        return id;
    }

    /** Points attribute {@code index} at the bound array buffer: an integer attribute through {@code glVertexAttribIPointer}. */
    private static void pointer(int index, CgVertexAttribute attr, int stride, long offset) {
        int type = attr.getType().getGlConstant();
        if (attr.isInteger()) CgGL.glVertexAttribIPointer(index, attr.getComponents(), type, stride, offset);
        else CgGL.glVertexAttribPointer(index, attr.getComponents(), type, attr.isNormalized(), stride, offset);
    }
}
