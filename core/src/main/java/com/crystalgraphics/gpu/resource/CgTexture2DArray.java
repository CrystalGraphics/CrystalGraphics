package com.crystalgraphics.gpu.resource;


import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.resource.CgTextureRegion;
import com.crystalgraphics.platform.gl.format.GLFormats;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gpu.CgUploadLease;
import com.crystalgraphics.gpu.CgUploads;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.util.io.CgTextureIO.CgImageData;
import com.crystalgraphics.util.io.CgTextureIO;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Objects;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import java.util.logging.Level;
import java.util.logging.Logger;
import lombok.Getter;
import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.platform.rhi.format.CgFormat;

/**
 * A 2D-array texture: layers of one size, format and sampling, on the device. Built from files, or allocated empty and
 * filled layer region by layer region, growing as it fills.
 *
 * <h3>From files</h3>
 * <p>{@link #create(String...)} is a layer an image, cached by the paths and the first one's settings and loaded at its
 * first use, as a {@link CgTexture2D} from a file is: a flipbook, or a set of paintings to pick from past a material's
 * sampler limit.</p>
 *
 * <pre>{@code
 * static final CgTexture2DArray FLAMES = CgTextures.frames("mymod:textures/vfx/flame_%d.png", 3);   // numbered files
 * static final CgTexture2DArray PAINTINGS = CgTexture2DArray.create(
 *         "mymod:textures/vfx/dash_wide.png", "mymod:textures/vfx/dash_thin.png");
 * }</pre>
 *
 * <ul>
 *   <li>Images of different sizes are each centred on a layer the largest one's size, clear round it.</li>
 *   <li>An image that will not load, or has another channel count than the first, is logged and its layer left clear:
 *       {@link #isMissing()}.</li>
 * </ul>
 *
 * <h3>Filled over time</h3>
 * <pre>{@code
 * CgTexture2DArray atlas = CgTextures.frames("mymod.glyphs", CgTextureDesc.array(1024, 1024, 4, CgFormat.R8_UNORM));
 * atlas.uploadLayerRegion(2, x, y, w, h, GL_RED, GL_UNSIGNED_BYTE, glyph);
 * atlas.growLayers(8);   // layers 0-3 kept, copied on the GPU; read getId() at each use
 * }</pre>
 */
public final class CgTexture2DArray extends CgTextureAbstract {

    private static final Logger LOGGER = Logger.getLogger(CgTexture2DArray.class.getName());

    private static final int GL_TEXTURE_2D_ARRAY = 0x8C1A;

    /** Grows ahead of the storage: {@link #growLayers(int)} sets it at once, the storage on the render thread. */
    @Getter private volatile int depth;

    @Override
    protected CgGpuTexture.Desc rhiDesc() {
        return new CgGpuTexture.Desc(source(), CgGpuTexture.Kind.D2_ARRAY, GLFormats.of(spec.getGlInternalFormat()), width,
                height, depth, mips(), 1, SAMPLED_ONLY);
    }

    /** Source paths for reload; {@code null} for a made array. */
    @Getter private final String[] sourcePaths;

    /** Layers the storage has, which {@link #depth} runs ahead of while growth waits for the render thread. */
    private int storageDepth;

    private final Consumer<CgUploadLease> landing = this::land;

    private CgTexture2DArray(int width, int height, int depth, Texels spec, String[] sourcePaths) {
        super(0, width, height, spec);
        this.depth = depth;
        this.sourcePaths = sourcePaths;
    }

    // ── Factories ────────────────────────────────────────────────────

    /**
     * A layer for each of {@code paths}, in order, as the first one's settings file says: shared, loaded at its first
     * use. Any thread.
     */
    public static CgTexture2DArray create(String... paths) {
        String[] own = checkPaths(paths);
        Texels spec = Texels.file(own[0]);
        CgTexture result = CgTextureManager.get().getOrCreate(String.join(CgTextureManager.PATH_SEPARATOR, own), spec, () -> {
            CgTexture2DArray tex = new CgTexture2DArray(0, 0, own.length, spec, own);
            tex.loadOnFirstUse();
            return tex;
        });
        return (CgTexture2DArray) result;
    }

    /**
     * Samples it by {@code sampler} where a binding names none: a texture you made. Returns it, to chain.
     *
     * <pre>{@code
     * CgTexture2DArray atlas = CgTextures.frames("mymod.atlas", CgTextureDesc.array(256, 256, 4, CgFormat.R8_UNORM))
     *         .sampler(CgSampler.NEAREST_CLAMP);
     * }</pre>
     *
     * @throws IllegalStateException for a texture from a file, shared by every caller of its path
     */
    public CgTexture2DArray sampler(CgSampler sampler) {
        setSampler(sampler);
        return this;
    }

    private static String[] checkPaths(String[] paths) {
        if (paths.length == 0) throw new IllegalArgumentException("an array of no images");
        for (String path : paths) Objects.requireNonNull(path, "path");
        return paths.clone();
    }

