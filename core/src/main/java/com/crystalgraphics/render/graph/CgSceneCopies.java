package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.rhi.resource.CgGpuSampler;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import javax.annotation.Nullable;
import java.util.ArrayList;

/**
 * The scene colour and depth a draw samples ({@code cg_SceneColor}, {@code cg_DepthBuffer}), as copies the compile
 * decided and the encoders replay. Unreal makes one copy of scene colour before translucency and resolves scene depth
 * once; here the compile ({@link CgFrameCompiler#sceneCopies}) says, per batch and per step, which copy happens before
 * it, and this holds their storage and does the blit.
 *
 * <ul>
 *   <li>Four halves, each acquired from the graph's pool by the first copy that needs it: the pass's own colour, the pass's
 *       own depth, another target's colour ({@code readsColor(other)}), and the resolved depth, which every pass reading
 *       that depth shares until something writes it. A colour copy refreshes only the rect its readers sample, in place;
 *       depth is copied whole.</li>
 *   <li>The own halves and the other target's colour go back to the pool when the step ends ({@link #endStep}), the
 *       resolved depth when the execution does ({@link #endExecution}).</li>
 *   <li>Each half has its source's format, single-sampled: a float target keeps its range, and a depth blit, which
 *       refuses formats that differ, copies 26.2's {@code D32_FLOAT} and earlier hosts' 24 bits alike.</li>
 *   <li>Render thread; one per execution context, so a nested execution never moves what an outer pass samples.</li>
 * </ul>
 */
final class CgSceneCopies {

    /** What a reader samples, as the builder places it before a batch: {@link #CLEAN} with {@link #DEPTH} for a depth no draw of the pass wrote. */
    static final int COLOR = 1, DEPTH = 2, CLEAN = 4;
    /**
     * What the compile resolved a batch's (or a step's) reads to, replayed as they stand: refresh the own colour, copy
     * the own depth, copy the resolved depth, read the resolved depth as it is, sample the depth where it is.
     */
    static final int COPY_COLOR = 1, COPY_DEPTH = 2, RESOLVE_DEPTH = 4, REUSE_DEPTH = 8, SAMPLE_DEPTH = 16;

    private static final int COLOR_ZONE = CgTrace.name("graph.copy.color"), DEPTH_ZONE = CgTrace.name("graph.copy.depth");
    private static final int TARGET_COPIES = CgTrace.name("graph.target-copies"),
            TARGET_COPY_PIXELS = CgTrace.name("graph.target-copy-pixels"),
            DEPTH_SHARED = CgTrace.name("graph.depth-copies-shared"),
            DEPTH_SAMPLED = CgTrace.name("graph.depth-sampled-in-place");

    private final CgExecutor execution;
    private final CgGraphDevice device;
    private final Half color = new Half(true), depth = new Half(false), colorFrom = new Half(true), resolved = new Half(false);
    /** What a pass binds for each half. */
    final CgTexture colorView = new View(color), depthView = new View(depth), colorFromView = new View(colorFrom),
            resolvedView = new View(resolved);
    /** The description of a copy by shape, one kept each: copies alternate, and a key made per copy would allocate. */
    private final ArrayList<CgTextureDesc> descs = new ArrayList<>();

    CgSceneCopies(CgExecutor execution, CgGraphDevice device) {
        this.execution = execution;
        this.device = device;
    }

    /**
     * Refreshes the pass's own colour copy from {@code source}, in the rect at {@code at} of {@code rects} (pixels x0,
     * y0, x1, y1, or the whole where its x1 is below 0). Answers the pixels copied.
     */
    long copyColor(CgGraphStorage source, int[] rects, int at) {
        if (source.color(0) == null) return 0L;
        int width = source.width(), height = source.height();
        int x0 = 0, y0 = 0, x1 = width, y1 = height;
        if (rects[at + 2] >= 0) {
            x0 = Math.max(0, rects[at]);
            y0 = Math.max(0, rects[at + 1]);
            x1 = Math.min(width, rects[at + 2]);
            y1 = Math.min(height, rects[at + 3]);
        }
        if (x1 <= x0 || y1 <= y0) return 0L;
        color.copy(source, x0, y0, x1, y1);
        long pixels = (long) (x1 - x0) * (y1 - y0);
        CgTrace.add(CgChannels.GL, TARGET_COPIES, 1);
        CgTrace.add(CgChannels.GL, TARGET_COPY_PIXELS, pixels);
        return pixels;
    }

