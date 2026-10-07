package com.crystalgraphics.render.graph;

import com.crystalgraphics.gl.buffer.CgReadback;
import com.crystalgraphics.render.CgGpuBudget;
import com.crystalgraphics.render.draw.CgPipeline;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;

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
        CgPass.Compile, CgPass.Release, CgPass.Fill, CgPass.Update, CgPass.BufferCopy, CgPass.BufferRelease, CgPass.Readback {

    final String name;
    @Nullable
    final CgGraphTexture target;
    @Nullable
    final CgRequest request;
    /** Its GPU zone's name id ({@code CgGpuTrace.name}), or -1 when it is not timed on its own. */
    int gpuZone = -1;
    /** What its GPU time is charged to, or null. */
    @Nullable
    CgGpuBudget budget;

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

    /** Bytes into a texture. */
    static final class Upload extends CgPass {
        final CgUpload writer;

        Upload(CgGraphTexture target, CgUpload writer, CgRequest request) {
            super("upload " + target.name(), target, request);
            this.writer = writer;
        }
    }

    /** Code run with a target bound and GL state scoped: what is not recorded yet. */
    static final class Callback extends CgPass {
        final Runnable body;

        Callback(String name, @Nullable CgGraphTexture target, Runnable body, CgRequest request) {
            super(name, target, request);
            this.body = body;
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

    /** A buffer range set to one 32-bit value. */
    static final class Fill extends CgPass {
        final CgGraphBuffer buffer;
        final long offset, size;
        final int value;

        Fill(CgGraphBuffer buffer, long offset, long size, int value) {
            super("fill " + buffer.name(), null, null);
            this.buffer = buffer;
            this.offset = offset;
            this.size = size;
            this.value = value;
        }
    }

    /** Bytes into a buffer, copied when recorded; or a caller's floats, read when executed. */
    static final class Update extends CgPass {
        final CgGraphBuffer buffer;
        final long offset;
        @Nullable
        final byte[] bytes;
        @Nullable
        final float[] floats;
        final int from;
        /** In bytes. */
        final int size;

        Update(CgGraphBuffer buffer, long offset, byte[] bytes) {
            super("update " + buffer.name(), null, null);
            this.buffer = buffer;
            this.offset = offset;
            this.bytes = bytes;
            this.floats = null;
            this.from = 0;
            this.size = bytes.length;
        }

        Update(CgGraphBuffer buffer, long offset, float[] floats, int from, int count) {
            super("update " + buffer.name(), null, null);
            this.buffer = buffer;
            this.offset = offset;
            this.bytes = null;
            this.floats = floats;
            this.from = from;
            this.size = count * Float.BYTES;
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
        final CgGraphBuffer buffer;
        final long offset, size;
        @Nullable
        final CgGraphTexture texture;
        final int level, x, y, z, w, h, d;
        private final CgReadback.Sink sink;

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
            sink.accept(data);
            request.complete();
        }

        @Override
        public void failed(String reason) {
            request.fail(reason);
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
