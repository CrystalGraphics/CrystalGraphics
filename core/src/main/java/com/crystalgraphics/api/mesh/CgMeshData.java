package com.crystalgraphics.api.mesh;

import com.github.bsideup.jabel.Desugar;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.mesh.CgGltfLoader;
import com.crystalgraphics.gl.mesh.CgMesh;
import com.crystalgraphics.gl.mesh.CgMeshBuilder;
import com.crystalgraphics.gl.mesh.CgObjLoader;

import java.nio.ByteBuffer;

/**
 * CPU-side mesh data: interleaved vertex bytes + optional index bytes.
 *
 * <p>This is a pure data holder with no GL dependencies. Produced by mesh
 * builders ({@link CgMeshBuilder})
 * and loaders ({@link CgObjLoader},
 * {@link CgGltfLoader}), and consumed
 * by {@link CgMesh#upload(CgMeshData)}
 * for GPU upload.</p>
 *
 * <h3>Buffer contracts</h3>
 * <ul>
 *   <li>{@code vertexBuffer} — direct {@link ByteBuffer}, flipped ({@code position=0},
 *       {@code limit=byteCount}). Capacity = {@code vertexCount * format.getStride()}.</li>
 *   <li>{@code indexBuffer} — direct {@link ByteBuffer}, flipped. May be {@code null}
 *       for non-indexed meshes. Element width is u16 or u32 depending on vertex count.</li>
 * </ul>
 *
 * <h3>Index count field</h3>
 * <p>{@code indexCount} is stored explicitly because the element byte width (u16 vs u32)
 * is not yet determined at this stage — deriving the count from {@code indexBuffer.remaining()}
 * would require knowing the width. Use {@link #indexCount ()} directly.</p>
 * @param format  Vertex format describing the attribute layout of {@code vertexBuffer}. 
 * @param topology  Primitive topology for this mesh. 
 * @param vertexBuffer
Interleaved vertex bytes. Flipped: {@code position=0, limit=usedBytes}.
Capacity = {@code vertexCount * format.getStride()}.
 * @param indexBuffer
Index bytes, or {@code null} for non-indexed meshes.
Flipped when non-null.
 * @param indexCount
Number of index elements (not bytes). Zero for non-indexed meshes.

<p>Stored explicitly because the element byte width (u16 vs u32) is not
determined at this stage — deriving the count from buffer remaining would
require knowing the width. Provided by the builder or loader at construction.</p>
 */
@Desugar
public record CgMeshData(CgVertexFormat format, CgMeshTopology topology, ByteBuffer vertexBuffer,
                         ByteBuffer indexBuffer, int indexCount) {

    /**
     * Derives vertex count from buffer remaining and format stride.
     *
     * @return number of vertices in {@code vertexBuffer}
     */
    public int getVertexCount() {
        return vertexBuffer.remaining() / format.getStride();
    }

    /**
     * Uploads the mesh data and generates the CgMesh object.
     *
     * @return generated CgMesh
     */
    public CgMesh upload() {
        return CgMesh.upload(this);
    }
}
