package com.crystalgraphics.gl.mesh;


import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMeshData;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.vertex.CgAttributeFormat;
import com.crystalgraphics.api.vertex.CgVertexAttribute;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.buffer.CgStreamBuffer;
import com.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import com.crystalgraphics.gl.render.CgInstanceRenderer;
import com.crystalgraphics.gl.vertex.CgVertexArray;
import com.crystalgraphics.gl.vertex.CgVertexArrayRegistry;
import com.crystalgraphics.platform.gl.CgGL;
import java.nio.ByteBuffer;
import lombok.Getter;

/**
 * Immutable static GPU mesh: owns a VBO, an optional IBO, and a standalone VAO.
 *
 * <p>Unlike the streaming batch path, a {@code CgMesh} uses {@code GL_STATIC_DRAW}
 * raw GL buffers — not {@link CgStreamBuffer}.
 * It is suitable for geometry that is uploaded once and drawn many times.</p>
 *
 * <h3>VAO ownership</h3>
 * <p>Each {@code CgMesh} owns a standalone VAO for non-instanced draws. For instanced
 * draws, a separate VAO combining this mesh's VBO with an instance stream buffer would
 * be used (not implemented here — that is the responsibility of the instanced binding layer).</p>
 *
 * <h3>Attribute setup</h3>
 * <p>The attribute pointer loop in {@link #upload(CgVertexFormat, CgMeshTopology, ByteBuffer, ByteBuffer, int)}
 * uses the {@link CgAttributeFormat} interface for the attribute iteration, enabling
 * reuse of the same setup logic for base and instance layouts.</p>
 *
 * <h3>IBO binding order (critical)</h3>
 * <p>The VAO captures the IBO binding via the element array buffer bind target.
 * The IBO must be bound <em>while the VAO is bound</em>, and the VAO must be
     * unbound <em>before</em> the IBO is unbound. Unbinding the IBO while the VAO
     * is still bound would write null into the VAO's element array buffer slot.</p>
 *
 * <h3>Lifetime</h3>
 * <p>Call {@link #delete()} only when no rendering code still references this mesh.
 * Deleting the mesh while VAO bindings in registries still reference its buffers
 * leaves stale GPU state pointing at deleted resources.</p>
 */
public final class CgMesh {

    /** Vertex format describing the per-vertex attribute layout of the VBO. */
    @Getter private final CgVertexFormat format;

    /** Primitive topology used for draw calls. */
    @Getter private final CgMeshTopology topology;

    /** Raw GL buffer id for vertex data ({@code GL_STATIC_DRAW}). */
    @Getter private final int glVertexBuffer;

    /** Raw GL buffer id for index data, or {@code 0} for non-indexed. */
    @Getter private final int glIndexBuffer;

    /** Standalone VAO id for non-instanced {@link #drawDirect()} calls. */
    @Getter private final int glVao;

    /** Number of vertices in the VBO. */
    @Getter private final int vertexCount;

    /** Number of index elements (not bytes). {@code 0} for non-indexed. */
    @Getter private final int indexCount;

    /**
     * GL index type: {@code GL_UNSIGNED_SHORT} or {@code GL_UNSIGNED_INT},
     * auto-selected based on vertex count.
     */
    @Getter private final int indexType;

    /** Whether {@link #delete()} has been called. */
    private boolean deleted;

    private CgMesh(CgVertexFormat format, CgMeshTopology topology,
                   int glVertexBuffer, int glIndexBuffer, int glVao,
                   int vertexCount, int indexCount, int indexType) {
        this.format = format;
        this.topology = topology;
        this.glVertexBuffer = glVertexBuffer;
        this.glIndexBuffer = glIndexBuffer;
        this.glVao = glVao;
        this.vertexCount = vertexCount;
        this.indexCount = indexCount;
        this.indexType = indexType;
    }

