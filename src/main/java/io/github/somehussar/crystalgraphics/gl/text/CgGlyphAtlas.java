package io.github.somehussar.crystalgraphics.gl.text;

import io.github.somehussar.crystalgraphics.api.font.CgAtlasRegion;
import io.github.somehussar.crystalgraphics.api.font.CgGlyphKey;
import io.github.somehussar.crystalgraphics.text.atlas.MaxRectsPacker;
import io.github.somehussar.crystalgraphics.text.atlas.PackedRect;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.logging.Logger;

/**
 * Manages a single OpenGL texture atlas for glyph storage, with LRU eviction.
 *
 * <p>Each instance holds <strong>one</strong> GL texture — either a bitmap atlas
 * ({@code GL_R8}, single-channel unsigned byte) or an MSDF atlas
 * ({@code GL_RGB16F}, three-channel half-float storage with float upload).
 * Atlas instances are typically created per {@code CgFontKey} bucket.</p>
 *
 * <p>Packing is delegated to a {@link MaxRectsPacker}. Each occupied slot
 * tracks its last-used frame counter for LRU eviction: when the atlas is full,
 * the slot with the lowest {@code lastUsedFrame} is evicted to make room.</p>
 *
 * <h3>Ownership Model</h3>
 * <p>Follows the pattern of {@code AbstractCgFramebuffer}: instances created via
 * {@link #create(int, int, Type)} are <em>owned</em> and tracked in a static set
 * for bulk cleanup. {@link #delete()} releases the GL texture; subsequent calls
 * are no-ops. {@link #freeAll()} deletes every tracked atlas.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Not thread-safe. Must only be used from the GL context thread.</p>
 *
 * @see CgAtlasRegion
 * @see MaxRectsPacker
 */
public class CgGlyphAtlas {

    private static final Logger LOGGER = Logger.getLogger(CgGlyphAtlas.class.getName());

    // ── GL constants ───────────────────────────────────────────────────

    private static final int GL_TEXTURE_2D         = 0x0DE1;
    private static final int GL_R8                 = 0x8229;
    private static final int GL_RED                = 0x1903;
    private static final int GL_RGB                = 0x1907;
    private static final int GL_RGB16F             = 0x881B;
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

    /** Initial capacity of the pre-allocated upload ByteBuffer (64×64×1 byte). */
    private static final int INITIAL_UPLOAD_BUFFER_SIZE = 64 * 64;

    // ── Static tracking ────────────────────────────────────────────────

    static final Set<CgGlyphAtlas> ALL_OWNED =
            new CopyOnWriteArraySet<CgGlyphAtlas>();

    // ── Atlas type ─────────────────────────────────────────────────────

    /** Discriminates bitmap (GL_R8) from MSDF (GL_RGB16F) atlas textures. */
    public enum Type {
        /** Single-channel bitmap atlas ({@code GL_R8}, {@code GL_UNSIGNED_BYTE}). */
        BITMAP,
        /** Three-channel MSDF atlas ({@code GL_RGB16F}, uploaded as {@code GL_FLOAT}). */
        MSDF
    }

    // ── Instance fields ────────────────────────────────────────────────

    private final int pageWidth;
    private final int pageHeight;
    private final Type type;
    private final boolean skipGlUpload;
    private int textureId;
    private boolean deleted;

    private final MaxRectsPacker packer;
    private final Map<CgGlyphKey, SlotEntry> slotMap;

    private ByteBuffer uploadBuffer;
    private FloatBuffer msdfUploadBuffer;

    // ── Constructor (private — use create()) ───────────────────────────

    private CgGlyphAtlas(int pageWidth, int pageHeight, Type type,
                         int textureId, boolean skipGlUpload) {
        this.pageWidth = pageWidth;
        this.pageHeight = pageHeight;
        this.type = type;
        this.textureId = textureId;
        this.skipGlUpload = skipGlUpload;
        this.deleted = false;
        this.packer = new MaxRectsPacker(pageWidth, pageHeight);
        this.slotMap = new HashMap<CgGlyphKey, SlotEntry>();

        if (!skipGlUpload) {
            if (type == Type.BITMAP) {
                this.uploadBuffer = BufferUtils.createByteBuffer(INITIAL_UPLOAD_BUFFER_SIZE);
            } else {
                this.msdfUploadBuffer = BufferUtils.createFloatBuffer(64 * 64 * 3);
            }
        }

        ALL_OWNED.add(this);
    }

