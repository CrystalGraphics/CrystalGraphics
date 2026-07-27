package com.crystalgraphics.gl.texture;


import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.util.CgBufferUtils;
import com.crystalgraphics.util.CgHalfFloat;
import com.crystalgraphics.util.profiling.CgProfiler;
import com.crystalgraphics.util.io.CgTextureIO.CgImageData;
import com.crystalgraphics.util.io.CgTextureIO;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;
import lombok.Getter;

/**
 * 2D-array GL texture (target {@code GL_TEXTURE_2D_ARRAY = 0x8C1A}). Single
 * concrete impl of {@link CgTexture} for 2D-arrays.
 *
 * <p>Each "layer" of the array is a 2D image of identical width/height. All
 * layers share the same pixel format, mipmap config, and sampler params.
 * Storage is allocated via {@link GL12#glTexImage3D} and per-layer upload
 * happens via {@link GL12#glTexSubImage3D}.</p>
 *
 * <h3>Factories</h3>
 * <ul>
 *   <li>{@link #create(String...)} / {@link #create(CgTextureSpec, String...)} — cached via
 *       {@link CgTextureManager}; supports in-place {@link #reload()}.</li>
 *   <li>{@link #createDirect(CgTextureSpec, String...)} — bypass cache; no reload support.</li>
 * </ul>
 *
 * <h3>Growth without a GPU readback</h3>
 * <p>Every {@link #uploadLayerRegion} call also mirrors its data into that layer's
 * {@link PageData} (see {@link #growLayers}'s javadoc for why). This only applies to the
 * incremental "allocate empty, upload sub-rects over time" pattern ({@link #allocateEmpty} +
 * {@link #uploadLayerRegion}) — {@link #upload(CgImageData[])} (the "N whole images, uploaded
 * once, from file" path used by {@link #create}/{@link #createDirect}) never calls
 * {@link #uploadLayerRegion} and so is untouched by this.</p>
 */
public final class CgTexture2DArray extends CgTextureAbstract {

    private static final Logger LOGGER = Logger.getLogger(CgTexture2DArray.class.getName());

    // ── GL constants ────────────────────────────────────────────────
    private static final int GL_TEXTURE_2D_ARRAY = 0x8C1A;

    // ── Type-specific state ─────────────────────────────────────────
    /** Not final — {@link #growLayers(int)} reallocates storage in place and bumps this. */
    @Getter private int depth;

    /** Source paths for reload; {@code null} for createDirect (no reload support). */
    @Getter private final String[] sourcePaths;

    /**
     * Per-layer CPU-side upload mirror — see class javadoc "Growth without a GPU readback".
     * Sized to at least {@link #depth}, grown alongside it; an entry stays {@code null} for a
     * layer that never received an upload (nothing to preserve across growth for a layer
     * nobody ever wrote to).
     */
    private PageData[] pages = new PageData[0];

    private CgTexture2DArray(int textureId, int width, int height, int depth, CgTextureSpec spec, String[] sourcePaths) {
        super(textureId, width, height, spec);
        this.depth = depth;
        this.sourcePaths = sourcePaths;
        ensurePageCapacity(depth);
    }

    // ── Factories ────────────────────────────────────────────────────

    /** Creates a 2D-array texture from image paths using {@link CgTextureSpec#RGBA8_LINEAR}, cached. */
    public static CgTexture2DArray create(String... paths) {
        return create(CgTextureSpec.RGBA8_LINEAR, paths);
    }

    /**
     * Creates a 2D-array texture from a list of image paths, cached.
     * Subsequent calls with the same paths return the cached instance.
     *
     * @throws IllegalArgumentException if {@code paths} is empty, any layer
     *         fails to load, or layers have mismatching dimensions
     */
    public static CgTexture2DArray create(CgTextureSpec spec, String... paths) {
        String key = String.join(CgTextureManager.PATH_SEPARATOR, paths);
        CgTexture result = CgTextureManager.get().getOrCreate(key, () -> doCreate(spec, paths, paths));
        return result != null ? (CgTexture2DArray) result : null;
    }

