package com.crystalgraphics.text.atlas;

import com.crystalgraphics.api.font.CgGlyphKey;
import com.crystalgraphics.api.font.CgGlyphPlacement;
import com.crystalgraphics.gl.texture.CgTexture2DArray;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.text.atlas.packing.CgPackingStrategy;
import com.crystalgraphics.text.atlas.packing.PackedRect;

import com.crystalgraphics.util.CgBufferUtils;
import lombok.Getter;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * A single atlas page within a {@link CgPagedGlyphAtlas} — one layer of the
 * atlas family's shared {@link CgTexture2DArray}, plus one packing strategy
 * instance. Glyph placements within a page are <strong>stable</strong>: once
 * a glyph is allocated, its position never changes. There is no
 * <em>slot-level</em> eviction within a page — when a page is full, the
 * paged atlas allocates a new page (a fresh layer, or a reused layer freed by
 * whole-page eviction — see {@link CgPagedGlyphAtlas}).
 *
 * <h3>Ownership</h3>
 * <p>Unlike the pre-array-migration version of this class, a page owns
 * <strong>no GL resource of its own</strong> — the GL texture (the array) is
 * owned once by the parent {@link CgPagedGlyphAtlas}. A page is just a
 * {@code (layerIndex, CgPackingStrategy)} pair that knows how to upload into
 * its one assigned layer of the shared array via
 * {@link CgTexture2DArray#uploadLayerRegion}. {@link #delete()} therefore
 * does not free any GL object — it only clears this page's own bookkeeping,
 * since the array texture itself outlives any individual page/layer being
 * evicted and reused.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Not thread-safe. Must only be used from the GL context thread.</p>
 *
 * @see CgPagedGlyphAtlas
 */
public class CgGlyphAtlasPage {

    private static final Logger LOGGER = Logger.getLogger(CgGlyphAtlasPage.class.getName());

    // ── GL constants (upload-format only — no texture-object constants needed anymore) ──
    private static final int GL_RED           = CgGL.GL_RED;
    private static final int GL_RGB           = CgGL.GL_RGB;
    private static final int GL_RGBA          = CgGL.GL_RGBA;
    private static final int GL_UNSIGNED_BYTE = CgGL.GL_UNSIGNED_BYTE;
    private static final int GL_FLOAT         = CgGL.GL_FLOAT;


    private static final int INITIAL_UPLOAD_BUFFER_SIZE = 64 * 64;

    // ── Instance fields ────────────────────────────────────────────────

    /** Index of this page's layer within the owning atlas's array texture. */
    @Getter
    private final int pageIndex;
    @Getter
    private final int pageWidth;
    @Getter
    private final int pageHeight;
    @Getter
    private final CgGlyphAtlas.Type type;
    private final CgPackingStrategy packer;

    /** The owning atlas's shared array texture. {@code null} in {@link #createForTest} mode. */
    private final CgTexture2DArray arrayTexture;

    @Getter
    private boolean deleted;

    /**
     * Highest {@code lastUsedFrame} of any glyph currently resident on this page —
     * i.e. how recently this whole page was last touched. Used by
     * {@link CgPagedGlyphAtlas}'s page-budget eviction to pick the coldest page,
     * without scanning every page's slot map on every allocation.
     */
    @Getter
    private long lastTouchedFrame = -1;

    private final Map<CgGlyphKey, SlotEntry> slotMap;

    private ByteBuffer uploadBuffer;
    private FloatBuffer msdfUploadBuffer;

    /**
     * UV rect of the reserved opaque-white texel (see {@link #reserveWhiteTexel()}), or
     * {@code null} if not yet reserved on this page.
     */
    private float[] whiteTexelUv;

    /** Sentinel packer id for the reserved white texel — never a real {@link CgGlyphKey}. */
    private static final Object WHITE_TEXEL_ID = new Object();

    // ── Constructor (use factory methods) ──────────────────────────────

    private CgGlyphAtlasPage(int pageIndex, int pageWidth, int pageHeight,
                             CgGlyphAtlas.Type type, CgTexture2DArray arrayTexture,
                             CgPackingStrategy packer) {
        this.pageIndex = pageIndex;
        this.pageWidth = pageWidth;
        this.pageHeight = pageHeight;
        this.type = type;
        this.arrayTexture = arrayTexture;
        this.packer = packer;
        this.deleted = false;
        this.slotMap = new HashMap<>();

        if (arrayTexture != null) {
            if (type == CgGlyphAtlas.Type.BITMAP) {
                this.uploadBuffer = CgBufferUtils.createByteBuffer(INITIAL_UPLOAD_BUFFER_SIZE);
            } else {
                this.msdfUploadBuffer = CgBufferUtils.createFloatBuffer(64 * 64 * 4);
            }
        }
    }

    // ── Factory ────────────────────────────────────────────────────────

    /**
     * Creates a new atlas page backed by layer {@code atlasPageIndex} of
     * {@code arrayTexture}. The array itself must already be allocated by the
     * caller ({@link CgPagedGlyphAtlas} owns it) — this only records which
     * layer this page uploads into.
     *
     * @param pageWidth    layer width in pixels
     * @param pageHeight   layer height in pixels
     * @param type         bitmap or MSDF/MTSDF
     * @param pageIndex    layer index within {@code arrayTexture}
     * @param arrayTexture the owning atlas's shared array texture (never null here)
     * @param packer       packing strategy to use for this page
     * @return a new page instance
     */
    public static CgGlyphAtlasPage create(int pageWidth, int pageHeight,
                                           CgGlyphAtlas.Type type, int pageIndex,
                                           CgTexture2DArray arrayTexture,
                                           CgPackingStrategy packer) {
        if (arrayTexture == null) {
            throw new IllegalArgumentException("arrayTexture must not be null for a real (non-test) page");
        }
        return new CgGlyphAtlasPage(pageIndex, pageWidth, pageHeight, type, arrayTexture, packer);
    }

    /**
     * Creates a test-mode page that skips all GL calls (no array texture).
     */
    static CgGlyphAtlasPage createForTest(int pageWidth, int pageHeight,
                                           CgGlyphAtlas.Type type, int pageIndex,
                                           CgPackingStrategy packer) {
        return new CgGlyphAtlasPage(pageIndex, pageWidth, pageHeight, type, null, packer);
    }

    // ── Core API ───────────────────────────────────────────────────────

    /**
     * Looks up a cached glyph in this page.
     *
     * @param key          glyph key
     * @param currentFrame current frame for LRU tracking
     * @return the placement, or {@code null} if not in this page
     */
    public CgGlyphPlacement get(CgGlyphKey key, long currentFrame) {
        SlotEntry entry = slotMap.get(key);
        if (entry == null) {
            return null;
        }
        entry.lastUsedFrame = currentFrame;
        if (currentFrame > lastTouchedFrame) {
            lastTouchedFrame = currentFrame;
        }
        return entry.placement;
    }

    /**
     * Attempts to allocate and upload a bitmap glyph into this page.
     *
     * @return the placement, or {@code null} if the glyph does not fit
     */
    public CgGlyphPlacement allocateBitmap(CgGlyphKey key, byte[] bitmapData,
                                            int width, int height,
                                            float bearingX, float bearingY,
                                            float metricsWidth, float metricsHeight,
                                            long currentFrame) {
        checkNotDeleted();
        // Cheap reject before the packer's free-list walk — see CgPackingStrategy#mayFit and
        // CgPagedGlyphAtlas's oldest-first page scan, which relies on this to stay affordable.
        if (!packer.mayFit(width, height)) {
            return null;
        }
        PackedRect packed = packer.insert(width, height, key);
        if (packed == null) {
            return null;
        }

        uploadBitmap(packed.x(), packed.y(), width, height, bitmapData);

        CgGlyphPlacement placement = buildPlacement(
                packed, key, bearingX, bearingY,
                bearingX, bearingY - metricsHeight, bearingX + metricsWidth, bearingY,
                metricsWidth, metricsHeight, 0f);
        slotMap.put(key, new SlotEntry(packed, placement, currentFrame));
        if (currentFrame > lastTouchedFrame) {
            lastTouchedFrame = currentFrame;
        }
        return placement;
    }

    /**
     * Attempts to allocate and upload an MSDF glyph into this page.
     *
     * @return the placement, or {@code null} if the glyph does not fit
     */
    public CgGlyphPlacement allocateMsdf(CgGlyphKey key, float[] msdfData,
                                          int width, int height,
                                          float bearingX, float bearingY,
                                          float planeLeft, float planeBottom,
                                          float planeRight, float planeTop,
                                          float metricsWidth, float metricsHeight,
                                          float pxRange,
                                          long currentFrame) {
        checkNotDeleted();
        // Cheap reject before the packer's free-list walk — see CgPackingStrategy#mayFit and
        // CgPagedGlyphAtlas's oldest-first page scan, which relies on this to stay affordable.
        if (!packer.mayFit(width, height)) {
            return null;
        }
        PackedRect packed = packer.insert(width, height, key);
        if (packed == null) {
            return null;
        }

        uploadMsdf(packed.x(), packed.y(), width, height, msdfData);

        CgGlyphPlacement placement = buildPlacement(
                packed, key, bearingX, bearingY,
                planeLeft, planeBottom, planeRight, planeTop,
                metricsWidth, metricsHeight, pxRange);
        slotMap.put(key, new SlotEntry(packed, placement, currentFrame));
        if (currentFrame > lastTouchedFrame) {
            lastTouchedFrame = currentFrame;
        }
        return placement;
    }

    /**
     * Attempts to allocate space for a glyph without uploading pixel data.
     * Used for layout-only (prewarm) paths.
     *
     * @return the packed rect, or {@code null} if it does not fit
     */
    public PackedRect tryAllocate(int width, int height, Object id) {
        checkNotDeleted();
        return packer.insert(width, height, id);
    }

    /**
     * Reserves (once) a 1x1 opaque-white texel on this page — RGB/R all {@code 1.0},
     * which reads back as full white under both the bitmap fragment path
     * ({@code texture.r * color.a}) and the MSDF path (a max-channel median of
     * {@code 1,1,1} decodes to full inside-coverage). Text decoration lines
     * (underline/strikethrough) sample this texel through the same {@code text.shader}
     * material already bound for this page's glyphs, so they draw as flat-colored
     * quads without any shader change or extra material/texture bind.
     *
     * @return this page's white-texel UV rect ({@code u0,v0,u1,v1}), or {@code null}
     *         if the page has no room left for it
     */
    public float[] reserveWhiteTexel() {
        checkNotDeleted();
        if (whiteTexelUv != null) {
            return whiteTexelUv;
        }
        PackedRect packed = packer.insert(1, 1, WHITE_TEXEL_ID);
        if (packed == null) {
            return null;
        }
        if (type == CgGlyphAtlas.Type.BITMAP) {
            uploadBitmap(packed.x(), packed.y(), 1, 1, new byte[]{(byte) 0xFF});
        } else {
            int channels = (type == CgGlyphAtlas.Type.MTSDF) ? 4 : 3;
            float[] white = new float[channels];
            java.util.Arrays.fill(white, 1.0f);
            uploadMsdf(packed.x(), packed.y(), 1, 1, white);
        }
        // Sample dead-center of the texel — no distance-field inset needed since this
        // is a flat 1x1 fill, not a rasterized glyph shape.
        float u = (packed.x() + 0.5f) / pageWidth;
        float v = (packed.y() + 0.5f) / pageHeight;
        whiteTexelUv = new float[]{u, v, u, v};
        return whiteTexelUv;
    }

    /** {@code true} if {@link #reserveWhiteTexel()} has already succeeded on this page. */
    public boolean hasWhiteTexel() {
        return whiteTexelUv != null;
    }

    // ── Queries ────────────────────────────────────────────────────────

    /**
     * Returns the GL texture id of the owning atlas's shared array texture
     * (the same id for every page/layer of this atlas family), or {@code 0}
     * in test mode.
     */
    public int getTextureId() {
        return arrayTexture != null ? arrayTexture.getId() : 0;
    }

    /** Returns the number of glyphs currently stored in this page. */
    public int getSlotCount() { return slotMap.size(); }

    /** Returns the packing utilization ratio for this page. */
    public float getUtilization() { return packer.utilization(); }

    /**
     * Diagnostic: how many free regions on this page could still accept a
     * {@code width x height} glyph. See {@link CgPackingStrategy#countFreeRegionsFitting} —
     * distinguishes "this page looks half empty in a dump image" from "this page has space a
     * glyph could actually use".
     */
    public int countFreeRegionsFitting(int width, int height) {
        return packer.countFreeRegionsFitting(width, height);
    }

    /** Diagnostic: this page's free regions as {@code {x, y, w, h}}, largest first.
     * See {@link CgPackingStrategy#describeFreeRegions}. */
    public int[][] describeFreeRegions() {
        return packer.describeFreeRegions();
    }

    /** Returns the total pixel area occupied by packed glyph slots. */
    public long getPackedArea() {
        long area = 0;
        for (SlotEntry entry : slotMap.values()) {
            area += (long) entry.packed.width() * entry.packed.height();
        }
        return area;
    }

    /** Returns the glyph keys currently stored in this page. */
    public Set<CgGlyphKey> getGlyphKeys() {
        return java.util.Collections.unmodifiableSet(slotMap.keySet());
    }

    // ── Deletion ───────────────────────────────────────────────────────

    /**
     * Clears this page's bookkeeping (slot map, packer state stays as-is —
     * the caller replaces this page object entirely rather than reusing it).
     * Frees no GL resource — the array texture is owned by the parent
     * {@link CgPagedGlyphAtlas} and outlives individual evicted pages; its
     * layer is simply reassigned to whatever page reuses this layer index
     * next, letting new uploads overwrite the pixels this page's glyphs used
     * to occupy. Subsequent calls are no-ops.
     */
    public void delete() {
        if (deleted) {
            return;
        }
        slotMap.clear();
        deleted = true;
    }

    // ── Internal: placement builder ───────────────────────────────────

    private CgGlyphPlacement buildPlacement(PackedRect packed, CgGlyphKey key,
                                             float bearingX, float bearingY,
                                             float planeLeft, float planeBottom,
                                             float planeRight, float planeTop,
                                             float metricsWidth, float metricsHeight,
                                             float pxRange) {
        int px = packed.x();
        int py = packed.y();
        int pw = packed.width();
        int ph = packed.height();

        boolean distanceField = type != CgGlyphAtlas.Type.BITMAP;
        float insetX = distanceField && pw > 1 ? 0.5f : 0.0f;
        float insetY = distanceField && ph > 1 ? 0.5f : 0.0f;
        float u0 = (px + insetX) / pageWidth;
        float v0 = (py + insetY) / pageHeight;
        float u1 = (px + pw - insetX) / pageWidth;
        float v1 = (py + ph - insetY) / pageHeight;

        // Plane bounds derived from bearing/metrics in raster space.
        // For MSDF: use full box size (includes SDF range border).
        // For bitmap: use metrics extents (visible glyph outline).
        float resolvedPlaneLeft;
        float resolvedPlaneBottom;
        float resolvedPlaneRight;
        float resolvedPlaneTop;
        if (distanceField) {
            resolvedPlaneLeft = planeLeft;
            resolvedPlaneBottom = planeBottom;
            resolvedPlaneRight = planeRight;
            resolvedPlaneTop = planeTop;
        } else {
            resolvedPlaneLeft = bearingX;
            resolvedPlaneTop = bearingY;
            resolvedPlaneRight = resolvedPlaneLeft + metricsWidth;
            resolvedPlaneBottom = resolvedPlaneTop - metricsHeight;
        }

        // Atlas bounds as integer pixel coordinates (matching existing CgGlyphPlacement contract)
        int atlasLeft = px;
        int atlasBottom = py + ph;  // bottom = top + height in top-left-origin
        int atlasRight = px + pw;
        int atlasTop = py;

        return new CgGlyphPlacement(
                key, getTextureId(), pageIndex, type,
                resolvedPlaneLeft, resolvedPlaneBottom, resolvedPlaneRight, resolvedPlaneTop,
                atlasLeft, atlasBottom, atlasRight, atlasTop,
                u0, v0, u1, v1,
                pxRange
        );
    }

    // ── Internal: array-layer upload ────────────────────────────────────

    private void uploadBitmap(int x, int y, int w, int h, byte[] data) {
        if (arrayTexture == null) {
            return;
        }
        int required = w * h;
        if (uploadBuffer == null || uploadBuffer.capacity() < required) {
            uploadBuffer = CgBufferUtils.createByteBuffer(required);
        }
        uploadBuffer.clear();
        uploadBuffer.put(data, 0, required);
        uploadBuffer.flip();

        arrayTexture.uploadLayerRegion(pageIndex, x, y, w, h, GL_RED, GL_UNSIGNED_BYTE, uploadBuffer);
    }

    private void uploadMsdf(int x, int y, int w, int h, float[] data) {
        if (arrayTexture == null) {
            return;
        }
        // Client-side upload format matches what CgMsdfGenerator actually produces
        // (3 floats/pixel for MSDF, 4 for MTSDF) — independent of the array's
        // unified RGBA8 internal storage format (see CgPagedGlyphAtlas javadoc).
        // For plain MSDF this leaves the destination alpha channel untouched
        // (undefined initial content from the empty allocation); harmless, since
        // text.shader's MSDF path only ever reads .rgb.
        int channels = (type == CgGlyphAtlas.Type.MTSDF) ? 4 : 3;
        int glFormat = (type == CgGlyphAtlas.Type.MTSDF) ? GL_RGBA : GL_RGB;
        int required = w * h * channels;
        if (msdfUploadBuffer == null || msdfUploadBuffer.capacity() < required) {
            msdfUploadBuffer = CgBufferUtils.createFloatBuffer(required);
        }
        msdfUploadBuffer.clear();
        msdfUploadBuffer.put(data, 0, required);
        msdfUploadBuffer.flip();

        // GL_FLOAT into an RGBA8 array: the driver clamps and quantises to 8-bit unorm itself.
        // Quantising CPU-side first was measured and is slower -- see uploadLayerRegion's javadoc.
        // 8 bits is enough for a distance field regardless of who does the conversion: measured on
        // M+ 1p, quantising introduced zero structural defects across six atlas scales (no counter
        // closed, no stroke merged). See CgMsdfFieldStorageTest.
        arrayTexture.uploadLayerRegion(pageIndex, x, y, w, h, glFormat, GL_FLOAT, msdfUploadBuffer);
    }

    private void checkNotDeleted() {
        if (deleted) {
            throw new IllegalStateException("Atlas page has been deleted");
        }
    }

    // ── Internal: slot tracking entry ──────────────────────────────────

    /**
     * Mutable entry tracking a packed glyph slot's position, placement, and LRU frame.
     */
    static final class SlotEntry {
        final PackedRect packed;
        final CgGlyphPlacement placement;
        long lastUsedFrame;

        SlotEntry(PackedRect packed, CgGlyphPlacement placement, long lastUsedFrame) {
            this.packed = packed;
            this.placement = placement;
            this.lastUsedFrame = lastUsedFrame;
        }
    }
}
