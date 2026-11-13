package com.crystalgraphics.api.mesh;

import com.crystalgraphics.api.vertex.CgVertexAttribute;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.api.vertex.CgVertexSemantic;
import com.github.bsideup.jabel.Desugar;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * A mesh's tangents made when its body gave none: MikkTSpace's per corner of each triangle ({@link CgMikkTSpace}), as
 * Unreal's mesh build makes them by default ({@code FMeshBuildSettings::bUseMikkTSpace}). A vertex whose corners
 * disagree, where a mirrored UV island meets its twin, is split: the copy appended to its own submesh, its indices
 * pointed at it, as Unreal builds a vertex per distinct wedge.
 *
 * <ul>
 *   <li>Lines, points and a mesh with no UV set or normal take a frame about the normal instead (Duff et al.,
 *       "Building an Orthonormal Basis, Revisited"), as the particle factory's did: a tangent nothing samples along.</li>
 *   <li>Run once a body, never a frame's path: a mesh edited every frame writes its own tangents.</li>
 * </ul>
 */
final class CgMeshTangents {

    private CgMeshTangents() {
    }

    /** {@code w}'s tangents, its vertices split where they must be; its submeshes and indices follow. */
    static void make(CgMeshWriter w) {
        CgVertexFormat format = w.format();
        int stride = format.getStride(), n = w.vertexCount;
        CgVertexAttribute position = attribute(format, CgVertexSemantic.POSITION),
                normal = attribute(format, CgVertexSemantic.NORMAL), uv = attribute(format, CgVertexSemantic.UV),
                tangent = attribute(format, CgVertexSemantic.TANGENT);
        ByteBuffer view = ByteBuffer.wrap(w.vertices).order(ByteOrder.nativeOrder());
        float[] positions = new float[n * 3], normals = new float[n * 3], uvs = new float[n * 2];
        for (int v = 0; v < n; v++) {
            int at = v * stride;
            for (int c = 0; c < 3; c++) {
                if (position != null) positions[v * 3 + c] = position.read(view, at, c);
                normals[v * 3 + c] = normal == null ? (c == 1 ? 1f : 0f) : normal.read(view, at, c);
            }
            if (uv != null) {
                uvs[v * 2] = uv.read(view, at, 0);
                uvs[v * 2 + 1] = uv.read(view, at, 1);
            }
        }
        int at = tangent.getOffset();
        if (w.topology != CgMeshTopology.TRIANGLES || uv == null || normal == null || position == null) {
            float[] t = new float[4];
            for (int v = 0; v < n; v++) put(view, v * stride + at, frame(normals, v, t));
            return;
        }

        int[] split = new int[w.submeshCount];
        byte[][] copies = new byte[w.submeshCount][];
        for (int s = 0; s < w.submeshCount; s++) {
            int firstIndex = w.submeshes[s * 4], indexCount = w.submeshes[s * 4 + 1];
            int firstVertex = w.submeshes[s * 4 + 2], vertexCount = w.submeshes[s * 4 + 3];
            Submesh part = new Submesh(w.indices, firstIndex, indexCount, firstVertex, vertexCount, positions, normals, uvs);
            CgMikkTSpace.genTangSpaceDefault(part);
            copies[s] = part.assign(view, stride, at, normals);
            split[s] = part.copies;
        }
        // Each submesh's vertices, then its copies: its vertex range grows, and its indices still count from its first
        int total = n;
        for (int c : split) total += c;
        if (total == n) return;
        byte[] out = new byte[total * stride];
        int outCount = 0;
        for (int s = 0; s < w.submeshCount; s++) {
            int firstVertex = w.submeshes[s * 4 + 2], vertexCount = w.submeshes[s * 4 + 3];
            System.arraycopy(w.vertices, firstVertex * stride, out, outCount * stride, vertexCount * stride);
            System.arraycopy(copies[s], 0, out, (outCount + vertexCount) * stride, split[s] * stride);
            w.submeshes[s * 4 + 2] = outCount;
            w.submeshes[s * 4 + 3] = vertexCount + split[s];
            outCount += vertexCount + split[s];
        }
        w.vertices = out;
        w.vertexCount = total;
    }

    @Nullable
    private static CgVertexAttribute attribute(CgVertexFormat format, CgVertexSemantic semantic) {
        for (int i = 0; i < format.getAttributeCount(); i++) {
            CgVertexAttribute a = format.getAttribute(i);
            if (a.getSemantic() == semantic && a.getSemanticIndex() == 0) return a;
        }
        return null;
    }

    /** A unit tangent perpendicular to vertex {@code v}'s normal, w 1: Duff et al.'s branchless basis. */
    private static float[] frame(float[] normals, int v, float[] out) {
        float x = normals[v * 3], y = normals[v * 3 + 1], z = normals[v * 3 + 2];
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        if (length > 0f) {
            x /= length;
            y /= length;
            z /= length;
        } else {
            y = 1f;
        }
        float s = z >= 0f ? 1f : -1f, a = -1f / (s + z), b = x * y * a;
        out[0] = 1f + s * x * x * a;
        out[1] = s * b;
        out[2] = -s * x;
        out[3] = 1f;
        return out;
    }

    private static short snorm16(float value) {
        return (short) Math.round(Math.max(-1f, Math.min(1f, value)) * 32767f);
    }

    private static void put(ByteBuffer view, int at, float[] t) {
        for (int c = 0; c < 4; c++) view.putShort(at + 2 * c, snorm16(t[c]));
    }

