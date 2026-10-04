package com.crystalgraphics.compute.cpu;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gl.texture.CgTexture3D;
import com.crystalgraphics.compute.CgDispatchBindings;
import com.crystalgraphics.compute.emit.CgPropertyBlock;
import com.crystalgraphics.compute.source.CgBufferAccess;
import com.crystalgraphics.compute.source.CgBufferAccessor;
import com.crystalgraphics.compute.source.CgBufferDecl;
import com.crystalgraphics.compute.source.CgComputeSource;
import com.crystalgraphics.compute.source.CgImageAccessor;
import com.crystalgraphics.compute.source.CgImageDecl;
import com.crystalgraphics.compute.source.CgImageFormat;
import com.crystalgraphics.compute.source.CgKernelDecl;
import com.crystalgraphics.compute.source.CgKernelShape;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.RecursiveAction;

/**
 * Runs a dispatch as its kernel's Java body: the CPU tier (gpu-compute C6), where no GPU tier can run the kernel or the
 * tier is forced. The buffers it binds are the CPU copies {@link CgCpuMirrors} keeps; what the body writes is uploaded
 * before the next pass, so the GPU and the CPU see one buffer. Render thread, at the dispatch's place in the frame.
 *
 * <pre>{@code
 * CgCpuRunner.dispatch(source, kernel, keywords, body, bindings, values, constants, block, samplers);
 * }</pre>
 *
 * <ul>
 *   <li>A map, gather, append or image kernel's body runs on several workers, a range of elements each; a scatter or
 *       general kernel's runs once over every element, on the calling thread, so its writes at any index are ordered.</li>
 *   <li>Appends land in element order however the ranges ran, after what the counter held, and only while they fit;
 *       the counter counts every one, as on the GPU.</li>
 *   <li>An image is read whole before the body runs and written whole after, where the kernel writes it; a sampler
 *       property's texture is read whole, every level, where the kernel names it.</li>
 * </ul>
 */
public final class CgCpuRunner {

    private static final int ELEMENTS = CgTrace.name("compute.cpu-elements");
    private static final int RUN = CgTrace.name("compute.cpu");
    /** Fewer elements than this a range are not worth a worker. */
    private static final int MIN_RANGE = 1024;

