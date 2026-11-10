package com.crystalgraphics.render.graph;

import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.resource.CgGpuSampler;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * One raster pass's copy of its own target, for its draws reading {@code cg_SceneColor} or {@code cg_DepthBuffer}
 * ({@link CgRasterPass#sceneColor}, {@link CgRasterPass#sceneDepth}). The executor takes it where
 * {@link CgFrameBuilder} placed a copy, into one framebuffer from the graph's pool held for the pass and given back
 * when it ends. A colour copy refreshes only the rect its readers sample, in place: a reader samples only what was
 * copied for it, never what an earlier copy left. Depth is copied whole.
 *
 * <p>A depth copy may be borrowed ({@link #borrowDepth}): the executor shares one copy of a target's depth between the
 * passes reading it until something writes that depth, as Unreal resolves scene depth once for every pass after the
 * base pass.</p>
 *
 * <ul>
 *   <li>Render thread. A pass of its own each, so a nested execution or a pass of another size never moves what an
 *       earlier pass samples.</li>
 *   <li>The copy has the target's colour and depth formats, single-sampled textures: a float target keeps its
 *       range. The current target's colour is copied as RGBA8 and its depth in its own format (26.2's main target is
 *       {@code D32_FLOAT}, earlier ones 24-bit), since a depth blit between formats fails.</li>
 * </ul>
 */
final class CgTargetCopy {

    /** What a copy takes, as bits. */
    static final int COLOR = 1, DEPTH = 2;
    /** With {@link #DEPTH}: no draw of the pass wrote depth before it, so a copy shared between passes serves. */
    static final int CLEAN = 4;

    /** Copy formats by the source's: its colour slot 0 and depth, as textures. Render thread. */
    private static final Map<CgFrameBufferFormat, CgFrameBufferFormat> FORMATS = new HashMap<>();

    /**
     * Current targets probed, most recent first: each framebuffer, the copy format and whether it has depth ({@link
     * #DEPTH} or 0). Several, since a host's stages may alternate targets.
     */
    private static final int PROBED = 4;
    private static final int[] probedSource = {-1, -1, -1, -1};
    private static final CgFrameBufferFormat[] probedFormat = new CgFrameBufferFormat[PROBED];
    private static final int[] probedDepthMask = new int[PROBED];

    /**
     * Where a run's copies find the device textures behind a framebuffer name: the executor's, installed for its run.
     */
    interface Sources {
        @Nullable CgGpuTexture color(int framebuffer);

        @Nullable CgGpuTexture depth(int framebuffer);
    }

    @Nullable
    static Sources sources;

    final CgTexture color = new View(true), depth = new View(false);
    @Nullable
    private CgFrameBuffer storage;
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
     * Copies what {@code bits} name from {@code width} x {@code height} framebuffer {@code source}: the current target
     * when {@code sourceFormat} is null, else a target of that format. Colour is copied in the rect at {@code at}
     * of {@code rects}, GL pixels x0, y0, x1, y1, or whole where its x1 is below 0. Answers the colour pixels copied.
     */
    long copy(int source, @Nullable CgFrameBufferFormat sourceFormat, int width, int height, int bits,
              int[] rects, int at, CgTexturePool pool) {
        CgFrameBufferFormat format;
        int depthMask;
        if (sourceFormat == null) {
            int probed = probe(source);
            format = probedFormat[probed];
            depthMask = probedDepthMask[probed];
        } else {
            format = FORMATS.computeIfAbsent(sourceFormat, f -> copyFormat(f.getColorSlot(0), f.getDepthType()));
            depthMask = sourceFormat.hasDepth() ? DEPTH : 0;
        }
        return copy(source, source, format, depthMask, width, height, bits, rects, at, pool);
    }

    /**
     * As {@link #copy}, for a target drawn beside another's depth ({@link CgGraphTexture#besideCurrentDepth}): colour
     * from {@code colorSource} of {@code colorFormat}, depth from current target {@code depthSource}.
     */
    long copyBeside(int colorSource, CgFrameBufferFormat colorFormat, int depthSource, int width, int height, int bits,
                    int[] rects, int at, CgTexturePool pool) {
        int probed = probe(depthSource);
        CgFrameBufferFormat format = BESIDE_FORMATS
                .computeIfAbsent(colorFormat, f -> new HashMap<>())
                .computeIfAbsent(probedFormat[probed], d -> copyFormat(colorFormat.getColorSlot(0), d.getDepthType()));
        return copy(colorSource, depthSource, format, probedDepthMask[probed], width, height, bits, rects, at, pool);
    }

    /** Copy formats of a target beside another's depth, by its colour format and the depth's probed copy format. */
    private static final Map<CgFrameBufferFormat, Map<CgFrameBufferFormat, CgFrameBufferFormat>> BESIDE_FORMATS = new HashMap<>();

    /** Copies {@code source}'s colour whole, into colour alone: the current target, as RGBA8, when {@code sourceFormat} is null. */
    void copyColor(int source, @Nullable CgFrameBufferFormat sourceFormat, int width, int height, CgTexturePool pool) {
        CgFrameBufferFormat format = sourceFormat == null ? CURRENT_COLOR
                : COLOR_FORMATS.computeIfAbsent(sourceFormat, f -> copyFormat(f.getColorSlot(0), null));
        copy(source, source, format, 0, width, height, COLOR, WHOLE, 0, pool);
    }

    private static final CgFrameBufferFormat CURRENT_COLOR = copyFormat(CgTextureType.RGBA8, null);
    private static final Map<CgFrameBufferFormat, CgFrameBufferFormat> COLOR_FORMATS = new HashMap<>();

    private long copy(int colorSource, int depthSource, CgFrameBufferFormat format, int depthMask, int width, int height,
                      int bits, int[] rects, int at, CgTexturePool pool) {
        int colorMask = (bits & COLOR) != 0 && format.colorSlotCount() > 0 ? COLOR : 0;
        if ((bits & DEPTH) == 0) depthMask = 0;
        if (depthMask != 0) depthLender = null;
        if ((colorMask | depthMask) == 0 || width <= 0 || height <= 0) return 0L;
        if (!fits(desc, width, height, format)) {
            release(pool);
            desc = new CgTextureDesc(width, height, format);
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

    /**
     * Copies framebuffer {@code source}'s depth whole, for a pass reading another target's
     * ({@link CgRasterPass#sceneDepth(int, CgGraphTexture)}): the current target, {@code width} x {@code height}, when
     * {@code sourceFormat} is null. Throws, naming {@code pass}, where the source has no depth.
     */
    void copyDepth(int source, @Nullable CgFrameBufferFormat sourceFormat, int width, int height, CgPass pass,
                   CgTexturePool pool) {
        boolean depth = sourceFormat != null ? sourceFormat.hasDepth() : probedDepthMask[probe(source)] != 0;
        if (!depth) throw new IllegalStateException(pass + " reads the depth of framebuffer " + source + ", which has none");
        copy(source, sourceFormat, width, height, DEPTH, WHOLE, 0, pool);
    }

    /**
     * Copies {@code from}, framebuffer {@code source}'s depth imported as an RHI texture, whole through the device: a
     * renderbuffer's or the window's, which no pass samples in place, as Unreal resolves scene depth into a texture once
     * for the passes after the base pass. Outside a device section; opens its own.
     */
    void copyDepth(int source, CgGpuTexture from, CgTexturePool pool) {
        CgTextureType type = depthType(from.desc().format());
        int width = from.desc().width(), height = from.desc().height();
        CgFrameBufferFormat format = DEPTH_FORMATS.computeIfAbsent(type, t -> copyFormat(null, t));
        depthLender = null;
        if (!fits(desc, width, height, format)) {
            release(pool);
            desc = new CgTextureDesc(width, height, format);
        }
        if (storage == null) storage = pool.acquire(desc);
        CgTextureView to = storage.getDepthTexture().rhiView();
        if (to == null) throw new IllegalStateException("a depth copy of " + from + " has no device texture");
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, DEPTH_ZONE)) {
            // The framebuffers' states moved round it as round a copy's blit: what explicit Vulkan barriers read
            CgExecutor.blitting(source, storage.getId());
            CgDeviceSection.begin();
            try {
                CgRHI.get().encoder().blit(CgTextureView.attachment(from, 0, 0), 0, 0, width, height,
                        to, 0, 0, width, height, CgGpuSampler.Filter.NEAREST);
            } finally {
                CgDeviceSection.end();
            }
            CgExecutor.blitted(source, storage.getId());
        }
    }

    /** Depth-only copy formats, by depth type. */
    private static final Map<CgTextureType, CgFrameBufferFormat> DEPTH_FORMATS = new HashMap<>();

    /** The texture type holding a depth of {@code format}: a blit between depth formats fails, so they match. */
    private static CgTextureType depthType(CgFormat format) {
        switch (format) {
            case DEPTH16_UNORM: return CgTextureType.DEPTH16;
            case DEPTH24_PLUS: return CgTextureType.DEPTH24;
            case DEPTH32_FLOAT: return CgTextureType.DEPTH32F;
            case DEPTH24_PLUS_STENCIL8: return CgTextureType.DEPTH24_STENCIL8;
            case DEPTH32_FLOAT_STENCIL8: return CgTextureType.DEPTH32F_STENCIL8;
            default: throw new IllegalArgumentException(format + " is no depth format");
        }
    }

    private static final int[] WHOLE = {0, 0, -1, -1};

    /** Where current target {@code source}'s probe is, probing it into slot 0 if none is held. */
    private static int probe(int source) {
        for (int i = 0; i < PROBED; i++) if (probedSource[i] == source) return i;
        System.arraycopy(probedSource, 0, probedSource, 1, PROBED - 1);
        System.arraycopy(probedFormat, 0, probedFormat, 1, PROBED - 1);
        System.arraycopy(probedDepthMask, 0, probedDepthMask, 1, PROBED - 1);
        CgGpuTexture depth = sources().depth(source);
        CgTextureType type = depth == null ? null : depthType(depth.desc().format());
        probedSource[0] = source;
        probedFormat[0] = copyFormat(CgTextureType.RGBA8, type);
        probedDepthMask[0] = type == null ? 0 : DEPTH;
        return 0;
    }

    /** Blits {@code mask}, {@link #COLOR} and {@link #DEPTH} bits, from {@code source} into the same rect of the copy. */
    private void blit(int source, int mask, int x0, int y0, int x1, int y1) {
        if (mask == 0) return;
        int zone = (mask & COLOR) == 0 ? DEPTH_ZONE : mask == COLOR ? COLOR_ZONE : BOTH_ZONE;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, zone)) {
            CgExecutor.blitting(source, storage.getId());
            blitOnDevice(sources(), source, mask, x0, y0, x1, y1);
            CgExecutor.blitted(source, storage.getId());
        }
    }

    private static Sources sources() {
        if (sources == null) throw new IllegalStateException("a target copy outside the executor's run");
        return sources;
    }

    /** {@link #blit} on the device's command list, in a device section. */
    private void blitOnDevice(Sources from, int source, int mask, int x0, int y0, int x1, int y1) {
        CgGpuTexture color = (mask & COLOR) != 0 ? from.color(source) : null;
        CgGpuTexture depth = (mask & DEPTH) != 0 ? from.depth(source) : null;
        if ((mask & COLOR) != 0 && color == null || (mask & DEPTH) != 0 && depth == null) {
            throw new IllegalStateException("framebuffer " + source + " has no device texture to copy from");
        }
        CgDeviceSection.begin();
        try {
            if (color != null) {
                CgRHI.get().encoder().blit(CgTextureView.attachment(color, 0, 0), x0, y0, x1, y1,
                        CgTextureView.attachment(storage.rhiColor(0), 0, 0), x0, y0, x1, y1, CgGpuSampler.Filter.NEAREST);
            }
            if (depth != null) {
                CgRHI.get().encoder().blit(CgTextureView.attachment(depth, 0, 0), x0, y0, x1, y1,
                        CgTextureView.attachment(storage.rhiDepth(), 0, 0), x0, y0, x1, y1, CgGpuSampler.Filter.NEAREST);
            }
        } finally {
            CgDeviceSection.end();
        }
    }

    private static final int COLOR_ZONE = CgTrace.name("graph.copy.color"), DEPTH_ZONE = CgTrace.name("graph.copy.depth"),
            BOTH_ZONE = CgTrace.name("graph.copy.colorDepth");

    /** Gives the copy's framebuffer back to the pool. When the pass ends. */
    private static boolean fits(@Nullable CgTextureDesc d, int width, int height, CgFrameBufferFormat format) {
        return d != null && d.width() == width && d.height() == height && d.format().equals(format);
    }

    /** Gives its storage back to {@code pool}; it keeps what it asked for, the key of its next copy's. */
    void release(CgTexturePool pool) {
        if (storage != null) pool.release(desc, storage);
        storage = null;
        depthLender = null;
    }

    private static CgFrameBufferFormat copyFormat(@Nullable CgTextureType colorType, @Nullable CgTextureType depthType) {
        CgFrameBufferFormat.Builder builder = CgFrameBufferFormat.builder("cg_target_copy");
        if (colorType != null) builder.color(0, colorType);
        if (depthType != null) builder.depth(depthType);
        return builder.build();
    }

    /** The texture a pass binds for one half of its copy. */
    private final class View implements CgTexture {

        private final boolean isColor;

        View(boolean isColor) {
            this.isColor = isColor;
        }

        @Nullable
        private CgFrameBuffer source() {
            return !isColor && depthLender != null ? depthLender.storage : storage;
        }

        @Nullable
        private CgTexture texture() {
            CgFrameBuffer from = source();
            if (from == null) return null;
            return isColor ? from.getColorTexture(0) : from.getDepthTexture();
        }

        @Override
        public void bind(int unit) {
            CgTexture texture = texture();
            if (texture != null) texture.bind(unit);
        }

        @Override
        @Nullable
        public CgTextureView rhiView() {
            CgTexture texture = texture();
            return texture == null ? null : texture.rhiView();
        }

        @Override
        public int getId() {
            CgTexture texture = texture();
            return texture == null ? 0 : texture.getId();
        }

        @Override
        public int getWidth() {
            CgFrameBuffer from = source();
            return from == null ? 0 : from.getWidth();
        }

        @Override
        public int getHeight() {
            CgFrameBuffer from = source();
            return from == null ? 0 : from.getHeight();
        }

        @Override
        public int getTarget() {
            CgTexture texture = texture();
            return texture == null ? CgGL.GL_TEXTURE_2D : texture.getTarget();
        }

        @Override
        public boolean isDeleted() {
            return false;
        }

        @Override
        public void delete() {
            throw new UnsupportedOperationException("a target copy belongs to its pass");
        }
    }
}