    /**
     * An empty array named {@code name}, that {@link #uploadLayerRegion} fills and {@link #growLayers} deepens: what
     * {@code CgTextures.frames(name, desc)} makes, its maker's to {@link #release}. Any thread, as are its uploads and
     * growth.
     *
     * @throws IllegalArgumentException for a description that is not an array of one level
     */
    public static CgTexture2DArray create(String name, CgTextureDesc desc) {
        if (!desc.isArray() || desc.levels() > 1) {
            throw new IllegalArgumentException(name + ": an array of one level, not " + desc);
        }
        CgTexture2DArray tex = allocate(desc.width(), desc.height(), desc.layers(), desc.format());
        tex.label(name);
        return tex;
    }

    /** Releases a texture of yours, once the frames reading it retire. Any thread. */
    public void release() {
        delete();
    }

    /** {@link #create(String, CgTextureDesc)}'s storage, of one layer or more. */
    private static CgTexture2DArray allocate(int width, int height, int layers, CgFormat format) {
        Texels spec = Texels.made(format, 1);
        if (width <= 0 || height <= 0)
            throw new IllegalArgumentException("width/height must be positive, got: " + width + "x" + height);
        if (layers <= 0)
            throw new IllegalArgumentException("layers must be positive, got: " + layers);

        CgTexture2DArray tex = new CgTexture2DArray(width, height, layers, spec, null);
        tex.gpu.run(() -> {
            CgDeviceTextures.release(tex.store(layers, 1));
            tex.storageDepth = layers;
        });
        return tex;
    }

    // ── Per-layer sub-rect upload ───────────────────────────────────────

    /**
     * Writes a sub-rectangle of one layer: rows bottom first, tightly packed, in {@code format} and {@code type}, which
     * may differ from its storage's (floats into an {@code RGBA16F} array convert as they land). Any thread.
     *
     * @param layer target layer index, {@code [0, getDepth())}
     */
    public void uploadLayerRegion(int layer, int x, int y, int w, int h, int format, int type, ByteBuffer data) {
        checkUsable();
        gpu.run(CgUploads.copyOf(data, converts(format, type)).into(landing, 0, x, y, layer, w, h, 1, format, type));
    }

    /** {@code float}-data variant of {@link #uploadLayerRegion(int, int, int, int, int, int, int, ByteBuffer)}. */
    public void uploadLayerRegion(int layer, int x, int y, int w, int h, int format, int type, FloatBuffer data) {
        checkUsable();
        gpu.run(CgUploads.copyOf(data, converts(format, type)).into(landing, 0, x, y, layer, w, h, 1, format, type));
    }

    private void land(CgUploadLease lease) {
        CgDeviceTextures.write(storage(), new CgTextureRegion(0, lease.x(), lease.y(), lease.z(), lease.width(),
                lease.height(), 1), lease, 0, lease.size());
    }

    // ── Grow ─────────────────────────────────────────────────────────────

    /**
     * Deepens it to {@code newLayerCount} layers, its layers kept: new storage, the old layers copied into it on the GPU.
     * Every reference to this instance keeps working, but its {@link #getId()} changes: read the id at each use, never
     * keep it. Any thread; a cold path, for an atlas that ran out of room.
     *
     * @param newLayerCount new depth; must exceed the current {@link #getDepth()}
     */
    public void growLayers(int newLayerCount) {
        checkUsable();
        if (newLayerCount <= depth) {
            throw new IllegalArgumentException(
                    "newLayerCount (" + newLayerCount + ") must exceed current depth (" + depth + ")");
        }
        depth = newLayerCount;
        gpu.run(() -> {
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "texArray.grow")) {
                grow(storageDepth, newLayerCount);
            }
            storageDepth = newLayerCount;
        });
    }

    private void grow(int oldDepth, int newLayerCount) {
        CgGpuTexture old = store(newLayerCount, 1);
        if (old == null) return;
        int layers = Math.min(oldDepth, old.desc().depthOrLayers());
        if (layers > 0) {
            CgTextureRegion region = new CgTextureRegion(0, 0, 0, 0, width, height, layers);
            CgDeviceSection.begin();
            try {
                CgRHI.get().encoder().copyTexture(old, region, image, region);
            } finally {
                CgDeviceSection.end();
            }
        }
        CgDeviceTextures.release(old);
        CgTrace.add(CgChannels.GL, "texArray.growGpuCopy.count", 1);
    }

    /** {@link #storeOnDevice} of {@code layers} layers at its size: what it replaced, for the caller to release. */
    @Nullable
    private CgGpuTexture store(int layers, int chain) {
        return storeOnDevice(CgGpuTexture.Kind.D2_ARRAY, GLFormats.of(spec.getGlInternalFormat()), width, height, layers,
                chain);
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
        int format = pixelFormatForChannels(channels);
        width = w;
        height = h;
        gpu.run(() -> uploadLayers(format, layers));
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

    private void uploadLayers(int format, ByteBuffer[] layers) {
        int chain = mips();
        CgDeviceTextures.release(store(layers.length, chain));
        for (int i = 0; i < layers.length; i++) {
            CgDeviceTextures.write(image, new CgTextureRegion(0, 0, 0, i, width, height, 1), layers[i], format,
                    GL_UNSIGNED_BYTE);
        }
        if (chain > 1) CgDeviceTextures.mipmaps(image);
        storageDepth = layers.length;
    }

    @Override public int getTarget() { return GL_TEXTURE_2D_ARRAY; }
}
