package com.crystalgraphics.api.mesh;

import com.crystalgraphics.api.vertex.CgAttribType;
import com.crystalgraphics.api.vertex.CgVertexAttribute;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.api.vertex.CgVertexSemantic;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * Geometry as data: vertices in one {@link CgVertexFormat}, indices, submeshes and bounds, kept on the CPU. A renderer
 * keeps the GPU copy up to date when it draws, so nothing here touches a GPU and any thread may build or edit.
 *
 * <pre>{@code
 * CgMesh tri = CgMesh.build(CgVertexFormat.SPATIAL, m -> {
 *     int a = m.vertex().position(0, 0, 0).normal(0, 1, 0).uv(0, 0).end();
 *     int b = m.vertex().position(1, 0, 0).normal(0, 1, 0).uv(1, 0).end();
 *     int c = m.vertex().position(0, 0, 1).normal(0, 1, 0).uv(0, 1).end();
 *     m.triangle(a, b, c);
 * });
 * }</pre>
 *
 * <p>Editing replaces the contents, or overwrites part of them; a draw recorded after the edit sees it:</p>
 *
 * <pre>{@code
 * mesh.edit(m -> CgMeshShapes.sphere(m, 8, 16, 1f));   // the same writer as build
 * mesh.writeVertices(16, bytes);                         // vertices 16.. from interleaved bytes in its format
 * mesh.writeIndices(0, new int[]{0, 1, 2});
 * }</pre>
 *
 * <p>An edit every frame passes its state beside a body that captures nothing, so the call allocates nothing:</p>
 *
 * <pre>{@code
 * trail.edit(points, Trail::write);               // static void write(CgMeshWriter m, TrailPoints points)
 * }</pre>
 *
 * <p>How long the geometry lives decides where its GPU copy goes ({@link Usage}):</p>
 *
 * <pre>{@code
 * CgMesh sparks = CgMesh.build(format, CgMesh.Usage.FRAME, m -> ...);   // rewritten every frame it draws
 * }</pre>
 *
 * <ul>
 *   <li>Indices count from their submesh's first vertex ({@link CgSubmesh}).</li>
 *   <li>Bounds follow the positions written, unless {@link #bounds(float, float, float, float, float, float)} states
 *       them; {@link #pad} grows either, for a vertex shader that displaces.</li>
 *   <li>A shared shape from {@link CgMeshShapes} refuses edits and {@link #release()}: build your own with the
 *       writer form of the shape.</li>
 *   <li>One writer at a time; the body of {@link #edit} runs outside the mesh's lock, so a reader is never held up
 *       by it, and the contents change in one step when it returns.</li>
 * </ul>
 */
public final class CgMesh implements CgMeshSource {

    /** How long a mesh's geometry lives, which decides where its GPU copy goes. */
    public enum Usage {
        /** Built once, drawn many times: a range in its format's pool. */
        STATIC,
        /** Edited now and then: the same, placed again on every edit. */
        DYNAMIC,
        /** Rewritten every frame it draws: the frame ring, nothing kept. */
        FRAME,
        /** As {@link #STATIC}, with the CPU copy dropped once the GPU has it. */
        GPU_ONLY
    }

    /** Changes remembered for {@link #changesSince}; a reader further behind reads everything. */
    private static final int LOG = 16;
    private static final int[] NO_INDICES = new int[0];

    private final CgVertexFormat format;
    private final Usage usage;
    private final boolean shared;
    private final int stride;

    private CgMeshTopology topology = CgMeshTopology.TRIANGLES;
    private byte[] vertices = new byte[0];
    private int vertexCount;
    private int[] indices = NO_INDICES;
    private int indexCount;
    /** Four ints per submesh (first index, index count, first vertex, vertex count); null: one, the whole mesh. */
    private int[] submeshes;
    private int submeshCount;

    private final float[] computed = new float[6];
    private boolean computedStale;
    private final float[] declared = new float[6];
    private boolean hasDeclared;
    private float pad;

    private int revision;
    private int logged;
    private final int[] logRevision = new int[LOG], logRanges = new int[LOG * 4];
    private final boolean[] logAll = new boolean[LOG];

    private volatile int releases;
    private boolean noBounds;
    private CgMeshWriter idleWriter;

    private static final BiConsumer<CgMeshWriter, Consumer<CgMeshWriter>> ACCEPT = (w, body) -> body.accept(w);

    private CgMesh(CgVertexFormat format, Usage usage, boolean shared) {
        this.format = format;
        this.usage = usage;
        this.shared = shared;
        this.stride = format.getStride();
    }

    /** A {@link Usage#STATIC} mesh written by {@code body}. */
    public static CgMesh build(CgVertexFormat format, Consumer<CgMeshWriter> body) {
        return build(format, Usage.STATIC, body);
    }

    public static CgMesh build(CgVertexFormat format, Usage usage, Consumer<CgMeshWriter> body) {
        CgMesh mesh = new CgMesh(format, usage, false);
        mesh.write(body, ACCEPT);
        return mesh;
    }

    /** A shape {@link CgMeshShapes} hands to everyone who asks: edits and release refuse. */
    static CgMesh shared(CgVertexFormat format, Consumer<CgMeshWriter> body) {
        CgMesh mesh = new CgMesh(format, Usage.STATIC, true);
        mesh.write(body, ACCEPT);
        return mesh;
    }

    // ── Writing ────────────────────────────────────────────────────────────────

    /** Replaces the contents with what {@code body} writes. */
    public void edit(Consumer<CgMeshWriter> body) {
        requireEditable();
        write(body, ACCEPT);
    }

    /** Replaces the contents with what {@code body} writes from {@code context}: no capture, no allocation. */
    public <T> void edit(T context, BiConsumer<CgMeshWriter, T> body) {
        requireEditable();
        write(context, body);
    }

    private <T> void write(T context, BiConsumer<CgMeshWriter, T> body) {
        CgMeshWriter w;
        synchronized (this) {
            w = idleWriter != null ? idleWriter : new CgMeshWriter(format);
            idleWriter = null;
        }
        w.reset(CgMeshTopology.TRIANGLES);
        body.accept(w, context);
        w.finish();
        synchronized (this) {
            byte[] oldVertices = vertices;
            int[] oldIndices = indices;
            vertices = w.vertices;
            vertexCount = w.vertexCount;
            indices = w.indices;
            indexCount = w.indexCount;
            topology = w.topology;
            if (w.submeshCount > 1) {
                submeshes = Arrays.copyOf(w.submeshes, w.submeshCount * 4);
                submeshCount = w.submeshCount;
            } else {
                submeshes = null;
                submeshCount = 1;
            }
            System.arraycopy(w.bounds, 0, computed, 0, 6);
            computedStale = !w.anyPosition;
            w.vertices = oldVertices.length > 0 ? oldVertices : new byte[Math.max(stride, 1) * 16];
            w.indices = oldIndices.length > 0 ? oldIndices : new int[48];
            idleWriter = w;
            changed(true, 0, 0, 0, 0);
        }
    }

    /**
     * Overwrites vertices from {@code firstVertex} with {@code bytes}' remaining bytes, interleaved in this mesh's
     * format; past the end it grows the mesh. Unity's {@code SetVertexBufferData}: the caller keeps its buffer.
     */
    public synchronized void writeVertices(int firstVertex, ByteBuffer bytes) {
        requireEditable();
        int n = bytes.remaining();
        if (n % stride != 0) throw new IllegalArgumentException(n + " bytes is not a whole number of " + stride + "-byte vertices");
        if (firstVertex < 0 || firstVertex > vertexCount) {
            throw new IndexOutOfBoundsException("first vertex " + firstVertex + " of " + vertexCount);
        }
        int count = n / stride, end = firstVertex + count;
        if (end * stride > vertices.length) vertices = Arrays.copyOf(vertices, Math.max(end * stride, vertices.length * 2));
        int position = bytes.position();
        bytes.get(vertices, firstVertex * stride, n);
        ((Buffer) bytes).position(position);
        vertexCount = Math.max(vertexCount, end);
        computedStale = true;
        changed(false, firstVertex, end, 0, 0);
    }

    /**
     * Overwrites indices from {@code firstIndex}; past the end it grows the mesh. Each is checked against the whole
     * mesh's vertices, not its submesh's: a raw write knows no submesh.
     */
    public synchronized void writeIndices(int firstIndex, int[] values) {
        requireEditable();
        int end = beginIndices(firstIndex, values.length);
        for (int i = 0; i < values.length; i++) indices[firstIndex + i] = checkIndex(values[i]);
        endIndices(firstIndex, end);
    }

    /** {@link #writeIndices(int, int[])} from {@code values}' remaining ints. */
    public synchronized void writeIndices(int firstIndex, IntBuffer values) {
        requireEditable();
        int n = values.remaining(), at = values.position();
        int end = beginIndices(firstIndex, n);
        for (int i = 0; i < n; i++) indices[firstIndex + i] = checkIndex(values.get(at + i));
        endIndices(firstIndex, end);
    }

    private int beginIndices(int firstIndex, int count) {
        if (firstIndex < 0 || firstIndex > indexCount) {
            throw new IndexOutOfBoundsException("first index " + firstIndex + " of " + indexCount);
        }
        int end = firstIndex + count;
        if (end > indices.length) indices = Arrays.copyOf(indices, Math.max(end, Math.max(48, indices.length * 2)));
        return end;
    }

    private void endIndices(int firstIndex, int end) {
        indexCount = Math.max(indexCount, end);
        changed(false, 0, 0, firstIndex, end);
    }

    private int checkIndex(int i) {
        if (i < 0 || i >= vertexCount) throw new IllegalArgumentException("index " + i + " of " + vertexCount + " vertices");
        return i;
    }

    /** Sets submesh {@code i}'s run of indices; its vertices stay as they are. */
    public synchronized void submesh(int i, int firstIndex, int indexCount) {
        requireEditable();
        if (i < 0 || i >= submeshCount) throw new IndexOutOfBoundsException("submesh " + i + " of " + submeshCount);
        if (firstIndex < 0 || indexCount < 0 || firstIndex + indexCount > this.indexCount) {
            throw new IndexOutOfBoundsException("indices " + firstIndex + "+" + indexCount + " of " + this.indexCount);
        }
        if (submeshes == null) submeshes = new int[]{0, this.indexCount, 0, vertexCount};
        submeshes[i * 4] = firstIndex;
        submeshes[i * 4 + 1] = indexCount;
        changed(false, 0, 0, 0, 0);
    }

    /** States the bounds, in the mesh's own space, in place of those of its positions. */
    public synchronized void bounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        declared[0] = minX;
        declared[1] = minY;
        declared[2] = minZ;
        declared[3] = maxX;
        declared[4] = maxY;
        declared[5] = maxZ;
        hasDeclared = true;
        changed(false, 0, 0, 0, 0);
    }

    /** Returns to bounds of the positions written. */
    public synchronized void autoBounds() {
        hasDeclared = false;
        changed(false, 0, 0, 0, 0);
    }

    /** Grows the bounds by {@code radius} on every side: what a displacing vertex shader can move a vertex. */
    public synchronized void pad(float radius) {
        pad = radius;
        changed(false, 0, 0, 0, 0);
    }

    /**
     * Frees the GPU copy once no frame in flight reads it. The mesh keeps its data; drawing it again places it
     * again.
     */
    public void release() {
        if (shared) throw new IllegalStateException("a shared shape is never released");
        releases++;
    }

    private void requireEditable() {
        if (shared) throw new IllegalStateException("a shared shape: build your own with the writer form of the shape");
    }

    /** Called under the lock: one more revision, and what it touched. */
    private void changed(boolean all, int vertexFrom, int vertexTo, int indexFrom, int indexTo) {
        revision++;
        int slot = logged++ % LOG;
        logRevision[slot] = revision;
        logAll[slot] = all;
        logRanges[slot * 4] = vertexFrom;
        logRanges[slot * 4 + 1] = vertexTo;
        logRanges[slot * 4 + 2] = indexFrom;
        logRanges[slot * 4 + 3] = indexTo;
    }

    // ── Reading ────────────────────────────────────────────────────────────────

    public CgVertexFormat format() {
        return format;
    }

    public Usage usage() {
        return usage;
    }

    public boolean isShared() {
        return shared;
    }

    /** How many times {@link #release()} was asked: a renderer frees its copy when this moves. */
    public int releases() {
        return releases;
    }

    @Override
    public CgMesh mesh() {
        return this;
    }

    public synchronized CgMeshTopology topology() {
        return topology;
    }

    public synchronized int vertexCount() {
        return vertexCount;
    }

    public synchronized int indexCount() {
        return indexCount;
    }

    /** Drawn by its indices; without them, its vertices in order. */
    public synchronized boolean isIndexed() {
        return indexCount > 0;
    }

    /** Every write so far: 1 once built, and one more per change. */
    public synchronized int revision() {
        return revision;
    }

    public synchronized int submeshCount() {
        return submeshCount;
    }

    public synchronized CgSubmesh submesh(int i) {
        if (i < 0 || i >= submeshCount) throw new IndexOutOfBoundsException("submesh " + i + " of " + submeshCount);
        if (submeshes == null) return new CgSubmesh(0, indexCount, 0, vertexCount);
        return new CgSubmesh(submeshes[i * 4], submeshes[i * 4 + 1], submeshes[i * 4 + 2], submeshes[i * 4 + 3]);
    }

    /** Submesh {@code i} into {@code out} as first index, index count, first vertex, vertex count: for a draw. */
    public synchronized int[] submesh(int i, int[] out) {
        if (i < 0 || i >= submeshCount) throw new IndexOutOfBoundsException("submesh " + i + " of " + submeshCount);
        if (submeshes == null) {
            out[0] = 0;
            out[1] = indexCount;
            out[2] = 0;
            out[3] = vertexCount;
        } else {
            System.arraycopy(submeshes, i * 4, out, 0, 4);
        }
        return out;
    }

    /**
     * The bounds, padded, into {@code out} as min x, y, z then max x, y, z; zero for a mesh with no vertices. Null
     * when there are none to give: positions that are not floats, or none, and no bounds stated.
     */
    @Nullable
    public synchronized float[] bounds(float[] out) {
        float[] b = declared;
        if (!hasDeclared) {
            if (computedStale) computeBounds();
            if (noBounds) return null;
            b = computed;
        }
        for (int i = 0; i < 3; i++) {
            out[i] = b[i] - pad;
            out[i + 3] = b[i + 3] + pad;
        }
        return out;
    }

    /** Bounds from the position bytes, after a raw {@link #writeVertices} write. */
    private void computeBounds() {
        computedStale = false;
        noBounds = false;
        Arrays.fill(computed, 0f);
        int position = -1;
        for (int i = 0; i < format.getAttributeCount(); i++) {
            if (format.getAttribute(i).getSemantic() == CgVertexSemantic.POSITION) {
                position = i;
                break;
            }
        }
        noBounds = position < 0 || format.getAttribute(position).getType() != CgAttribType.FLOAT;
        if (noBounds || vertexCount == 0) return;
        CgVertexAttribute a = format.getAttribute(position);
        ByteBuffer v = ByteBuffer.wrap(vertices).order(ByteOrder.nativeOrder());
        int components = a.getComponents();
        for (int i = 0; i < vertexCount; i++) {
            int at = i * stride + a.getOffset();
            for (int c = 0; c < 3; c++) {
                float p = c < components ? v.getFloat(at + c * 4) : 0f;
                computed[c] = i == 0 ? p : Math.min(computed[c], p);
                computed[c + 3] = i == 0 ? p : Math.max(computed[c + 3], p);
            }
        }
    }

    /** Copies {@code count} vertices from {@code firstVertex} into {@code dst} at its position, advancing it. */
    public synchronized void readVertices(int firstVertex, int count, ByteBuffer dst) {
        if (firstVertex < 0 || count < 0 || firstVertex + count > vertexCount) {
            throw new IndexOutOfBoundsException("vertices " + firstVertex + "+" + count + " of " + vertexCount);
        }
        dst.put(vertices, firstVertex * stride, count * stride);
    }

    /** Copies {@code count} indices from {@code firstIndex} into {@code dst} at {@code at}. */
    public synchronized void readIndices(int firstIndex, int count, int[] dst, int at) {
        if (firstIndex < 0 || count < 0 || firstIndex + count > indexCount) {
            throw new IndexOutOfBoundsException("indices " + firstIndex + "+" + count + " of " + indexCount);
        }
        System.arraycopy(indices, firstIndex, dst, at, count);
    }

    /** Copies {@code count} indices from {@code firstIndex} into {@code dst} as native-order ints, advancing it. */
    public synchronized void readIndices(int firstIndex, int count, ByteBuffer dst) {
        if (firstIndex < 0 || count < 0 || firstIndex + count > indexCount) {
            throw new IndexOutOfBoundsException("indices " + firstIndex + "+" + count + " of " + indexCount);
        }
        ByteOrder order = dst.order();
        dst.order(ByteOrder.nativeOrder());
        for (int i = firstIndex; i < firstIndex + count; i++) dst.putInt(indices[i]);
        dst.order(order);
    }

    /**
     * What changed after {@code revision}, into {@code out}: false when nothing did. A reader keeps the revision it
     * last read ({@code out.revision}); 0 reads everything.
     */
    public synchronized boolean changesSince(int revision, CgMeshChanges out) {
        out.clear(this.revision);
        if (revision >= this.revision) return false;
        int remembered = Math.min(logged, LOG);
        if (revision < this.revision - remembered) {
            out.all = true;
            return true;
        }
        boolean anyVertices = false, anyIndices = false;
        for (int r = revision + 1; r <= this.revision; r++) {
            int slot = (logged - 1 - (this.revision - r)) % LOG;
            if (logAll[slot]) {
                out.all = true;
                return true;
            }
            int vf = logRanges[slot * 4], vt = logRanges[slot * 4 + 1], xf = logRanges[slot * 4 + 2], xt = logRanges[slot * 4 + 3];
            if (vt > vf) {
                out.vertexFrom = anyVertices ? Math.min(out.vertexFrom, vf) : vf;
                out.vertexTo = anyVertices ? Math.max(out.vertexTo, vt) : vt;
                anyVertices = true;
            }
            if (xt > xf) {
                out.indexFrom = anyIndices ? Math.min(out.indexFrom, xf) : xf;
                out.indexTo = anyIndices ? Math.max(out.indexTo, xt) : xt;
                anyIndices = true;
            }
        }
        return true;
    }

    @Override
    public synchronized String toString() {
        return "CgMesh(" + format.getKey() + ", " + vertexCount + " vertices, " + indexCount + " indices, "
                + submeshCount + " submesh" + (submeshCount == 1 ? "" : "es") + ", " + usage + ")";
    }
}
