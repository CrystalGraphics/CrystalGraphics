package com.crystalgraphics.gl.texture;


import com.crystalgraphics.platform.rhi.resource.CgTextureRegion;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.gl.format.GLFormats;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.gpu.CgUploadLease;
import com.crystalgraphics.gpu.CgUploads;
import com.crystalgraphics.util.io.CgTextureIO.CgImageData;
import com.crystalgraphics.util.io.CgTextureIO;
import java.nio.ByteBuffer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import lombok.Getter;

/**
 * 3D GL texture (target {@code GL_TEXTURE_3D = 0x806F}). Single concrete impl
 * of {@link CgTexture} for 3D textures.
 *
 * <p>Each "slice" along the R axis is a 2D image of identical width/height.
 * Unlike a 2D-array, slices can be filtered linearly across the depth axis,
 * making 3D textures useful for volumetric data and 3D LUTs.</p>
 *
 * <h3>Factories</h3>
 * <ul>
 *   <li>{@link #create(CgTextureSpec, String...)} — a slice an image, cached by the paths and the spec, loaded at its
 *       first use as a {@link CgTexture2D} from a file is. Slices of another size than the first, or that will not
 *       load, are logged and leave a clear 1x1x1 volume: {@link #isMissing()}.</li>
 *   <li>{@link #createDirect(CgTextureSpec, String...)} — the same decoded here, not cached.</li>
 *   <li>{@link #createEmpty(int, int, int, CgTextureSpec, int)} — storage a kernel, a bake or an upload fills; any
 *       thread, its GL work waiting for the render thread as a {@link CgTexture2D}'s does.</li>
 * </ul>
 *
 * <pre>{@code
 * // a tiling noise volume baked on a worker: level 0 uploaded, the rest generated
 * CgTextureSpec repeat = CgTextureSpec.builder().type(CgTextureType.RGBA8).minFilter(GL_LINEAR).magFilter(GL_LINEAR)
 *         .wrapS(GL_REPEAT).wrapT(GL_REPEAT).wrapR(GL_REPEAT).build();
 * CgTexture3D noise = CgTexture3D.createEmpty(128, 128, 128, repeat, CgTexture3D.fullChain(128, 128, 128));
 * noise.uploadRegion(0, 0, 0, 0, 128, 128, 128, texels, GL_RGBA, GL_UNSIGNED_BYTE);
 * noise.generateMipmaps();
 *
 * // a bake that writes its texels straight into staging: no copy on the worker or the render thread
 * CgUploadLease lease = CgUploads.lease(4 * 128 * 128 * 128);
 * bake(lease.bytes());
 * noise.uploadRegion(0, 0, 0, 0, 128, 128, 128, lease, GL_RGBA, GL_UNSIGNED_BYTE);
 * }</pre>
 */
public final class CgTexture3D extends CgTextureAbstract {

    private static final Logger LOGGER = Logger.getLogger(CgTexture3D.class.getName());

    // ── GL constants ────────────────────────────────────────────────
    private static final int GL_TEXTURE_3D = 0x806F;

    // ── Type-specific state ─────────────────────────────────────────
    private int depth;

    @Override
    protected CgGpuTexture.Desc rhiDesc() {
        return new CgGpuTexture.Desc(source(), CgGpuTexture.Kind.D3, GLFormats.of(spec.getGlInternalFormat()), width, height,
                depth, mips(), 1, SAMPLED_ONLY);
    }

    /** Levels allocated by {@link #createEmpty}; a spec that generates mipmaps has its full chain instead. */
    private int levels = 1;

    private final Consumer<CgUploadLease> landing = this::land;

    /** Source paths for reload; {@code null} for createDirect (no reload support). */
    @Getter private final String[] sourcePaths;

    private CgTexture3D(int textureId, int width, int height, int depth, CgTextureSpec spec, String[] sourcePaths) {
        super(textureId, width, height, spec);
        this.depth = depth;
        this.sourcePaths = sourcePaths;
    }

