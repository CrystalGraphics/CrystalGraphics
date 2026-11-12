package com.crystalgraphics.api.buffer;

import com.crystalgraphics.gpu.CgDeferral;
import com.crystalgraphics.gpu.CgUploadLease;
import com.crystalgraphics.gpu.CgUploads;
import com.crystalgraphics.gpu.resource.CgStreamBuffer;
import com.crystalgraphics.render.draw.CgBufferHandle;
import com.crystalgraphics.render.graph.CgGraphBuffer;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * A buffer of your own, kept across frames: {@code count} records of a {@link CgBufferLayout}, written when they change
 * and read by any material or kernel that binds it. Made by {@link CgBuffers#create}.
 *
 * <pre>{@code
 * static final CgBufferLayout HEIGHT = CgBufferLayout.std430("Height").float_("height").build();
 *
 * CgBuffer heights = CgBuffers.create("mymod.heights", HEIGHT, cells);
 * heights.write(0, cells, records -> {                       // any thread; lands before the next frame
 *     for (int i = 0; i < cells; i++) records.next().float_("height", h[i]);
 * });
 * material.buffer("HEIGHTS", heights);                      // Buffers { HEIGHTS ("Heights", Height, readonly) }
 * pass.dispatch(kernel, cells).bind("HEIGHTS", heights);
 * heights.release();
 * }</pre>
 *
 * <ul>
 *   <li>A write of some records keeps the rest: write only what moved.</li>
 *   <li>Bound where the shader's struct matches the layout, field by field; a mismatch throws naming both.</li>
 *   <li>For data rewritten every frame, {@code rec.upload} is the cheaper kind: it lives in the frame's memory.</li>
 * </ul>
 */
public final class CgBuffer implements CgBufferHandle {

    private final String name;
    private final CgBufferLayout layout;
    private final int count;
    /** What the device holds, the CPU's copy: a partial write uploads it whole. Guarded by {@code this}. */
    private final float[] contents;
    private final CgBufferRecords records;
    private final CgDeferral gpu = new CgDeferral();
    private final Consumer<CgUploadLease> landing = this::land;
    private CgStreamBuffer storage;
    private volatile boolean released;
    private CgGraphBuffer graph;

    CgBuffer(String name, CgBufferLayout layout, int count) {
        if (count < 1) throw new IllegalArgumentException(name + ": a buffer of " + count + " records");
        this.name = Objects.requireNonNull(name, "name");
        this.layout = Objects.requireNonNull(layout, "layout");
        this.count = count;
        this.contents = new float[count * layout.getFloatCount()];
        this.records = new CgBufferRecords(layout);
        int bytes = layout.getStride() * count;
        gpu.run(() -> {
            storage = CgStreamBuffer.createForShaderBuffer(CgStreamBuffer.Use.STORAGE, bytes);
            storage.uploadFloats(contents, contents.length);   // zeroed until written
        });
    }

    /**
     * Writes {@code count} records from record {@code first}, the rest kept. Any thread; lands before the next frame.
     *
     * @throws IndexOutOfBoundsException for records past its count
     */
    public synchronized void write(int first, int count, Consumer<CgBufferRecords> writer) {
        if (released) throw new IllegalStateException(name + " is released");
        if (first < 0 || count < 0 || first + count > this.count) {
            throw new IndexOutOfBoundsException(name + ": records " + first + " to " + (first + count) + " of " + this.count);
        }
        records.begin(count);
        writer.accept(records);
        int floats = records.end();
        System.arraycopy(records.floats(), 0, contents, first * layout.getFloatCount(), floats);
        CgUploadLease lease = CgUploads.lease(contents.length * Float.BYTES).put(contents, 0, contents.length);
        gpu.run(lease.into(landing, 0, 0, 0, 0, contents.length, 1, 1, 0, 0));
    }

    private void land(CgUploadLease lease) {
        storage.uploadFrom(lease);
    }

    /** Releases it, once the frames reading it retire. Any thread; idempotent. */
    public void release() {
        if (released) return;
        released = true;
        gpu.clear();
        gpu.run(() -> {
            if (storage != null) storage.delete();
            storage = null;
        });
    }

    public String name() {
        return name;
    }

    public CgBufferLayout layout() {
        return layout;
    }

    /** Its records. */
    public int count() {
        return count;
    }

    /** Its size in bytes. */
    public long bytes() {
        return (long) layout.getStride() * count;
    }

    /**
     * The frame graph's view of it, made once: what a pass binds, copies from or reads back, ordered as a buffer the
     * graph did not make.
     */
    public synchronized CgGraphBuffer graphBuffer() {
        if (graph == null) graph = CgGraphBuffer.imported(name, this, bytes());
        return graph;
    }

    /** Render thread: its storage's device name, its writes landed first; 0 once released. */
    @Override
    public int bufferId() {
        gpu.flush();
        return storage == null ? 0 : storage.getGlBuffer();
    }

    @Override
    public String toString() {
        return "CgBuffer(" + name + ", " + count + " " + layout.getGlslName() + ")";
    }
}
