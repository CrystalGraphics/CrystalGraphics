package com.crystalgraphics.vfx.particle.gpu.sim;

import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.mesh.CgSubmesh;
import com.crystalgraphics.api.vertex.CgAttribType;
import com.crystalgraphics.api.vertex.CgVertexAttribute;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.api.vertex.CgVertexSemantic;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgDispatch;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgRecording;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The meshes emitters spawn on, in one buffer every Step kernel spawning on a mesh reads ({@code MESHES}): each mesh's
 * triangles with Vose's alias table over their areas, so a triangle is picked by area in one draw, then its vertices
 * with their normals. A mesh's section is fixed once added and kept for the run, since emitters' parameter rows name it
 * by place. Any thread adds; the render thread uploads.
 *
 * <pre>{@code
 * CgVfxSpawnMeshes.Section s = CgVfxSpawnMeshes.of(mesh);   // where its triangles and vertices sit
 * CgVfxSpawnMeshes.upload(recording);                       // what was added since the last upload
 * CgVfxSpawnMeshes.bind(dispatch);                          // each Step spawning on a mesh
 * }</pre>
 *
 * <ul>
 *   <li>A mesh is read once, as it is when first added: an edit after that is not seen.</li>
 *   <li>Triangle lists only; positions must be floats. The vertices' normals are the mesh's own where its format has
 *       them as floats, else its faces' summed by area.</li>
 * </ul>
 */
public final class CgVfxSpawnMeshes {

    /** uvec4s a triangle takes: {@code (a, its share of a column)}, {@code (b, its alias)}, {@code (c, 0)}. */
    public static final int TRIANGLE_VECTORS = 3;
    /** uvec4s a vertex takes: {@code (position, 0)}, {@code (normal, 0)}. */
    public static final int VERTEX_VECTORS = 2;

    /**
     * A mesh's place in the buffer, in uvec4s: its first triangle and how many, its first vertex and how many, and the
     * farthest it reaches from its origin.
     */
    public record Section(int firstTriangle, int triangles, int firstVertex, int vertices, float radius) {
    }

    private static final Map<CgMesh, Section> SECTIONS = new IdentityHashMap<>();
    private static int[] words = new int[0];
    private static int end, uploaded;
    private static CgGraphBuffer buffer;
    private static ByteBuffer staging = ByteBuffer.allocate(0);

    private CgVfxSpawnMeshes() {
    }

    /** {@code mesh}'s section, read and added the first time it is asked. */
    public static synchronized Section of(CgMesh mesh) {
        Section section = SECTIONS.get(mesh);
        if (section == null) SECTIONS.put(mesh, section = add(mesh));
        return section;
    }

    /** Uploads what was added since the last upload, growing the buffer as it needs. Render thread. */
    static synchronized void upload(CgRecording recording) {
        long bytes = Math.max(256L, end * 4L);
        int from = uploaded;
        if (buffer == null || buffer.size() < bytes) {
            if (buffer != null) recording.release(buffer);
            buffer = CgGraphBuffer.persistent("vfx.pool.meshes", CgBufferDesc.of(Long.highestOneBit(bytes - 1) << 1,
                    CgBufferUsage.STORAGE, CgBufferUsage.COPY));
            from = 0;
        }
        if (from == end) return;
        int n = end - from;
        if (staging.capacity() < n * 4) staging = ByteBuffer.allocate(n * 4).order(ByteOrder.nativeOrder());
        staging.clear();
        for (int i = 0; i < n; i++) staging.putInt(i * 4, words[from + i]);
        staging.limit(n * 4);
        recording.update(buffer, from * 4L, staging);
        uploaded = end;
    }

    static synchronized void bind(CgDispatch d) {
        d.bind("MESHES", buffer);
    }

    /** A dying context took the buffer: everything is uploaded again into the next one. */
    static synchronized void forget() {
        buffer = null;
        uploaded = 0;
    }