    /**
     * Uploads vertex and index data to the GPU and returns a new {@code CgMesh}.
     *
     * <p><strong>Must be called on the GL thread.</strong></p>
     *
     * <h3>Index type inference</h3>
     * <p>Index type is auto-detected from the vertex count: {@code GL_UNSIGNED_SHORT} if
     * {@code vertexCount ≤ 65535}, else {@code GL_UNSIGNED_INT}. The caller is responsible
     * for ensuring the index buffer was packed with matching element width. When in doubt,
     * use the explicit-index-type overload:
     * {@link #upload(CgVertexFormat, CgMeshTopology, ByteBuffer, ByteBuffer, int, int)}.</p>
     *
     * <h3>VAO / IBO binding invariant</h3>
     * <p>The IBO is bound while the VAO is bound so it is captured into VAO state.
      * The VAO is unbound <em>first</em> before the IBO is unbound — unbinding the IBO
     * while the VAO is still active would write null into the VAO's element array
     * buffer slot and silently break indexed draws.</p>
     *
     * @param format      vertex format describing the per-vertex attribute layout
     * @param topology    primitive topology
     * @param vertexData  flipped direct {@code ByteBuffer} of interleaved vertex data
     * @param indexData   flipped direct {@code ByteBuffer} of index data, or {@code null}
     * @param indexCount  number of index elements (not bytes); {@code 0} if {@code indexData} is {@code null}
     * @return a new GPU-resident mesh
     */
    public static CgMesh upload(CgVertexFormat format, CgMeshTopology topology,
                                 ByteBuffer vertexData, ByteBuffer indexData, int indexCount) {
        int vertexCount = vertexData.remaining() / format.getStride();
        int indexType = (vertexCount <= 65535) ? CgGL.GL_UNSIGNED_SHORT : CgGL.GL_UNSIGNED_INT;
        return upload(format, topology, vertexData, indexData, indexCount, indexType);
    }

    /**
     * Uploads vertex and index data with an explicit index type, bypassing the auto-detection.
     *
     * <p>Use this overload when you have pre-packed index data and need to specify
     * {@code GL_UNSIGNED_SHORT} or {@code GL_UNSIGNED_INT} explicitly, independent of
     * the vertex count heuristic used by the auto-detecting overload.</p>
     *
     * <p><strong>Must be called on the GL thread.</strong></p>
     *
     * @param format      vertex format describing the per-vertex attribute layout
     * @param topology    primitive topology
     * @param vertexData  flipped direct {@code ByteBuffer} of interleaved vertex data
     * @param indexData   flipped direct {@code ByteBuffer} of index data, or {@code null}
     * @param indexCount  number of index elements (not bytes); {@code 0} if {@code indexData} is {@code null}
     * @param indexType   {@code GL_UNSIGNED_SHORT} (5123) or {@code GL_UNSIGNED_INT} (5125)
     * @return a new GPU-resident mesh
     */
    public static CgMesh upload(CgVertexFormat format, CgMeshTopology topology,
                                 ByteBuffer vertexData, ByteBuffer indexData, int indexCount, int indexType) {
        try {
            if (!CgGL.isContextCurrent()) {
                throw new IllegalStateException("CgMesh.upload() must be called on the OpenGL thread");
            }
        } catch (org.lwjgl.LWJGLException e) {
            throw new IllegalStateException("CgMesh.upload() GL thread check failed", e);
        }
        int vertexCount = vertexData.remaining() / format.getStride();

        // ── Upload VBO ────────────────────────────────────────────────────
        int vbo = CgGL.glGenBuffers();
        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, vbo);
        CgGL.glBufferData(CgGL.GL_ARRAY_BUFFER, vertexData, CgGL.GL_STATIC_DRAW);

        // ── Upload IBO (optional) ─────────────────────────────────────────
        int ibo = 0;
        if (indexData != null) {
            ibo = CgGL.glGenBuffers();
            CgGL.glBindBuffer(CgGL.GL_ELEMENT_ARRAY_BUFFER, ibo);
            CgGL.glBufferData(CgGL.GL_ELEMENT_ARRAY_BUFFER, indexData, CgGL.GL_STATIC_DRAW);
        }

        // ── Create VAO and configure attribute pointers ───────────────────
        int vao = CgVertexArray.createRawVaoId();
        CgVertexArray.bind(vao);

        // VBO is already bound from the upload step above.
        // Attribute pointer loop via CgAttributeFormat interface
        CgAttributeFormat layout = format;
        for (int i = 0; i < layout.getAttributeCount(); i++) {
            CgVertexAttribute attr = layout.getAttribute(i);
            CgGL.glVertexAttribPointer(
                    i,
                    attr.getComponents(),
                    attr.getType().getGlConstant(),
                    attr.isNormalized(),
                    layout.getStride(),
                    attr.getOffset()
            );
            CgGL.glEnableVertexAttribArray(i);
        }

