package com.crystalgraphics.vfx.render;

import com.crystalgraphics.api.mesh.CgMeshData;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.buffer.staging.CgVertexWriter;
import com.crystalgraphics.util.CgBufferUtils;

import java.nio.ByteBuffer;

/**
 * A static quad a shader turns to face the eye: one particle, one draw. Each vertex carries its corner in
 * {@code cg_TexCoord0} (0..1 on each axis); its {@code cg_Position} is a corner of the unit cube, only so the mesh's
 * bounds are the cube a draw's transform scales, whichever way the shader turns it. The shader places the corner at
 * the draw's centre, {@code CG_OBJECT_TO_WORLD[3]}, along the eye's right and up, scaled by the transform's length.
 *
 * <pre>{@code
 * vec2 corner = cg_TexCoord0 * 2.0 - 1.0;
 * vec3 right = vec3(cg_ViewMatrix[0][0], cg_ViewMatrix[1][0], cg_ViewMatrix[2][0]);
 * vec3 up = vec3(cg_ViewMatrix[0][1], cg_ViewMatrix[1][1], cg_ViewMatrix[2][1]);
 * vec3 world = CG_OBJECT_TO_WORLD[3].xyz + (right * corner.x + up * corner.y) * length(CG_OBJECT_TO_WORLD[0].xyz);
 * }</pre>
 *
 * <p>Drawn through {@code CgVfxFrame.billboard}.</p>
 */
public final class CgVfxBillboard {

    private CgVfxBillboard() {
    }

    /** The mesh, for {@code CgVfxSystem} to make: it owns every mesh the engine draws. */
    public static CgMeshData meshData() {
        CgVertexFormat format = CgVertexFormat.SPATIAL;
        ByteBuffer vbo = CgBufferUtils.createByteBuffer(4 * format.getStride());
        CgVertexWriter writer = CgVertexWriter.forBuffer(vbo, format);
        // Opposite corners of the cube, so the bounds span it on every axis.
        float[][] corners = {{-1f, -1f, -1f, 0f, 0f}, {1f, -1f, 1f, 1f, 0f}, {1f, 1f, -1f, 1f, 1f}, {-1f, 1f, 1f, 0f, 1f}};
        for (float[] c : corners) {
            writer.vertex(c[0], c[1], c[2]);
            writer.uv(c[3], c[4]);
            writer.normal(0f, 0f, 1f);
            writer.endVertex();
        }
        vbo.flip();
        ByteBuffer ibo = CgBufferUtils.createByteBuffer(6 * 2);
        ibo.putShort((short) 0).putShort((short) 1).putShort((short) 2);
        ibo.putShort((short) 0).putShort((short) 2).putShort((short) 3);
        ibo.flip();
        return new CgMeshData(format, CgMeshTopology.TRIANGLES, vbo, ibo, 6);
    }
}
