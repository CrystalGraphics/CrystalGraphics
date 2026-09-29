package com.crystalgraphics.platform.gl.tracked.gl;

import com.crystalgraphics.platform.device.format.CgAttribFormat;
import com.crystalgraphics.platform.device.pipeline.CgPipelineDesc;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.tracked.tracker.CgDrawState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Vertex array objects. An attribute pointer captures the buffer <b>object</b> bound when it was set; a draw reads
 * that object's storage as it is then, so an orphan between draws is seen. Attributes sharing a buffer, stride
 * and step become one vertex binding.
 */
public final class TrackedVertexArrays {

    static final int ATTRIBS = 16;

    static final class Attrib {
        boolean enabled;
        int buffer, size, type, stride, divisor;
        boolean normalized;
        long offset;
    }

    /** One vertex array object. Name 0 is the default one a draw with none bound uses. */
    public static final class Vao {
        final Attrib[] attribs = new Attrib[ATTRIBS];
        public int elementBuffer;
        private List<CgPipelineDesc.VertexBuffer> layouts;
        private int[] bindingBuffer = new int[0];
        private long[] bindingBase = new long[0];

        Vao() {
            for (int i = 0; i < ATTRIBS; i++) attribs[i] = new Attrib();
        }

        void changed() { layouts = null; }
    }

    private final TrackedGlErrors errors;
    private final GlNames<Vao> names = new GlNames<>("Vertex array");
    private final Vao defaultVao = new Vao();
    private Vao current = defaultVao;
    private int currentName;
    private TrackedBuffers buffers;

    public TrackedVertexArrays(TrackedGlErrors errors) {
        this.errors = errors;
    }

    public void buffers(TrackedBuffers buffers) { this.buffers = buffers; }

    public Vao current() { return current; }

    public int currentName() { return currentName; }

    public int gen() { return names.add(new Vao()); }

    public void bind(int name) {
        if (name != 0 && !names.exists(name)) {
            errors.invalidOperation("glBindVertexArray: " + name + " was never generated");
            return;
        }
        current = name == 0 ? defaultVao : names.get(name);
        currentName = name;
    }

    public void delete(int name) {
        if (names.remove(name) != null && currentName == name) bind(0);
    }

    public void enable(int index) {
        if (index >= ATTRIBS) { errors.invalidValue("glEnableVertexAttribArray " + index); return; }
        current.attribs[index].enabled = true;
        current.changed();
    }

    public void pointer(int index, int size, int type, boolean normalized, int stride, long offset) {
        if (index >= ATTRIBS) { errors.invalidValue("glVertexAttribPointer " + index); return; }
        if (buffers.array == 0) {
            errors.invalidOperation("glVertexAttribPointer with no GL_ARRAY_BUFFER bound: client arrays are not core");
            return;
        }
        format(size, type, normalized);
        Attrib a = current.attribs[index];
        a.buffer = buffers.array;
        a.size = size;
        a.type = type;
        a.normalized = normalized;
        a.stride = stride;
        a.offset = offset;
        current.changed();
    }

    public void divisor(int index, int divisor) {
        if (divisor > 1) throw new UnsupportedOperationException("glVertexAttribDivisor " + divisor + ": only 0 and 1");
        current.attribs[index].divisor = divisor;
        current.changed();
    }

    /** A deleted buffer leaves every attribute and element binding that named it pointing at nothing. */
    void bufferDeleted(int name) {
        if (current.elementBuffer == name) current.elementBuffer = 0;
        for (Attrib a : current.attribs) {
            if (a.buffer == name) { a.buffer = 0; current.changed(); }
        }
    }

    /** Puts the current VAO's layouts and each binding's storage now into {@code state}. */
    public void apply(CgDrawState state) {
        Vao v = current;
        if (v.layouts == null) build(v);
        state.vertexLayouts = v.layouts;
        for (int b = 0; b < v.bindingBuffer.length; b++) {
            TrackedBuffers.GlBuffer buffer = buffers.get(v.bindingBuffer[b]);
            if (buffer == null || buffer.storage.allocation() == null)
                throw new IllegalStateException("Vertex binding " + b + " reads a buffer with no storage");
            state.vertexBuffer(b, buffer.storage.allocation(), v.bindingBase[b]);
        }
    }

