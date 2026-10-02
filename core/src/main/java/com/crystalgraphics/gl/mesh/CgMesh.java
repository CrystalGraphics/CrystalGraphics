package com.crystalgraphics.gl.mesh;

import com.crystalgraphics.api.mesh.CgMeshData;
import com.crystalgraphics.api.mesh.CgMeshSource;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gpu.CgDeferral;
import com.crystalgraphics.gl.vertex.CgVertexArray;
import com.crystalgraphics.platform.gl.CgGL;
import lombok.Getter;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A mesh uploaded from {@link CgMeshData}: since mesh rewrite M3 a holder of an {@code api.mesh.CgMesh}, which is what
 * the graph and the world renderer draw, from the mesh store's pools. Goes in M5, when callers build
 * {@code api.mesh.CgMesh} directly.
 *
 * <pre>{@code
 * CgMesh sphere = CgMesh.upload(CgMeshBuilder.uvSphere(CgVertexFormat.SPATIAL, 24, 32, 1f));   // any thread
 * chunks.draw(material.pipeline(CgInstanceKind.OBJECT), snapshot, sphere);                      // drawable this frame
 * }</pre>
 *
 * <ul>
 *   <li>{@link #upload} reads the buffers at once, on any thread; the caller may reuse them after.</li>
 *   <li>{@link #drawDirect()} and {@link #drawInstanced(int)} draw immediately, outside the graph, from GL objects of
 *       this mesh's own, made at the first such draw. Render thread.</li>
 *   <li>{@link #delete()} releases the mesh's pooled copy and those objects; any thread.</li>
 * </ul>
 */
public final class CgMesh implements CgMeshSource {

    @Getter private final CgVertexFormat format;
    @Getter private final CgMeshTopology topology;
    @Getter private final int vertexCount;
    /** Index elements, not bytes; 0 when it has none. */
    @Getter private final int indexCount;
    /** {@code GL_UNSIGNED_SHORT} or {@code GL_UNSIGNED_INT}: how the indices were packed when uploaded. */
    @Getter private final int indexType;

    private final com.crystalgraphics.api.mesh.CgMesh data;
    @Nullable
    private final float[] bounds;

    private int glVertexBuffer, glIndexBuffer, glVao;
    private boolean deleted;
    private final CgDeferral gpu = new CgDeferral();

    private CgMesh(CgVertexFormat format, CgMeshTopology topology, int indexType,
                   com.crystalgraphics.api.mesh.CgMesh data) {
        this.format = format;
        this.topology = topology;
        this.indexType = indexType;
        this.data = data;
        this.vertexCount = data.vertexCount();
        this.indexCount = data.indexCount();
        this.bounds = data.bounds(new float[6]);
    }

    @Override
    public com.crystalgraphics.api.mesh.CgMesh mesh() {
        return data;
    }

    /**
     * Its local bounds as {@code [minX, minY, minZ, maxX, maxY, maxZ]}, from the vertices it was uploaded with; null
     * when its positions are not floats. Shared: do not write it.
     */
    @Nullable
    public float[] bounds() {
        return bounds;
    }

    /**
     * A mesh of {@code vertexData}'s remaining bytes and {@code indexCount} indices, packed 16-bit up to 65535
     * vertices and 32-bit above.
     */
    public static CgMesh upload(CgVertexFormat format, CgMeshTopology topology,
                                ByteBuffer vertexData, ByteBuffer indexData, int indexCount) {
        int vertexCount = vertexData.remaining() / format.getStride();
        int indexType = (vertexCount <= 65535) ? CgGL.GL_UNSIGNED_SHORT : CgGL.GL_UNSIGNED_INT;
        return upload(format, topology, vertexData, indexData, indexCount, indexType);
    }

    /** As {@link #upload(CgVertexFormat, CgMeshTopology, ByteBuffer, ByteBuffer, int)}, the index packing stated. */
    public static CgMesh upload(CgVertexFormat format, CgMeshTopology topology,
                                ByteBuffer vertexData, @Nullable ByteBuffer indexData, int indexCount, int indexType) {
        com.crystalgraphics.api.mesh.CgMesh data = com.crystalgraphics.api.mesh.CgMesh.build(format, m -> m.topology(topology));
        data.writeVertices(0, vertexData);
        if (indexData != null && indexCount > 0) {
            ByteBuffer packed = indexData.duplicate().order(ByteOrder.nativeOrder());
            int at = packed.position();
            int[] indices = new int[indexCount];
            boolean wide = indexType == CgGL.GL_UNSIGNED_INT;
            for (int i = 0; i < indexCount; i++) {
                indices[i] = wide ? packed.getInt(at + i * 4) : packed.getShort(at + i * 2) & 0xFFFF;
            }
            data.writeIndices(0, indices);
        }
        return new CgMesh(format, topology, indexType, data);
    }

    /** {@link #upload(CgVertexFormat, CgMeshTopology, ByteBuffer, ByteBuffer, int)} from {@code data}. */
    public static CgMesh upload(CgMeshData data) {
        return upload(data.format(), data.topology(), data.vertexBuffer(), data.indexBuffer(), data.indexCount());
    }

    /** Draws it once, immediately, outside the graph. Render thread. */
    public void drawDirect() {
        bindForImmediateDraw();
        if (glIndexBuffer != 0) CgGL.glDrawElements(topology.getGlMode(), indexCount, CgGL.GL_UNSIGNED_INT, 0L);
        else CgGL.glDrawArrays(topology.getGlMode(), 0, vertexCount);
        CgVertexArray.bind(0);
    }

    /** Draws it {@code count} times, immediately, with a material bound. Render thread. */
    public void drawInstanced(int count) {
        if (count < 1) throw new IllegalArgumentException("count must be >= 1, got " + count);
        bindForImmediateDraw();
        if (glIndexBuffer != 0) {
            CgGL.glDrawElementsInstanced(topology.getGlMode(), indexCount, CgGL.GL_UNSIGNED_INT, 0L, count);
        } else {
            CgGL.glDrawArraysInstanced(topology.getGlMode(), 0, vertexCount, count);
        }
        CgVertexArray.bind(0);
    }

    private void bindForImmediateDraw() {
        if (deleted) throw new IllegalStateException("CgMesh has been deleted");
        if (glVao == 0) createObjects();
        CgVertexArray.bind(glVao);
        // LWJGL 2 checks an indexed draw's offset against the element binding it saw bound, never the VAO's.
        if (glIndexBuffer != 0) CgGL.glBindBuffer(CgGL.GL_ELEMENT_ARRAY_BUFFER, glIndexBuffer);
    }

    private void createObjects() {
        ByteBuffer vertices = ByteBuffer.allocateDirect(vertexCount * format.getStride()).order(ByteOrder.nativeOrder());
        data.readVertices(0, vertexCount, vertices);
        vertices.flip();
        int vao = CgVertexArray.createRawVaoId();
        CgVertexArray.bind(vao);
        int vbo = CgGL.glGenBuffers();
        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, vbo);
        CgGL.glBufferData(CgGL.GL_ARRAY_BUFFER, vertices, CgGL.GL_STATIC_DRAW);
        for (int i = 0; i < format.getAttributeCount(); i++) {
            CgVertexArray.pointer(i, format.getAttribute(i), format.getStride(), format.getAttribute(i).getOffset());
            CgGL.glEnableVertexAttribArray(i);
        }
        int ibo = 0;
        if (indexCount > 0) {
            ByteBuffer indices = ByteBuffer.allocateDirect(indexCount * 4).order(ByteOrder.nativeOrder());
            data.readIndices(0, indexCount, indices);
            indices.flip();
            ibo = CgGL.glGenBuffers();
            CgGL.glBindBuffer(CgGL.GL_ELEMENT_ARRAY_BUFFER, ibo);   // captured by the bound VAO
            CgGL.glBufferData(CgGL.GL_ELEMENT_ARRAY_BUFFER, indices, CgGL.GL_STATIC_DRAW);
        }
        CgVertexArray.bind(0);
        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, 0);
        glVertexBuffer = vbo;
        glIndexBuffer = ibo;
        glVao = vao;
    }

    /** Releases the pooled copy and any immediate-draw objects. Any thread; idempotent. */
    public void delete() {
        if (deleted) return;
        deleted = true;
        data.release();
        gpu.run(this::releaseObjects);
    }

    private void releaseObjects() {
        if (glVao == 0) return;
        CgVertexArray.deleteRaw(glVao);
        CgGL.glDeleteBuffers(glVertexBuffer);
        if (glIndexBuffer != 0) CgGL.glDeleteBuffers(glIndexBuffer);
        glVao = 0;
    }
}
