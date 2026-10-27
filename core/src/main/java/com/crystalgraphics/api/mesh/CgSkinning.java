package com.crystalgraphics.api.mesh;

import com.crystalgraphics.api.vertex.CgVertexAttribute;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import org.joml.Matrix4fc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * A skinned mesh posed on the CPU: the Java twin of the skinned vertex factory ({@code env/buffer/bones.glsl}), each
 * vertex moved by the weighted sum of its bones' 3×4 transforms (Unreal's {@code ComputeBoneMatrixWithLimitedInfluences}),
 * its normal by the same matrix, renormalised. What a gate compares the GPU's skinning against, and what a tool bakes a
 * pose with; never a frame's path.
 *
 * <pre>{@code
 * Matrix4f[] skinning = ...;                                   // pose(bone) * inverseBind(bone), in the skeleton's order
 * CgMesh posed = CgSkinning.skin(kurama.mesh(), skinning);    // SPATIAL: drawn by any material, no palette
 * world.draw(posed, material).at(x, y, z).submit();
 * }</pre>
 *
 * <ul>
 *   <li>The mesh must be in a skinned format with its CPU copy, and {@code skinning} hold a matrix for every bone a
 *       vertex names.</li>
 *   <li>Colour, UVs and submeshes are kept as stored; a normal skinned to zero keeps its bind-pose normal, as the
 *       shader does.</li>
 * </ul>
 */
public final class CgSkinning {

    private CgSkinning() {
    }

    /** {@code skinned} posed by {@code skinning}, as a new {@link CgVertexFormat#SPATIAL} mesh the caller owns. */
    public static CgMesh skin(CgMesh skinned, Matrix4fc[] skinning) {
        CgVertexFormat from = skinned.format();
        if (!from.isSkinned()) throw new IllegalArgumentException(skinned + " is not in a skinned format");
        CgVertexFormat to = CgVertexFormat.SPATIAL;
        int stride = from.getStride(), outStride = to.getStride();
        int vertices = skinned.vertexCount();
        ByteBuffer src = ByteBuffer.allocate(vertices * stride).order(ByteOrder.nativeOrder());
        skinned.readVertices(0, vertices, src);
        byte[] out = new byte[vertices * outStride];
        ByteBuffer dst = ByteBuffer.wrap(out).order(ByteOrder.nativeOrder());

        CgVertexAttribute position = from.getAttribute(from.indexOf("cg_Position"));
        CgVertexAttribute normal = from.getAttribute(from.indexOf("cg_Normal"));
        CgVertexAttribute indices = from.getAttribute(from.indexOf("cg_BoneIndices"));
        CgVertexAttribute weights = from.getAttribute(from.indexOf("cg_BoneWeights"));
        int positionAt = to.getAttribute(to.indexOf("cg_Position")).getOffset();
        int normalAt = to.getAttribute(to.indexOf("cg_Normal")).getOffset();
        float[] rows = new float[12];
        for (int v = 0; v < vertices; v++) {
            int at = v * stride;
            src.position(at).limit(at + outStride);
            dst.position(v * outStride);
            dst.put(src);            // SPATIAL's attributes lead a skinned vertex, at the same offsets
            src.clear();
            Arrays.fill(rows, 0f);
            for (int k = 0; k < 4; k++) {
                float w = weights.read(src, at, k);
                if (w == 0f) continue;
                Matrix4fc m = skinning[(int) indices.read(src, at, k)];
                rows[0] += w * m.m00(); rows[1] += w * m.m10(); rows[2] += w * m.m20(); rows[3] += w * m.m30();
                rows[4] += w * m.m01(); rows[5] += w * m.m11(); rows[6] += w * m.m21(); rows[7] += w * m.m31();
                rows[8] += w * m.m02(); rows[9] += w * m.m12(); rows[10] += w * m.m22(); rows[11] += w * m.m32();
            }
            float px = position.read(src, at, 0), py = position.read(src, at, 1), pz = position.read(src, at, 2);
            int o = v * outStride;
            dst.putFloat(o + positionAt, rows[0] * px + rows[1] * py + rows[2] * pz + rows[3]);
            dst.putFloat(o + positionAt + 4, rows[4] * px + rows[5] * py + rows[6] * pz + rows[7]);
            dst.putFloat(o + positionAt + 8, rows[8] * px + rows[9] * py + rows[10] * pz + rows[11]);
            float nx = normal.read(src, at, 0), ny = normal.read(src, at, 1), nz = normal.read(src, at, 2);
            float sx = rows[0] * nx + rows[1] * ny + rows[2] * nz;
            float sy = rows[4] * nx + rows[5] * ny + rows[6] * nz;
            float sz = rows[8] * nx + rows[9] * ny + rows[10] * nz;
            float length = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (length > 0f) {
                nx = sx / length;
                ny = sy / length;
                nz = sz / length;
            }
            dst.putShort(o + normalAt, snorm16(nx));
            dst.putShort(o + normalAt + 2, snorm16(ny));
            dst.putShort(o + normalAt + 4, snorm16(nz));
        }

        int indexCount = skinned.indexCount();
        int[] index = new int[indexCount];
        if (indexCount > 0) skinned.readIndices(0, indexCount, index, 0);
        int submeshes = skinned.submeshCount();
        return CgMesh.build(to, w -> {
            for (int s = 0; s < submeshes; s++) {
                CgSubmesh part = skinned.submesh(s);
                if (s > 0) w.submesh();
                w.copyVertices(out, part.firstVertex() * outStride, part.vertexCount());
                for (int i = 0; i < part.indexCount(); i++) w.index(index[part.firstIndex() + i]);
            }
        });
    }

    private static short snorm16(float value) {
        return (short) Math.round(Math.max(-1f, Math.min(1f, value)) * 32767f);
    }
}