    /**
     * Creates a fresh 2D-array texture without consulting the cache.
     * Not registered with {@link CgTextureManager}; caller owns the lifecycle.
     * No reload support.
     */
    public static CgTexture2DArray createDirect(CgTextureSpec spec, String... paths) {
        return doCreate(spec, paths, null);
    }

    /**
     * Allocates an empty {@code layers}-deep array texture with no initial pixel
     * data — one {@code glTexImage3D} call with a {@code null} data pointer,
     * orphaning/reserving storage for every layer up front. Pairs with
     * {@link #uploadLayerRegion} to push data into individual layers afterward.
     *
     * <p>Not registered with {@link CgTextureManager}; caller owns the lifecycle.
     * No reload support (there is no source path to reload from). This is the
     * "N-layer atlas built up incrementally over many frames" entry point, as
     * opposed to {@link #create}/{@link #createDirect}'s "N whole images,
     * uploaded once" model.</p>
     *
     * @param width  layer width in pixels (must be positive)
     * @param height layer height in pixels (must be positive)
     * @param layers initial number of layers to reserve (must be positive).
     *               Not a hard ceiling — see {@link #growLayers(int)} to add
     *               more layers later without losing this instance's identity.
     * @param spec   format/filter/wrap; {@link CgTextureSpec#getGlType()} is used
     *               only for this initial allocation call (no real data is
     *               transferred with a null pointer) — {@link #uploadLayerRegion}
     *               takes its own explicit format/type per call, which does not
     *               need to match this spec's type (e.g. uploading {@code float}
     *               data into an {@code RGBA16F}-internal-format array, whose
     *               spec type may be {@code GL_HALF_FLOAT}).
     */
    public static CgTexture2DArray allocateEmpty(int width, int height, int layers, CgTextureSpec spec) {
        if (width <= 0 || height <= 0)
            throw new IllegalArgumentException("width/height must be positive, got: " + width + "x" + height);
        if (layers <= 0)
            throw new IllegalArgumentException("layers must be positive, got: " + layers);

        int id = CgGL.glGenTextures();
        CgTexture2DArray tex = new CgTexture2DArray(id, width, height, layers, spec, null);
        CgGL.glBindTexture(GL_TEXTURE_2D_ARRAY, id);
        try {
            CgGL.glTexImage3D(GL_TEXTURE_2D_ARRAY, 0,
                    spec.getGlInternalFormat(), width, height, layers, 0,
                    spec.getGlBaseFormat(), spec.getGlType(), (ByteBuffer) null);
            spec.applyTo(GL_TEXTURE_2D_ARRAY);
        } finally {
            CgGL.glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        }
        return tex;
    }

    // ── Per-layer sub-rect upload ───────────────────────────────────────

    /**
     * Uploads pixel data into a sub-rectangle of a single layer, via
     * {@code glTexSubImage3D}. Pairs with {@link #allocateEmpty}. Also mirrors
     * {@code data} into this layer's CPU-side buffer — see class javadoc
     * "Growth without a GPU readback".
     *
     * <p>{@code format}/{@code type} describe {@code data}'s actual layout and
     * are supplied explicitly by the caller rather than derived from this
     * texture's spec — the spec describes GPU-side storage, not what a given
     * upload call's CPU-side data looks like (e.g. this array's internal
     * format may be {@code RGBA16F} while a caller uploads plain {@code float}
     * data via {@code GL_FLOAT}, letting the driver quantize on upload).</p>
     *
     * @param layer  target layer index, {@code [0, getDepth())}
     * @param x      sub-rectangle left edge within the layer, in pixels
     * @param y      sub-rectangle top edge within the layer, in pixels
     * @param w      sub-rectangle width, in pixels
     * @param h      sub-rectangle height, in pixels
     * @param format GL pixel format of {@code data} (e.g. {@code GL_RED}, {@code GL_RGBA})
     * @param type   GL pixel type of {@code data} (e.g. {@code GL_UNSIGNED_BYTE}, {@code GL_FLOAT})
     * @param data   tightly-packed pixel data, {@code w * h * channels(format)} elements
     */
    public void uploadLayerRegion(int layer, int x, int y, int w, int h,
                                   int format, int type, ByteBuffer data) {
        checkNotDeleted();
        mirrorByteUpload(layer, x, y, w, h, format, type, data);
        rawUpload(layer, x, y, w, h, format, type, data);
    }

