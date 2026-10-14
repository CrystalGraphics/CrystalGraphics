package com.crystalgraphics.gl.texture;


import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.gpu.CgUploadLease;
import com.crystalgraphics.gpu.CgUploads;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.util.CgBufferUtils;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.util.io.CgTextureIO.CgImageData;
import com.crystalgraphics.util.io.CgTextureIO;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import javax.annotation.Nullable;
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
 * <h3>From files</h3>
 * <p>{@link #create(CgTextureSpec, String...)} is a layer an image, cached by the paths and the spec and loaded at its
 * first use, as a {@link CgTexture2D} from a file is: a flipbook, or a set of paintings to pick from past a material's
 * sampler limit.</p>
 *
 * <pre>{@code
 * static final CgTexture2DArray FLAMES = CgTexture2DArray.frames(CgTextureSpec.RGBA8_LINEAR, "mymod:textures/vfx/flame_%d.png", 3);
 * static final CgTexture2DArray PAINTINGS = CgTexture2DArray.create(CgTextureSpec.RGBA8_LINEAR,
 *         "mymod:textures/vfx/dash_wide.png", "mymod:textures/vfx/dash_thin.png");
 * }</pre>
 *
 * <ul>
 *   <li>Images of different sizes are each centred on a layer the largest one's size, clear round it.</li>
 *   <li>An image that will not load, or has another channel count than the first, is logged and its layer left clear:
 *       {@link #isMissing()}.</li>
 * </ul>
 *
 * <h3>Growth without a GPU readback</h3>
 * <p>Every {@link #uploadLayerRegion} call also mirrors its data into that layer's
 * {@link PageData} (see {@link #growLayers}'s javadoc for why). This only applies to the
 * incremental "allocate empty, upload sub-rects over time" pattern ({@link #allocateEmpty} +
 * {@link #uploadLayerRegion}); an array from files is not mirrored.</p>
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

    /** Layers the GL storage has, which {@link #depth} runs ahead of while growth waits for the render thread. */
    private int storageDepth;

    private final Consumer<CgUploadLease> landing = this::land;

    private CgTexture2DArray(int width, int height, int depth, CgTextureSpec spec, String[] sourcePaths) {
        super(0, width, height, spec);
        this.depth = depth;
        this.sourcePaths = sourcePaths;
        ensurePageCapacity(depth);
    }

    // ── Factories ────────────────────────────────────────────────────

    /**
     * A layer for each of {@code paths}, in order, sampled as {@code spec}: cached by the two, loaded at its first use.
     * Any thread.
     */
    public static CgTexture2DArray create(CgTextureSpec spec, String... paths) {
        String[] own = checkPaths(paths);
        CgTexture result = CgTextureManager.get().getOrCreate(String.join(CgTextureManager.PATH_SEPARATOR, own), spec, () -> {
            CgTexture2DArray tex = new CgTexture2DArray(0, 0, own.length, spec, own);
            tex.loadOnFirstUse();
            return tex;
        });
        return (CgTexture2DArray) result;
    }

    /** {@code pattern} formatted with 0 to {@code count - 1}, a layer each: {@code "mymod:textures/fx/flame_%d.png"}. */
    public static CgTexture2DArray frames(CgTextureSpec spec, String pattern, int count) {
        if (count < 1) throw new IllegalArgumentException("frames of " + pattern + ": count " + count);
        String[] paths = new String[count];
        for (int i = 0; i < count; i++) paths[i] = String.format(Locale.ROOT, pattern, i);
        return create(spec, paths);
    }

    /** {@link #create} decoded here, not cached: the caller owns it, and no change to a file finds it. Any thread. */
    public static CgTexture2DArray createDirect(CgTextureSpec spec, String... paths) {
        String[] own = checkPaths(paths);
        CgTexture2DArray tex = new CgTexture2DArray(0, 0, own.length, spec, own);
        tex.load();
        return tex;
    }

    private static String[] checkPaths(String[] paths) {
        if (paths.length == 0) throw new IllegalArgumentException("an array of no images");
        for (String path : paths) Objects.requireNonNull(path, "path");
        return paths.clone();
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
     * <p>Any thread, as are its uploads and growth: where no GL may run, the texture waits for the render thread,
     * before the next frame executes, and {@link #getId} is 0 until then off it.</p>
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

        CgTexture2DArray tex = new CgTexture2DArray(width, height, layers, spec, null);
        tex.gpu.run(() -> {
            tex.textureId = CgGL.glGenTextures();
            tex.reallocateStorage(layers);
            tex.storageDepth = layers;
        });
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
        checkUsable();
        synchronized (this) {
            mirrorByteUpload(layer, x, y, w, h, format, type, data);
        }
        CgUploadLease lease = CgUploads.copyOf(data, converts(format, type)).into(landing, 0, x, y, layer, w, h, 1, format, type);
        // From an unpack buffer on the render thread too: NVIDIA's GL converts the whole array at the first
        // client-memory upload after a grow's GPU copy into it, about 1.4 ms per 4 MB layer, and from a buffer it does not.
        gpu.run(lease);
    }

    /** {@code float}-data variant of {@link #uploadLayerRegion(int, int, int, int, int, int, int, ByteBuffer)}. */
    public void uploadLayerRegion(int layer, int x, int y, int w, int h,
                                   int format, int type, FloatBuffer data) {
        checkUsable();
        synchronized (this) {
            mirrorFloatUpload(layer, x, y, w, h, format, data);
        }
        if (gpu.immediate()) {
            try (CgGlScope restore = gpu.restoring()) {
                uploadFloats(layer, x, y, w, h, format, type, data);
            }
            return;
        }
        gpu.run(CgUploads.copyOf(data, converts(format, type)).into(landing, 0, x, y, layer, w, h, 1, format, type));
    }

    /** A queued layer region, from its lease. Not taken from callers: the CPU mirror needs a source it can read. */
    private void land(CgUploadLease lease) {
        CgGL.glBindTexture(GL_TEXTURE_2D_ARRAY, textureId);
        try (CgTightUnpack ignored = CgTightUnpack.begin()) {
            lease.texSubImage3D(GL_TEXTURE_2D_ARRAY);
        } finally {
            CgGL.glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        }
    }

    private void uploadFloats(int layer, int x, int y, int w, int h, int format, int type, FloatBuffer data) {
        // Hand the driver GL_FLOAT and let it convert, rather than quantising CPU-side first.
        //
        // Converting in Java was tried and is a clear pessimisation. It sends a quarter of the
        // bytes, but the scalar clamp/round/put loop costs far more than the transfer it saves:
        // measured on matched 28224-component uploads, 2.403 ms converting versus 0.035 ms just
        // handing over floats, and ~8x worse per component in warm steady state (2.72 ns vs
        // 0.34 ns). The driver's own conversion is native and vectorised; a Java loop cannot
        // compete with it, and the bus was never the bottleneck. See git history for
        // uploadLayerRegionAsUnorm8 and the half-float variant before it, which made the same
        // trade and measured ~2%.
        long bytes = (long) data.remaining() << 2;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "texArray.upload.texSubImage")) {
            rawUpload(layer, x, y, w, h, format, type, data);
        }
        CgTrace.add(CgChannels.GL, "texArray.upload.bytes", bytes);
    }


    // NOTE: there is deliberately no upload-batching API here (a begin/end pair hoisting the bind
    // and GL_UNPACK_ALIGNMENT setup out of a run of uploads). It was built twice and removed
    // twice; see CgFontRegistry#drainCompletedGlyphs for the measurements and for what would have
    // to be true of a workload to justify bringing it back.

    private void rawUpload(int layer, int x, int y, int w, int h, int format, int type, ByteBuffer data) {
        CgGL.glBindTexture(GL_TEXTURE_2D_ARRAY, textureId);
        try (CgTightUnpack ignored = CgTightUnpack.begin()) {
            CgGL.glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, x, y, layer, w, h, 1, format, type, data);
        } finally {
            CgGL.glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        }
    }

    private void rawUpload(int layer, int x, int y, int w, int h, int format, int type, FloatBuffer data) {
        CgGL.glBindTexture(GL_TEXTURE_2D_ARRAY, textureId);
        try (CgTightUnpack ignored = CgTightUnpack.begin()) {
            CgGL.glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, x, y, layer, w, h, 1, format, type, data);
        } finally {
            CgGL.glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        }
    }

    private void rawUpload(int layer, int x, int y, int w, int h, int format, int type, ShortBuffer data) {
        CgGL.glBindTexture(GL_TEXTURE_2D_ARRAY, textureId);
        try (CgTightUnpack ignored = CgTightUnpack.begin()) {
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
     * <p><b>Rebinds the 2D-array target.</b> This binds other textures (the scratch array) and
     * leaves the target unbound on exit — {@link #reallocateStorage} unbinds in its
     * {@code finally}. Any future caller that hoists a bind across a run of uploads must account
     * for that, because growth fires <em>indirectly</em>: nothing can predict which individual
     * upload will be the one that exhausts the layers. Getting it wrong is silent — the uploads
     * afterwards land on texture 0 and are simply lost. That is not a hypothetical; it is what
     * happened the one time this was not accounted for (see
     * {@code CgFontRegistry#drainCompletedGlyphs}).</p>
     *
     * @param newLayerCount new depth; must exceed the current {@link #getDepth()}
     */
    public void growLayers(int newLayerCount) {
        checkUsable();
        if (newLayerCount <= depth) {
            throw new IllegalArgumentException(
                    "newLayerCount (" + newLayerCount + ") must exceed current depth (" + depth + ")");
        }
        synchronized (this) {
            depth = newLayerCount;
            ensurePageCapacity(newLayerCount);
        }
        gpu.run(() -> {
            // The mirror is replayed from here, while a worker may be writing it.
            synchronized (this) {
                try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "texArray.grow")) {
                    growStorage(storageDepth, newLayerCount);
                }
                storageDepth = newLayerCount;
            }
        });
    }

    /** Grows the GL storage from {@code oldDepth} layers to {@code newLayerCount}, keeping its content and its id. */
    private void growStorage(int oldDepth, int newLayerCount) {

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
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "texArray.growGpuCopy")) {
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
                    CgTrace.add(CgChannels.GL, "texArray.growGpuCopy.count", 1);
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
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "texArray.realloc")) {
            reallocateStorage(newLayerCount);
        }
        ensurePageCapacity(newLayerCount);

        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "texArray.replayLayers")) {
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
            CgTrace.counter(CgChannels.GL, "texArray.replayLayers.count", replayed);
            CgTrace.add(CgChannels.GL, "texArray.replayLayers.bytes", replayedBytes);
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

    // ── Files ─────────────────────────────────────────────────────────

    @Override
    protected void load() {
        if (sourcePaths == null) return;
        CgImageData[] images = new CgImageData[sourcePaths.length];
        int w = 0, h = 0, channels = 0;
        boolean failed = false;
        for (int i = 0; i < images.length; i++) {
            CgImageData image = CgTextureIO.load(sourcePaths[i]);
            if (image == null || channels != 0 && image.channels() != channels) {
                LOGGER.log(Level.WARNING, "[CgTexture2DArray] Layer {0}, {1}, would not load as the first did: it stays clear",
                        new Object[] {i, sourcePaths[i]});
                failed = true;
                continue;
            }
            channels = image.channels();
            images[i] = image;
            w = Math.max(w, image.width());
            h = Math.max(h, image.height());
        }
        if (failed && width != 0) return;
        missing = failed;
        if (channels == 0) {
            w = h = 1;
            channels = 4;
        }
        ByteBuffer[] layers = new ByteBuffer[images.length];
        for (int i = 0; i < layers.length; i++) layers[i] = centred(images[i], w, h, channels);
        int format = pixelFormatForChannels(channels), lw = w, lh = h;
        width = w;
        height = h;
        gpu.run(() -> uploadLayers(lw, lh, format, layers));
    }

    /** {@code image} centred on a {@code w} x {@code h} layer, clear round it; its own pixels where it fills one. */
    static ByteBuffer centred(@Nullable CgImageData image, int w, int h, int channels) {
        if (image != null && image.width() == w && image.height() == h) return image.pixels();
        ByteBuffer layer = ByteBuffer.allocateDirect(w * h * channels);
        if (image == null) return layer;
        int row = image.width() * channels, x = (w - image.width()) / 2, y = (h - image.height()) / 2;
        ByteBuffer src = image.pixels().duplicate();
        for (int r = 0; r < image.height(); r++) {
            src.limit(r * row + row).position(r * row);
            layer.position(((y + r) * w + x) * channels);
            layer.put(src);
        }
        layer.clear();
        return layer;
    }

    private void uploadLayers(int w, int h, int format, ByteBuffer[] layers) {
        boolean made = textureId == 0;
        if (made) textureId = CgGL.glGenTextures();
        CgGL.glBindTexture(GL_TEXTURE_2D_ARRAY, textureId);
        try {
            CgGL.glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, spec.getGlInternalFormat(), w, h, layers.length, 0, format,
                    GL_UNSIGNED_BYTE, (ByteBuffer) null);
            try (CgTightUnpack ignored = CgTightUnpack.begin()) {
                for (int i = 0; i < layers.length; i++) {
                    CgGL.glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, i, w, h, 1, format, GL_UNSIGNED_BYTE, layers[i]);
                }
            }
            spec.applyTo(GL_TEXTURE_2D_ARRAY);
            storageDepth = layers.length;
        } catch (RuntimeException e) {
            if (made) {
                CgGL.glDeleteTextures(textureId);
                textureId = 0;
            }
            throw e;
        } finally {
            CgGL.glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        }
    }

    @Override public int getTarget() { return GL_TEXTURE_2D_ARRAY; }

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
