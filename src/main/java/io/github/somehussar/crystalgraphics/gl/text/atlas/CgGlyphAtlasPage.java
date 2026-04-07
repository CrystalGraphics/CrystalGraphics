package io.github.somehussar.crystalgraphics.gl.text.atlas;

import io.github.somehussar.crystalgraphics.api.font.CgAtlasRegion;
import io.github.somehussar.crystalgraphics.api.font.CgGlyphKey;
import io.github.somehussar.crystalgraphics.api.font.CgGlyphPlacement;
import io.github.somehussar.crystalgraphics.gl.text.CgGlyphAtlas;
import io.github.somehussar.crystalgraphics.text.atlas.CgGuillotinePacker;
import io.github.somehussar.crystalgraphics.text.atlas.CgPackingStrategy;
import io.github.somehussar.crystalgraphics.text.atlas.PackedRect;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * A single atlas page within a {@link CgPagedGlyphAtlas}.
 *
 * <p>Each page owns one GL texture and one packing strategy instance. Glyph
 * placements within a page are <strong>stable</strong>: once a glyph is
 * allocated, its position never changes. There is no LRU eviction at the
 * page level — when a page is full, the paged atlas allocates a new page.</p>
 *
 * <h3>GL Texture</h3>
 * <p>The page texture is allocated lazily on first use or eagerly via
 * {@link #create(int, int, CgGlyphAtlas.Type, int, CgPackingStrategy)}.
 * Format is either {@code GL_R8} (bitmap) or {@code GL_RGB16F} (MSDF).</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Not thread-safe. Must only be used from the GL context thread.</p>
 *
 * @see CgPagedGlyphAtlas
 */
public class CgGlyphAtlasPage {

    private static final Logger LOGGER = Logger.getLogger(CgGlyphAtlasPage.class.getName());

    // ── GL constants ───────────────────────────────────────────────────
    private static final int GL_TEXTURE_2D         = 0x0DE1;
    private static final int GL_R8                 = 0x8229;
    private static final int GL_RED                = 0x1903;
    private static final int GL_RGB                = 0x1907;
    private static final int GL_RGBA               = 0x1908;
    private static final int GL_RGB16F             = 0x881B;
    private static final int GL_RGBA16F            = 0x881A;
    private static final int GL_UNSIGNED_BYTE      = 0x1401;
    private static final int GL_FLOAT              = 0x1406;
    private static final int GL_TEXTURE_MIN_FILTER = 0x2801;
    private static final int GL_TEXTURE_MAG_FILTER = 0x2800;
    private static final int GL_TEXTURE_WRAP_S     = 0x2802;
    private static final int GL_TEXTURE_WRAP_T     = 0x2803;
    private static final int GL_NEAREST            = 0x2600;
    private static final int GL_LINEAR             = 0x2601;
    private static final int GL_CLAMP_TO_EDGE      = 0x812F;
    private static final int GL_UNPACK_ALIGNMENT   = 0x0CF5;

    private static final int INITIAL_UPLOAD_BUFFER_SIZE = 64 * 64;

    // ── Instance fields ────────────────────────────────────────────────

    private final int pageIndex;
    private final int pageWidth;
    private final int pageHeight;
    private final CgGlyphAtlas.Type type;
    private final CgPackingStrategy packer;
    private final boolean skipGlUpload;

    private int textureId;
    private boolean deleted;

    private final Map<CgGlyphKey, SlotEntry> slotMap;

    private ByteBuffer uploadBuffer;
    private FloatBuffer msdfUploadBuffer;

    // ── Constructor (use factory methods) ──────────────────────────────

    private CgGlyphAtlasPage(int pageIndex, int pageWidth, int pageHeight,
                             CgGlyphAtlas.Type type, int textureId,
                             boolean skipGlUpload, CgPackingStrategy packer) {
        this.pageIndex = pageIndex;
        this.pageWidth = pageWidth;
        this.pageHeight = pageHeight;
        this.type = type;
        this.textureId = textureId;
        this.skipGlUpload = skipGlUpload;
        this.packer = packer;
        this.deleted = false;
        this.slotMap = new HashMap<CgGlyphKey, SlotEntry>();

        if (!skipGlUpload) {
            if (type == CgGlyphAtlas.Type.BITMAP) {
                this.uploadBuffer = BufferUtils.createByteBuffer(INITIAL_UPLOAD_BUFFER_SIZE);
            } else if (type == CgGlyphAtlas.Type.MTSDF) {
                this.msdfUploadBuffer = BufferUtils.createFloatBuffer(64 * 64 * 4);
            } else {
                this.msdfUploadBuffer = BufferUtils.createFloatBuffer(64 * 64 * 3);
            }
        }
    }

    // ── Factory ────────────────────────────────────────────────────────

    /**
     * Creates a new atlas page with a GL texture. Requires a GL context.
     *
     * @param pageWidth  page width in pixels
     * @param pageHeight page height in pixels
     * @param type       bitmap or MSDF
     * @param pageIndex  index within the paged atlas
     * @param packer     packing strategy to use for this page
     * @return a new page instance
     */
    public static CgGlyphAtlasPage create(int pageWidth, int pageHeight,
                                           CgGlyphAtlas.Type type, int pageIndex,
                                           CgPackingStrategy packer) {
        int texId = GL11.glGenTextures();
        GL11.glBindTexture(GL_TEXTURE_2D, texId);

        if (type == CgGlyphAtlas.Type.BITMAP) {
            int prevAlignment = GL11.glGetInteger(GL_UNPACK_ALIGNMENT);
            GL11.glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            GL11.glTexImage2D(GL_TEXTURE_2D, 0, GL_R8,
                    pageWidth, pageHeight, 0,
                    GL_RED, GL_UNSIGNED_BYTE, BufferUtils.createByteBuffer(pageWidth * pageHeight));
            GL11.glPixelStorei(GL_UNPACK_ALIGNMENT, prevAlignment);
        } else if (type == CgGlyphAtlas.Type.MTSDF) {
            GL11.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F,
                    pageWidth, pageHeight, 0,
                    GL_RGBA, GL_FLOAT, BufferUtils.createFloatBuffer(pageWidth * pageHeight * 4));
        } else {
            GL11.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB16F,
                    pageWidth, pageHeight, 0,
                    GL_RGB, GL_FLOAT, BufferUtils.createFloatBuffer(pageWidth * pageHeight * 3));
        }

        if (type == CgGlyphAtlas.Type.BITMAP) {
            GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        } else {
            GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        }
        GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        GL11.glBindTexture(GL_TEXTURE_2D, 0);

        return new CgGlyphAtlasPage(pageIndex, pageWidth, pageHeight,
                type, texId, false, packer);
    }

    /**
     * Creates a test-mode page that skips GL calls.
     */
    static CgGlyphAtlasPage createForTest(int pageWidth, int pageHeight,
                                           CgGlyphAtlas.Type type, int pageIndex,
                                           CgPackingStrategy packer) {
        return new CgGlyphAtlasPage(pageIndex, pageWidth, pageHeight,
                type, 0, true, packer);
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
        PackedRect packed = packer.insert(width, height, key);
        if (packed == null) {
            return null;
        }

        uploadBitmap(packed.getX(), packed.getY(), width, height, bitmapData);

        CgGlyphPlacement placement = buildPlacement(
                packed, key, bearingX, bearingY,
                bearingX, bearingY - metricsHeight, bearingX + metricsWidth, bearingY,
                metricsWidth, metricsHeight, 0f);
        slotMap.put(key, new SlotEntry(packed, placement, currentFrame));
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
        PackedRect packed = packer.insert(width, height, key);
        if (packed == null) {
            return null;
        }

        uploadMsdf(packed.getX(), packed.getY(), width, height, msdfData);

        CgGlyphPlacement placement = buildPlacement(
                packed, key, bearingX, bearingY,
                planeLeft, planeBottom, planeRight, planeTop,
                metricsWidth, metricsHeight, pxRange);
        slotMap.put(key, new SlotEntry(packed, placement, currentFrame));
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

    // ── Queries ────────────────────────────────────────────────────────

    public int getPageIndex() { return pageIndex; }
    public int getPageWidth() { return pageWidth; }
    public int getPageHeight() { return pageHeight; }
    public int getTextureId() { return textureId; }
    public CgGlyphAtlas.Type getType() { return type; }
    public boolean isDeleted() { return deleted; }

    /** Returns the number of glyphs currently stored in this page. */
    public int getSlotCount() { return slotMap.size(); }

    /** Returns the packing utilization ratio for this page. */
    public float getUtilization() { return packer.utilization(); }

    /** Returns the total pixel area occupied by packed glyph slots. */
    public long getPackedArea() {
        long area = 0;
        for (SlotEntry entry : slotMap.values()) {
            area += (long) entry.packed.getWidth() * entry.packed.getHeight();
        }
        return area;
    }

    /** Returns the glyph keys currently stored in this page. */
    public Set<CgGlyphKey> getGlyphKeys() {
        return java.util.Collections.unmodifiableSet(slotMap.keySet());
    }

    // ── Deletion ───────────────────────────────────────────────────────

    /**
     * Deletes the GL texture and clears all slot tracking.
     * Subsequent calls are no-ops.
     */
    public void delete() {
        if (deleted) {
            return;
        }
        if (!skipGlUpload) {
            GL11.glDeleteTextures(textureId);
        }
        textureId = 0;
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
        int px = packed.getX();
        int py = packed.getY();
        int pw = packed.getWidth();
        int ph = packed.getHeight();

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
                key, pageIndex, textureId, type,
                resolvedPlaneLeft, resolvedPlaneBottom, resolvedPlaneRight, resolvedPlaneTop,
                atlasLeft, atlasBottom, atlasRight, atlasTop,
                u0, v0, u1, v1,
                pxRange
        );
    }

    // ── Internal: GL upload ────────────────────────────────────────────

    private void uploadBitmap(int x, int y, int w, int h, byte[] data) {
        if (skipGlUpload) {
            return;
        }
        int required = w * h;
        if (uploadBuffer == null || uploadBuffer.capacity() < required) {
            uploadBuffer = BufferUtils.createByteBuffer(required);
        }
        uploadBuffer.clear();
        uploadBuffer.put(data, 0, required);
        uploadBuffer.flip();

        GL11.glBindTexture(GL_TEXTURE_2D, textureId);
        int prevAlignment = GL11.glGetInteger(GL_UNPACK_ALIGNMENT);
        GL11.glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        GL11.glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, w, h,
                GL_RED, GL_UNSIGNED_BYTE, uploadBuffer);
        GL11.glPixelStorei(GL_UNPACK_ALIGNMENT, prevAlignment);
        GL11.glBindTexture(GL_TEXTURE_2D, 0);
    }

    private void uploadMsdf(int x, int y, int w, int h, float[] data) {
        if (skipGlUpload) {
            return;
        }
        int channels = (type == CgGlyphAtlas.Type.MTSDF) ? 4 : 3;
        int glFormat = (type == CgGlyphAtlas.Type.MTSDF) ? GL_RGBA : GL_RGB;
        int required = w * h * channels;
        if (msdfUploadBuffer == null || msdfUploadBuffer.capacity() < required) {
            msdfUploadBuffer = BufferUtils.createFloatBuffer(required);
        }
        msdfUploadBuffer.clear();
        msdfUploadBuffer.put(data, 0, required);
        msdfUploadBuffer.flip();

        GL11.glBindTexture(GL_TEXTURE_2D, textureId);
        GL11.glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, w, h,
                glFormat, GL_FLOAT, msdfUploadBuffer);
        GL11.glBindTexture(GL_TEXTURE_2D, 0);
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
