package com.crystalgraphics.gl.texture;

import com.crystalgraphics.platform.rhi.CgDeviceNames;
import com.crystalgraphics.platform.gl.driver.GLC;
import com.crystalgraphics.render.draw.CgBoundResources;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gpu.CgUploadLease;
import com.crystalgraphics.gpu.CgUploads;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.format.GLFormats;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.resource.CgTextureRegion;
import com.crystalgraphics.util.io.CgBc7;
import com.crystalgraphics.util.CgHalfFloat;
import com.crystalgraphics.util.io.CgCookedTexture;
import com.crystalgraphics.util.io.CgOpenExr;
import com.crystalgraphics.util.io.CgRadianceHdr;
import com.crystalgraphics.util.io.CgTextureIO;
import com.crystalgraphics.util.io.CgTextureIO.CgImageData;

import lombok.Getter;

import javax.annotation.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 2D GL texture (target {@code GL_TEXTURE_2D}). The single concrete impl of
 * {@link CgTexture} for 2D textures — owns one GL texture id, allocated by the
 * static factories and released by {@link #delete()}.
 *
 * <p>Failure during creation is always failure-atomic: the partially-allocated
 * GL id is deleted before re-throwing, so no ids are ever leaked.</p>
 *
 * <h3>From a file</h3>
 * <p>{@link #create(String, CgTextureSpec)} is cached by its path and spec, and loaded at its first use: its size, its
 * id, a bind. Name one anywhere, a constant in common code included: a server never decodes it.</p>
 *
 * <pre>{@code
 * static final CgTexture2D STRIP = CgTexture2D.create("mymod:textures/vfx/strip.png", CgTextureSpec.RGBA8_LINEAR_REPEAT);
 * float aspect = STRIP.getWidth() / (float) STRIP.getHeight();   // decodes it here, on this thread
 * STRIP.bind(0);                                                 // uploads it, on the render thread
 * }</pre>
 *
 * <ul>
 *   <li>A file that will not load is logged and drawn as the purple and black checkerboard: {@link #isMissing()}.</li>
 *   <li>{@link #reload()} decodes it again into the same id, so references stay valid; a texture made without a file
 *       ({@code createEmpty}, {@code createFromPixels}) has nothing to reload.</li>
 * </ul>
 *
 * <h3>Cooked</h3>
 * <p>A path ending in {@code .cgtex} is a cooked texture ({@link CgCookedTexture}): BC7 with its mips, uploaded as
 * blocks where the context samples BC7 ({@link CgCapabilities#bc7()}), else decoded to RGBA8 as it loads. The file
 * decides the format and the levels; the spec, the filter and the wrap.</p>
 *
 * <pre>{@code
 * static final CgTexture2D SMOKE = CgTextures.of("mymod:cooked/fx/smoke.cgtex", CgTextureSpec.RGBA8_LINEAR_REPEAT);
 * }</pre>
 *
 * <h3>Float colour</h3>
 * <p>A path ending in {@code .hdr} is a Radiance image ({@link CgRadianceHdr}): linear RGB past 1, uploaded as half
 * floats. Its spec must be {@code RGBA16F}; any other throws at its first use, naming both.</p>
 *
 * <pre>{@code
 * static final CgTexture2D RAMP = CgTextures.of("mymod:textures/fx/fire_ramp.hdr", CgTextureSpec.RGBA16F_LINEAR);
 * }</pre>
 *
 * <h3>Upload</h3>
 * <p>Two {@code upload()} instance methods allow callers to push fresh pixel
 * data into a live texture without re-allocating the GL object:
 * {@link #upload(CgImageData)} for path-loaded images and
 * {@link #upload(int, int, ByteBuffer, int, int)} for raw pixel data.</p>
 *
 * <h3>Any thread</h3>
 * <p>Every factory and upload works anywhere. Where no GL may run — a recording, a worker — the GL work waits for the
 * render thread, before the next frame executes; the size is known at once, and {@link #getId()} is 0 until then
 * off the render thread. A recorded draw binds it when the frame executes. The pixels are copied into a
 * {@link CgUploadLease} by the thread that passed them, and land on the render thread with no further copy there.</p>
 *
 * <pre>{@code
 * CgTexture2D sprite = CgTexture2D.create("mymod:textures/gui/atlas.png", CgTextureSpec.RGBA8_NEAREST);
 * float u = 16f / sprite.getWidth();                         // any thread
 * recording.bindings().begin().texture(0, sprite).end();     // bound when the frame executes
 * }</pre>
 */
public final class CgTexture2D extends CgTextureAbstract {

    private static final Logger LOGGER = Logger.getLogger(CgTexture2D.class.getName());

    // ── GL constants ────────────────────────────────────────────────
    private static final int GL_TEXTURE_2D = 0x0DE1;

    /** Asset path this texture was loaded from; {@code null} for procedural textures. */
    @Getter private final String sourcePath;

    /** Levels specified at allocation; a spec that generates mipmaps has its full chain instead. */
    private int levels = 1;

    /** Whether its levels came from a cooked file rather than the spec. */
    private boolean cooked;

    /** Where a lease lands: a region of one level, and the whole image with its storage. */
    private final Consumer<CgUploadLease> landing = this::land, wholeLanding = this::landWhole;

    private CgTexture2D(int textureId, int width, int height, CgTextureSpec spec, String sourcePath) {
        super(textureId, width, height, spec);
        this.sourcePath = sourcePath;
    }

    private CgTexture2D(int textureId, int width, int height) {
        super(textureId, width, height, CgTextureSpec.RGBA8_LINEAR, false);
        this.sourcePath = null;
    }

    private CgTexture2D(int textureId, int width, int height, CgTextureSpec spec, boolean owned) {
        super(textureId, width, height, spec, owned);
        this.sourcePath = null;
    }

    // ── Factories ────────────────────────────────────────────────────

    /** {@code path}, linear and clamped: {@link #create(String, CgTextureSpec)} with {@link CgTextureSpec#RGBA8_LINEAR}. */
    public static CgTexture2D create(String path) {
        return create(path, CgTextureSpec.RGBA8_LINEAR);
    }

    /**
     * {@code path} sampled as {@code spec}, cached by the two: one image sampled two ways is two textures. Any thread;
     * loaded at its first use. An empty path is {@link CgTextureManager#getFallback()}.
     */
    public static CgTexture2D create(String path, CgTextureSpec spec) {
        return CgTextureManager.get().getOrCreate(path, spec);
    }

    /**
     * {@code path} decoded here, not cached: the caller owns it, and no change to the file finds it. Any thread. A file
     * that will not load leaves the checkerboard.
     */
    public static CgTexture2D createDirect(String path, CgTextureSpec spec) {
        CgTexture2D tex = new CgTexture2D(0, 0, 0, spec, path);
        tex.load();
        return tex;
    }

    /** {@code path}, loaded at its first use: what the manager caches. */
    static CgTexture2D unloaded(String path, CgTextureSpec spec) {
        CgTexture2D tex = new CgTexture2D(0, 0, 0, spec, path);
        tex.loadOnFirstUse();
        return tex;
    }

    /**
     * The stand-in for an image that would not load: an 8x8 purple and black checkerboard, sampled nearest, its
     * {@link #isMissing()} true. Not cached; the manager keeps one ({@link CgTextureManager#getFallback()}).
     */
    public static CgTexture2D missing() {
        CgTexture2D tex = createFromPixels(8, 8, checkerboard(), CgTextureSpec.RGBA8_NEAREST);
        tex.missing = true;
        return tex;
    }

    private static ByteBuffer checkerboard() {
        ByteBuffer pixels = ByteBuffer.allocateDirect(8 * 8 * 4);
        for (int i = 0; i < 64; i++) {
            boolean purple = ((i & 7) + (i >> 3) & 1) == 0;
            pixels.putInt(purple ? 0xFF00FFFF : 0x000000FF);
        }
        pixels.flip();
        return pixels;
    }

    /**
     * Adopts a texture someone else owns, so the engine can sample it: a host's, imported by
     * {@code CgHostImports} and named by {@link CgDeviceNames}.
     *
     * <pre>{@code
     * int name = CgDeviceNames.name(CgHostImports.get().texture(hostHandle));
     * CgTexture2D scene = CgTexture2D.wrap(name, width, height);
     * scene.bind(unit);
     * scene.delete();   // forgets it; the host's texture is untouched
     * }</pre>
     *
     * <p>Uploads throw: its storage and parameters are its owner's. Not cached.</p>
     */
    public static CgTexture2D wrap(int textureId, int width, int height) {
        return new CgTexture2D(textureId, width, height);
    }

    /**
     * An empty texture made on the installed device ({@code CgRHI}), known by the name {@link CgDeviceNames} gives
     * it: what the frame graph makes its attachments as. Render thread.
     *
     * <pre>{@code
     * colour = CgTexture2D.onDevice("bloom", w, h, CgTextureSpec.RGBA16F_LINEAR, levels);
     * }</pre>
     *
     * <ul>
     *   <li>Its storage is immutable: uploads and resizes throw, as on a wrapped texture.</li>
     *   <li>{@link #delete} releases it through the device, once the frames reading it retire.</li>
     * </ul>
     */
    public static CgTexture2D onDevice(String label, int width, int height, CgTextureSpec spec, int levels) {
        return onDevice(CgRHI.get().createTexture(deviceDesc(label, width, height, spec, levels)), spec);
    }

    /**
     * What {@link #onDevice(String, int, int, CgTextureSpec, int)} makes: a 2D texture of {@code spec}'s format,
     * sampled, copied, an attachment, and a storage image where the device can make the format one.
     */
    public static CgGpuTexture.Desc deviceDesc(String label, int width, int height, CgTextureSpec spec, int levels) {
        CgFormat format = GLFormats.of(spec.getGlInternalFormat());
        Set<CgGpuTexture.Usage> usage = format.hasDepth()
                ? EnumSet.of(CgGpuTexture.Usage.ATTACHMENT, CgGpuTexture.Usage.SAMPLED, CgGpuTexture.Usage.COPY_SRC,
                        CgGpuTexture.Usage.COPY_DST)
                : CgRHI.get().supports(format, CgGpuTexture.Usage.STORAGE) ? CgGpuTexture.Usage.ALL
                : EnumSet.complementOf(EnumSet.of(CgGpuTexture.Usage.STORAGE));
        return new CgGpuTexture.Desc(label, CgGpuTexture.Kind.D2, format, width, height, 1, levels, 1, usage);
    }

    /**
     * {@code image}, a texture {@link #deviceDesc} describes, made by the caller: as {@code onDevice}'s, which
     * {@link #delete} releases. The frame graph's placed transients.
     *
     * <pre>{@code
     * CgGpuTexture image = memory.place(heap, offset, CgTexture2D.deviceDesc("bloom", w, h, spec, 1));
     * CgTexture2D bloom = CgTexture2D.onDevice(image, spec);
     * }</pre>
     */
    public static CgTexture2D onDevice(CgGpuTexture image, CgTextureSpec spec) {
        CgGpuTexture.Desc desc = image.desc();
        int width = desc.width(), height = desc.height(), levels = desc.mips();
        CgTexture2D tex = new CgTexture2D(CgDeviceNames.name(image), width, height, spec, false);
        tex.levels = levels;
        tex.image = image;
        return tex;
    }

    @Override
    protected int mips() {
        return getLevels();
    }

    /** Creates an empty 2D texture with no image data. Not cached; caller owns the lifecycle. */
    public static CgTexture2D createEmpty(int width, int height, CgTextureSpec spec) {
        return createEmpty(width, height, spec, 1);
    }

    /**
     * An empty texture of {@code levels} mip levels, sampled through all of them: what a kernel or a pass fills level by
     * level. Not cached; caller owns the lifecycle.
     *
     * <pre>{@code
     * CgTexture2D chain = CgTexture2D.createEmpty(w, h, CgTextureSpec.RGBA16F_LINEAR, CgTexture.fullChain(w, h));
     * float level2 = textureLod(chain, uv, 2.0).r;   // in a shader, once something wrote level 2
     * }</pre>
     *
     * <ul>
     *   <li>Its minification filter becomes the spec's, mipmapped: trilinear from {@code GL_LINEAR}.</li>
     *   <li>A spec that generates its mipmaps ({@code CgMipmapConfig}) makes its own chain and is refused here.</li>
     * </ul>
     */
    public static CgTexture2D createEmpty(int width, int height, CgTextureSpec spec, int levels) {
        if (levels < 1 || levels > CgTexture.fullChain(width, height)) {
            throw new IllegalArgumentException(levels + " levels for a " + width + "x" + height + " texture: it holds 1 to "
                    + CgTexture.fullChain(width, height));
        }
        if (levels > 1 && spec.getMipmaps().isEnabled()) {
            throw new IllegalArgumentException("a spec that generates mipmaps makes its own chain: give levels 1");
        }
        return doCreate(width, height, null, spec.getGlBaseFormat(), spec.getGlType(), spec, null, levels);
    }

    /**
     * Creates a 2D texture from a raw RGBA {@link ByteBuffer}.
     * Not cached; caller owns the lifecycle. Use for procedural/dynamic textures.
     */
    public static CgTexture2D createFromPixels(int width, int height, ByteBuffer pixels, CgTextureSpec spec) {
        return doCreate(width, height, pixels,
                spec.getGlBaseFormat(), spec.getGlType(), spec, null, 1);
    }

    // ── Upload ────────────────────────────────────────────────────────

    /**
     * Re-uploads pixel data from a decoded image in-place.
     * Also reapplies the spec's filter/wrap params and regenerates mipmaps if enabled.
     * Updates {@link #getWidth()} / {@link #getHeight()} to match the new image.
     *
     * <p>Use {@link CgTextureIO#load(String)} to obtain a {@link CgImageData}.</p>
     */
    public void upload(CgImageData image) {
        upload(image.width(), image.height(), image.pixels(), pixelFormatForChannels(image.channels()),
                GL_UNSIGNED_BYTE);
    }

    /**
     * Re-uploads raw pixel data in-place.
     * Also reapplies the spec's filter/wrap params and regenerates mipmaps if enabled.
     * Updates {@link #getWidth()} / {@link #getHeight()} to match the new dimensions.
     *
     * <p>Use this overload for procedural textures that generate pixels directly into a
     * {@link ByteBuffer} rather than loading from an asset path.</p>
     *
     * @param pixelFormat upload pixel format (e.g. {@code GL_RGBA}, {@code GL_RED})
     * @param pixelType   upload pixel type (e.g. {@code GL_UNSIGNED_BYTE})
     */
    public void upload(int width, int height, ByteBuffer pixels, int pixelFormat, int pixelType) {
        checkUsable();
        checkOwned();
        this.width = width;
        this.height = height;
        if (pixels == null || gpu.immediate()) {
            gpu.run(() -> whole(width, height, pixels, null, pixelFormat, pixelType));
            return;
        }
        gpu.run(CgUploads.copyOf(pixels, converts(pixelFormat, pixelType))
                .into(wholeLanding, 0, 0, 0, 0, width, height, 1, pixelFormat, pixelType));
    }

    /**
     * Writes a {@code width} x {@code height} region at {@code (x, y)} of level {@code level}: rows bottom first, tightly
     * packed, in {@code pixelFormat} and {@code pixelType}. The rest of the texture keeps what it held.
     *
     * <pre>{@code
     * heights.uploadRegion(0, 32, 0, 16, 16, slab, GL_RED, GL_FLOAT);   // a 16x16 R32F slab at (32, 0)
     * }</pre>
     */
    public void uploadRegion(int level, int x, int y, int width, int height, ByteBuffer pixels, int pixelFormat,
                             int pixelType) {
        uploadPixels(level, x, y, width, height, pixels, pixelFormat, pixelType);
    }

    /**
     * {@link #uploadRegion(int, int, int, int, int, ByteBuffer, int, int)} in its own format: RGBA8's bytes, R32F's
     * floats. Any thread.
     *
     * <pre>{@code
     * lightmap.uploadRegion(0, 0, 0, 16, 16, rgba);   // an RGBA8 texture's 16x16 texels, 4 bytes each
     * }</pre>
     */
    public void uploadRegion(int level, int x, int y, int width, int height, ByteBuffer pixels) {
        uploadPixels(level, x, y, width, height, pixels, spec.getGlBaseFormat(), spec.getGlType());
    }

    private void uploadPixels(int level, int x, int y, int width, int height, ByteBuffer pixels, int pixelFormat,
                              int pixelType) {
        checkUsable();
        if (!gpu.immediate()) {
            uploadRegion(level, x, y, width, height, CgUploads.copyOf(pixels, converts(pixelFormat, pixelType)),
                    pixelFormat, pixelType);
            return;
        }
        CgDeviceTextures.write(storage(), CgTextureRegion.of2D(level, x, y, width, height), pixels, pixelFormat, pixelType);
    }

    /**
     * {@link #uploadRegion(int, int, int, int, int, ByteBuffer, int, int)} from a lease its producer wrote: no copy on
     * this thread or the render thread. Takes the lease; any thread.
     *
     * <pre>{@code
     * CgUploadLease lease = CgUploads.lease(4 * w * h);
     * bake(lease.bytes());
     * sprite.uploadRegion(0, x, y, w, h, lease, GL_RGBA, GL_UNSIGNED_BYTE);
     * }</pre>
     */
    public void uploadRegion(int level, int x, int y, int width, int height, CgUploadLease lease, int pixelFormat,
                             int pixelType) {
        checkUsable();
        gpu.run(lease.into(landing, level, x, y, 0, width, height, 1, pixelFormat, pixelType));
    }

    private void land(CgUploadLease lease) {
        CgDeviceTextures.write(storage(), CgTextureRegion.of2D(lease.level(), lease.x(), lease.y(), lease.width(),
                lease.height()), lease, 0, lease.size());
    }

    private void landWhole(CgUploadLease lease) {
        whole(lease.width(), lease.height(), null, lease, lease.format(), lease.type());
    }

    /**
     * The whole image: storage of this size made where it has none of it, level 0 written, the chain made where the spec
     * generates one.
     */
    private void whole(int width, int height, @Nullable ByteBuffer pixels, @Nullable CgUploadLease lease,
                       int pixelFormat, int pixelType) {
        int chain = spec.getMipmaps().isEnabled() ? CgTexture.fullChain(width, height) : levels;
        store2D(GLFormats.of(spec.getGlInternalFormat()), width, height, chain);
        CgTextureRegion region = CgTextureRegion.of2D(0, 0, 0, width, height);
        if (pixels != null) CgDeviceTextures.write(image, region, pixels, pixelFormat, pixelType);
        else if (lease != null) CgDeviceTextures.write(image, region, lease, 0, lease.size());
        if (spec.getMipmaps().isEnabled()) CgDeviceTextures.mipmaps(image);
    }

    /** {@link #storeOnDevice} of a 2D texture, what it replaced released. */
    private void store2D(CgFormat format, int width, int height, int chain) {
        CgDeviceTextures.release(storeOnDevice(CgGpuTexture.Kind.D2, format, width, height, 1, chain));
    }

    // ── Files ─────────────────────────────────────────────────────────

    @Override
    protected String source() {
        return sourcePath != null ? sourcePath : super.source();
    }

    @Override
    protected void load() {
        if (sourcePath == null) return;
        if (CgCookedTexture.is(sourcePath) ? loadCooked() : loadImage()) {
            missing = false;
            return;
        }
        LOGGER.log(Level.WARNING, "[CgTexture2D] {0} would not load", sourcePath);
        if (width != 0) return;
        missing = true;
        cooked = false;
        upload(8, 8, checkerboard(), GL_RGBA, GL_UNSIGNED_BYTE);
    }

    private boolean loadImage() {
        if (CgRadianceHdr.is(sourcePath)) return loadRadiance();
        if (CgOpenExr.is(sourcePath)) return loadExr();
        CgImageData data = CgTextureIO.load(sourcePath);
        if (data == null) return false;
        cooked = false;
        upload(data);
        return true;
    }

    /** An OpenEXR image's halves as they are: signed, alpha kept. Its spec must be {@code RGBA16F}, as a Radiance image's. */
    private boolean loadExr() {
        if (spec.getType() != CgTextureType.RGBA16F) {
            throw new IllegalStateException(sourcePath + " is half-float data, but its texture is " + spec.getType()
                    + ": make it with an RGBA16F spec");
        }
        CgOpenExr.Image image;
        try {
            image = CgOpenExr.read(sourcePath);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "[CgTexture2D] " + e.getMessage());
            return false;
        }
        cooked = false;
        upload(image.width(), image.height(), image.halves(), GL_RGBA, GLC.GL_HALF_FLOAT);
        return true;
    }

    /** A Radiance image as half floats: its spec must be {@code RGBA16F}, which the file's colour needs. */
    private boolean loadRadiance() {
        if (spec.getType() != CgTextureType.RGBA16F) {
            throw new IllegalStateException(sourcePath + " is float colour, but its texture is " + spec.getType()
                    + ": make it with an RGBA16F spec");
        }
        CgRadianceHdr.Image image;
        try {
            image = CgRadianceHdr.read(sourcePath);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "[CgTexture2D] " + e.getMessage());
            return false;
        }
        cooked = false;
        upload(image.width(), image.height(), CgHalfFloat.halves(image.rgba()), GL_RGBA, GLC.GL_HALF_FLOAT);
        return true;
    }

    /** A cooked file's levels into one lease: its blocks where the context samples BC7, else decoded here. */
    private boolean loadCooked() {
        CgCookedTexture file = CgCookedTexture.read(sourcePath);
        if (file == null) return false;
        checkUsable();
        checkOwned();
        // Before any probe, off the render thread, the answer is unknown: decoding draws the same, only larger.
        CgCapabilities caps = CgRHI.onDeviceThread() ? CgCapabilities.detect() : CgCapabilities.detected();
        boolean blocks = caps != null && caps.bc7();
        CgTextureType type = file.srgb() ? CgTextureType.BC7_SRGB : CgTextureType.BC7;
        int w = file.width(), h = file.height(), n = file.levels();
        int[] at = new int[n], lengths = new int[n];
        int total = 0;
        for (int l = 0; l < n; l++) {
            int lw = Math.max(1, w >> l), lh = Math.max(1, h >> l);
            at[l] = total;
            lengths[l] = blocks ? CgBc7.levelBytes(lw, lh) : 4 * lw * lh;
            total += lengths[l];
        }
        CgUploadLease lease = CgUploads.lease(total);
        ByteBuffer out = lease.bytes();
        if (blocks) {
            file.putBlocks(out);
        } else {
            for (int l = 0; l < n; l++) {
                out.position(at[l]);
                CgBc7.decode(file.level(l), Math.max(1, w >> l), Math.max(1, h >> l), out);
            }
        }
        int internal = (blocks ? type : type.decoded()).glInternalFormat;
        this.width = w;
        this.height = h;
        this.levels = n;
        this.cooked = true;
        gpu.run(lease.into(l -> landCooked(l, internal, at, lengths), 0, 0, 0, 0, w, h, 1, GL_RGBA,
                GL_UNSIGNED_BYTE));
        return true;
    }

    /** The cooked chain is the file's, so a spec's mipmaps are never generated over it. */
    private void landCooked(CgUploadLease lease, int internal, int[] at, int[] lengths) {
        store2D(GLFormats.of(internal), lease.width(), lease.height(), at.length);
        for (int l = 0; l < at.length; l++) {
            int lw = Math.max(1, lease.width() >> l), lh = Math.max(1, lease.height() >> l);
            CgDeviceTextures.write(image, CgTextureRegion.of2D(l, 0, 0, lw, lh), lease, at[l], lengths[l]);
        }
    }

    /**
     * Binds it at {@code unit} sampling level {@code level} alone, as a view of that level: what lets a pass draw into one
     * level while reading another. A shader reads the level as the texture's base, at LOD 0.
     *
     * <pre>{@code
     * chain.bindLevel(0, 2);            // a pass drawing into level 3 reads level 2
     * }</pre>
     */
    public void bindLevel(int unit, int level) {
        checkUsable();
        if (level < 0 || level >= getLevels()) throw new IllegalArgumentException("level " + level + " of " + getLevels());
        gpu.flush();
        CgBoundResources.texture(unit, this, level);
    }

    @Override public int getTarget() { return GL_TEXTURE_2D; }

    @Override
    public int getLevels() {
        ensureLoaded();
        return !cooked && spec.getMipmaps().isEnabled() ? CgTexture.fullChain(width, height) : levels;
    }

    // ── Internal factory ──────────────────────────────────────────────

    /** The object now, its GL texture through {@link #gpu}. */
    private static CgTexture2D doCreate(int width, int height, @Nullable ByteBuffer pixels,
                                        int pixelFormat, int pixelType,
                                        CgTextureSpec spec, @Nullable String sourcePath, int levels) {
        CgTexture2D tex = new CgTexture2D(0, width, height, spec, sourcePath);
        tex.levels = levels;
        tex.upload(width, height, pixels, pixelFormat, pixelType);
        return tex;
    }
}
