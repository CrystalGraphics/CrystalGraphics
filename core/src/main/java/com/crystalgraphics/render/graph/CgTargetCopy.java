package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlState;
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

    /** Copy formats by the source's: its colour slot 0 and depth, as textures. Render thread. */
    private static final Map<CgFrameBufferFormat, CgFrameBufferFormat> FORMATS = new HashMap<>();

    /**
     * Current targets probed, most recent first: each framebuffer, the copy format and the depth blit mask that match
     * it. Several, since a host's stages may alternate targets and each probe is a {@code glGet}.
     */
    private static final int PROBED = 4;
    private static final int[] probedSource = {-1, -1, -1, -1};
    private static final CgFrameBufferFormat[] probedFormat = new CgFrameBufferFormat[PROBED];
    private static final int[] probedDepthMask = new int[PROBED];

    final CgTexture color = new View(true), depth = new View(false);
    @Nullable
    private CgFrameBuffer storage;
    @Nullable
    private CgTextureDesc desc;

    /**
     * Copies what {@code bits} name from {@code width} x {@code height} framebuffer {@code source}: the current target
     * when {@code sourceFormat} is null, else a target of that format. Colour is copied in the rect at {@code at}
     * of {@code rects}, GL pixels x0, y0, x1, y1, or whole where its x1 is below 0. Leaves the framebuffer binding and
     * scissor as found. Answers the colour pixels copied.
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
            depthMask = sourceFormat.hasDepth() ? CgGL.GL_DEPTH_BUFFER_BIT : 0;
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
        int colorMask = (bits & COLOR) != 0 && format.colorSlotCount() > 0 ? CgGL.GL_COLOR_BUFFER_BIT : 0;
        if ((bits & DEPTH) == 0) depthMask = 0;
        if ((colorMask | depthMask) == 0 || width <= 0 || height <= 0) return 0L;
        if (desc == null || desc.width() != width || desc.height() != height || !desc.format().equals(format)) {
            release(pool);
            desc = new CgTextureDesc(width, height, format);
            storage = pool.acquire(desc);
        }
        int x0 = 0, y0 = 0, x1 = width, y1 = height;
        if (rects[at + 2] >= 0) {
            x0 = Math.max(0, rects[at]);
            y0 = Math.max(0, rects[at + 1]);
            x1 = Math.min(width, rects[at + 2]);
            y1 = Math.min(height, rects[at + 3]);
        }
        if (x1 <= x0 || y1 <= y0) colorMask = 0;
        try (CgGlScope scope = CgGlState.save(CgGlSlot.SCISSOR)) {
            CgGL.glDisable(CgGL.GL_SCISSOR_TEST);   // a blit is scissored
            if (colorSource == depthSource && x0 == 0 && y0 == 0 && x1 == width && y1 == height) {
                blit(colorSource, colorMask | depthMask, 0, 0, width, height);
            } else {
                blit(depthSource, depthMask, 0, 0, width, height);
                blit(colorSource, colorMask, x0, y0, x1, y1);
            }
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

    private static final int[] WHOLE = {0, 0, -1, -1};

    /** Where current target {@code source}'s probe is, probing it into slot 0 if none is held. */
    private static int probe(int source) {
        for (int i = 0; i < PROBED; i++) if (probedSource[i] == source) return i;
        System.arraycopy(probedSource, 0, probedSource, 1, PROBED - 1);
        System.arraycopy(probedFormat, 0, probedFormat, 1, PROBED - 1);
        System.arraycopy(probedDepthMask, 0, probedDepthMask, 1, PROBED - 1);
        CgTextureType type = CgFrameBuffer.depthTypeOf(source);
        probedSource[0] = source;
        probedFormat[0] = copyFormat(CgTextureType.RGBA8, type);
        probedDepthMask[0] = type == null ? 0 : CgFrameBuffer.optimalDepthBlitMask(source);
        return 0;
    }

    /** Blits {@code mask} from {@code source} into the same rect of the copy. */
    private void blit(int source, int mask, int x0, int y0, int x1, int y1) {
        if (mask == 0) return;
        int zone = (mask & CgGL.GL_COLOR_BUFFER_BIT) == 0 ? DEPTH_ZONE : mask == CgGL.GL_COLOR_BUFFER_BIT ? COLOR_ZONE : BOTH_ZONE;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, zone)) {
            CgFrameBuffer.blitFrom(source, storage.getId(), x0, y0, x1, y1, x0, y0, x1, y1, mask, CgGL.GL_NEAREST);
        }
    }

    private static final int COLOR_ZONE = CgTrace.name("graph.copy.color"), DEPTH_ZONE = CgTrace.name("graph.copy.depth"),
            BOTH_ZONE = CgTrace.name("graph.copy.colorDepth");

    /** Gives the copy's framebuffer back to the pool. When the pass ends. */
    void release(CgTexturePool pool) {
        if (storage != null) pool.release(desc, storage);
        storage = null;
        desc = null;
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
        private CgTexture texture() {
            if (storage == null) return null;
            return isColor ? storage.getColorTexture(0) : storage.getDepthTexture();
        }

        @Override
        public void bind() {
            CgTexture texture = texture();
            if (texture != null) texture.bind();
        }

        @Override
        public void bind(int unit) {
            CgTexture texture = texture();
            if (texture != null) texture.bind(unit);
        }

        @Override
        public int getId() {
            CgTexture texture = texture();
            return texture == null ? 0 : texture.getId();
        }

        @Override
        public int getWidth() {
            return storage == null ? 0 : storage.getWidth();
        }

        @Override
        public int getHeight() {
            return storage == null ? 0 : storage.getHeight();
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
