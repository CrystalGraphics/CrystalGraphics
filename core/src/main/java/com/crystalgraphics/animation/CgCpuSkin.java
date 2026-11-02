package com.crystalgraphics.animation;

import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.vertex.CgVertexAttribute;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.api.vertex.CgVertexSemantic;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * An instance's skinning done on the CPU, for a host that must see the posed vertices: Unreal's
 * {@code FSkeletalMeshObjectCPUSkin} ({@code SkeletalRenderCPUSkin.cpp}, {@code SkinVertexSection}): each vertex's bone
 * matrices blended by its weights, the position through the blend, the normal through its 3×3 and normalised. Its
 * triangles leave as quads, a pair written {@code a b c, c d a} as one, any other as {@code a b c c}.
 */
final class CgCpuSkin {

    /** A mesh's vertices decoded once, by mesh, while its revision holds. */
    private static final Map<CgMesh, Source> SOURCES = Collections.synchronizedMap(new WeakHashMap<CgMesh, Source>());

    private float[] bones = new float[0];
    private final Matrix4f m = new Matrix4f();

    void skin(CgMesh mesh, int submesh, Matrix4f[] skinning, Matrix4fc placement, CgPosedVertexSink sink) {
        Source s = source(mesh);
        int n = skinning.length;
        if (bones.length < n * 12) bones = new float[n * 12];
        for (int b = 0; b < n; b++) {
            placement.mul(skinning[b], m);
            int o = b * 12;
            bones[o] = m.m00(); bones[o + 1] = m.m01(); bones[o + 2] = m.m02();
            bones[o + 3] = m.m10(); bones[o + 4] = m.m11(); bones[o + 5] = m.m12();
            bones[o + 6] = m.m20(); bones[o + 7] = m.m21(); bones[o + 8] = m.m22();
            bones[o + 9] = m.m30(); bones[o + 10] = m.m31(); bones[o + 11] = m.m32();
        }
        int first = s.submeshes[submesh * 4], count = s.submeshes[submesh * 4 + 1], base = s.submeshes[submesh * 4 + 2];
        int[] ix = s.indices;
        int end = first + count - count % 3;
        for (int i = first; i < end; ) {
            int a = base + ix[i], b = base + ix[i + 1], c = base + ix[i + 2];
            if (i + 6 <= end && base + ix[i + 3] == c && base + ix[i + 5] == a) {
                int d = base + ix[i + 4];
                vertex(s, a, sink);
                vertex(s, b, sink);
                vertex(s, c, sink);
                vertex(s, d, sink);
                i += 6;
            } else {
                vertex(s, a, sink);
                vertex(s, b, sink);
                vertex(s, c, sink);
                vertex(s, c, sink);
                i += 3;
            }
        }
    }

