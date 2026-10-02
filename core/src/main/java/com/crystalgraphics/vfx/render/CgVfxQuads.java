package com.crystalgraphics.vfx.render;

import com.crystalgraphics.api.mesh.CgMeshData;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.buffer.staging.CgVertexWriter;
import com.crystalgraphics.util.CgBufferUtils;

import java.nio.ByteBuffer;

/**
 * A static mesh of {@link #COUNT} quads, one per particle, that a shader places from the frame's particle records
 * ({@code #pragma cg_use particle}): quad {@code i} reads record {@code base + i}. Drawn through
 * {@code CgVfxFrame.particles}, one draw per emitter and up to {@link #COUNT} particles a draw.
 *
 * <p>What a vertex carries, in {@code CgVertexFormat.SPATIAL}:</p>
 * <ul>
 *   <li>{@code cg_Normal.x}: its quad's index, 0 to {@link #COUNT} - 1. In an attribute, never {@code gl_VertexID}.</li>
 *   <li>{@code cg_TexCoord0}: its corner, 0..1 on each axis.</li>
 *   <li>{@code cg_Position}: a corner of the unit cube, only so the mesh's bounds are the cube the draw's transform
 *       scales to the particles' bounding box.</li>
 * </ul>
 * <p>A quad past the draw's count collapses to a point in the vertex shader.</p>
 *
 * <pre>{@code
 * // in the vertex shader of a particle material (#pragma cg_use particle)
 * int n = fx_particle_index(cg_Normal.x, CG_OBJECT_CUSTOM0.x, CG_OBJECT_CUSTOM0.y);   // -1 past the count
 * vec3 centre = origin + CG_PARTICLE_POSITION(max(n, 0));
 * vec3 world = fx_particle_corner(centre, cg_TexCoord0 * 2.0 - 1.0, vec2(size), angle, right, up);
 * }</pre>
 *
 * <h3>Vertex pulling, not an instanced unit quad</h3>
 * <p>The GPU does the same work either way: four vertices a particle, each reading its record by index. This
 * shape wins here because:</p>
 * <ul>
 *   <li><b>The world renderer's instancing is already taken.</b> Each {@code CgWorldRenderer} draw is an instance
 *       with its own object record, so a quad instanced per particle is a draw, a record, a sort key and a cull test per
 *       particle every frame, copying what the particle buffer already holds. This mesh is one draw and one record per
 *       {@link #COUNT} particles. A particle {@code CgInstanceKind} would be the graph-native alternative, but that enum
 *       is closed and not worth opening for this.</li>
 *   <li><b>Tiny instances underfill the GPU.</b> Vertices are shaded in batches of 32 to 64; a 4-vertex instance leaves
 *       most of each batch idle on many GPUs, which is why merged geometry or vertex pulling is the usual advice for
 *       quad-heavy work.</li>
 *   <li><b>It costs almost nothing.</b> About 70 KB, built once; the price is a cap of {@link #COUNT} a draw, so a
 *       larger emitter takes several draws.</li>
 * </ul>
 * <p>Instancing or an indirect draw becomes the better tool once counts reach tens of thousands, as with a GPU
 * simulation that decides the count itself.</p>
 */
public final class CgVfxQuads {

    public static final int COUNT = 1024;

    private CgVfxQuads() {
    }

    /** The mesh, for {@code CgVfxSystem} to make: it owns every mesh the engine draws. */
    public static CgMeshData meshData() {
        CgVertexFormat format = CgVertexFormat.SPATIAL;
        ByteBuffer vbo = CgBufferUtils.createByteBuffer(COUNT * 4 * format.getStride());
        CgVertexWriter writer = CgVertexWriter.forBuffer(vbo, format);
        // Opposite corners of the cube across each quad, so the bounds span it on every axis.
        float[][] corners = {{-1f, -1f, -1f, 0f, 0f}, {1f, -1f, 1f, 1f, 0f}, {1f, 1f, -1f, 1f, 1f}, {-1f, 1f, 1f, 0f, 1f}};
        for (int quad = 0; quad < COUNT; quad++) {
            for (float[] c : corners) {
                writer.vertex(c[0], c[1], c[2]);
                writer.uv(c[3], c[4]);
                writer.normal(quad, 0f, 0f);
                writer.endVertex();
            }
        }
        vbo.flip();
        int indexCount = COUNT * 6;
        ByteBuffer ibo = CgBufferUtils.createByteBuffer(indexCount * 2);
        for (int quad = 0; quad < COUNT; quad++) {
            int base = quad * 4;
            ibo.putShort((short) base).putShort((short) (base + 1)).putShort((short) (base + 2));
            ibo.putShort((short) base).putShort((short) (base + 2)).putShort((short) (base + 3));
        }
        ibo.flip();
        return new CgMeshData(format, CgMeshTopology.TRIANGLES, vbo, ibo, indexCount);
    }
}