    /** {@code float}-data variant of {@link #uploadLayerRegion(int, int, int, int, int, int, int, ByteBuffer)}. */
    public void uploadLayerRegion(int layer, int x, int y, int w, int h,
                                   int format, int type, FloatBuffer data) {
        checkNotDeleted();
        mirrorFloatUpload(layer, x, y, w, h, format, data);
        rawUpload(layer, x, y, w, h, format, type, data);
    }

    /**
     * Uploads {@code float} source data to a <strong>half-float</strong> ({@code RGBA16F}-class)
     * array, converting to {@code GL_HALF_FLOAT} on the CPU instead of handing the driver
     * {@code GL_FLOAT} and letting it quantize.
     *
     * <h3>Why this is free precision-wise</h3>
     * <p>The destination storage is 16-bit float either way, so the values are quantized to
     * half regardless of which path is used — doing it here produces bit-identical texels to
     * letting the driver do it, while sending <strong>half the bytes</strong> and skipping the
     * driver's per-pixel conversion pass entirely. There is no quality trade-off to weigh.</p>
     *
     * <h3>Why it matters</h3>
     * <p>Measured on distance-field glyph upload: ~1.1 MiB/frame of {@code GL_FLOAT} data was
     * producing 30-218 ms frame stalls, most of it surfacing <em>after</em> the upload call
     * returned (deferred GPU work billed at buffer swap, invisible to CPU-side timers). Halving
     * the transfer and removing the conversion attacks that directly.</p>
     *
     * <p>The CPU-side mirror (see class javadoc "Growth without a GPU readback") still stores
     * the original {@code float} data, so layer-growth re-upload is unaffected and keeps full
     * source precision.</p>
     *
     * @param format GL pixel format of {@code data} (e.g. {@code GL_RGB}, {@code GL_RGBA})
     * @param data   tightly-packed float pixel data, {@code w * h * channels(format)} elements
     */
    public void uploadLayerRegionAsHalf(int layer, int x, int y, int w, int h,
                                         int format, FloatBuffer data) {
        checkNotDeleted();
        mirrorFloatUpload(layer, x, y, w, h, format, data);

        int count = data.remaining();
        if (halfUploadBuffer == null || halfUploadBuffer.capacity() < count) 
            halfUploadBuffer = CgBufferUtils.createShortBuffer(count);
        
        halfUploadBuffer.clear();
        int base = data.position();
        for (int i = 0; i < count; i++) 
            halfUploadBuffer.put(CgHalfFloat.toHalf(data.get(base + i)));
        
        halfUploadBuffer.flip();

        rawUpload(layer, x, y, w, h, format, CgGL.GL_HALF_FLOAT, halfUploadBuffer);
    }

    /**
     * Reusable scratch for {@link #uploadLayerRegionAsHalf}'s converted texels — grow-only, so a
     * steady stream of same-or-smaller glyph uploads allocates nothing after the first.
     */
    private ShortBuffer halfUploadBuffer;


