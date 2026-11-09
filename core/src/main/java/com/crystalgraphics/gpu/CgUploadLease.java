package com.crystalgraphics.gpu;

import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.function.Consumer;

/**
 * Staging memory for one upload, written by whoever made the bytes, on any thread. The texture or buffer it is handed
 * to lands it on the render thread with no further CPU copy: a GPU copy from a mapped device buffer, else from direct
 * memory the device writes at the call.
 *
 * <pre>{@code
 * CgUploadLease lease = CgUploads.lease(4 * w * h);         // any thread
 * decoder.decodeInto(lease.bytes());                        // write it once, relatively
 * texture.uploadRegion(0, x, y, w, h, lease, GL_RGBA, GL_UNSIGNED_BYTE);   // the texture's now
 *
 * CgUploadLease copy = CgUploads.lease(pixels.remaining());
 * copy.put(pixels);                                         // a buffer you keep: one copy, on this thread
 * }</pre>
 *
 * <ul>
 *   <li>Hand it to exactly one upload, or {@link #release()} it. A lease never handed over keeps its block from being
 *       reused.</li>
 *   <li>Its bytes are write-only: unpack-buffer memory is slow to read, and the GPU may already be reading it.</li>
 *   <li>Its size is fixed at {@link CgUploads#lease}; write exactly that many bytes.</li>
 *   <li>Land it while the context it was leased in is current: a context's teardown frees its blocks.</li>
 * </ul>
 */
public final class CgUploadLease implements Runnable {

    CgUploads.Block block;
    int offset, size;

    /** The upload it was handed to: who lands it, and where. */
    private Consumer<CgUploadLease> sink;
    private int level, x, y, z, width, height, depth, format, type;

    /** Views of {@link #block}'s memory, kept while the lease object is reused on the same block. */
    private CgUploads.Block viewed;
    private ByteBuffer view;
    private FloatBuffer floats;
    private ByteBuffer exposed;

    CgUploadLease() {}

    /** Its size in bytes. */
    public int size() {
        return size;
    }

    /** Whether it is direct memory rather than a device buffer: what the device writes at the call. */
    public boolean direct() {
        return block.buffer == null;
    }

    /**
     * Its memory, positioned at 0 with {@link #size()} remaining, in native order: for a producer that writes the
     * bytes itself. The first call on each lease allocates the view.
     */
    public ByteBuffer bytes() {
        if (exposed == null) {
            ByteBuffer m = at();
            exposed = m.slice().order(ByteOrder.nativeOrder());
        }
        return exposed;
    }

    /** Copies {@code from}'s remaining bytes in from the start, its position left alone. Allocates nothing. */
    public CgUploadLease put(ByteBuffer from) {
        if (from.remaining() > size) throw new IllegalArgumentException(from.remaining() + " bytes into a " + size + "-byte lease");
        int position = from.position();
        at().put(from);
        from.position(position);
        return this;
    }

    /** Copies {@code length} bytes of {@code from} in from the start. */
    public CgUploadLease put(byte[] from, int start, int length) {
        if (length > size) throw new IllegalArgumentException(length + " bytes into a " + size + "-byte lease");
        at().put(from, start, length);
        return this;
    }

    /** Copies {@code from}'s remaining floats in from the start, its position left alone. Allocates nothing once warm. */
    public CgUploadLease put(FloatBuffer from) {
        if (4L * from.remaining() > size) throw new IllegalArgumentException(from.remaining() + " floats into a " + size + "-byte lease");
        at();
        floats.limit((offset + size) >> 2);
        floats.position(offset >> 2);
        int position = from.position();
        floats.put(from);
        from.position(position);
        return this;
    }

    /** Copies {@code count} floats of {@code from}, from {@code start}, in from the start. Allocates nothing once warm. */
    public CgUploadLease put(float[] from, int start, int count) {
        if (4L * count > size) throw new IllegalArgumentException(count + " floats into a " + size + "-byte lease");
        at();
        floats.limit((offset + size) >> 2);
        floats.position(offset >> 2);
        floats.put(from, start, count);
        return this;
    }

    /** Gives it back unused: what to do with one that will not be handed to an upload. */
    public void release() {
        CgUploads.dropped(this);
    }

    // ── engine side: a texture hands it its upload, and lands it on the render thread ────────────────────────────

    /**
     * Engine: records where it goes, for {@code sink} to land on the render thread. A texture calls this, then hands the
     * lease to its {@link CgDeferral}.
     */
    public CgUploadLease into(Consumer<CgUploadLease> sink, int level, int x, int y, int z, int width, int height,
                              int depth, int format, int type) {
        this.sink = sink;
        this.level = level;
        this.x = x;
        this.y = y;
        this.z = z;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.format = format;
        this.type = type;
        return this;
    }

    public int level() { return level; }
    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }
    public int width() { return width; }
    public int height() { return height; }
    public int depth() { return depth; }
    public int format() { return format; }
    public int type() { return type; }

    /** Engine: the device buffer holding it, which a GPU copy reads; null where it is direct memory. */
    public CgGpuBuffer unpackBuffer() {
        return block.buffer;
    }

    /** Engine: where in {@link #unpackBuffer} it starts. */
    public int unpackOffset() {
        return offset;
    }

    /** Engine, render thread: {@code length} of its bytes from byte {@code from}, for a device's write to read. */
    public ByteBuffer range(int from, int length) {
        ByteBuffer m = at();
        m.position(offset + from);
        m.limit(offset + from + length);
        return m;
    }

    /** Lands it through its sink, then gives it back: what the deferral runs. */
    @Override
    public void run() {
        try {
            sink.accept(this);
        } finally {
            CgUploads.landed(this);
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────────────────────────────────────

    /** Leased: {@code size} bytes at {@code offset} of {@code block}. */
    void lease(CgUploads.Block block, int offset, int size) {
        this.block = block;
        this.offset = offset;
        this.size = size;
        this.exposed = null;
        this.sink = null;
    }

    /** The block's memory positioned at this lease, limited to it. */
    private ByteBuffer at() {
        if (viewed != block) {
            view = block.memory.duplicate().order(ByteOrder.nativeOrder());
            floats = view.asFloatBuffer();
            viewed = block;
        }
        view.limit(offset + size);
        view.position(offset);
        return view;
    }
}