    private void vertex(Source s, int v, CgPosedVertexSink sink) {
        float m00 = 0, m01 = 0, m02 = 0, m10 = 0, m11 = 0, m12 = 0, m20 = 0, m21 = 0, m22 = 0, m30 = 0, m31 = 0, m32 = 0;
        for (int k = 0; k < 4; k++) {
            float w = s.weights[v * 4 + k];
            if (w == 0f) continue;
            int o = s.bones[v * 4 + k] * 12;
            float[] b = bones;
            m00 += b[o] * w; m01 += b[o + 1] * w; m02 += b[o + 2] * w;
            m10 += b[o + 3] * w; m11 += b[o + 4] * w; m12 += b[o + 5] * w;
            m20 += b[o + 6] * w; m21 += b[o + 7] * w; m22 += b[o + 8] * w;
            m30 += b[o + 9] * w; m31 += b[o + 10] * w; m32 += b[o + 11] * w;
        }
        float px = s.positions[v * 3], py = s.positions[v * 3 + 1], pz = s.positions[v * 3 + 2];
        float qx = s.normals[v * 3], qy = s.normals[v * 3 + 1], qz = s.normals[v * 3 + 2];
        float nx = m00 * qx + m10 * qy + m20 * qz, ny = m01 * qx + m11 * qy + m21 * qz, nz = m02 * qx + m12 * qy + m22 * qz;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 0f) {
            nx /= len;
            ny /= len;
            nz /= len;
        }
        sink.vertex(m00 * px + m10 * py + m20 * pz + m30, m01 * px + m11 * py + m21 * pz + m31,
                m02 * px + m12 * py + m22 * pz + m32, s.uvs[v * 2], s.uvs[v * 2 + 1], s.colours[v], nx, ny, nz);
    }

    private static Source source(CgMesh mesh) {
        int revision = mesh.revision();
        Source s = SOURCES.get(mesh);
        if (s == null || s.revision != revision) {
            s = new Source(mesh, revision);
            SOURCES.put(mesh, s);
        }
        return s;
    }

    /** One mesh's vertices as floats, read once from its CPU copy. */
    private static final class Source {
        final int revision;
        final float[] positions, normals, uvs, weights;
        final int[] colours, bones, indices, submeshes;

        Source(CgMesh mesh, int revision) {
            this.revision = revision;
            CgVertexFormat format = mesh.format();
            if (!format.isSkinned()) throw new IllegalArgumentException(mesh + " is not skinned: " + format);
            int n = mesh.vertexCount();
            int stride = format.getStride();
            ByteBuffer bytes = ByteBuffer.allocate(n * stride).order(ByteOrder.nativeOrder());
            mesh.readVertices(0, n, bytes);
            positions = new float[n * 3];
            normals = new float[n * 3];
            uvs = new float[n * 2];
            weights = new float[n * 4];
            colours = new int[n];
            bones = new int[n * 4];
            CgVertexAttribute position = attribute(format, CgVertexSemantic.POSITION, 0);
            CgVertexAttribute normal = attribute(format, CgVertexSemantic.NORMAL, 0);
            CgVertexAttribute uv = attribute(format, CgVertexSemantic.UV, 0);
            CgVertexAttribute colour = attribute(format, CgVertexSemantic.COLOR, 0);
            CgVertexAttribute index = attribute(format, CgVertexSemantic.BONE_INDICES, 0);
            CgVertexAttribute weight = attribute(format, CgVertexSemantic.BONE_WEIGHTS, 0);
            for (int v = 0; v < n; v++) {
                int at = v * stride;
                for (int c = 0; c < 3; c++) {
                    positions[v * 3 + c] = position.read(bytes, at, c);
                    normals[v * 3 + c] = normal.read(bytes, at, c);
                }
                uvs[v * 2] = uv.read(bytes, at, 0);
                uvs[v * 2 + 1] = uv.read(bytes, at, 1);
                int argb = 0;
                for (int c = 0; c < 4; c++) {
                    int channel = Math.round(colour.read(bytes, at, c) * 255f) & 0xFF;
                    argb |= c == 3 ? channel << 24 : channel << (16 - 8 * c);
                    bones[v * 4 + c] = (int) index.read(bytes, at, c);
                    weights[v * 4 + c] = weight.read(bytes, at, c);
                }
                colours[v] = argb;
            }
            indices = new int[mesh.indexCount()];
            mesh.readIndices(0, indices.length, indices, 0);
            int count = mesh.submeshCount();
            submeshes = new int[count * 4];
            int[] one = new int[4];
            for (int i = 0; i < count; i++) System.arraycopy(mesh.submesh(i, one), 0, submeshes, i * 4, 4);
        }

        private static CgVertexAttribute attribute(CgVertexFormat format, CgVertexSemantic semantic, int index) {
            for (int i = 0; i < format.getAttributeCount(); i++) {
                CgVertexAttribute a = format.getAttribute(i);
                if (a.getSemantic() == semantic && a.getSemanticIndex() == index) return a;
            }
            throw new IllegalArgumentException(format + " has no " + semantic);
        }
    }
}