    /** Copies {@code source}'s colour whole, for a pass reading another target's ({@link CgRasterPass#readsColor}). */
    void copyColorFrom(CgGraphStorage source) {
        if (source.color(0) == null) throw new IllegalStateException(source + " has no colour to copy");
        colorFrom.copy(source, 0, 0, source.width(), source.height());
        CgTrace.add(CgChannels.GL, TARGET_COPIES, 1);
    }

    /** Copies {@code source}'s depth whole into the pass's own depth copy; throws, naming {@code pass}, where it has none. */
    void copyDepth(CgGraphStorage source, CgPass pass) {
        requireDepth(source, pass);
        depth.copy(source, 0, 0, 0, 0);
        CgTrace.add(CgChannels.GL, TARGET_COPIES, 1);
    }

    /** Copies {@code source}'s depth whole into the resolved depth every later reader shares. */
    void resolveDepth(CgGraphStorage source, CgPass pass) {
        requireDepth(source, pass);
        resolved.copy(source, 0, 0, 0, 0);
        CgTrace.add(CgChannels.GL, TARGET_COPIES, 1);
    }

    /**
     * What a pass reading another texture's depth binds, as {@code op} compiled it: {@code source}'s depth sampled
     * where it is, the resolved copy as it stands, or {@code source} resolved now.
     */
    CgTexture depthFrom(int op, CgGraphStorage source, CgPass pass) {
        if ((op & SAMPLE_DEPTH) != 0) return sampleDepth(source);
        if ((op & REUSE_DEPTH) != 0) return reuseDepth();
        resolveDepth(source, pass);
        return resolvedView;
    }

    /** What a pass binds where the resolved depth is read as it stands. */
    CgTexture reuseDepth() {
        CgTrace.add(CgChannels.GL, DEPTH_SHARED, 1);
        return resolvedView;
    }

    /** {@code source}'s depth sampled where it is, read-only in the pass: no copy. */
    CgTexture sampleDepth(CgGraphStorage source) {
        CgTrace.add(CgChannels.GL, DEPTH_SAMPLED, 1);
        return source.sampledDepth();
    }

    private static void requireDepth(CgGraphStorage source, CgPass pass) {
        if (source.depth() == null) throw new IllegalStateException(pass + " reads the depth of " + source + ", which has none");
    }

    /** Gives back what the step that ended copied for itself; the resolved depth stays for the steps after it. */
    void endStep() {
        color.giveBack();
        depth.giveBack();
        colorFrom.giveBack();
    }

    /** Gives back the resolved depth: the execution is over. */
    void endExecution() {
        resolved.giveBack();
    }

    /** Which depth the resolved copy holds, as the compile follows it step by step. */
    final DepthSource depthSource = new DepthSource();

    /**
     * What the resolved depth is a copy of while {@link CgFrameCompiler#sceneCopies} walks the steps. An external
     * target's depth is named by its device texture and layer (two handles of one target are one depth); anything else
     * by its handle.
     */
    static final class DepthSource {

        @Nullable
        private Object source;
        private int layer;
        private boolean external;

        /** Nothing is held: the next reader copies. */
        void forget() {
            source = null;
        }

        /** A reader of {@code texture}'s depth: {@link #REUSE_DEPTH} where the copy holds it, else {@link #RESOLVE_DEPTH}, which then does. */
        int read(CgGraphTexture texture) {
            CgGraphStorage storage = texture.kind() == CgGraphTexture.Kind.EXTERNAL ? texture.storage() : null;
            boolean outside = storage != null && storage.depth() != null;
            Object key = outside ? storage.depth() : texture;
            int at = outside ? storage.depthLayer() : 0;
            if (texture.kind() != CgGraphTexture.Kind.HISTORY && source == key && layer == at) return REUSE_DEPTH;
            source = texture.kind() == CgGraphTexture.Kind.HISTORY ? null : key;   // a history's storage moves each draw
            layer = at;
            external = outside;
            return RESOLVE_DEPTH;
        }