    private static void build(Vao v) {
        List<Integer> enabled = new ArrayList<>();
        for (int i = 0; i < ATTRIBS; i++) {
            if (v.attribs[i].enabled) enabled.add(i);
        }
        enabled.sort((x, y) -> {
            Attrib a = v.attribs[x], b = v.attribs[y];
            if (a.buffer != b.buffer) return Integer.compare(a.buffer, b.buffer);
            if (stride(a) != stride(b)) return Integer.compare(stride(a), stride(b));
            if (a.divisor != b.divisor) return Integer.compare(a.divisor, b.divisor);
            return Long.compare(a.offset, b.offset);
        });
        List<CgPipelineDesc.VertexBuffer> layouts = new ArrayList<>();
        List<Integer> buffersOf = new ArrayList<>();
        List<Long> bases = new ArrayList<>();
        List<CgPipelineDesc.VertexAttrib> group = null;
        Attrib first = null;
        for (int index : enabled) {
            Attrib a = v.attribs[index];
            if (a.buffer == 0) throw new IllegalStateException("Attribute " + index + " is enabled with no buffer");
            boolean same = first != null && a.buffer == first.buffer && stride(a) == stride(first)
                    && a.divisor == first.divisor && a.offset - first.offset < stride(first);
            if (!same) {
                if (group != null) close(layouts, group, first);
                group = new ArrayList<>();
                first = a;
                buffersOf.add(a.buffer);
                bases.add(a.offset);
            }
            group.add(new CgPipelineDesc.VertexAttrib(index, format(a.size, a.type, a.normalized), (int) (a.offset - first.offset)));
        }
        if (group != null) close(layouts, group, first);
        v.layouts = Collections.unmodifiableList(layouts);
        v.bindingBuffer = buffersOf.stream().mapToInt(Integer::intValue).toArray();
        v.bindingBase = bases.stream().mapToLong(Long::longValue).toArray();
    }

    private static void close(List<CgPipelineDesc.VertexBuffer> layouts, List<CgPipelineDesc.VertexAttrib> group, Attrib first) {
        layouts.add(new CgPipelineDesc.VertexBuffer(layouts.size(), stride(first), first.divisor == 1,
                Collections.unmodifiableList(group)));
    }

    /** GL's stride 0 means tightly packed. */
    private static int stride(Attrib a) {
        return a.stride != 0 ? a.stride : a.size * bytes(a.type);
    }

    private static int bytes(int type) {
        switch (type) {
            case CgGL.GL_BYTE: case CgGL.GL_UNSIGNED_BYTE: return 1;
            case CgGL.GL_SHORT: case CgGL.GL_UNSIGNED_SHORT: case CgGL.GL_HALF_FLOAT: return 2;
            default: return 4;
        }
    }

    /**
     * GL's (size, type, normalized) as a vertex format. Integer data reaches a float attribute here, normalised
     * or scaled; three components read as four, the fourth unused.
     */
    static CgAttribFormat format(int size, int type, boolean normalized) {
        int n = size == 1 ? 1 : size == 2 ? 2 : 4;
        switch (type) {
            case CgGL.GL_FLOAT:
                return size == 1 ? CgAttribFormat.FLOAT32 : size == 2 ? CgAttribFormat.FLOAT32X2
                        : size == 3 ? CgAttribFormat.FLOAT32X3 : CgAttribFormat.FLOAT32X4;
            case CgGL.GL_HALF_FLOAT:
                if (n == 1) break;
                return n == 2 ? CgAttribFormat.FLOAT16X2 : CgAttribFormat.FLOAT16X4;
            case CgGL.GL_UNSIGNED_BYTE:
                if (n == 1) break;
                return n == 2 ? (normalized ? CgAttribFormat.UNORM8X2 : CgAttribFormat.USCALED8X2)
                        : (normalized ? CgAttribFormat.UNORM8X4 : CgAttribFormat.USCALED8X4);
            case CgGL.GL_BYTE:
                if (n == 1) break;
                return n == 2 ? (normalized ? CgAttribFormat.SNORM8X2 : CgAttribFormat.SSCALED8X2)
                        : (normalized ? CgAttribFormat.SNORM8X4 : CgAttribFormat.SSCALED8X4);
            case CgGL.GL_UNSIGNED_SHORT:
                if (n == 1) break;
                return n == 2 ? (normalized ? CgAttribFormat.UNORM16X2 : CgAttribFormat.USCALED16X2)
                        : (normalized ? CgAttribFormat.UNORM16X4 : CgAttribFormat.USCALED16X4);
            case CgGL.GL_SHORT:
                if (n == 1) break;
                return n == 2 ? (normalized ? CgAttribFormat.SNORM16X2 : CgAttribFormat.SSCALED16X2)
                        : (normalized ? CgAttribFormat.SNORM16X4 : CgAttribFormat.SSCALED16X4);
            default:
                break;
        }
        throw new UnsupportedOperationException("A vertex attribute of " + size + " x 0x" + Integer.toHexString(type)
                + (normalized ? " normalized" : "") + " has no device format");
    }

    public int query(int pname, double[] out) {
        if (pname == CgGL.GL_VERTEX_ARRAY_BINDING) return TrackedRenderState.one(out, currentName);
        return -1;
    }
}