    private void rawUpload(int layer, int x, int y, int w, int h, int format, int type, ByteBuffer data) {
        CgGL.glBindTexture(GL_TEXTURE_2D_ARRAY, textureId);
        // GL defaults GL_UNPACK_ALIGNMENT to 4 (rows padded to a 4-byte boundary in the
        // client buffer). data here is tightly packed with no such padding, so any
        // single-byte-per-pixel upload (e.g. R8 bitmap glyphs) whose row width isn't a
        // multiple of 4 gets every row after the first misread — the driver skips/shifts
        // bytes trying to find each row's "padded" start, corrupting the uploaded pixels.
        // Must be set to 1 for the sub-image call itself, then restored.
        int prevAlignment = CgGL.glGetInteger(CgGL.GL_UNPACK_ALIGNMENT);
        CgGL.glPixelStorei(CgGL.GL_UNPACK_ALIGNMENT, 1);
        try {
            CgGL.glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, x, y, layer, w, h, 1, format, type, data);
        } finally {
            CgGL.glPixelStorei(CgGL.GL_UNPACK_ALIGNMENT, prevAlignment);
            CgGL.glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        }
    }

    private void rawUpload(int layer, int x, int y, int w, int h, int format, int type, FloatBuffer data) {
        CgGL.glBindTexture(GL_TEXTURE_2D_ARRAY, textureId);
        try {
            CgGL.glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, x, y, layer, w, h, 1, format, type, data);
        } finally {
            CgGL.glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        }
    }

    private void rawUpload(int layer, int x, int y, int w, int h, int format, int type, ShortBuffer data) {
        CgGL.glBindTexture(GL_TEXTURE_2D_ARRAY, textureId);
        try {
            CgGL.glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, x, y, layer, w, h, 1, format, type, data);
        } finally {
            CgGL.glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        }
    }

    // ── Per-layer CPU mirroring ──────────────────────────────────────────

    /** Ensures layer {@code layer}'s {@link PageData} exists, then delegates the write to it. */
    private void mirrorByteUpload(int layer, int x, int y, int w, int h, int format, int type, ByteBuffer data) {
        ensurePageCapacity(layer + 1);
        if (pages[layer] == null) {
            pages[layer] = PageData.ofBytes(format, type, width, height);
        }
        pages[layer].writeSubRect(x, y, w, h, data);
    }

    /** {@code float}-data variant of {@link #mirrorByteUpload}. */
    private void mirrorFloatUpload(int layer, int x, int y, int w, int h, int format, FloatBuffer data) {
        ensurePageCapacity(layer + 1);
        if (pages[layer] == null) {
            pages[layer] = PageData.ofFloats(format, width, height);
        }
        pages[layer].writeSubRect(x, y, w, h, data);
    }

    private void ensurePageCapacity(int neededDepth) {
        if (neededDepth <= pages.length) return;
        pages = Arrays.copyOf(pages, neededDepth);
    }

    // ── Grow ─────────────────────────────────────────────────────────────

    /**
     * Grows this array's layer depth in place — same GL texture id, so every
     * existing reference to this {@code CgTexture2DArray} instance (and every
     * {@code sampler2DArray} binding already pointing at its id) keeps working
     * with no rewiring.
     *
     * <p>GL has no "extend an array texture" call — {@code glTexImage3D} always
     * (re)defines a texture's storage from scratch, discarding whatever was
     * there. An earlier version of this method recovered the old content with a
     * {@code glGetTexImage} readback before reallocating — correct, but
     * {@code glGetTexImage} is a <strong>pipeline-stalling</strong> call: it
     * blocks the calling thread until the GPU finishes every pending operation
     * up to that texture's last write, which showed up as real, visible frame
     * hitches on every growth event. Instead, this reallocates storage and then
     * simply replays each existing layer's already-current CPU-side mirror (see
     * class javadoc) back in via plain, non-blocking uploads — no GPU
     * involvement in the "read old data" step at all, because there isn't one.</p>
     *
     * <p>Intended as a cold-path operation — growth events should be rare
     * relative to per-frame work (e.g. a glyph atlas doubling its page budget
     * only when it actually runs out of room), not something called every frame.</p>
     *
     * @param newLayerCount new depth; must exceed the current {@link #getDepth()}
     */
    public void growLayers(int newLayerCount) {
        checkNotDeleted();
        if (newLayerCount <= depth) {
            throw new IllegalArgumentException(
                    "newLayerCount (" + newLayerCount + ") must exceed current depth (" + depth + ")");
        }
        int oldDepth = depth;

        // Preferred path: park the existing layers in a scratch texture, reallocate THIS texture
        // in place, then copy them back — both hops GPU-side, no CPU round trip.
        //
        // Why the scratch texture rather than simply allocating a bigger texture and adopting
        // it: this instance's GL texture id must not change. CgGlyphPlacement caches
        // atlasTextureId per glyph and CgTextRenderer batches draws on it (see its sort-key
        // packing), so swapping ids would leave every cached placement pointing at a texture
        // that no longer exists. Reallocating in place keeps that invariant intact (it is why
        // the class javadoc calls out "same texture id"), and the cost of the extra hop is
        // negligible: two GPU-local copies versus a full CPU->GPU re-upload measured at
        // 3.8ms -> 65.7ms and climbing with atlas size. The realloc itself is 0.01ms.
        if (CgTextureCopy.isSupported()) {
            try (CgProfiler.Scope ignored = CgProfiler.scope("texArray.growGpuCopy")) {
                // Scratch goes through this class's own allocateEmpty rather than a raw
                // glGenTextures/glTexImage3D pair: same format/filter/wrap setup as any other
                // array texture (no chance of the two drifting apart), a real object with a
                // delete() rather than a naked GL name, and no raw GL object creation sitting
                // in a feature path. allocateEmpty deliberately does not register with
                // CgTextureManager — the caller owns the lifecycle, which is exactly what a
                // transient scratch wants.
                CgTexture2DArray scratch = allocateEmpty(width, height, oldDepth, spec);
                boolean copied = false;
                try {
                    // Hop 1: current contents -> scratch, while the original still holds them.
                    if (CgTextureCopy.copyArrayLayers(textureId, scratch.getId(), width, height, oldDepth)) {
                        reallocateStorage(newLayerCount);
                        // Hop 2: scratch -> the freshly (and larger) reallocated original.
                        copied = CgTextureCopy.copyArrayLayers(scratch.getId(), textureId, width, height, oldDepth);
                        if (!copied) {
                            // Storage was already reallocated, so the old texels are gone from
                            // the original and only the CPU mirror can restore them. Fall through
                            // to the replay loop below rather than leaving the atlas corrupt.
                            LOGGER.warning("GPU copy-back failed during array growth; replaying layers from CPU mirror");
                        }
                    }
                } finally {
                    scratch.delete();
                }

                if (copied) {
                    this.depth = newLayerCount;
                    ensurePageCapacity(newLayerCount);
                    CgProfiler.count("texArray.growGpuCopy.count");
                    return;
                }
                // Fall through. reallocateStorage may or may not have run; the code below is
                // idempotent with respect to it (reallocating twice is harmless, and the replay
                // restores every layer either way).
            }
        }

        // Fallback: reallocate in place, then replay every layer from its CPU mirror.
        // Split into two scopes deliberately -- "realloc" is the unavoidable glTexImage3D that
        // resizes GPU storage, while "replayLayers" is the CPU->GPU re-upload. Measuring them
        // separately is what established that the replay, not the realloc, is the real cost.
        try (CgProfiler.Scope ignored = CgProfiler.scope("texArray.realloc")) {
            reallocateStorage(newLayerCount);
        }
        this.depth = newLayerCount;
        ensurePageCapacity(newLayerCount);

        try (CgProfiler.Scope ignored = CgProfiler.scope("texArray.replayLayers")) {
            int replayed = 0;
            long replayedBytes = 0;
            for (int i = 0; i < oldDepth; i++) {
                PageData page = pages[i];
                if (page == null) {
                    continue; // this layer never received an upload — nothing to replay.
                }
                if (page.byteData != null) {
                    page.byteData.position(0);
                    rawUpload(i, 0, 0, width, height, page.format, page.type, page.byteData);
                    replayedBytes += page.byteData.remaining();
                } else {
                    page.floatData.position(0);
                    rawUpload(i, 0, 0, width, height, page.format, page.type, page.floatData);
                    replayedBytes += (long) page.floatData.remaining() << 2;
                }
                replayed++;
            }
            CgProfiler.sample("texArray.replayLayers.count", replayed);
            CgProfiler.count("texArray.replayLayers.bytes", replayedBytes);
        }
    }

    /**
     * Reallocates this texture's GL storage to {@code layerCount} layers, keeping the same GL
     * texture id (see {@link #growLayers}'s comment on why the id must not change). Discards
     * all existing texel content — the caller is responsible for restoring it.
     */
    private void reallocateStorage(int layerCount) {
        CgGL.glBindTexture(GL_TEXTURE_2D_ARRAY, textureId);
        try {
            CgGL.glTexImage3D(GL_TEXTURE_2D_ARRAY, 0,
                    spec.getGlInternalFormat(), width, height, layerCount, 0,
                    spec.getGlBaseFormat(), spec.getGlType(), (ByteBuffer) null);
            spec.applyTo(GL_TEXTURE_2D_ARRAY);
        } finally {
            CgGL.glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        }
    }

    // ── Upload ────────────────────────────────────────────────────────

    /**
     * Re-uploads all layers from pre-loaded image data in-place.
     * Also reapplies the spec's filter/wrap params and regenerates mipmaps if enabled.
     * Updates {@link #getWidth()} / {@link #getHeight()} to match the new images.
     *
     * <p>The images array must have exactly {@link #getDepth()} entries, all
     * the same width/height. Validation is the caller's responsibility.</p>
     */
    public void upload(CgImageData[] images) {
        checkNotDeleted();
        int w = images[0].width();
        int h = images[0].height();
        int uploadPixelFormat = pixelFormatForChannels(images[0].channels());
        CgGL.glBindTexture(GL_TEXTURE_2D_ARRAY, textureId);
        try {
            CgGL.glTexImage3D(GL_TEXTURE_2D_ARRAY, 0,
                    spec.getGlInternalFormat(), w, h, images.length, 0,
                    uploadPixelFormat, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            for (int i = 0; i < images.length; i++) {
                CgGL.glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0,
                        0, 0, i, w, h, 1,
                        uploadPixelFormat, GL_UNSIGNED_BYTE, images[i].pixels());
            }
            spec.applyTo(GL_TEXTURE_2D_ARRAY);

            this.width = w;
            this.height = h;
        } finally {
            CgGL.glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        }
    }

    // ── Reload ────────────────────────────────────────────────────────

    @Override
    public void reload() {
        if (sourcePaths == null) return;
        try {
            upload(loadAndValidate(sourcePaths));
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[CgTexture2DArray] Failed to reload layers", e);
        }
    }

    @Override public int getTarget() { return GL_TEXTURE_2D_ARRAY; }

    // ── Internal factory ──────────────────────────────────────────────

    private static CgTexture2DArray doCreate(CgTextureSpec spec, String[] paths, String[] sourcePaths) {
        CgImageData[] images = loadAndValidate(paths);
        int id = CgGL.glGenTextures();
        CgTexture2DArray tex = new CgTexture2DArray(id, images[0].width(), images[0].height(), paths.length, spec, sourcePaths);
        try {
            tex.upload(images);
            return tex;
        } catch (RuntimeException e) {
            tex.delete();
            throw e;
        }
    }

    private static CgImageData[] loadAndValidate(String[] paths) {
        if (paths == null || paths.length == 0) {
            throw new IllegalArgumentException("paths must not be empty");
        }
        CgImageData[] images = new CgImageData[paths.length];
        for (int i = 0; i < paths.length; i++) {
            images[i] = CgTextureIO.load(paths[i]);
            if (images[i] == null) {
                throw new IllegalArgumentException("Failed to load layer " + i + ": " + paths[i]);
            }
        }
        int w = images[0].width(), h = images[0].height();
        for (int i = 1; i < images.length; i++) {
            if (images[i].width() != w || images[i].height() != h) {
                throw new IllegalArgumentException("All layers must be same size. Layer 0: " + w + "x" + h
                        + ", layer " + i + ": " + images[i].width() + "x" + images[i].height());
            }
        }
        return images;
    }

    /**
     * One layer's CPU-side upload mirror — owns both the storage and the sub-rect write logic
     * for that layer, so the outer class only ever has to say "write this rect into this
     * layer" rather than reaching into raw buffers itself. Exactly one of {@link #byteData}/
     * {@link #floatData} is populated, matching whichever {@link #uploadLayerRegion} overload
     * this layer's uploads actually use. Created once, on this layer's first upload — via
     * {@link #ofBytes}/{@link #ofFloats} — sized for one full layer in this exact
     * {@code format}/{@code type}; the buffers' contents then mutate in place on every
     * subsequent {@link #writeSubRect}, so the record itself never needs replacing.
     */
    private record PageData(int format, int type, int width, int height, ByteBuffer byteData, FloatBuffer floatData) {

        static PageData ofBytes(int format, int type, int width, int height) {
            int bpp = bytesPerPixel(format, type);
            return new PageData(format, type, width, height, CgBufferUtils.createByteBuffer(width * height * bpp), null);
        }

        static PageData ofFloats(int format, int width, int height) {
            int channels = channelsForFormat(format);
            return new PageData(format, CgGL.GL_FLOAT, width, height, null, CgBufferUtils.createFloatBuffer(width * height * channels));
        }

        /**
         * Writes {@code data} (left untouched — read via an independent
         * {@link ByteBuffer#duplicate()} view, not {@code data} itself, so the caller's buffer
         * is still positioned at 0 for the real GL upload that follows) into this page's
         * mirror at {@code (x, y, w, h)}, one row at a time via bulk relative
         * {@link ByteBuffer#put(ByteBuffer)} — no scratch array needed, just narrowing the
         * duplicate's limit/position to exactly one row's bytes per iteration.
         */
        void writeSubRect(int x, int y, int w, int h, ByteBuffer data) {
            int bpp = bytesPerPixel(format, type);
            int rowBytes = w * bpp;
            ByteBuffer src = data.duplicate();
            int destStride = width * bpp;
            for (int row = 0; row < h; row++) {
                int rowStart = row * rowBytes;
                src.limit(rowStart + rowBytes);
                src.position(rowStart);
                byteData.position((y + row) * destStride + x * bpp);
                byteData.put(src);
            }
            byteData.position(0);
        }

        /** {@code float}-data variant of {@link #writeSubRect(int, int, int, int, ByteBuffer)}. */
        void writeSubRect(int x, int y, int w, int h, FloatBuffer data) {
            int channels = channelsForFormat(format);
            int rowFloats = w * channels;
            FloatBuffer src = data.duplicate();
            int destStride = width * channels;
            for (int row = 0; row < h; row++) {
                int rowStart = row * rowFloats;
                src.limit(rowStart + rowFloats);
                src.position(rowStart);
                floatData.position((y + row) * destStride + x * channels);
                floatData.put(src);
            }
            floatData.position(0);
        }

        /**
         * Bytes per pixel for a plain (non-packed) {@code (baseFormat, type)} pair —
         * component count from {@code baseFormat} times bytes-per-component from
         * {@code type}. Used only to size/index this page's byte mirror.
         */
        private static int bytesPerPixel(int baseFormat, int type) {
            int bytesPerComponent;
            if (type == CgGL.GL_UNSIGNED_BYTE || type == CgGL.GL_BYTE) {
                bytesPerComponent = 1;
            } else if (type == CgGL.GL_UNSIGNED_SHORT || type == CgGL.GL_SHORT || type == CgGL.GL_HALF_FLOAT) {
                bytesPerComponent = 2;
            } else if (type == CgGL.GL_UNSIGNED_INT || type == CgGL.GL_INT || type == CgGL.GL_FLOAT) {
                bytesPerComponent = 4;
            } else {
                throw new UnsupportedOperationException(
                        "Cannot size a mirror buffer for packed/exotic pixel type 0x"
                                + Integer.toHexString(type) + " — only plain per-component types "
                                + "(UNSIGNED_BYTE/BYTE/UNSIGNED_SHORT/SHORT/HALF_FLOAT/UNSIGNED_INT/INT/FLOAT) "
                                + "are supported for CgTexture2DArray layer mirroring.");
            }
            return channelsForFormat(baseFormat) * bytesPerComponent;
        }

        /** Component count for a GL base/pixel format constant (e.g. {@code GL_RED} → 1, {@code GL_RGBA} → 4). */
        private static int channelsForFormat(int baseFormat) {
            if (baseFormat == CgGL.GL_RED || baseFormat == CgGL.GL_RED_INTEGER || baseFormat == CgGL.GL_DEPTH_COMPONENT) {
                return 1;
            } else if (baseFormat == CgGL.GL_RG || baseFormat == CgGL.GL_RG_INTEGER) {
                return 2;
            } else if (baseFormat == CgGL.GL_RGB) {
                return 3;
            } else {
                return 4; // GL_RGBA / GL_RGBA_INTEGER
            }
        }
    }
}
