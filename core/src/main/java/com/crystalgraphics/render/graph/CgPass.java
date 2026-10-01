package com.crystalgraphics.render.graph;

import com.crystalgraphics.render.draw.CgPipeline;

import javax.annotation.Nullable;

/**
 * One unit of graph work, made through a {@link CgRecording}: a raster pass ({@link CgRasterPass}), or a copy, an
 * upload, a callback, a compile or a release. Ordered by what it reads and writes, not by when it was made.
 *
 * <ul>
 *   <li>A pass with an effect beyond the frame — writing a texture that is not transient, or carrying a
 *       {@link CgRequest} — always runs; any other runs only if something that runs reads what it writes.</li>
 * </ul>
 */
public abstract sealed class CgPass permits CgRasterPass, CgPass.Copy, CgPass.Upload, CgPass.Callback, CgPass.Compile,
        CgPass.Release {

    final String name;
    @Nullable
    final CgGraphTexture target;
    @Nullable
    final CgRequest request;

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