    // ── Factories ────────────────────────────────────────────────────

    /** A slice for each of {@code paths}, in order, sampled as {@code spec}: cached, loaded at its first use. */
    public static CgTexture3D create(CgTextureSpec spec, String... paths) {
        String[] own = checkPaths(paths);
        CgTexture result = CgTextureManager.get().getOrCreate(String.join(CgTextureManager.PATH_SEPARATOR, own), spec, () -> {
            CgTexture3D tex = new CgTexture3D(0, 0, 0, own.length, spec, own);
            tex.loadOnFirstUse();
            return tex;
        });
        return (CgTexture3D) result;
    }

    /** {@link #create} decoded here, not cached: the caller owns it. */
    public static CgTexture3D createDirect(CgTextureSpec spec, String... paths) {
        CgTexture3D tex = new CgTexture3D(0, 0, 0, paths.length, spec, checkPaths(paths));
        tex.load();
        return tex;
    }

    private static String[] checkPaths(String[] paths) {
        if (paths.length == 0) throw new IllegalArgumentException("a volume of no images");
        return paths.clone();
    }

    /** Slices along R; known before it loads. */
    public int getDepth() {
        return depth;
    }

    /** An empty texture of one level. Not cached; caller owns the lifecycle. */
    public static CgTexture3D createEmpty(int width, int height, int depth, CgTextureSpec spec) {
        return createEmpty(width, height, depth, spec, 1);
    }

    /**
     * An empty texture of {@code levels} mip levels, sampled through all of them. Any thread. Not cached; caller owns the
     * lifecycle.
     *
     * <ul>
     *   <li>Its minification filter becomes the spec's, mipmapped: trilinear from {@code GL_LINEAR}.</li>
     *   <li>A spec that generates its mipmaps ({@code CgMipmapConfig}) is refused: fill level 0, then
     *       {@link #generateMipmaps()}.</li>
     * </ul>
     */
    public static CgTexture3D createEmpty(int width, int height, int depth, CgTextureSpec spec, int levels) {
        if (width <= 0 || height <= 0 || depth <= 0) {
            throw new IllegalArgumentException("a 3D texture of " + width + "x" + height + "x" + depth);
        }
        if (levels < 1 || levels > fullChain(width, height, depth)) {
            throw new IllegalArgumentException(levels + " levels for a " + width + "x" + height + "x" + depth
                    + " texture: it holds 1 to " + fullChain(width, height, depth));
        }
        if (spec.getMipmaps().isEnabled()) {
            throw new IllegalArgumentException("a spec that generates mipmaps would generate them from nothing: "
                    + "give levels, fill level 0, then generateMipmaps()");
        }
        CgTexture3D tex = new CgTexture3D(0, width, height, depth, spec, null);
        tex.levels = levels;
        tex.gpu.run(tex::store3D);
        return tex;
    }

    /** Levels in a full mip chain of a {@code width} x {@code height} x {@code depth} texture, down to 1x1x1. */
    public static int fullChain(int width, int height, int depth) {
        return CgTexture.fullChain(Math.max(width, depth), height);
    }

    /**
     * Writes a {@code width} x {@code height} x {@code depth} box at {@code (x, y, z)} of level {@code level}: slices
     * from {@code z} up, each its rows bottom first, tightly packed, in {@code pixelFormat} and {@code pixelType}. The
     * rest keeps what it held. Any thread; {@code pixels} is copied into a lease where the work waits.
     *
     * <pre>{@code
     * window.uploadRegion(0, 0, 0, 32, 128, 96, 16, slab, GL_RED_INTEGER, GL_UNSIGNED_BYTE);   // slices 32 to 47
     * }</pre>
     */
    public void uploadRegion(int level, int x, int y, int z, int width, int height, int depth, ByteBuffer pixels,
                             int pixelFormat, int pixelType) {
        uploadPixels(level, x, y, z, width, height, depth, pixels, pixelFormat, pixelType);
    }