        /** A raster step writes {@code texture}'s depth: any external target's may be the one held. */
        void written(CgGraphTexture texture) {
            if (source == null) return;
            if (texture.kind() == CgGraphTexture.Kind.EXTERNAL ? external || source == texture : source == texture) source = null;
        }

        /** {@code texture}'s storage goes back to the pool. */
        void released(CgGraphTexture texture) {
            if (!external && source == texture) source = null;
        }
    }

    /** One texture of the copies: its storage while a step holds it, and the description it is acquired as. */
    private final class Half {

        private final boolean isColor;
        @Nullable
        CgGraphStorage storage;
        /** Kept after {@link #giveBack}, so the next copy asks the pool with the same key. */
        @Nullable
        CgTextureDesc desc;

        Half(boolean isColor) {
            this.isColor = isColor;
        }

        /** Copies {@code source}'s colour, or its depth, over the rect into the same rect here, made to fit first. */
        void copy(CgGraphStorage source, int x0, int y0, int x1, int y1) {
            CgGpuTexture from = isColor ? source.color(0) : source.depth();
            CgGpuTexture.Desc of = from.desc();
            int width = isColor ? source.width() : of.width(), height = isColor ? source.height() : of.height();
            if (!isColor) {
                x1 = width;
                y1 = height;
            }
            CgFormat format = of.format();
            if (desc == null || desc.width() != width || desc.height() != height || desc.format() != format) {
                giveBack();
                desc = descOf(width, height, format);
            }
            if (storage == null) storage = device.pool.acquire(desc);
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, isColor ? COLOR_ZONE : DEPTH_ZONE)) {
                execution.transitions.blitting(source, storage);
                CgDeviceSection.begin();
                try {
                    CgTextureView src = isColor ? attached(from, source.colorLevel(), source.colorLayer())
                            : attached(from, 0, source.depthLayer());
                    CgGpuTexture to = isColor ? storage.color(0) : storage.depth();
                    CgRHI.get().encoder().blit(src, x0, y0, x1, y1, attached(to, 0, 0), x0, y0, x1, y1,
                            CgGpuSampler.Filter.NEAREST);
                } finally {
                    CgDeviceSection.end();
                }
                execution.transitions.blitted(source, storage);
            }
        }

        void giveBack() {
            if (storage != null) device.pool.release(desc, storage);
            storage = null;
        }
    }

    /** {@code texture}'s level and layer as a blit names it: one of the few a frame copies between, kept. */
    private CgTextureView attached(CgGpuTexture texture, int level, int layer) {
        CgTextureView[] views = device.attachedViews;
        for (CgTextureView v : views) {
            if (v != null && v.texture() == texture && v.baseMip() == level && v.baseLayer() == layer) return v;
        }
        CgTextureView made = CgTextureView.attachment(texture, level, layer);
        views[device.nextAttached] = made;
        device.nextAttached = (device.nextAttached + 1) % views.length;
        return made;
    }

    private CgTextureDesc descOf(int width, int height, CgFormat format) {
        for (int i = 0; i < descs.size(); i++) {
            CgTextureDesc d = descs.get(i);
            if (d.width() == width && d.height() == height && d.format() == format) return d;
        }
        CgTextureDesc d = CgTextureDesc.of(width, height, format);
        if (descs.size() == 32) descs.remove(0);
        descs.add(d);
        return d;
    }

    /** What a pass binds for one half: its storage as it is now, null before the half has copied. */
    private static final class View implements CgTexture {

        private final Half half;

        View(Half half) {
            this.half = half;
        }

        @Nullable
        private CgTexture sampled() {
            CgGraphStorage from = half.storage;
            if (from == null) return null;
            return half.isColor ? from.sampled(0) : from.sampledDepth();
        }

        @Override
        @Nullable
        public CgTextureView rhiView() {
            CgTexture sampled = sampled();
            return sampled == null ? null : sampled.rhiView();
        }

        @Override
        @Nullable
        public CgSampler sampler() {
            CgTexture sampled = sampled();
            return sampled == null ? null : sampled.sampler();
        }

        @Override
        public int getWidth() {
            CgGraphStorage from = half.storage;
            return from == null ? 0 : from.width();
        }

        @Override
        public int getHeight() {
            CgGraphStorage from = half.storage;
            return from == null ? 0 : from.height();
        }
    }
}
