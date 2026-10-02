package com.crystalgraphics.vfx.render;

import com.crystalgraphics.api.mesh.CgMeshData;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.buffer.staging.CgVertexWriter;
import com.crystalgraphics.util.CgBufferUtils;

import java.nio.ByteBuffer;

/**
 * A static mesh of {@link #COUNT} ribbons, {@link #SEGMENTS} segments each, that a shader places itself: stateless GPU
 * particles. Each vertex names its ribbon and where it lies along and across it, and the shader hashes the ribbon's index
 * into a path of its own (a streak falling in, an arc of lightning), evaluated from the effect's age. Nothing is
 * simulated and nothing is uploaded per frame.
 *
 * <p>What a vertex carries, in {@code CgVertexFormat.SPATIAL}:</p>
 * <ul>
 *   <li>{@code cg_Normal.x}: the ribbon's index, 0 to {@link #COUNT} - 1. In an attribute, never {@code gl_VertexID}
 *       or {@code gl_InstanceID}, whose bases differ between GL and SPIR-V.</li>
 *   <li>{@code cg_TexCoord0}: (how far along the ribbon, 0 at its tail and 1 at its head; its side, 0 or 1).</li>
 *   <li>{@code cg_Position}: a corner of the unit cube, only so the mesh's bounds are the cube a draw's transform
 *       scales: place every ribbon inside it.</li>
 * </ul>
 * <p>Drawn through {@code CgVfxFrame.ribbons}, with the same per-draw data as {@code CgVfxFrame.mesh}. A shader that
 * wants fewer ribbons collapses the rest.</p>
 */
public final class CgVfxRibbons {

    public static final int COUNT = 96, SEGMENTS = 32;

    private CgVfxRibbons() {
    }

    /** The mesh, for {@code CgVfxSystem} to make: it owns every mesh the engine draws. */
    public static CgMeshData meshData() {
        CgVertexFormat format = CgVertexFormat.SPATIAL;
        int perRibbon = (SEGMENTS + 1) * 2;
        ByteBuffer vbo = CgBufferUtils.createByteBuffer(COUNT * perRibbon * format.getStride());
        CgVertexWriter writer = CgVertexWriter.forBuffer(vbo, format);
        int corner = 0;
        for (int ribbon = 0; ribbon < COUNT; ribbon++) {
            for (int s = 0; s <= SEGMENTS; s++) {
                for (int side = 0; side < 2; side++, corner++) {
                    writer.vertex((corner & 1) == 0 ? -1f : 1f, (corner & 2) == 0 ? -1f : 1f, (corner & 4) == 0 ? -1f : 1f);
                    writer.uv((float) s / SEGMENTS, side);
                    writer.normal(ribbon, 0f, 0f);
                    writer.endVertex();
                }
            }
        }
        vbo.flip();
        int indexCount = COUNT * SEGMENTS * 6;
        ByteBuffer ibo = CgBufferUtils.createByteBuffer(indexCount * 2);
        for (int ribbon = 0; ribbon < COUNT; ribbon++) {
            int base = ribbon * perRibbon;
            for (int s = 0; s < SEGMENTS; s++) {
                int a = base + s * 2, b = a + 1, c = a + 2, d = a + 3;
                ibo.putShort((short) a).putShort((short) b).putShort((short) c);
                ibo.putShort((short) b).putShort((short) d).putShort((short) c);
            }
        }
        ibo.flip();
        return new CgMeshData(format, CgMeshTopology.TRIANGLES, vbo, ibo, indexCount);
    }
}
