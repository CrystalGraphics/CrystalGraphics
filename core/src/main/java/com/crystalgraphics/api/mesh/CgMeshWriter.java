package com.crystalgraphics.api.mesh;

import com.crystalgraphics.api.vertex.CgAttribType;
import com.crystalgraphics.api.vertex.CgVertexAttribute;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.api.vertex.CgVertexSemantic;

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
    private final int positionAttribute, normalAttribute, colorAttribute;
    /** The attribute of each UV set by its index, -1 where the format has none. */
    private final int[] uvAttributes = {-1, -1, -1, -1};
    private final int required;

    /** A component's encoding: its type, {@link #NORM} added where the attribute normalises it. */
    private static final int F32 = 0, HALF = 1, U8 = 2, I8 = 3, U16 = 4, I16 = 5, U32 = 6, I32 = 7, NORM = 8;
    private static final boolean LITTLE = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
    /** Each attribute's encoding, byte offset, components, bytes a component and bytes in all, resolved once. */
    private final int[] encodings, offsets, components, sizes, byteSizes;

    private final byte[] scratchBytes;
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
        int n = attributes.length;
        this.encodings = new int[n];
        this.offsets = new int[n];
        this.components = new int[n];
        this.sizes = new int[n];
        this.byteSizes = new int[n];
        int position = -1, normal = -1, color = -1;
        for (int i = 0; i < attributes.length; i++) {
            CgVertexAttribute a = format.getAttribute(i);
            attributes[i] = a;
            encodings[i] = encoding(a.getType()) + (a.isNormalized() ? NORM : 0);
            offsets[i] = a.getOffset();
            components[i] = a.getComponents();
            sizes[i] = a.getType().getByteSize();
            byteSizes[i] = a.getByteSize();
            if (a.getSemantic() == CgVertexSemantic.UV && a.getSemanticIndex() < uvAttributes.length) uvAttributes[a.getSemanticIndex()] = i;
            if (a.getSemanticIndex() != 0) continue;
            switch (a.getSemantic()) {
                case POSITION: position = i; break;
                case NORMAL: normal = i; break;
                case COLOR: color = i; break;
                default: break;
            }
        }
        if (attributes.length > 31) throw new IllegalArgumentException(format.getKey() + " has more than 31 attributes");
        this.positionAttribute = position;
        this.normalAttribute = normal;
        this.colorAttribute = color;
        this.required = (1 << attributes.length) - 1;
        this.scratchBytes = new byte[stride];
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
        return uv(0, u, v);
    }

    /** The second UV set ({@code cg_TexCoord1}); nothing where the format has none. */
    public CgMeshWriter uv1(float u, float v) {
        return uv(1, u, v);
    }

    /** The third UV set ({@code cg_TexCoord2}); nothing where the format has none. */
    public CgMeshWriter uv2(float u, float v) {
        return uv(2, u, v);
    }

    /** The fourth UV set ({@code cg_TexCoord3}); nothing where the format has none. */
    public CgMeshWriter uv3(float u, float v) {
        return uv(3, u, v);
    }

    /** UV set {@code set}, 0 to 3; nothing where the format has none. */
    public CgMeshWriter uv(int set, float u, float v) {
        int attribute = uvAttributes[set];
        return attribute < 0 ? this : floats(attribute, u, v, 0f, 1f, 2);
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
        // Unreal's streams: a null colour stream reads white, and a missing UV channel repeats the last.
        if (colorAttribute >= 0 && (given & (1 << colorAttribute)) == 0) floats(colorAttribute, 1f, 1f, 1f, 1f, 4);
        for (int set = 1; set < uvAttributes.length; set++) {
            int before = uvAttributes[set - 1], at = uvAttributes[set];
            if (at < 0 || before < 0 || (given & (1 << at)) != 0 || (given & (1 << before)) == 0) continue;
            int size = byteSizes[at], from = offsets[before], to = offsets[at];
            if (byteSizes[before] != size) continue;
            for (int b = 0; b < size; b++) scratchBytes[to + b] = scratchBytes[from + b];
            given |= 1 << at;
        }
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

    // Unrolled, the encoding an int resolved at construction: per vertex this path is the whole cost of an edit.
    private CgMeshWriter floats(int attribute, float x, float y, float z, float w, int n) {
        requireOpen(attribute);
        int e = encodings[attribute], at = offsets[attribute], count = components[attribute], size = sizes[attribute];
        putFloat(e, at, x);
        if (count > 1) putFloat(e, at + size, n > 1 ? y : 0f);
        if (count > 2) putFloat(e, at + 2 * size, n > 2 ? z : 0f);
        if (count > 3) putFloat(e, at + 3 * size, n > 3 ? w : 1f);
        given |= 1 << attribute;
        return this;
    }

    private CgMeshWriter ints(int attribute, int x, int y, int z, int w, int n) {
        requireOpen(attribute);
        int e = encodings[attribute] & ~NORM, at = offsets[attribute], count = components[attribute], size = sizes[attribute];
        putInt(e, at, x);
        if (count > 1) putInt(e, at + size, n > 1 ? y : 0);
        if (count > 2) putInt(e, at + 2 * size, n > 2 ? z : 0);
        if (count > 3) putInt(e, at + 3 * size, n > 3 ? w : 1);
        given |= 1 << attribute;
        return this;
    }

    private static int encoding(CgAttribType type) {
        switch (type) {
            case FLOAT: return F32;
            case HALF_FLOAT: return HALF;
            case UNSIGNED_BYTE: return U8;
            case BYTE: return I8;
            case UNSIGNED_SHORT: return U16;
            case SHORT: return I16;
            case UNSIGNED_INT: return U32;
            case INT: return I32;
            default: throw new IllegalStateException("no encoding for " + type);
        }
    }

    private void requireOpen(int attribute) {
        if (!open) throw new IllegalStateException("an attribute outside vertex() ... end()");
        if (attribute < 0 || attribute >= attributes.length) {
            throw new IndexOutOfBoundsException("attribute " + attribute + " of " + format.getKey());
        }
    }

    private void putFloat(int encoding, int at, float v) {
        switch (encoding) {
            case F32: case F32 + NORM: put32(at, Float.floatToRawIntBits(v)); break;
            case HALF: case HALF + NORM: put16(at, toHalf(v)); break;
            case U8: case I8: scratchBytes[at] = (byte) Math.round(v); break;
            case U8 + NORM: scratchBytes[at] = (byte) Math.round(clamp(v, 0f) * 255f); break;
            case I8 + NORM: scratchBytes[at] = (byte) Math.round(clamp(v, -1f) * 127f); break;
            case U16: case I16: put16(at, Math.round(v)); break;
            case U16 + NORM: put16(at, Math.round(clamp(v, 0f) * 65535f)); break;
            case I16 + NORM: put16(at, Math.round(clamp(v, -1f) * 32767f)); break;
            case U32: case I32: put32(at, (int) Math.round((double) v)); break;
            case U32 + NORM: put32(at, (int) Math.round(clamp(v, 0f) * 4294967295.0)); break;
            case I32 + NORM: put32(at, (int) Math.round(clamp(v, -1f) * 2147483647.0)); break;
            default: throw new IllegalStateException("encoding " + encoding);
        }
    }

    private void putInt(int encoding, int at, int v) {
        switch (encoding) {
            case F32: put32(at, Float.floatToRawIntBits(v)); break;
            case HALF: put16(at, toHalf(v)); break;
            case U8: case I8: scratchBytes[at] = (byte) v; break;
            case U16: case I16: put16(at, v); break;
            case U32: case I32: put32(at, v); break;
            default: throw new IllegalStateException("encoding " + encoding);
        }
    }

    /** The low 16 bits of {@code v}, in native order. */
    private void put16(int at, int v) {
        byte[] b = scratchBytes;
        if (LITTLE) {
            b[at] = (byte) v;
            b[at + 1] = (byte) (v >>> 8);
        } else {
            b[at] = (byte) (v >>> 8);
            b[at + 1] = (byte) v;
        }
    }

    private void put32(int at, int v) {
        byte[] b = scratchBytes;
        if (LITTLE) {
            b[at] = (byte) v;
            b[at + 1] = (byte) (v >>> 8);
            b[at + 2] = (byte) (v >>> 16);
            b[at + 3] = (byte) (v >>> 24);
        } else {
            b[at] = (byte) (v >>> 24);
            b[at + 1] = (byte) (v >>> 16);
            b[at + 2] = (byte) (v >>> 8);
            b[at + 3] = (byte) v;
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
