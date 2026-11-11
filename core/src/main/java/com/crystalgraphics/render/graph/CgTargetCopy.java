package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureSpec;
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
 * One raster pass's copy of its own target, for its draws reading {@code cg_SceneColor} or {@code cg_DepthBuffer}
 * ({@link CgRasterPass#sceneColor}, {@link CgRasterPass#sceneDepth}). The executor takes it where
 * {@link CgFrameBuilder} placed a copy, into storage from the graph's pool held for the pass and given back when it
 * ends: a colour texture and a depth texture, each made at the first copy that takes it. A colour copy refreshes only
 * the rect its readers sample, in place: a reader samples only what was copied for it, never what an earlier copy left.
 * Depth is copied whole.
 *
 * <p>A depth copy may be borrowed ({@link #borrowDepth}): the executor shares one copy of a storage's depth between the
 * passes reading it until something writes that depth, as Unreal resolves scene depth once for every pass after the
 * base pass.</p>
 *
 * <ul>
 *   <li>Render thread. A pass of its own each, so a nested execution or a pass of another size never moves what an
 *       earlier pass samples.</li>
 *   <li>Each half has its source's format, single-sampled: a float target keeps its range, and a depth blit, which
 *       refuses formats that differ, copies 26.2's {@code D32_FLOAT} and earlier hosts' 24 bits alike.</li>
 * </ul>
 */
final class CgTargetCopy {

    /** What a copy takes, as bits. */
    static final int COLOR = 1, DEPTH = 2;
    /** With {@link #DEPTH}: no draw of the pass wrote depth before it, so a copy shared between passes serves. */
    static final int CLEAN = 4;

    final CgTexture color = new View(true), depth = new View(false);
    private final Half colorHalf = new Half(), depthHalf = new Half();
    /** The copy whose depth this one reads instead of its own, until it copies depth or is released. */
    @Nullable
    private CgTargetCopy depthLender;

    /** Reads {@code lender}'s depth in place of a copy of its own; null to read its own again. */
    void borrowDepth(@Nullable CgTargetCopy lender) {
        depthLender = lender;
    }

    /**
     * Copies what {@code bits} name: colour from {@code colorSource}, in the rect at {@code at} of {@code rects} (pixels
     * x0, y0, x1, y1, or whole where its x1 is below 0), and depth from {@code depthSource}, whole: one storage, or a
     * colour drawn beside another's depth. Answers the colour pixels copied.
     */
    long copy(CgGraphStorage colorSource, CgGraphStorage depthSource, int bits, int[] rects, int at, CgTexturePool pool) {
        int width = colorSource.width(), height = colorSource.height();
        boolean takesColor = (bits & COLOR) != 0 && colorSource.color(0) != null;
        boolean takesDepth = (bits & DEPTH) != 0 && depthSource.depth() != null;
        if (takesDepth) {
            depthLender = null;
            depthHalf.copy(depthSource, false, 0, 0, width, height, pool);
        }
        if (!takesColor) return 0L;
        int x0 = 0, y0 = 0, x1 = width, y1 = height;
        if (rects[at + 2] >= 0) {
            x0 = Math.max(0, rects[at]);
            y0 = Math.max(0, rects[at + 1]);
            x1 = Math.min(width, rects[at + 2]);
            y1 = Math.min(height, rects[at + 3]);
        }
        if (x1 <= x0 || y1 <= y0) return 0L;
        colorHalf.copy(colorSource, true, x0, y0, x1, y1, pool);
        return (long) (x1 - x0) * (y1 - y0);
    }

    /** Copies {@code source}'s colour whole. */
    void copyColor(CgGraphStorage source, CgTexturePool pool) {
        if (source.color(0) == null) throw new IllegalStateException(source + " has no colour to copy");
        colorHalf.copy(source, true, 0, 0, source.width(), source.height(), pool);
    }

    /**
     * Copies {@code source}'s depth whole, for a pass reading another target's
     * ({@link CgRasterPass#sceneDepth(int, CgGraphTexture)}); throws, naming {@code pass}, where it has none.
     */
    void copyDepth(CgGraphStorage source, CgPass pass, CgTexturePool pool) {
        if (source.depth() == null) throw new IllegalStateException(pass + " reads the depth of " + source + ", which has none");
        CgGpuTexture.Desc of = source.depth().desc();
        depthLender = null;
        depthHalf.copy(source, false, 0, 0, of.width(), of.height(), pool);
    }

    /** Gives its storage back to {@code pool}; each half keeps what it asked for, the key of its next copy's. */
    void release(CgTexturePool pool) {
        colorHalf.giveBack(pool);
        depthHalf.giveBack(pool);
        depthLender = null;
    }

    /** One texture of the copy: its storage while the pass holds it, and the description it is acquired as. */
    private static final class Half {

        @Nullable
        CgGraphStorage storage;
        /** Kept after {@link #giveBack}, so the next frame's copy asks with the same key. */
        @Nullable
        CgTextureDesc desc;

        /** Copies {@code source}'s colour, or its depth, over the rect into the same rect here, made to fit first. */
        void copy(CgGraphStorage source, boolean isColor, int x0, int y0, int x1, int y1, CgTexturePool pool) {
            CgGpuTexture from = isColor ? source.color(0) : source.depth();
            CgGpuTexture.Desc of = from.desc();
            int width = isColor ? source.width() : of.width(), height = isColor ? source.height() : of.height();
            CgFormat format = of.format();
            if (desc == null || desc.width() != width || desc.height() != height || desc.format() != format) {
                giveBack(pool);
                desc = descOf(width, height, format);
            }
            if (storage == null) storage = pool.acquire(desc);
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, isColor ? COLOR_ZONE : DEPTH_ZONE)) {
                CgExecutor.blitting(source, storage);
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
                CgExecutor.blitted(source, storage);
            }
        }

        void giveBack(CgTexturePool pool) {
            if (storage != null) pool.release(desc, storage);
            storage = null;
        }
    }

    /** {@code texture}'s level and layer as a blit names it: one of the few a frame copies between, kept. */
    private static CgTextureView attached(CgGpuTexture texture, int level, int layer) {
        for (CgTextureView v : ATTACHED) {
            if (v != null && v.texture() == texture && v.baseMip() == level && v.baseLayer() == layer) return v;
        }
        CgTextureView made = CgTextureView.attachment(texture, level, layer);
        ATTACHED[nextAttached] = made;
        nextAttached = (nextAttached + 1) % ATTACHED.length;
        return made;
    }

    /** The views {@link #attached} made last, the oldest replaced first. */
    private static final CgTextureView[] ATTACHED = new CgTextureView[8];
    private static int nextAttached;

    private static final int COLOR_ZONE = CgTrace.name("graph.copy.color"), DEPTH_ZONE = CgTrace.name("graph.copy.depth");

    /** The description of a copy {@code width} x {@code height} of {@code format}, one kept per shape: copies alternate. */
    private static CgTextureDesc descOf(int width, int height, CgFormat format) {
        for (int i = 0; i < DESCS.size(); i++) {
            CgTextureDesc d = DESCS.get(i);
            if (d.width() == width && d.height() == height && d.format() == format) return d;
        }
        CgTextureDesc d = CgTextureDesc.of(width, height, format);
        if (DESCS.size() == 32) DESCS.remove(0);
        DESCS.add(d);
        return d;
    }

    private static final ArrayList<CgTextureDesc> DESCS = new ArrayList<>();

    /** What a pass binds for one half of its copy. */
    private final class View implements CgTexture {

        private final boolean isColor;

        View(boolean isColor) {
            this.isColor = isColor;
        }

        @Nullable
        private CgGraphStorage source() {
            return isColor ? colorHalf.storage : depthLender != null ? depthLender.depthHalf.storage : depthHalf.storage;
        }

        @Nullable
        private CgTexture sampled() {
            CgGraphStorage from = source();
            if (from == null) return null;
            return isColor ? from.sampled(0) : from.sampledDepth();
        }

        @Override
        @Nullable
        public CgTextureView rhiView() {
            CgTexture sampled = sampled();
            return sampled == null ? null : sampled.rhiView();
        }

        @Override
        @Nullable
        public CgTextureSpec spec() {
            CgTexture sampled = sampled();
            return sampled == null ? null : sampled.spec();
        }

        @Override
        public int getWidth() {
            CgGraphStorage from = source();
            return from == null ? 0 : from.width();
        }

        @Override
        public int getHeight() {
            CgGraphStorage from = source();
            return from == null ? 0 : from.height();
        }
    }
}
