package com.crystalgraphics.render.graph;

import com.crystalgraphics.render.draw.CgPipeline;

import javax.annotation.Nullable;

/**
 * One unit of graph work, made through a {@link CgRecording}: a raster pass ({@link CgRasterPass}), a compute pass
 * ({@link CgComputePass}), or a copy, an upload, a fill, a callback, a compile or a release. Ordered by what it reads
 * and writes, not by when it was made.
 *
 * <ul>
 *   <li>A pass with an effect beyond the frame — writing a resource that is not transient, or carrying a
 *       {@link CgRequest} — always runs; any other runs only if something that runs reads what it writes.</li>
 * </ul>
 */
public abstract sealed class CgPass permits CgRasterPass, CgComputePass, CgPass.Copy, CgPass.Upload, CgPass.Callback,
        CgPass.Compile, CgPass.Release, CgPass.Fill, CgPass.Update, CgPass.BufferCopy, CgPass.BufferRelease {

    final String name;
    @Nullable
    final CgGraphTexture target;
    @Nullable
    final CgRequest request;
    /** Its GPU zone's name id ({@code CgGpuTrace.name}), or -1 when it is not timed on its own. */
    int gpuZone = -1;

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

    /** Bytes into a buffer, copied when recorded. */
    static final class Update extends CgPass {
        final CgGraphBuffer buffer;
        final long offset;
        final byte[] bytes;

        Update(CgGraphBuffer buffer, long offset, byte[] bytes) {
            super("update " + buffer.name(), null, null);
            this.buffer = buffer;
            this.offset = offset;
            this.bytes = bytes;
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