    /** The pool, made on the first dispatch that needs one: a server, or a context with compute, starts no thread. */
    private static final class Workers {
        static final ForkJoinPool POOL = new ForkJoinPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 1),
                pool -> {
                    ForkJoinWorkerThread t = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
                    t.setName("crystalgraphics-compute-" + t.getPoolIndex());
                    t.setDaemon(true);
                    return t;
                }, null, false);
    }

    /** One range of a dispatch, reused: a task forked again after {@link #reinitialize()}. */
    private static final class Range extends RecursiveAction {
        CgCpuBody body;
        CgCpuDispatch dispatch;

        @Override
        protected void compute() {
            body.run(dispatch);
        }
    }

    /** What every dispatch of one file shares: its views, counts and images, and the ranges it has used. */
    private static final class State {
        final CgCpuBuffer[] views;
        final int[] counts;
        final CgCpuImage[] images;
        final CgCpuImage[][] textures;
        CgCpuDispatch[] ranges = new CgCpuDispatch[0];
        Range[] tasks = new Range[0];
        /** The ranges the dispatch running now uses. */
        int active;
        final Map<CgKernelDecl, Plan> plans = new IdentityHashMap<>();

        State(CgComputeSource source) {
            views = new CgCpuBuffer[source.buffers().size()];
            for (CgBufferDecl b : source.buffers()) views[b.index()] = new CgCpuBuffer(b, null, 0, 0);
            counts = new int[views.length];
            images = new CgCpuImage[source.images().size()];
            textures = new CgCpuImage[source.properties().size()][];
        }
    }

    /** What one kernel writes and appends to, by buffer and image index: worked out once, read every dispatch. */
    private static final class Plan {
        final boolean[] writes, appends, writesImage;

        Plan(CgComputeSource source, CgKernelDecl kernel) {
            writes = new boolean[source.buffers().size()];
            appends = new boolean[writes.length];
            writesImage = new boolean[source.images().size()];
            for (CgBufferDecl d : source.buffers()) {
                for (CgBufferAccessor a : WRITERS) writes[d.index()] |= kernel.accessors().contains(d.name() + a.suffix);
                appends[d.index()] = d.access() == CgBufferAccess.APPEND
                        && kernel.accessors().contains(d.name() + CgBufferAccessor.APPEND.suffix);
            }
            for (CgImageDecl image : source.images()) {
                for (CgImageAccessor a : CgImageAccessor.values()) {
                    boolean writer = a != CgImageAccessor.LOAD && a != CgImageAccessor.SIZE;
                    writesImage[image.index()] |= writer && kernel.accessors().contains(image.name() + a.suffix);
                }
            }
        }
    }

    private static final Map<CgComputeSource, State> STATES = new IdentityHashMap<>();
    private static ByteBuffer texels;

    private CgCpuRunner() {}

    /**
     * Runs {@code body} as kernel {@code kernel} of {@code source} over what {@code b} binds, with its property
     * {@code values} as {@code block} lays them out, the pass's {@code constants} (null for none) and the textures of
     * its sampler properties by unit.
     */
    public static void dispatch(CgComputeSource source, CgKernelDecl kernel, Set<String> keywords, CgCpuBody body,
                                CgDispatchBindings b, @Nullable float[] values, @Nullable float[] constants,
                                CgPropertyBlock block, CgTexture[] samplers) {
        State state = STATES.computeIfAbsent(source, State::new);
        int cx, cy, cz;
        if (b.isIndirect()) {
            int at = (int) (b.argsOffset() >>> 2);
            int[] args = CgCpuMirrors.words(b.args(), b.argsOffset() + 12);
            cx = args[at] * kernel.sizeX();
            cy = args[at + 1] * kernel.sizeY();
            cz = args[at + 2] * kernel.sizeZ();
        } else {
            cx = b.x();
            cy = b.y();
            cz = b.z();
        }
        long total = (long) cx * cy * cz;
        if (total <= 0) return;
        if (total > Integer.MAX_VALUE) throw new IllegalStateException(kernel.name() + " dispatches " + total + " elements");
        Plan plan = state.plans.get(kernel);
        if (plan == null) state.plans.put(kernel, plan = new Plan(source, kernel));
        try (CgTrace.Zone zone = CgTrace.zone(CgChannels.GL, RUN)) {
            bind(source, b, state);
            readTextures(kernel, block, samplers, state);
            run(source, kernel, keywords, body, values, constants, block, state, cx, cy, cz, (int) total);
            appendAll(source, plan, b, state);
            uploadWrites(source, plan, b, state);
        }
        CgTrace.add(CgChannels.GL, ELEMENTS, total);
    }

    /** Points every view at its buffer's CPU copy, reads the counters, and reads every bound image. */
    private static void bind(CgComputeSource source, CgDispatchBindings b, State state) {
        // Every copy reaches its furthest view before any view takes its array: a copy grows by replacing it.
        for (CgBufferDecl d : source.buffers()) {
            int i = d.index();
            if (b.buffer(i) != 0) CgCpuMirrors.words(b.buffer(i), b.offset(i) + b.bytes(i));
            if (b.counter(i) != 0) CgCpuMirrors.words(b.counter(i), b.counterOffset(i) + 4);
        }
        for (CgBufferDecl d : source.buffers()) {
            int i = d.index();
            CgCpuBuffer view = state.views[i];
            if (b.buffer(i) == 0) {
                view.words = null;
                view.length = 0;
                continue;
            }
            view.words = CgCpuMirrors.words(b.buffer(i), b.offset(i) + b.bytes(i));
            view.first = (int) (b.offset(i) >>> 2);
            view.length = (int) (b.bytes(i) / d.stride());
            if (d.access() == CgBufferAccess.APPEND && b.counter(i) != 0) {
                state.counts[i] = CgCpuMirrors.words(b.counter(i), b.counterOffset(i) + 4)[(int) (b.counterOffset(i) >>> 2)];
            }
        }
        for (CgImageDecl image : source.images()) {
            int i = image.index();
            if (b.image(i) == 0) {
                state.images[i] = null;
                continue;
            }
            int depth = b.layer(i) >= 0 ? 1 : b.depth(i);
            CgCpuImage held = state.images[i];
            if (held == null || held.width() != b.width(i) || held.height() != b.height(i) || held.depth() != depth) {
                held = state.images[i] = new CgCpuImage(image.name(), image.format().kind == CgImageFormat.Kind.FLOAT,
                        b.width(i), b.height(i), depth);
            }
            read(image, b, held);
        }
    }

    private static void run(CgComputeSource source, CgKernelDecl kernel, Set<String> keywords, CgCpuBody body,
                            @Nullable float[] values, @Nullable float[] constants, CgPropertyBlock block, State state,
                            int cx, int cy, int cz, int total) {
        CgKernelShape shape = kernel.shape();
        boolean ordered = shape == CgKernelShape.SCATTER || shape == CgKernelShape.GENERAL;
        int ranges = ordered || total < 2 * MIN_RANGE ? 1
                : Math.min(Workers.POOL.getParallelism() + 1, total / MIN_RANGE);
        if (state.ranges.length < ranges) {
            int had = state.ranges.length;
            state.ranges = Arrays.copyOf(state.ranges, ranges);
            state.tasks = Arrays.copyOf(state.tasks, ranges);
            for (int r = had; r < ranges; r++) {
                state.ranges[r] = new CgCpuDispatch(source, state.views, state.counts, state.images, state.textures);
                state.tasks[r] = new Range();
            }
        }
        state.active = ranges;
        for (int r = 0; r < ranges; r++) {
            int first = (int) ((long) total * r / ranges), end = (int) ((long) total * (r + 1) / ranges);
            state.ranges[r].set(kernel, keywords, values, constants, block, cx, cy, cz, first, end);
        }
        try {
            if (ranges == 1) {
                body.run(state.ranges[0]);
                return;
            }
            for (int r = 1; r < ranges; r++) {
                Range task = state.tasks[r];
                task.reinitialize();
                task.body = body;
                task.dispatch = state.ranges[r];
                Workers.POOL.execute(task);
            }
            RuntimeException failed = null;
            try {
                body.run(state.ranges[0]);
            } catch (RuntimeException e) {
                failed = e;
            }
            for (int r = 1; r < ranges; r++) {
                try {
                    state.tasks[r].join();
                } catch (RuntimeException e) {
                    if (failed == null) failed = e;
                }
            }
            if (failed != null) throw failed;
        } catch (RuntimeException e) {
            throw new IllegalStateException("[" + source.path() + "] kernel " + kernel.name() + "'s CPU body failed: " + e, e);
        }
    }

    /** Every range's appends after what each counter held, in element order, and the counters grown. */
    private static void appendAll(CgComputeSource source, Plan plan, CgDispatchBindings b, State state) {
        for (CgBufferDecl d : source.buffers()) {
            int i = d.index();
            if (!plan.appends[i]) continue;
            CgCpuBuffer view = state.views[i];
            long before = Integer.toUnsignedLong(state.counts[i]);
            long at = before;
            int stride = view.stride;
            for (int r = 0; r < state.active; r++) {
                CgCpuBuffer staged = state.ranges[r].appended[i];
                for (int e = 0; e < staged.length; e++, at++) {
                    if (at < view.length) {
                        System.arraycopy(staged.words, e * stride, view.words, view.first + (int) at * stride, stride);
                    }
                }
            }
            long landed = Math.min(at, view.length);
            if (landed > before) {
                CgCpuMirrors.upload(b.buffer(i), view.first + (int) before * stride, (int) (landed - before) * stride);
            }
            if (b.counter(i) != 0 && at != before) {
                int word = (int) (b.counterOffset(i) >>> 2);
                CgCpuMirrors.words(b.counter(i), b.counterOffset(i) + 4)[word] = (int) at;
                CgCpuMirrors.upload(b.counter(i), word, 1);
            }
        }
    }

    /** What the body wrote: every view it may have written whole, and every image it wrote. */
    private static void uploadWrites(CgComputeSource source, Plan plan, CgDispatchBindings b, State state) {
        for (CgBufferDecl d : source.buffers()) {
            int i = d.index();
            if (b.buffer(i) == 0 || !plan.writes[i]) continue;
            CgCpuBuffer view = state.views[i];
            CgCpuMirrors.upload(b.buffer(i), view.first, view.length * view.stride);
        }
        for (CgImageDecl image : source.images()) {
            int i = image.index();
            if (state.images[i] == null || !plan.writesImage[i]) continue;
            write(image, b, state.images[i]);
        }
    }

    private static final CgBufferAccessor[] WRITERS = {CgBufferAccessor.WRITE, CgBufferAccessor.STORE, CgBufferAccessor.ADD,
            CgBufferAccessor.MIN, CgBufferAccessor.MAX, CgBufferAccessor.INC, CgBufferAccessor.DATA};


    // ── Images ────────────────────────────────────────────────────────────────

    /** Every level of each sampler property the kernel names, as floats; the rest left unread. */
    private static void readTextures(CgKernelDecl kernel, CgPropertyBlock block, CgTexture[] samplers, State state) {
        Arrays.fill(state.textures, null);
        for (String name : kernel.samplers()) {
            int unit = block.samplerUnit(name);
            CgTexture texture = unit < samplers.length ? samplers[unit] : null;
            if (texture == null) continue;
            int levels = texture.getLevels();
            int depth = texture instanceof CgGraphTexture graph ? graph.getDepth()
                    : texture instanceof CgTexture3D volume ? volume.getDepth() : 1;
            CgCpuImage[] read = new CgCpuImage[levels];
            CgGL.glBindTexture(texture.getTarget(), texture.getId());
            for (int l = 0; l < levels; l++) {
                int w = Math.max(1, texture.getWidth() >> l), h = Math.max(1, texture.getHeight() >> l);
                int d = Math.max(1, depth >> l);
                ByteBuffer pixels = texels((long) w * h * d * 16);
                CgGL.glGetTexImage(texture.getTarget(), l, CgGL.GL_RGBA, CgGL.GL_FLOAT, pixels);
                CgCpuImage image = read[l] = new CgCpuImage(name, true, w, h, d);
                for (int c = 0; c < w * h * d * 4; c++) image.floats[c] = pixels.getFloat(c * 4);
            }
            state.textures[unit] = read;
        }
    }

    private static void read(CgImageDecl decl, CgDispatchBindings b, CgCpuImage image) {
        int i = decl.index();
        int layers = b.layer(i) >= 0 ? b.depth(i) : image.depth();
        ByteBuffer pixels = texels((long) image.width() * image.height() * layers * 16);
        CgGL.glBindTexture(b.imageTarget(i), b.image(i));
        CgGL.glGetTexImage(b.imageTarget(i), b.level(i), format(decl), type(decl), pixels);
        int from = b.layer(i) >= 0 ? b.layer(i) * image.width() * image.height() * 4 : 0;
        int n = image.width() * image.height() * image.depth() * 4;
        if (image.floats != null) for (int c = 0; c < n; c++) image.floats[c] = pixels.getFloat((from + c) * 4);
        else for (int c = 0; c < n; c++) image.ints[c] = pixels.getInt((from + c) * 4);
    }

    private static void write(CgImageDecl decl, CgDispatchBindings b, CgCpuImage image) {
        int i = decl.index();
        int n = image.width() * image.height() * image.depth() * 4;
        ByteBuffer pixels = texels(n * 4L);
        if (image.floats != null) for (int c = 0; c < n; c++) pixels.putFloat(c * 4, image.floats[c]);
        else for (int c = 0; c < n; c++) pixels.putInt(c * 4, image.ints[c]);
        pixels.limit(n * 4);
        CgGL.glBindTexture(b.imageTarget(i), b.image(i));
        if (b.imageTarget(i) == CgGL.GL_TEXTURE_2D) {
            CgGL.glTexSubImage2D(CgGL.GL_TEXTURE_2D, b.level(i), 0, 0, image.width(), image.height(), format(decl), type(decl),
                    pixels);
        } else {
            CgGL.glTexSubImage3D(b.imageTarget(i), b.level(i), 0, 0, Math.max(0, b.layer(i)), image.width(), image.height(),
                    image.depth(), format(decl), type(decl), pixels);
        }
    }

    private static ByteBuffer texels(long bytes) {
        if (texels == null || texels.capacity() < bytes) {
            texels = ByteBuffer.allocateDirect((int) Math.max(bytes, 1 << 16)).order(ByteOrder.nativeOrder());
        }
        texels.clear();
        return texels;
    }

    private static int format(CgImageDecl image) {
        return image.format().kind == CgImageFormat.Kind.FLOAT ? CgGL.GL_RGBA : CgGL.GL_RGBA_INTEGER;
    }

    private static int type(CgImageDecl image) {
        return switch (image.format().kind) {
            case FLOAT -> CgGL.GL_FLOAT;
            case INT -> CgGL.GL_INT;
            case UINT -> CgGL.GL_UNSIGNED_INT;
        };
    }

    /** Every file's views and images, and the texel buffer: the CPU copies go with {@link CgCpuMirrors#releaseAll}. */
    public static void releaseAll() {
        STATES.clear();
        texels = null;
    }
}