    // ── Factory ────────────────────────────────────────────────────────

    /**
     * Creates a new atlas texture. Requires a GL context on the calling thread.
     *
     * @param pageWidth  atlas width in pixels (must be positive)
     * @param pageHeight atlas height in pixels (must be positive)
     * @param type       bitmap ({@code GL_R8}) or MSDF ({@code GL_RGB16F})
     * @return the new atlas instance (never null)
     */
    public static CgGlyphAtlas create(int pageWidth, int pageHeight, Type type) {
        if (pageWidth <= 0 || pageHeight <= 0) {
            throw new IllegalArgumentException(
                    "Atlas dimensions must be positive, got: " + pageWidth + "x" + pageHeight);
        }

        int texId = GL11.glGenTextures();
        GL11.glBindTexture(GL_TEXTURE_2D, texId);

        // Allocate storage — no initial data
        if (type == Type.BITMAP) {
            // Save/restore unpack alignment for single-channel data
            int prevAlignment = GL11.glGetInteger(GL_UNPACK_ALIGNMENT);
            GL11.glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            GL11.glTexImage2D(GL_TEXTURE_2D, 0, GL_R8,
                    pageWidth, pageHeight, 0,
                    GL_RED, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL11.glPixelStorei(GL_UNPACK_ALIGNMENT, prevAlignment);
        } else {
            GL11.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB16F,
                    pageWidth, pageHeight, 0,
                    GL_RGB, GL_FLOAT, (FloatBuffer) null);
        }

        if (type == Type.BITMAP) {
            GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        } else {
            GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        }
        GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        GL11.glBindTexture(GL_TEXTURE_2D, 0);

        return new CgGlyphAtlas(pageWidth, pageHeight, type, texId, false);
    }

    /**
     * Creates a test-mode atlas that skips all GL calls. Package-private so
     * only tests in the same package can use it.
     */
    static CgGlyphAtlas createForTest(int pageWidth, int pageHeight, Type type) {
        if (pageWidth <= 0 || pageHeight <= 0) {
            throw new IllegalArgumentException(
                    "Atlas dimensions must be positive, got: " + pageWidth + "x" + pageHeight);
        }
        return new CgGlyphAtlas(pageWidth, pageHeight, type, 0, true);
    }

    // ── Core API ───────────────────────────────────────────────────────

    /**
     * Ensures a glyph is present in the atlas. Returns the atlas region with
     * pixel coordinates, normalised UVs, and bearing data.
     *
     * <ul>
     *   <li>If the glyph is already present, its LRU frame is updated and the
     *       existing region is returned.</li>
     *   <li>If absent, space is allocated via the packer, pixel data is uploaded,
     *       and a new region is returned.</li>
     *   <li>If the atlas is full, the coldest (lowest {@code lastUsedFrame}) slot
     *       is evicted first.</li>
     * </ul>
     *
     * <p>For <strong>bitmap</strong> atlases, pass {@code byte[]} pixel data
     * (grayscale, 1 byte per pixel). For <strong>MSDF</strong> atlases, pass
     * {@code float[]} pixel data (RGB, 3 floats per pixel) via
     * {@link #getOrAllocateMsdf}.</p>
     *
     * @param key          glyph key
     * @param bitmapData   grayscale pixel data (1 byte per pixel, row-major)
     * @param width        glyph width in pixels
     * @param height       glyph height in pixels
     * @param bearingX     horizontal bearing from pen origin (pixels)
     * @param bearingY     vertical bearing from baseline (pixels)
     * @param currentFrame current frame number for LRU tracking
     * @return the atlas region, or {@code null} if allocation fails even after eviction
     */
    public CgAtlasRegion getOrAllocate(CgGlyphKey key, byte[] bitmapData,
                                       int width, int height,
                                       float bearingX, float bearingY,
                                       long currentFrame) {
        checkNotDeleted();

        // Fast path: already present
        SlotEntry existing = slotMap.get(key);
        if (existing != null) {
            existing.lastUsedFrame = currentFrame;
            return existing.region;
        }

        // Try to insert
        PackedRect packed = packer.insert(width, height, key);

        // If full, evict coldest slot
        if (packed == null) {
            packed = evictAndInsert(width, height, key);
        }

        if (packed == null) {
            return null; // Cannot fit even after eviction
        }

        // Upload pixel data
        uploadBitmap(packed.getX(), packed.getY(), width, height, bitmapData);

        // Build region
        CgAtlasRegion region = buildRegion(packed, key, bearingX, bearingY);
        slotMap.put(key, new SlotEntry(packed, region, currentFrame));
        return region;
    }