    private static Section add(CgMesh mesh) {
        if (mesh.topology() != CgMeshTopology.TRIANGLES) {
            throw new IllegalArgumentException("particles spawn on a mesh of triangles, not " + mesh.topology());
        }
        CgVertexFormat format = mesh.format();
        CgVertexAttribute position = attribute(format, CgVertexSemantic.POSITION), normal = attribute(format, CgVertexSemantic.NORMAL);
        if (position == null || position.getType() != CgAttribType.FLOAT || position.getComponents() != 3) {
            throw new IllegalArgumentException("particles spawn on a mesh with float xyz positions, not " + format);
        }
        boolean ownNormals = normal != null && normal.getType() == CgAttribType.FLOAT && normal.getComponents() == 3;
        int vertexCount = mesh.vertexCount(), stride = format.getStride();
        ByteBuffer bytes = ByteBuffer.allocate(vertexCount * stride).order(ByteOrder.nativeOrder());
        mesh.readVertices(0, vertexCount, bytes);
        float[] p = new float[vertexCount * 3], n = new float[vertexCount * 3];
        for (int v = 0; v < vertexCount; v++) {
            for (int c = 0; c < 3; c++) {
                p[v * 3 + c] = bytes.getFloat(v * stride + position.getOffset() + c * 4);
                if (ownNormals) n[v * 3 + c] = bytes.getFloat(v * stride + normal.getOffset() + c * 4);
            }
        }
        int[] indices = new int[mesh.indexCount()];
        int at = 0;
        for (int s = 0; s < mesh.submeshCount(); s++) {
            CgSubmesh sub = mesh.submesh(s);
            mesh.readIndices(sub.firstIndex(), sub.indexCount(), indices, at);
            for (int i = at; i < at + sub.indexCount(); i++) indices[i] += sub.firstVertex();
            at += sub.indexCount();
        }
        int triangles = at / 3;
        float[] area = new float[triangles];
        double total = 0;
        for (int t = 0; t < triangles; t++) {
            int a = indices[t * 3] * 3, b = indices[t * 3 + 1] * 3, c = indices[t * 3 + 2] * 3;
            float e1x = p[b] - p[a], e1y = p[b + 1] - p[a + 1], e1z = p[b + 2] - p[a + 2];
            float e2x = p[c] - p[a], e2y = p[c + 1] - p[a + 1], e2z = p[c + 2] - p[a + 2];
            float cx = e1y * e2z - e1z * e2y, cy = e1z * e2x - e1x * e2z, cz = e1x * e2y - e1y * e2x;
            area[t] = (float) Math.sqrt(cx * cx + cy * cy + cz * cz) * 0.5f;
            total += area[t];
            if (!ownNormals) {
                // The cross product is twice the area: summed, each vertex's normal leans to its larger faces.
                for (int k = 0; k < 3; k++) {
                    int v = indices[t * 3 + k] * 3;
                    n[v] += cx;
                    n[v + 1] += cy;
                    n[v + 2] += cz;
                }
            }
        }
        if (!(total > 0)) throw new IllegalArgumentException("particles spawn on a mesh with area; this one has none");
        float[] share = new float[triangles];
        int[] alias = new int[triangles];
        vose(area, total, share, alias);

        int firstTriangle = end / 4, firstVertex = firstTriangle + triangles * TRIANGLE_VECTORS;
        grow(end + (triangles * TRIANGLE_VECTORS + vertexCount * VERTEX_VECTORS) * 4);
        for (int t = 0; t < triangles; t++) {
            put(p, indices[t * 3] * 3, Float.floatToRawIntBits(share[t]));
            put(p, indices[t * 3 + 1] * 3, Float.floatToRawIntBits(alias[t]));
            put(p, indices[t * 3 + 2] * 3, 0);
        }
        float radius = 0f;
        for (int v = 0; v < vertexCount; v++) {
            put(p, v * 3, 0);
            float x = n[v * 3], y = n[v * 3 + 1], z = n[v * 3 + 2], length = (float) Math.sqrt(x * x + y * y + z * z);
            if (length > 0f) {
                n[v * 3] = x / length;
                n[v * 3 + 1] = y / length;
                n[v * 3 + 2] = z / length;
            } else {
                n[v * 3 + 1] = 1f;
            }
            put(n, v * 3, 0);
            radius = Math.max(radius, (float) Math.sqrt(p[v * 3] * p[v * 3] + p[v * 3 + 1] * p[v * 3 + 1] + p[v * 3 + 2] * p[v * 3 + 2]));
        }
        return new Section(firstTriangle, triangles, firstVertex, vertexCount, radius);
    }

    /**
     * Vose's alias method over {@code weight}: column {@code i} keeps itself with probability {@code share[i]}, else
     * gives {@code alias[i]}, so a uniform column and one more uniform pick in proportion to weight.
     */
    static void vose(float[] weight, double total, float[] share, int[] alias) {
        int n = weight.length;
        double[] scaled = new double[n];
        int[] small = new int[n], large = new int[n];
        int smalls = 0, larges = 0;
        for (int i = 0; i < n; i++) {
            scaled[i] = weight[i] * n / total;
            alias[i] = i;
            if (scaled[i] < 1.0) small[smalls++] = i;
            else large[larges++] = i;
        }
        while (smalls > 0 && larges > 0) {
            int l = small[--smalls], g = large[--larges];
            share[l] = (float) scaled[l];
            alias[l] = g;
            scaled[g] = scaled[g] + scaled[l] - 1.0;
            if (scaled[g] < 1.0) small[smalls++] = g;
            else large[larges++] = g;
        }
        // What remains is a whole column, to rounding.
        while (larges > 0) share[large[--larges]] = 1f;
        while (smalls > 0) share[small[--smalls]] = 1f;
    }

    private static CgVertexAttribute attribute(CgVertexFormat format, CgVertexSemantic semantic) {
        for (int i = 0; i < format.getAttributeCount(); i++) {
            CgVertexAttribute a = format.getAttribute(i);
            if (a.getSemantic() == semantic) return a;
        }
        return null;
    }

    private static void grow(int words) {
        if (CgVfxSpawnMeshes.words.length < words) {
            CgVfxSpawnMeshes.words = Arrays.copyOf(CgVfxSpawnMeshes.words, Math.max(words, CgVfxSpawnMeshes.words.length * 2));
        }
    }

    private static void put(float[] xyz, int at, int w) {
        words[end] = Float.floatToRawIntBits(xyz[at]);
        words[end + 1] = Float.floatToRawIntBits(xyz[at + 1]);
        words[end + 2] = Float.floatToRawIntBits(xyz[at + 2]);
        words[end + 3] = w;
        end += 4;
    }
}
