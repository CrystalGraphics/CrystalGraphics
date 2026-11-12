package com.crystalgraphics.render.graph;

import com.crystalgraphics.gpu.resource.CgReadback;
import com.crystalgraphics.render.CgCpuMeter;
import com.crystalgraphics.render.CgGpuBudget;
import com.crystalgraphics.render.draw.CgPipeline;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * One unit of graph work, made through a {@link CgRecording}: a raster pass ({@link CgRasterPass}), a compute pass
 * ({@link CgComputePass}), or a copy, an upload, a fill, a readback, a callback, a compile or a release. Ordered by what it reads
 * and writes, not by when it was made.
 *
 * <ul>
 *   <li>A pass with an effect beyond the frame — writing a resource that is not transient, or carrying a
 *       {@link CgRequest} — always runs; any other runs only if something that runs reads what it writes.</li>
 * </ul>
 */
public abstract sealed class CgPass permits CgRasterPass, CgComputePass, CgPass.Copy, CgPass.Upload, CgPass.Callback,
        CgPass.Include, CgPass.Compile, CgPass.Release, CgPass.Fill, CgPass.Update, CgPass.BufferCopy, CgPass.BufferRelease, CgPass.Readback {

    /** Not final for a raster pass, which its recording reuses after a reset. */
    String name;
    @Nullable
    CgGraphTexture target;
    @Nullable
    final CgRequest request;
    /** Its GPU zone's name id ({@code CgGpuTrace.name}), or -1 when it is not timed on its own. */
    int gpuZone = -1;
    /** What its GPU time is charged to, or null. */
    @Nullable
    CgGpuBudget budget;
    /** What its execution's CPU time is charged to, or null. */
    @Nullable
    CgCpuMeter meter;
    /**
     * Frames built with this pass and not yet recycled: a frame reads its passes when it executes, so its recording
     * reuses a pass only once no frame holds it. Any thread: a frame may be recycled on another.
     */
    private volatile int holds;
    private static final AtomicIntegerFieldUpdater<CgPass> HOLDS = AtomicIntegerFieldUpdater.newUpdater(CgPass.class, "holds");

    void hold() {
        HOLDS.incrementAndGet(this);
    }

    void release() {
        HOLDS.decrementAndGet(this);
    }

    boolean held() {
        return holds > 0;
    }

    /** Drops what a reusable pass names once its recording resets; nothing for a kind never reused. */
    void clearReferences() {
    }

    CgPass(String name, @Nullable CgGraphTexture target, @Nullable CgRequest request) {
        this.name = name;
        this.target = target;
        this.request = request;
    }

    public String name() {
        return name;
    }

    /** What it writes, or null. */
    @Nullable
    public CgGraphTexture target() {
        return target;
    }

    /** Whether no cull may remove it. */
    boolean sideEffect() {
        return request != null || (target != null && target.outlivesFrame());
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + name + ")";
    }

    /** A region of one texture into a region of another, in each one's pixels, origin bottom-left as GL's. */
    static final class Copy extends CgPass {
        final CgGraphTexture from;
        final int x, y, w, h, tx, ty, tw, th;
        final boolean linear;

        Copy(CgGraphTexture from, int x, int y, int w, int h, CgGraphTexture to, int tx, int ty, int tw, int th,
             boolean linear) {
            super("copy " + from.name() + " -> " + to.name(), to, null);
            this.from = from;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.tx = tx;
            this.ty = ty;
            this.tw = tw;
            this.th = th;
            this.linear = linear;
        }
    }

    /** Bytes into a box of a texture's colour 0, in its type's layout ({@link CgRecording#update}). */
    static final class Upload extends CgPass {
        final int level, x, y, z, w, h, d;
        final byte[] bytes;

        Upload(CgGraphTexture target, int level, int x, int y, int z, int w, int h, int d, byte[] bytes, CgRequest request) {
            super("update " + target.name(), target, request);
            this.level = level;
            this.x = x;
            this.y = y;
            this.z = z;
            this.w = w;
            this.h = h;
            this.d = d;
            this.bytes = bytes;
        }
    }

    /** Code run with a target bound and GL state scoped: what is not recorded yet. */
    static final class Callback extends CgPass {
        final Runnable body;
        /** A depth of another texture it draws with beside its target, or null. */
        @Nullable
        final CgGraphTexture depth;

        Callback(String name, @Nullable CgGraphTexture target, @Nullable CgGraphTexture depth, Runnable body, CgRequest request) {
            super(name, target, request);
            this.depth = depth;
            this.body = body;
        }
    }

    /**
     * A frame recorded and built elsewhere, executed at this step of the frame that holds it ({@link CgRecording#include}):
     * on the next nesting depth's executor, so the outer frame's uploads and bindings stand when it returns, with
     * nothing built or executed again around it. Reused by its recording after a reset.
     *
     * <pre>{@code
     * recording.include(ui).timed(GPU_UI).then(FRAME_END);   // a held runnable: none is made a frame
     * }</pre>
     */
    public static final class Include extends CgPass {
        @Nullable
        CgFrame frame;
        boolean again, keepRequested;
        @Nullable
        Runnable then;

        Include() {
            super("include", null, null);
        }

        Include reuse(CgFrame frame, boolean again, boolean keepRequested) {
            this.frame = frame;
            this.again = again;
            this.keepRequested = keepRequested;
            gpuZone = -1;
            then = null;
            return this;
        }

        /** Times its execution as {@code gpu:<zone>}, a name made once with {@code CgGpuTrace.name}. */
        public Include timed(int zone) {
            gpuZone = zone;
            return this;
        }

        /** Runs {@code after} on the render thread once it has executed, or failed. */
        public Include then(Runnable after) {
            then = after;
            return this;
        }

        /** Executes the frame at the executor's next depth, then {@link #then}. */
        void execute() {
            try {
                if (again) CgExecutor.executeAgain(frame, keepRequested);
                else CgExecutor.execute(frame);
            } finally {
                if (then != null) then.run();
            }
        }

        @Override
        void clearReferences() {
            frame = null;
            then = null;
        }
    }

    /** A pipeline's program, compiled without waiting where the driver allows. */
    static final class Compile extends CgPass {
        final CgPipeline pipeline;

        Compile(CgPipeline pipeline, CgRequest request) {
            super("compile " + pipeline, null, request);
            this.pipeline = pipeline;
        }
    }

    /** A buffer range set to one 32-bit value. Reused by its recording after a reset. */
    static final class Fill extends CgPass {
        CgGraphBuffer buffer;
        long offset, size;
        int value;

        Fill(CgGraphBuffer buffer, long offset, long size, int value) {
            super("fill " + buffer.name(), null, null);
            reuse(buffer, offset, size, value);
        }

        void reuse(CgGraphBuffer buffer, long offset, long size, int value) {
            name = buffer.fillName();
            this.buffer = buffer;
            this.offset = offset;
            this.size = size;
            this.value = value;
        }

        @Override
        void clearReferences() {
            buffer = null;
        }
    }

    /** Bytes into a buffer, copied when recorded; or a caller's floats, read when executed. */
    static final class Update extends CgPass {
        CgGraphBuffer buffer;
        long offset;
        @Nullable
        byte[] bytes;
        @Nullable
        float[] floats;
        @Nullable
        int[] ints;
        int from;
        /** In bytes. */
        int size;
        /** The bytes a reused update copies into, grown as needed: {@link #bytes} while it holds bytes. */
        @Nullable
        private byte[] owned;

        Update(CgGraphBuffer buffer, long offset, byte[] bytes) {
            super("update " + buffer.name(), null, null);
            this.buffer = buffer;
            this.offset = offset;
            this.bytes = bytes;
            this.size = bytes.length;
        }

        /** Words read when executed, {@code floats} or {@code ints}: one is null. Reused by its recording after a reset. */
        Update(CgGraphBuffer buffer, long offset, @Nullable float[] floats, @Nullable int[] ints, int from, int count) {
            super("update " + buffer.name(), null, null);
            reuse(buffer, offset, floats, ints, from, count);
        }

        void reuse(CgGraphBuffer buffer, long offset, @Nullable float[] floats, @Nullable int[] ints, int from, int count) {
            name = buffer.updateName();
            this.buffer = buffer;
            this.offset = offset;
            this.bytes = null;
            this.floats = floats;
            this.ints = ints;
            this.from = from;
            this.size = count * 4;
        }

        /** Bytes copied now into its own array, reused after a reset: {@code data}'s remaining bytes. */
        void reuse(CgGraphBuffer buffer, long offset, ByteBuffer data) {
            reuse(buffer, offset, null, null, 0, 0);
            int n = data.remaining();
            if (owned == null || owned.length < n) owned = new byte[Math.max(n, owned == null ? 0 : owned.length * 2)];
            int at = data.position();   // read and put back: a duplicate a call was an allocation a frame
            data.get(owned, 0, n);
            data.position(at);
            bytes = owned;
            size = n;
        }

        @Override
        void clearReferences() {
            buffer = null;
            floats = null;
            ints = null;
        }
    }

    /** A range of one buffer into another. */
    static final class BufferCopy extends CgPass {
        final CgGraphBuffer from, to;
        final long fromOffset, toOffset, size;

        BufferCopy(CgGraphBuffer from, long fromOffset, CgGraphBuffer to, long toOffset, long size) {
            super("copy " + from.name() + " -> " + to.name(), null, null);
            this.from = from;
            this.fromOffset = fromOffset;
            this.to = to;
            this.toOffset = toOffset;
            this.size = size;
        }
    }

    /** A buffer range, or a region of one texture level, read back to the CPU and handed to a sink frames later. */
    static final class Readback extends CgPass implements CgReadback.Sink {
        @Nullable
        CgGraphBuffer buffer;
        long offset, size;
        @Nullable
        final CgGraphTexture texture;
        final int level, x, y, z, w, h, d;
        private CgReadback.Sink sink;
        /** Issued and not yet heard from: the pass is the readback's sink, so a recording reuses it only after. */
        volatile boolean delivering;

        /** A buffer range read back again, for {@link CgRecording#fetch}: renamed only for another buffer. */
        void reuse(CgGraphBuffer buffer, long offset, long size, CgReadback.Sink sink) {
            if (this.buffer != buffer || name == null) name = "readback " + buffer.name();
            this.buffer = buffer;
            this.offset = offset;
            this.size = size;
            this.sink = sink;
        }

        @Override
        boolean held() {
            return super.held() || delivering;
        }

        /** Its sink is outside the graph, with a request or none ({@link CgRecording#fetch}): RDG's readbacks never cull. */
        @Override
        boolean sideEffect() {
            return true;
        }

        @Override
        void clearReferences() {
            sink = null;
        }


        Readback(CgGraphBuffer buffer, long offset, long size, CgReadback.Sink sink, CgRequest request) {
            super("readback " + buffer.name(), null, request);
            this.buffer = buffer;
            this.offset = offset;
            this.size = size;
            this.texture = null;
            this.level = this.x = this.y = this.z = this.w = this.h = this.d = 0;
            this.sink = sink;
        }

        Readback(CgGraphTexture texture, int level, int x, int y, int z, int w, int h, int d, CgReadback.Sink sink,
                 CgRequest request) {
            super("readback " + texture.name() + " level " + level, null, request);
            this.buffer = null;
            this.offset = this.size = 0;
            this.texture = texture;
            this.level = level;
            this.x = x;
            this.y = y;
            this.z = z;
            this.w = w;
            this.h = h;
            this.d = d;
            this.sink = sink;
        }

        @Override
        public void accept(ByteBuffer data) {
            delivering = false;
            sink.accept(data);
            if (request != null) request.complete();
        }

        @Override
        public void failed(String reason) {
            delivering = false;
            if (request != null) request.fail(reason);
            sink.failed(reason);
        }
    }

    /** A persistent or history buffer's storage freed, after its last use. */
    static final class BufferRelease extends CgPass {
        final CgGraphBuffer buffer;

        BufferRelease(CgGraphBuffer buffer) {
            super("release " + buffer.name(), null, null);
            this.buffer = buffer;
        }

        @Override
        boolean sideEffect() {
            return true;
        }
    }

    /** A requested texture's storage freed, after its last reader. */
    static final class Release extends CgPass {
        Release(CgGraphTexture texture) {
            super("release " + texture.name(), texture, null);
        }

        @Override
        boolean sideEffect() {
            return true;
        }
    }
}