    public CgAtlasRegion get(CgGlyphKey key, long currentFrame) {
        checkNotDeleted();
        SlotEntry existing = slotMap.get(key);
        if (existing == null) {
            return null;
        }
        existing.lastUsedFrame = currentFrame;
        return existing.region;
    }

    /**
     * MSDF variant of {@link #getOrAllocate} that accepts float pixel data.
     *
     * @param key          glyph key (must have {@code msdf == true})
     * @param msdfData     RGB float pixel data (3 floats per pixel, row-major)
     * @param width        glyph width in pixels
     * @param height       glyph height in pixels
     * @param bearingX     horizontal bearing from pen origin (pixels)
     * @param bearingY     vertical bearing from baseline (pixels)
     * @param currentFrame current frame number for LRU tracking
     * @return the atlas region, or {@code null} if allocation fails even after eviction
     */
    public CgAtlasRegion getOrAllocateMsdf(CgGlyphKey key, float[] msdfData,
                                            int width, int height,
                                            float bearingX, float bearingY,
                                            long currentFrame) {
        checkNotDeleted();

        SlotEntry existing = slotMap.get(key);
        if (existing != null) {
            existing.lastUsedFrame = currentFrame;
            return existing.region;
        }

        PackedRect packed = packer.insert(width, height, key);
        if (packed == null) {
            packed = evictAndInsert(width, height, key);
        }
        if (packed == null) {
            return null;
        }

        uploadMsdf(packed.getX(), packed.getY(), width, height, msdfData);

        CgAtlasRegion region = buildRegion(packed, key, bearingX, bearingY);
        slotMap.put(key, new SlotEntry(packed, region, currentFrame));
        return region;
    }

    /** Returns the GL texture ID of this atlas. */
    public int getTextureId() {
        return textureId;
    }

    /** Returns the atlas type (BITMAP or MSDF). */
    public Type getType() {
        return type;
    }

    /** Returns the atlas width in pixels. */
    public int getPageWidth() {
        return pageWidth;
    }

    /** Returns the atlas height in pixels. */
    public int getPageHeight() {
        return pageHeight;
    }

    /**
     * Advances the LRU frame counter. Call once per render frame.
     * This is a bookkeeping hook — the actual frame value is passed
     * into {@code getOrAllocate} per call.
     *
     * @param frame current frame number
     */
    public void tickFrame(long frame) {
        // Currently a no-op; frame is tracked per-slot in getOrAllocate.
        // Reserved for future per-frame maintenance (e.g., batch eviction).
    }

    /** Whether this atlas owns its GL texture (always true for created instances). */
    public boolean isOwned() {
        return true;
    }

