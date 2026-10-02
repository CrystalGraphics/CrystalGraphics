package com.crystalgraphics.api.mesh;

import com.crystalgraphics.api.vertex.CgAttribType;
import com.crystalgraphics.api.vertex.CgVertexAttribute;
import com.crystalgraphics.api.vertex.CgVertexFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Writes a {@link CgMesh}'s vertices and indices: what {@link CgMesh#build} and {@link CgMesh#edit} hand their body.
 * A vertex's attributes may be set in any order; {@link #end()} names any the format has and the vertex was not given.
 *
 * <pre>{@code
 * CgMesh quad = CgMesh.build(CgVertexFormat.POS3_UV2_COL4UB, m -> {
 *     int a = m.vertex().position(0, 0, 0).uv(0, 0).color(0xFFFFFFFF).end();
 *     int b = m.vertex().position(1, 0, 0).uv(1, 0).color(0xFFFFFFFF).end();
 *     int c = m.vertex().position(1, 1, 0).uv(1, 1).color(0xFFFFFFFF).end();
 *     int d = m.vertex().position(0, 1, 0).uv(0, 1).color(0xFFFFFFFF).end();
 *     m.quad(a, b, c, d);                         // a, b, c, then c, d, a
 * });
 * }</pre>
 *
 * <p>An attribute with no semantic setter is set by its index in the format, as floats or as integers:</p>
 *
 * <pre>{@code
 * int flags = format.indexOf("a_flags");         // once, not per vertex
 * m.vertex().position(x, y, z).setInt(flags, kind, 0, 0, 0).end();
 * }</pre>
 *
 * <p>Several parts in one mesh, each drawn on its own, are submeshes; a part's indices count from its own first
 * vertex, so a shape writes the same indices wherever it lands:</p>
 *
 * <pre>{@code
 * CgMesh model = CgMesh.build(CgVertexFormat.SPATIAL, m -> {
 *     CgMeshShapes.cube(m);                       // submesh 0
 *     m.submesh();
 *     CgMeshShapes.sphere(m, 16, 32, 1f);         // submesh 1, indices from 0 again
 * });
 * }</pre>
 *
 * <ul>
 *   <li>A semantic setter for an attribute the format lacks does nothing, so one shape serves every format. A value
 *       for an attribute it has but the vertex was not given throws at {@link #end()}.</li>
 *   <li>Fewer components than the attribute has fill with 0, and a fourth with 1; extra components are dropped.</li>
 *   <li>Floats into a normalised integer attribute are clamped and scaled; {@code setInt} into one writes the stored
 *       value as is (255 is 1.0 in an unsigned byte).</li>
 *   <li>Not thread-safe: a writer belongs to the one {@code build} or {@code edit} that handed it out, and is not
 *       usable after that body returns.</li>
 * </ul>
 */
public final class CgMeshWriter {

    private final CgVertexFormat format;
    private final int stride;
    private final CgVertexAttribute[] attributes;
    private final int positionAttribute, uvAttribute, normalAttribute, colorAttribute;
    private final int required;

    private final byte[] scratchBytes;
    private final ByteBuffer scratch;
    private final float[] values = new float[4];
    private final int[] integers = new int[4];
    private int given;
    private boolean open;

    byte[] vertices;
    int vertexCount;
    int[] indices;
    int indexCount;
    CgMeshTopology topology;
    int[] submeshes = new int[8];
    int submeshCount;
    private int submeshFirstVertex, submeshFirstIndex;
    final float[] bounds = new float[6];
    boolean anyPosition;

    CgMeshWriter(CgVertexFormat format) {
        this.format = format;
        this.stride = format.getStride();
        this.attributes = new CgVertexAttribute[format.getAttributeCount()];
        int position = -1, uv = -1, normal = -1, color = -1;
        for (int i = 0; i < attributes.length; i++) {
            CgVertexAttribute a = format.getAttribute(i);
            attributes[i] = a;
            if (a.getSemanticIndex() != 0) continue;
            switch (a.getSemantic()) {
                case POSITION: position = i; break;
                case UV: uv = i; break;
                case NORMAL: normal = i; break;
                case COLOR: color = i; break;
                default: break;
            }
        }
        if (attributes.length > 31) throw new IllegalArgumentException(format.getKey() + " has more than 31 attributes");
        this.positionAttribute = position;
        this.uvAttribute = uv;
        this.normalAttribute = normal;
        this.colorAttribute = color;
        this.required = (1 << attributes.length) - 1;
        this.scratchBytes = new byte[stride];
        this.scratch = ByteBuffer.wrap(scratchBytes).order(ByteOrder.nativeOrder());
        this.vertices = new byte[Math.max(stride, 1) * 16];
        this.indices = new int[48];
    }

    /** Empties the writer for another body, keeping its arrays. */
    void reset(CgMeshTopology topology) {
        vertexCount = 0;
        indexCount = 0;
        submeshCount = 0;
        submeshFirstVertex = 0;
        submeshFirstIndex = 0;
        given = 0;
        open = false;
        anyPosition = false;
        Arrays.fill(bounds, 0f);
        this.topology = topology;
    }

    public CgVertexFormat format() {
        return format;
    }

    /** The primitive the indices (or, with none, the vertices) make. Triangles unless set. */
    public CgMeshWriter topology(CgMeshTopology topology) {
        this.topology = topology;
        return this;
    }

    // ── Vertices ───────────────────────────────────────────────────────────────

    /** Begins a vertex: set its attributes, then {@link #end()}. */
    public CgMeshWriter vertex() {
        if (open) throw new IllegalStateException("vertex() before the previous vertex's end()");
        open = true;
        given = 0;
        return this;
    }

    public CgMeshWriter position(float x, float y) {
        return position(x, y, 0f);
    }

    public CgMeshWriter position(float x, float y, float z) {
        if (positionAttribute < 0) return this;
        floats(positionAttribute, x, y, z, 1f, 3);
        if (!anyPosition) {
            bounds[0] = bounds[3] = x;
            bounds[1] = bounds[4] = y;
            bounds[2] = bounds[5] = z;
            anyPosition = true;
        } else {
            bounds[0] = Math.min(bounds[0], x);
            bounds[1] = Math.min(bounds[1], y);
            bounds[2] = Math.min(bounds[2], z);
            bounds[3] = Math.max(bounds[3], x);
            bounds[4] = Math.max(bounds[4], y);
            bounds[5] = Math.max(bounds[5], z);
        }
        return this;
    }

    public CgMeshWriter uv(float u, float v) {
        return uvAttribute < 0 ? this : floats(uvAttribute, u, v, 0f, 1f, 2);
    }

    public CgMeshWriter normal(float x, float y, float z) {
        return normalAttribute < 0 ? this : floats(normalAttribute, x, y, z, 1f, 3);
    }

    /** Colour as {@code 0xAARRGGBB}. */
    public CgMeshWriter color(int argb) {
        return color(((argb >>> 16) & 0xFF) / 255f, ((argb >>> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f,
                (argb >>> 24) / 255f);
    }

    public CgMeshWriter color(float r, float g, float b, float a) {
        return colorAttribute < 0 ? this : floats(colorAttribute, r, g, b, a, 4);
    }

    public CgMeshWriter set(int attribute, float x) {
        return floats(attribute, x, 0f, 0f, 1f, 1);
    }

    public CgMeshWriter set(int attribute, float x, float y) {
        return floats(attribute, x, y, 0f, 1f, 2);
    }

    public CgMeshWriter set(int attribute, float x, float y, float z) {
        return floats(attribute, x, y, z, 1f, 3);
    }

    public CgMeshWriter set(int attribute, float x, float y, float z, float w) {
        return floats(attribute, x, y, z, w, 4);
    }

    public CgMeshWriter setInt(int attribute, int x) {
        return ints(attribute, x, 0, 0, 1, 1);
    }

    public CgMeshWriter setInt(int attribute, int x, int y) {
        return ints(attribute, x, y, 0, 1, 2);
    }

    public CgMeshWriter setInt(int attribute, int x, int y, int z) {
        return ints(attribute, x, y, z, 1, 3);
    }

    public CgMeshWriter setInt(int attribute, int x, int y, int z, int w) {
        return ints(attribute, x, y, z, w, 4);
    }

    /** Ends the vertex. @return its index in the current submesh, for {@link #triangle} and the rest */
    public int end() {
        if (!open) throw new IllegalStateException("end() with no vertex() open");
        if (given != required) throw new IllegalStateException(missing());
        int at = vertexCount * stride;
        if (at + stride > vertices.length) vertices = Arrays.copyOf(vertices, Math.max(at + stride, vertices.length * 2));
        System.arraycopy(scratchBytes, 0, vertices, at, stride);
        open = false;
        return vertexCount++ - submeshFirstVertex;
    }

    /** The index the next vertex will have in the current submesh. */
    public int next() {
        return vertexCount - submeshFirstVertex;
    }

    // ── Indices ────────────────────────────────────────────────────────────────

    public CgMeshWriter index(int i) {
        if (indexCount == indices.length) indices = Arrays.copyOf(indices, indices.length * 2);
        indices[indexCount++] = i;
        return this;
    }

    public CgMeshWriter line(int a, int b) {
        return index(a).index(b);
    }

    public CgMeshWriter triangle(int a, int b, int c) {
        return index(a).index(b).index(c);
    }

    /** Two triangles: {@code a, b, c} and {@code c, d, a}. */
    public CgMeshWriter quad(int a, int b, int c, int d) {
        return triangle(a, b, c).triangle(c, d, a);
    }

    // ── Submeshes ──────────────────────────────────────────────────────────────

    /** Ends the current submesh and begins another, whose indices count from its own first vertex. */
    public CgMeshWriter submesh() {
        if (open) throw new IllegalStateException("submesh() inside a vertex");
        closeSubmesh();
        return this;
    }

    private void closeSubmesh() {
        if (submeshCount * 4 == submeshes.length) submeshes = Arrays.copyOf(submeshes, submeshes.length * 2);
        int s = submeshCount++ * 4;
        submeshes[s] = submeshFirstIndex;
        submeshes[s + 1] = indexCount - submeshFirstIndex;
        submeshes[s + 2] = submeshFirstVertex;
        submeshes[s + 3] = vertexCount - submeshFirstVertex;
        submeshFirstIndex = indexCount;
        submeshFirstVertex = vertexCount;
    }

    /** Closes the last submesh and checks every index names a vertex of its own submesh. */
    void finish() {
        if (open) throw new IllegalStateException("vertex() without end()");
        closeSubmesh();
        for (int s = 0; s < submeshCount; s++) {
            int first = submeshes[s * 4], count = submeshes[s * 4 + 1], vertexCount = submeshes[s * 4 + 3];
            for (int i = first; i < first + count; i++) {
                if (indices[i] < 0 || indices[i] >= vertexCount) {
                    throw new IllegalStateException("index " + i + " is " + indices[i] + ", but submesh " + s + " has "
                            + vertexCount + " vertices");
                }
            }
        }
    }

    // ── Encoding ───────────────────────────────────────────────────────────────

    private CgMeshWriter floats(int attribute, float x, float y, float z, float w, int n) {
        requireOpen(attribute);
        values[0] = x;
        values[1] = y;
        values[2] = z;
        values[3] = w;
        CgVertexAttribute a = attributes[attribute];
        int components = a.getComponents(), size = a.getType().getByteSize();
        for (int c = 0; c < components; c++) {
            float v = c < n ? values[c] : c == 3 ? 1f : 0f;
            putFloat(a.getType(), a.isNormalized(), a.getOffset() + c * size, v);
        }
        given |= 1 << attribute;
        return this;
    }

    private CgMeshWriter ints(int attribute, int x, int y, int z, int w, int n) {
        requireOpen(attribute);
        integers[0] = x;
        integers[1] = y;
        integers[2] = z;
        integers[3] = w;
        CgVertexAttribute a = attributes[attribute];
        int components = a.getComponents(), size = a.getType().getByteSize();
        for (int c = 0; c < components; c++) {
            int v = c < n ? integers[c] : c == 3 ? 1 : 0;
            putInt(a.getType(), a.getOffset() + c * size, v);
        }
        given |= 1 << attribute;
        return this;
    }

    private void requireOpen(int attribute) {
        if (!open) throw new IllegalStateException("an attribute outside vertex() ... end()");
        if (attribute < 0 || attribute >= attributes.length) {
            throw new IndexOutOfBoundsException("attribute " + attribute + " of " + format.getKey());
        }
    }

    private void putFloat(CgAttribType type, boolean normalized, int at, float v) {
        switch (type) {
            case FLOAT: scratch.putFloat(at, v); break;
            case HALF_FLOAT: scratch.putShort(at, toHalf(v)); break;
            case UNSIGNED_BYTE: scratch.put(at, (byte) (normalized ? Math.round(clamp(v, 0f) * 255f) : Math.round(v))); break;
            case BYTE: scratch.put(at, (byte) (normalized ? Math.round(clamp(v, -1f) * 127f) : Math.round(v))); break;
            case UNSIGNED_SHORT: scratch.putShort(at, (short) (normalized ? Math.round(clamp(v, 0f) * 65535f) : Math.round(v))); break;
            case SHORT: scratch.putShort(at, (short) (normalized ? Math.round(clamp(v, -1f) * 32767f) : Math.round(v))); break;
            case UNSIGNED_INT: scratch.putInt(at, (int) (normalized ? Math.round(clamp(v, 0f) * 4294967295.0) : Math.round((double) v))); break;
            case INT: scratch.putInt(at, (int) (normalized ? Math.round(clamp(v, -1f) * 2147483647.0) : Math.round((double) v))); break;
            default: throw new IllegalStateException("no encoding for " + type);
        }
    }

    private void putInt(CgAttribType type, int at, int v) {
        switch (type) {
            case FLOAT: scratch.putFloat(at, v); break;
            case HALF_FLOAT: scratch.putShort(at, toHalf(v)); break;
            case UNSIGNED_BYTE: case BYTE: scratch.put(at, (byte) v); break;
            case UNSIGNED_SHORT: case SHORT: scratch.putShort(at, (short) v); break;
            case UNSIGNED_INT: case INT: scratch.putInt(at, v); break;
            default: throw new IllegalStateException("no encoding for " + type);
        }
    }

    private static float clamp(float v, float min) {
        return v < min ? min : Math.min(v, 1f);
    }

    /** IEEE 754 binary16, rounded to nearest even, as the GPU reads it. */
    static short toHalf(float f) {
        int bits = Float.floatToRawIntBits(f);
        int sign = (bits >>> 16) & 0x8000;
        int value = bits & 0x7FFFFFFF;
        if (value >= 0x7F800000) {
            return (short) (sign | 0x7C00 | (value > 0x7F800000 ? 0x200 : 0));
        }
        if (value >= 0x477FF000) return (short) (sign | 0x7C00);
        if (value >= 0x38800000) {
            value += 0x0FFF + ((value >>> 13) & 1);
            return (short) (sign | ((value - 0x38000000) >>> 13));
        }
        int shift = 126 - (value >>> 23);
        if (shift > 24) return (short) sign;
        int full = (value & 0x7FFFFF) | 0x800000;
        int m = full >>> shift;
        int rest = full & ((1 << shift) - 1), halfway = 1 << (shift - 1);
        if (rest > halfway || (rest == halfway && (m & 1) != 0)) m++;
        return (short) (sign | m);
    }

    private String missing() {
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < attributes.length; i++) {
            if ((given & (1 << i)) != 0) continue;
            if (names.length() > 0) names.append(", ");
            names.append(attributes[i].getName());
        }
        return "vertex " + vertexCount + " of " + format.getKey() + " has no value for " + names;
    }
}
