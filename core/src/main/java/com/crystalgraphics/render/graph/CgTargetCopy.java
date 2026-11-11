package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.platform.rhi.CgRHI;
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
 * ends. A colour copy refreshes only the rect its readers sample, in place: a reader samples only what was copied for
 * it, never what an earlier copy left. Depth is copied whole.
 *
 * <p>A depth copy may be borrowed ({@link #borrowDepth}): the executor shares one copy of a storage's depth between the
 * passes reading it until something writes that depth, as Unreal resolves scene depth once for every pass after the
 * base pass.</p>
 *
 * <ul>
 *   <li>Render thread. A pass of its own each, so a nested execution or a pass of another size never moves what an
 *       earlier pass samples.</li>
 *   <li>The copy has its sources' colour and depth formats, single-sampled: a float target keeps its range, and a depth
 *       blit, which refuses formats that differ, copies 26.2's {@code D32_FLOAT} and earlier hosts' 24 bits alike.</li>
 * </ul>
 */
final class CgTargetCopy {

    /** What a copy takes, as bits. */
    static final int COLOR = 1, DEPTH = 2;
    /** With {@link #DEPTH}: no draw of the pass wrote depth before it, so a copy shared between passes serves. */
    static final int CLEAN = 4;

    private static final CgTextureType[] TYPES = CgTextureType.values();
    /** Copy formats by colour type and depth type, each its ordinal plus one, 0 for none. Render thread. */
    private static final CgFrameBufferFormat[][] FORMATS = new CgFrameBufferFormat[TYPES.length + 1][TYPES.length + 1];

    final CgTexture color = new View(true), depth = new View(false);
    @Nullable
    private CgGraphStorage storage;
    /** What its storage is acquired as: kept after {@link #release}, so the next frame's copy asks with the same key. */
    @Nullable
    private CgTextureDesc desc;
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
        CgTextureType colorType = colorSource.color(0) != null ? colorSource.format().getColorSlot(0) : null;
        CgTextureType depthType = depthSource.depth() != null ? depthSource.format().getDepthType() : null;
        return copy(colorSource, depthSource, copyFormat(colorType, depthType), colorSource.width(), colorSource.height(),
                bits, rects, at, pool);
    }

    /** Copies {@code source}'s colour whole, into colour alone. */
    void copyColor(CgGraphStorage source, CgTexturePool pool) {
        CgTextureType type = source.color(0) != null ? source.format().getColorSlot(0) : null;
        if (type == null) throw new IllegalStateException(source + " has no colour to copy");
        copy(source, source, copyFormat(type, null), source.width(), source.height(), COLOR, WHOLE, 0, pool);
    }

    /**
     * Copies {@code source}'s depth whole, for a pass reading another target's
     * ({@link CgRasterPass#sceneDepth(int, CgGraphTexture)}); throws, naming {@code pass}, where it has none.
     */
    void copyDepth(CgGraphStorage source, CgPass pass, CgTexturePool pool) {
        if (source.depth() == null) throw new IllegalStateException(pass + " reads the depth of " + source + ", which has none");
        CgGpuTexture.Desc of = source.depth().desc();
        copy(source, source, copyFormat(null, source.format().getDepthType()), of.width(), of.height(), DEPTH, WHOLE, 0, pool);
    }

    private long copy(CgGraphStorage colorSource, CgGraphStorage depthSource, CgFrameBufferFormat format, int width,
                      int height, int bits, int[] rects, int at, CgTexturePool pool) {
        int colorMask = (bits & COLOR) != 0 && format.colorSlotCount() > 0 ? COLOR : 0;
        int depthMask = (bits & DEPTH) != 0 && format.hasDepth() ? DEPTH : 0;
        if (depthMask != 0) depthLender = null;
        if ((colorMask | depthMask) == 0 || width <= 0 || height <= 0) return 0L;
        if (!fits(desc, width, height, format)) {
            // Its storage alone: a colour copy keeps the depth it borrowed
            giveBack(pool);
            desc = descOf(width, height, format);
        }
        if (storage == null) storage = pool.acquire(desc);
        int x0 = 0, y0 = 0, x1 = width, y1 = height;
        if (rects[at + 2] >= 0) {
            x0 = Math.max(0, rects[at]);
            y0 = Math.max(0, rects[at + 1]);
            x1 = Math.min(width, rects[at + 2]);
            y1 = Math.min(height, rects[at + 3]);
        }
        if (x1 <= x0 || y1 <= y0) colorMask = 0;
        if (colorSource == depthSource && x0 == 0 && y0 == 0 && x1 == width && y1 == height) {
            blit(colorSource, colorMask | depthMask, 0, 0, width, height);
        } else {
            blit(depthSource, depthMask, 0, 0, width, height);
            blit(colorSource, colorMask, x0, y0, x1, y1);
        }
        return colorMask == 0 ? 0L : (long) (x1 - x0) * (y1 - y0);
    }

    private static final int[] WHOLE = {0, 0, -1, -1};

    /** Blits {@code mask}, {@link #COLOR} and {@link #DEPTH} bits, from {@code source} into the same rect of the copy. */
    private void blit(CgGraphStorage source, int mask, int x0, int y0, int x1, int y1) {
        if (mask == 0) return;
        int zone = (mask & COLOR) == 0 ? DEPTH_ZONE : mask == COLOR ? COLOR_ZONE : BOTH_ZONE;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, zone)) {
            CgExecutor.blitting(source, storage);
            CgGpuTexture color = (mask & COLOR) != 0 ? source.color(0) : null;
            CgGpuTexture depth = (mask & DEPTH) != 0 ? source.depth() : null;
            if ((mask & COLOR) != 0 && color == null || (mask & DEPTH) != 0 && depth == null) {
                throw new IllegalStateException(source + " has no texture to copy from");
            }
            CgDeviceSection.begin();
            try {
                if (color != null) {
                    CgRHI.get().encoder().blit(attached(color, source.colorLevel(), source.colorLayer()), x0, y0, x1, y1,
                            attached(storage.color(0), 0, 0), x0, y0, x1, y1, CgGpuSampler.Filter.NEAREST);
                }
                if (depth != null) {
                    CgRHI.get().encoder().blit(attached(depth, 0, source.depthLayer()), x0, y0, x1, y1,
                            attached(storage.depth(), 0, 0), x0, y0, x1, y1, CgGpuSampler.Filter.NEAREST);
                }
            } finally {
                CgDeviceSection.end();
            }
            CgExecutor.blitted(source, storage);
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

    private static final int COLOR_ZONE = CgTrace.name("graph.copy.color"), DEPTH_ZONE = CgTrace.name("graph.copy.depth"),
            BOTH_ZONE = CgTrace.name("graph.copy.colorDepth");

    /** The description of a copy {@code width} x {@code height} of {@code format}, one kept per shape: copies alternate. */
    private static CgTextureDesc descOf(int width, int height, CgFrameBufferFormat format) {
        for (int i = 0; i < DESCS.size(); i++) if (fits(DESCS.get(i), width, height, format)) return DESCS.get(i);
        CgTextureDesc d = new CgTextureDesc(width, height, format);
        if (DESCS.size() == 32) DESCS.remove(0);
        DESCS.add(d);
        return d;
    }

    private static final ArrayList<CgTextureDesc> DESCS = new ArrayList<>();

    private static boolean fits(@Nullable CgTextureDesc d, int width, int height, CgFrameBufferFormat format) {
        return d != null && d.width() == width && d.height() == height && d.format().equals(format);
    }

    /** Gives its storage back to {@code pool}; it keeps what it asked for, the key of its next copy's. */
    void release(CgTexturePool pool) {
        giveBack(pool);
        depthLender = null;
    }

    private void giveBack(CgTexturePool pool) {
        if (storage != null) pool.release(desc, storage);
        storage = null;
    }

    /** A copy of a colour of {@code colorType} and a depth of {@code depthType}, either null for none. */
    private static CgFrameBufferFormat copyFormat(@Nullable CgTextureType colorType, @Nullable CgTextureType depthType) {
        int c = colorType == null ? 0 : colorType.ordinal() + 1, d = depthType == null ? 0 : depthType.ordinal() + 1;
        CgFrameBufferFormat format = FORMATS[c][d];
        if (format == null) {
            CgFrameBufferFormat.Builder builder = CgFrameBufferFormat.builder("cg_target_copy");
            if (colorType != null) builder.color(0, colorType);
            if (depthType != null) builder.depth(depthType);
            FORMATS[c][d] = format = builder.build();
        }
        return format;
    }

    /** What a pass binds for one half of its copy. */
    private final class View implements CgTexture {

        private final boolean isColor;

        View(boolean isColor) {
            this.isColor = isColor;
        }

        @Nullable
        private CgGraphStorage source() {
            return !isColor && depthLender != null ? depthLender.storage : storage;
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