    /** A tangent as stored, its four snorm16s in one long: equal stored tangents, equal keys. */
    private static long key(float[] t) {
        return ((snorm16(t[0]) & 0xFFFFL) << 48) | ((snorm16(t[1]) & 0xFFFFL) << 32) | ((snorm16(t[2]) & 0xFFFFL) << 16)
                | (snorm16(t[3]) & 0xFFFFL);
    }

    @Desugar
    private record Corner(int vertex, long tangent) {}

    /** One submesh as MikkTSpace reads it: its triangles, or its vertices three by three where it has no indices. */
    private static final class Submesh implements CgMikkTSpace.Context {
        final int[] indices;
        final int firstIndex, faces, firstVertex, vertexCount;
        final boolean indexed;
        final float[] positions, normals, uvs;
        /** Each corner's tangent, x y z and sign, from MikkTSpace. */
        final float[] cornerTangents;
        int copies;

        Submesh(int[] indices, int firstIndex, int indexCount, int firstVertex, int vertexCount, float[] positions,
                float[] normals, float[] uvs) {
            this.indices = indices;
            this.firstIndex = firstIndex;
            this.indexed = indexCount > 0;
            this.faces = (indexed ? indexCount : vertexCount) / 3;
            this.firstVertex = firstVertex;
            this.vertexCount = vertexCount;
            this.positions = positions;
            this.normals = normals;
            this.uvs = uvs;
            this.cornerTangents = new float[faces * 3 * 4];
        }

        /** The submesh's own vertex at a corner. */
        int vertex(int face, int vert) {
            return indexed ? indices[firstIndex + face * 3 + vert] : face * 3 + vert;
        }

        @Override
        public int getNumFaces() {
            return faces;
        }

        @Override
        public int getNumVerticesOfFace(int face) {
            return 3;
        }

        @Override
        public void getPosition(float[] out, int face, int vert) {
            int v = (firstVertex + vertex(face, vert)) * 3;
            out[0] = positions[v];
            out[1] = positions[v + 1];
            out[2] = positions[v + 2];
        }

        @Override
        public void getNormal(float[] out, int face, int vert) {
            int v = (firstVertex + vertex(face, vert)) * 3;
            out[0] = normals[v];
            out[1] = normals[v + 1];
            out[2] = normals[v + 2];
        }

        @Override
        public void getTexCoord(float[] out, int face, int vert) {
            int v = (firstVertex + vertex(face, vert)) * 2;
            out[0] = uvs[v];
            out[1] = uvs[v + 1];
        }

        @Override
        public void setTSpaceBasic(float[] tangent, float sign, int face, int vert) {
            // jME passes the reference's sign negated (mikktspace.c: bOrient ? 1 : -1): setTSpace's flag is read instead
        }

        /** The corner's tangent, and its bitangent's sign as {@code mikktspace.c} gives it: 1 where orientation holds. */
        @Override
        public void setTSpace(float[] tangent, float[] biTangent, float magS, float magT, boolean isOrientationPreserving,
                              int face, int vert) {
            int c = (face * 3 + vert) * 4;
            cornerTangents[c] = tangent[0];
            cornerTangents[c + 1] = tangent[1];
            cornerTangents[c + 2] = tangent[2];
            cornerTangents[c + 3] = isOrientationPreserving ? 1f : -1f;
        }

        /**
         * Writes each vertex its first corner's tangent, and each corner that disagrees a copy of its vertex: the
         * copies' bytes, in order, their indices {@code vertexCount} on. A vertex no triangle reaches takes a frame
         * about its normal.
         */
        byte[] assign(ByteBuffer view, int stride, int at, float[] allNormals) {
            long[] assigned = new long[vertexCount];
            boolean[] set = new boolean[vertexCount];
            Map<Corner, Integer> made = new HashMap<>();
            byte[] out = new byte[0];
            float[] t = new float[4];
            for (int face = 0; face < faces; face++) {
                for (int vert = 0; vert < 3; vert++) {
                    int v = vertex(face, vert), c = (face * 3 + vert) * 4;
                    t[0] = cornerTangents[c];
                    t[1] = cornerTangents[c + 1];
                    t[2] = cornerTangents[c + 2];
                    t[3] = cornerTangents[c + 3] < 0f ? -1f : 1f;
                    long k = key(t);
                    if (!set[v]) {
                        set[v] = true;
                        assigned[v] = k;
                        put(view, (firstVertex + v) * stride + at, t);
                        continue;
                    }
                    if (assigned[v] == k) continue;
                    if (!indexed) throw new IllegalStateException("an unindexed corner shares its vertex");
                    Integer copy = made.get(new Corner(v, k));
                    if (copy == null) {
                        copy = vertexCount + copies;
                        made.put(new Corner(v, k), copy);
                        if ((copies + 1) * stride > out.length) out = Arrays.copyOf(out, Math.max(stride * 4, out.length * 2));
                        System.arraycopy(view.array(), (firstVertex + v) * stride, out, copies * stride, stride);
                        ByteBuffer copied = ByteBuffer.wrap(out).order(ByteOrder.nativeOrder());
                        put(copied, copies * stride + at, t);
                        copies++;
                    }
                    indices[firstIndex + face * 3 + vert] = copy;
                }
            }
            for (int v = 0; v < vertexCount; v++) {
                if (!set[v]) put(view, (firstVertex + v) * stride + at, frame(allNormals, firstVertex + v, t));
            }
            return out;
        }
    }
}