    /** {@link #uploadRegion(int, int, int, int, int, int, int, ByteBuffer, int, int)} in its own format. Any thread. */
    public void uploadRegion(int level, int x, int y, int z, int width, int height, int depth, ByteBuffer pixels) {
        uploadPixels(level, x, y, z, width, height, depth, pixels, spec.getGlBaseFormat(), spec.getGlType());
    }

    private void uploadPixels(int level, int x, int y, int z, int width, int height, int depth, ByteBuffer pixels,
                              int pixelFormat, int pixelType) {
        checkUsable();
        if (!gpu.immediate()) {
            uploadRegion(level, x, y, z, width, height, depth, CgUploads.copyOf(pixels, converts(pixelFormat, pixelType)),
                    pixelFormat, pixelType);
            return;
        }
        CgDeviceTextures.write(storage(), new CgTextureRegion(level, x, y, z, width, height, depth), pixels, pixelFormat,
                pixelType);
    }

    /** {@link #uploadRegion(int, int, int, int, int, int, int, ByteBuffer, int, int)} from a lease. Takes it; any thread. */
    public void uploadRegion(int level, int x, int y, int z, int width, int height, int depth, CgUploadLease lease,
                             int pixelFormat, int pixelType) {
        checkUsable();
        gpu.run(lease.into(landing, level, x, y, z, width, height, depth, pixelFormat, pixelType));
    }

    private void land(CgUploadLease lease) {
        CgDeviceTextures.write(storage(), new CgTextureRegion(lease.level(), lease.x(), lease.y(), lease.z(), lease.width(),
                lease.height(), lease.depth()), lease, 0, lease.size());
    }

    /** Fills every level below 0 from level 0, box-filtered by the device. Any thread, after level 0 is written. */
    public void generateMipmaps() {
        checkUsable();
        gpu.run(() -> CgDeviceTextures.mipmaps(storage()));
    }

    @Override
    public int getLevels() {
        ensureLoaded();
        return spec.getMipmaps().isEnabled() ? fullChain(width, height, depth) : levels;
    }

    // ── Files ─────────────────────────────────────────────────────────

    @Override
    protected void load() {
        if (sourcePaths == null) return;
        CgImageData[] images = new CgImageData[sourcePaths.length];
        for (int i = 0; i < images.length; i++) {
            images[i] = CgTextureIO.load(sourcePaths[i]);
            CgImageData first = images[0];
            if (images[i] == null || first == null || images[i].width() != first.width()
                    || images[i].height() != first.height() || images[i].channels() != first.channels()) {
                LOGGER.log(Level.WARNING, "[CgTexture3D] Slice {0}, {1}, would not load as the first did",
                        new Object[] {i, sourcePaths[i]});
                if (width != 0) return;
                missing = true;
                width = height = depth = 1;
                gpu.run(() -> {
                    store3D();
                    if (spec.getMipmaps().isEnabled()) CgDeviceTextures.mipmaps(image);
                });
                return;
            }
        }
        missing = false;
        int w = images[0].width(), h = images[0].height(), format = pixelFormatForChannels(images[0].channels());
        width = w;
        height = h;
        depth = images.length;
        gpu.run(() -> {
            store3D();
            for (int i = 0; i < images.length; i++) {
                CgDeviceTextures.write(image, new CgTextureRegion(0, 0, 0, i, w, h, 1), images[i].pixels(), format,
                        GL_UNSIGNED_BYTE);
            }
            if (spec.getMipmaps().isEnabled()) CgDeviceTextures.mipmaps(image);
        });
    }

    /** Its storage on the device at its size and levels, what it replaced released. */
    private void store3D() {
        CgDeviceTextures.release(storeOnDevice(CgGpuTexture.Kind.D3, GLFormats.of(spec.getGlInternalFormat()), width,
                height, depth, getLevels()));
    }

    @Override
    public int getTarget() {return GL_TEXTURE_3D;}

}