    /** Whether {@link #delete()} has been called. */
    public boolean isDeleted() {
        return deleted;
    }

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
        ALL_OWNED.remove(this);
    }

    /**
     * Deletes all owned atlas instances. Intended for shutdown / context
     * destruction. After this call, {@link #ALL_OWNED} is empty.
     */
    public static void freeAll() {
        for (CgGlyphAtlas atlas : ALL_OWNED) {
            if (!atlas.deleted) {
                if (!atlas.skipGlUpload) {
                    GL11.glDeleteTextures(atlas.textureId);
                }
                atlas.textureId = 0;
                atlas.slotMap.clear();
                atlas.deleted = true;
            }
        }
        ALL_OWNED.clear();
    }

    // ── Package-private accessors for testing ──────────────────────────

    /**
     * Returns the number of glyphs currently packed in this atlas.
     * Exposed for test verification only.
     */
    int getSlotCount() {
        return slotMap.size();
    }

    /**
     * Returns the LRU frame of a given glyph key, or -1 if absent.
     * Exposed for test verification only.
     */
    long getLastUsedFrame(CgGlyphKey key) {
        SlotEntry entry = slotMap.get(key);
        return entry != null ? entry.lastUsedFrame : -1;
    }

    /**
     * Returns whether a glyph key is currently in this atlas.
     * Exposed for test verification only.
     */
    boolean containsKey(CgGlyphKey key) {
        return slotMap.containsKey(key);
    }

    // ── Internal: LRU eviction ─────────────────────────────────────────

    /**
     * Evicts the coldest slot and attempts to insert a new rect.
     *
     * @return the packed rect for the new insertion, or null if still cannot fit
     */
    private PackedRect evictAndInsert(int width, int height, Object newKey) {
        if (slotMap.isEmpty()) {
            return null;
        }

        // Find slot with minimum lastUsedFrame
        Map.Entry<CgGlyphKey, SlotEntry> coldest = null;
        for (Map.Entry<CgGlyphKey, SlotEntry> entry : slotMap.entrySet()) {
            if (coldest == null || entry.getValue().lastUsedFrame < coldest.getValue().lastUsedFrame) {
                coldest = entry;
            }
        }

        if (coldest == null) {
            return null;
        }

        // Evict
        SlotEntry evicted = coldest.getValue();
        packer.remove(evicted.packed);
        slotMap.remove(coldest.getKey());

        LOGGER.fine("Evicted glyph " + coldest.getKey() + " (frame " + evicted.lastUsedFrame + ")");

        // Retry insert
        return packer.insert(width, height, newKey);
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
        int required = w * h * 3;
        if (msdfUploadBuffer == null || msdfUploadBuffer.capacity() < required) {
            msdfUploadBuffer = BufferUtils.createFloatBuffer(required);
        }
        msdfUploadBuffer.clear();
        msdfUploadBuffer.put(data, 0, required);
        msdfUploadBuffer.flip();

        GL11.glBindTexture(GL_TEXTURE_2D, textureId);
        GL11.glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, w, h,
                GL_RGB, GL_FLOAT, msdfUploadBuffer);
        GL11.glBindTexture(GL_TEXTURE_2D, 0);
    }

    // ── Internal: region builder ───────────────────────────────────────

    private CgAtlasRegion buildRegion(PackedRect packed, CgGlyphKey key,
                                      float bearingX, float bearingY) {
        float u0 = (float) packed.getX() / pageWidth;
        float v0 = (float) packed.getY() / pageHeight;
        float u1 = (float) (packed.getX() + packed.getWidth()) / pageWidth;
        float v1 = (float) (packed.getY() + packed.getHeight()) / pageHeight;

        return new CgAtlasRegion(
                packed.getX(), packed.getY(),
                packed.getWidth(), packed.getHeight(),
                u0, v0, u1, v1,
                key,
                bearingX, bearingY
        );
    }

    private void checkNotDeleted() {
        if (deleted) {
            throw new IllegalStateException("Atlas has been deleted");
        }
    }

    // ── Internal: slot tracking entry ──────────────────────────────────

    /**
     * Mutable entry tracking a packed glyph slot's position, region, and LRU frame.
     */
    static final class SlotEntry {
        final PackedRect packed;
        final CgAtlasRegion region;
        long lastUsedFrame;

        SlotEntry(PackedRect packed, CgAtlasRegion region, long lastUsedFrame) {
            this.packed = packed;
            this.region = region;
            this.lastUsedFrame = lastUsedFrame;
        }
    }
}