        // Capture IBO into VAO state.
        // CRITICAL: the IBO bind must happen while the VAO is bound so the VAO
        // records the element array buffer reference. Do NOT unbind the IBO
        // while the VAO is still bound — that would write null into the VAO's
        // element array buffer slot and silently break all indexed draws.
        if (ibo != 0) {
            CgGL.glBindBuffer(CgGL.GL_ELEMENT_ARRAY_BUFFER, ibo);
        }

        // ── Unbind in safe order ──────────────────────────────────────────
        // Unbind VAO FIRST, then clean up other bindings.
        // Only safe to unbind the IBO after the VAO is unbound.
        CgVertexArray.bind(0);
        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, 0);
        if (ibo != 0) {
            CgGL.glBindBuffer(CgGL.GL_ELEMENT_ARRAY_BUFFER, 0);
        }

        return new CgMesh(format, topology, vbo, ibo, vao, vertexCount, indexCount, indexType);
    }

    /**
     * Convenience overload: uploads directly from a {@link CgMeshData}.
     *
     * <p><strong>Must be called on the GL thread.</strong></p>
     *
     * @param data the CPU-side mesh data to upload
     * @return a new GPU-resident mesh
     */
    public static CgMesh upload(CgMeshData data) {
        return upload(data.format(), data.topology(),
                data.vertexBuffer(), data.indexBuffer(), data.indexCount());
    }

    /**
     * Draws this mesh directly (non-instanced) using the standalone VAO.
     *
     * <p><strong>Must be called on the GL thread.</strong></p>
     *
     * @throws IllegalStateException if {@link #delete()} has already been called
     */
    public void drawDirect() {
        if (deleted) throw new IllegalStateException("CgMesh has been deleted");
        
        CgVertexArray.bind(glVao);
        if (glIndexBuffer != 0) 
            CgGL.glDrawElements(topology.getGlMode(), indexCount, indexType, 0L);
        else CgGL.glDrawArrays(topology.getGlMode(), 0, vertexCount);
        
        CgVertexArray.bind(0);
    }

    /**
     * Draws this mesh instanced using the same standalone VAO as {@link #drawDirect()}.
     *
     * <p>Prerequisite: {@link CgMaterial#bind}
     * must have been called before this method, which binds the
     * {@link CgShaderBuffer} containing
     * instance transforms.</p>
     *
     * <p>The instance count must not exceed the capacity of the bound
     * {@code CgShaderBuffer}. If it does, {@code CgShaderBuffer.bind()} will have already thrown.</p>
     *
     * <p>No {@code glVertexAttribDivisor} is used — the SSBO/TBO mechanism provides
     * per-instance data via {@code CG_INSTANCE_ID}, so no per-instance vertex attributes
     * are needed. The VAO is exactly the same as for {@link #drawDirect()}.</p>
     *
     * <p><strong>Must be called on the GL thread.</strong></p>
     *
     * @param count number of instances to draw; must be {@code >= 1}
     * @throws IllegalStateException    if {@link #delete()} has been called
     * @throws IllegalArgumentException if {@code count < 1}
     */
    public void drawInstanced(int count) {
        if (deleted) throw new IllegalStateException("CgMesh has been deleted");
        if (count < 1) throw new IllegalArgumentException("count must be >= 1, got " + count);

        CgVertexArray.bind(glVao);
        if (glIndexBuffer != 0)
            CgInstanceRenderer.drawElementsInstanced(topology.getGlMode(), indexCount, indexType, 0L, count);
        else CgInstanceRenderer.drawArraysInstanced(topology.getGlMode(), 0, vertexCount, count);
        
        CgVertexArray.bind(0);
    }

    /**
     * Deletes all GPU resources owned by this mesh: the VAO, the VBO, and the IBO (if any).
     *
     * <p><strong>Must be called on the GL thread.</strong></p>
     *
     * <p>Calling this method while any rendering code still holds a reference to this
     * mesh instance leaves stale GPU state. Ensure all references are dropped before
     * calling {@code delete()}.</p>
     *
     * <p>This method is idempotent — calling it multiple times has no additional effect.</p>
     */
    public void delete() {
        if (deleted) return;
        deleted = true;
        // Invalidate any instanced VAOs in the registry that reference this mesh's VBO/IBO,
        // so they don't linger as stale GPU state pointing at deleted buffer objects.
        CgVertexArrayRegistry.get().invalidateMeshBindings(this);
        CgVertexArray.deleteRaw(glVao);
        CgGL.glDeleteBuffers(glVertexBuffer);
        if (glIndexBuffer != 0) {
            CgGL.glDeleteBuffers(glIndexBuffer);
        }
    }
}
