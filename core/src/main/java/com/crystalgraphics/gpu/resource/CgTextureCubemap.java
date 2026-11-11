package com.crystalgraphics.gpu.resource;

import com.crystalgraphics.platform.gl.driver.GLC;
import com.crystalgraphics.platform.rhi.resource.CgTextureRegion;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.gl.format.GLFormats;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gpu.CgUploadLease;
import com.crystalgraphics.gpu.CgUploads;
import com.crystalgraphics.util.CgHalfFloat;
import com.crystalgraphics.util.io.CgRadianceHdr;
import com.crystalgraphics.util.io.CgTextureIO;
import com.crystalgraphics.util.io.CgTextureIO.CgImageData;

import lombok.Getter;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Cubemap GL texture (target {@code GLC.GL_TEXTURE_CUBE_MAP = 0x8513}). Single
 * concrete impl of {@link CgTexture} for cubemaps.
 *
 * <p>A cubemap is six square 2D images uploaded to six discrete face targets
 * ({@code GLC.GL_TEXTURE_CUBE_MAP_POSITIVE_X} through
 * {@code GLC.GL_TEXTURE_CUBE_MAP_NEGATIVE_Z}, GL constants 0x8515..0x851A). All
 * faces must be the same size and share the same pixel format, mipmap config,
 * and sampler params.</p>
 *
 * <h3>Factories</h3>
 * <ul>
 *   <li>{@link #create(CgTextureSpec, String, String, String, String, String, String)} — cached by the paths and the
 *       spec, loaded at its first use as a {@link CgTexture2D} from a file is. Faces that will not load, or are not
 *       one square size, are logged and leave a clear 1x1 cube: {@link #isMissing()}.</li>
 *   <li>{@link #createDirect(CgTextureSpec, String, String, String, String, String, String)} — the same decoded here,
 *       not cached.</li>
 *   <li>{@link #createStacked(String, CgTextureSpec)} — one image of its six faces stacked top to bottom, {@code w x 6w},
 *       as a {@code texture} asset with {@code cube: true} holds them: cached, loaded at its first use. A {@code .hdr}
 *       wants an {@code RGBA16F} spec, as a {@link CgTexture2D} of one does.</li>
 *   <li>{@link #createEmpty(int, CgTextureSpec)} — empty faces; caller owns lifecycle.</li>
 * </ul>
 *
 * <pre>{@code
 * // 128 x 768: six 128 x 128 faces, +X at the top
 * static final CgTextureCubemap BIKE = CgTextureCubemap.createStacked(
 *         "kakarot_kit:textures/MasterMaterials/AT_Materials/Texture/gim_s04a_bike01_cubemap.png", CgTextureSpec.RGBA8_LINEAR);
 * }</pre>
 *
 * <p>Made, filled and deleted from any thread, as a {@link CgTexture2D} is: off the render thread its GL work waits
 * for the render thread, and the pixels for it are copied into a lease by the thread that passed them.</p>
 *
 * <pre>{@code
 * // a sky baked on a worker: each face written straight into staging
 * CgTextureCubemap sky = CgTextureCubemap.createEmpty(512, CgTextureSpec.RGBA8_LINEAR);
 * for (int face = 0; face < 6; face++) {
 *     CgUploadLease lease = CgUploads.lease(4 * 512 * 512);
 *     bakeFace(face, lease.bytes());
 *     sky.uploadFace(face, 0, 0, 0, 512, 512, lease, GL_RGBA, GL_UNSIGNED_BYTE);
 * }
 *
 * // a face from a buffer you keep: copied into a lease here when the work must wait
 * sky.uploadFace(CgTextureCubemap.POSITIVE_Y, 0, 0, 0, 512, 512, pixels, GL_RGBA, GL_UNSIGNED_BYTE);
 * }</pre>
 */
public final class CgTextureCubemap extends CgTextureAbstract {

    private static final Logger LOGGER = Logger.getLogger(CgTextureCubemap.class.getName());

    /** Face indices for {@link #uploadFace}, in canonical order. */
    public static final int POSITIVE_X = 0, NEGATIVE_X = 1, POSITIVE_Y = 2, NEGATIVE_Y = 3, POSITIVE_Z = 4, NEGATIVE_Z = 5;

    /** Source face paths for reload; {@code null} for createDirect and createEmpty. */
    @Getter private final String[] sourcePaths;
    /** The one image its faces are stacked in; null for six face files or none. */
    @Nullable private final String stacked;

    private final Consumer<CgUploadLease> landing = this::land;


    @Override
    protected CgGpuTexture.Desc rhiDesc() {
        return new CgGpuTexture.Desc(source(), CgGpuTexture.Kind.CUBE, GLFormats.of(spec.getGlInternalFormat()), width,
                height, 6, mips(), 1, SAMPLED_ONLY);
    }

    private CgTextureCubemap(int textureId, int size, CgTextureSpec spec, String[] sourcePaths) {
        this(textureId, size, spec, sourcePaths, null);
    }

    private CgTextureCubemap(int textureId, int size, CgTextureSpec spec, String[] sourcePaths, @Nullable String stacked) {
        super(textureId, size, size, spec);
        this.sourcePaths = sourcePaths;
        this.stacked = stacked;
    }

    // ── Factories ────────────────────────────────────────────────────

    /** Six faces, +X, -X, +Y, -Y, +Z, -Z, sampled as {@code spec}: cached, loaded at its first use. */
    public static CgTextureCubemap create(CgTextureSpec spec,
                                          String posX, String negX,
                                          String posY, String negY,
                                          String posZ, String negZ) {
        String[] paths = { posX, negX, posY, negY, posZ, negZ };
        CgTexture result = CgTextureManager.get().getOrCreate(String.join(CgTextureManager.PATH_SEPARATOR, paths), spec, () -> {
            CgTextureCubemap tex = new CgTextureCubemap(0, 0, spec, paths);
            tex.loadOnFirstUse();
            return tex;
        });
        return (CgTextureCubemap) result;
    }

    /**
     * Six faces stacked top to bottom in one image, +X, -X, +Y, -Y, +Z, -Z (Unreal's slice order, which GL's and
     * Vulkan's face order is), sampled as {@code spec}: cached by the path and spec, loaded at its first use. Face i
     * goes to face i unchanged, as Unreal's GL backend uploads slice i.
     *
     * @throws IllegalStateException at its first use, for an image not {@code w x 6w}, or a {@code .hdr} under a spec
     *                               that is not RGBA16F
     */
    public static CgTextureCubemap createStacked(String path, CgTextureSpec spec) {
        return (CgTextureCubemap) CgTextureManager.get().getOrCreate(path, spec, () -> {
            CgTextureCubemap tex = new CgTextureCubemap(0, 0, spec, null, path);
            tex.loadOnFirstUse();
            return tex;
        });
    }

    /** {@link #create} decoded here, not cached: the caller owns it. */
    public static CgTextureCubemap createDirect(CgTextureSpec spec,
                                                String posX, String negX,
                                                String posY, String negY,
                                                String posZ, String negZ) {
        CgTextureCubemap tex = new CgTextureCubemap(0, 0, spec, new String[] { posX, negX, posY, negY, posZ, negZ });
        tex.load();
        return tex;
    }

    /** Creates an empty cubemap with no image data. Any thread. Not cached; caller owns the lifecycle. */
    public static CgTextureCubemap createEmpty(int size, CgTextureSpec spec) {
        if (size <= 0) throw new IllegalArgumentException("Cubemap size must be positive, got: " + size);
        CgTextureCubemap tex = new CgTextureCubemap(0, size, spec, null);
        tex.gpu.run(() -> {
            tex.storage(size);
            tex.applySpec();
        });
        return tex;
    }

    // ── Upload ────────────────────────────────────────────────────────

    /**
     * Six faces in place, +X to -Z, all one square size. Any thread; the pixels are copied into leases where the work
     * waits. Also reapplies the spec's filter/wrap params and regenerates mipmaps if enabled.
     */
    public void upload(CgImageData[] faces) {
        ByteBuffer[] pixels = new ByteBuffer[6];
        for (int i = 0; i < 6; i++) pixels[i] = faces[i].pixels();
        upload(faces[0].width(), pixels, pixelFormatForChannels(faces[0].channels()), GL_UNSIGNED_BYTE);
    }

    /**
     * Six faces in place, +X to -Z, each {@code size} square, rows top first in {@code pixelFormat} and
     * {@code pixelType}: as {@link #upload(CgImageData[])}, for pixels of any type.
     */
    public void upload(int size, ByteBuffer[] faces, int pixelFormat, int pixelType) {
        checkUsable();
        this.width = size;
        this.height = size;
        gpu.run(() -> storage(size));
        for (int i = 0; i < 6; i++) uploadFace(i, 0, 0, 0, size, size, faces[i], pixelFormat, pixelType);
        gpu.run(this::applySpec);
    }

    /**
     * Writes a {@code width} x {@code height} region at {@code (x, y)} of level {@code level} of face {@code face}
     * ({@link #POSITIVE_X} to {@link #NEGATIVE_Z}): rows bottom first, tightly packed, in {@code pixelFormat} and
     * {@code pixelType}. The rest keeps what it held. Any thread; {@code pixels} is copied into a lease where the work
     * waits.
     */
    public void uploadFace(int face, int level, int x, int y, int width, int height, ByteBuffer pixels, int pixelFormat,
                           int pixelType) {
        checkUsable();
        checkFace(face);
        if (!gpu.immediate()) {
            uploadFace(face, level, x, y, width, height, CgUploads.copyOf(pixels, converts(pixelFormat, pixelType)),
                    pixelFormat, pixelType);
            return;
        }
        CgDeviceTextures.write(storage(), new CgTextureRegion(level, x, y, face, width, height, 1), pixels, pixelFormat,
                pixelType);
    }

    /**
     * {@link #uploadFace(int, int, int, int, int, int, ByteBuffer, int, int)} from a lease its producer wrote: no copy on
     * this thread or the render thread. Takes the lease; any thread.
     */
    public void uploadFace(int face, int level, int x, int y, int width, int height, CgUploadLease lease,
                           int pixelFormat, int pixelType) {
        checkUsable();
        checkFace(face);
        // The face rides in the lease's z.
        gpu.run(lease.into(landing, level, x, y, face, width, height, 1, pixelFormat, pixelType));
    }

    private void land(CgUploadLease lease) {
        CgDeviceTextures.write(storage(), new CgTextureRegion(lease.level(), lease.x(), lease.y(), lease.z(), lease.width(),
                lease.height(), 1), lease, 0, lease.size());
    }

    /** Every face at {@code size}, with the spec's chain. */
    private void storage(int size) {
        int chain = spec.getMipmaps().isEnabled() ? CgTexture.fullChain(size, size) : 1;
        CgDeviceTextures.release(storeOnDevice(CgGpuTexture.Kind.CUBE, GLFormats.of(spec.getGlInternalFormat()), size,
                size, 6, chain));
    }

    /** The spec's mipmaps from what the faces hold now. */
    private void applySpec() {
        if (spec.getMipmaps().isEnabled()) CgDeviceTextures.mipmaps(storage());
    }

    private static void checkFace(int face) {
        if (face < POSITIVE_X || face > NEGATIVE_Z) throw new IllegalArgumentException("face " + face + " of 0 to 5");
    }

    // ── Files ─────────────────────────────────────────────────────────

    @Override
    protected void load() {
        if (stacked != null) {
            loadStacked();
            return;
        }
        if (sourcePaths == null) return;
        CgImageData[] faces = new CgImageData[6];
        for (int i = 0; i < 6; i++) {
            faces[i] = CgTextureIO.load(sourcePaths[i]);
            CgImageData first = faces[0];
            if (faces[i] == null || first == null || first.width() != first.height() || faces[i].width() != first.width()
                    || faces[i].height() != first.height() || faces[i].channels() != first.channels()) {
                LOGGER.log(Level.WARNING, "[CgTextureCubemap] Face {0}, {1}, would not load as one square size",
                        new Object[] {i, sourcePaths[i]});
                if (width != 0) return;
                missing = true;
                width = height = 1;
                gpu.run(() -> {
                    storage(1);
                    applySpec();
                });
                return;
            }
        }
        missing = false;
        upload(faces);
    }

    /** The stacked image cut into its six faces: each a run of rows, top first. */
    private void loadStacked() {
        ByteBuffer pixels = null;
        int w = 0, h = 0, texel = 0, format = GLC.GL_RGBA, type = GL_UNSIGNED_BYTE;
        if (CgRadianceHdr.is(stacked)) {
            if (spec.getType() != CgTextureType.RGBA16F) {
                throw new IllegalStateException(stacked + " is float colour, but its cube is " + spec.getType()
                        + ": make it with an RGBA16F spec");
            }
            try {
                CgRadianceHdr.Image image = CgRadianceHdr.read(stacked);
                pixels = CgHalfFloat.halves(image.rgba());
                w = image.width();
                h = image.height();
                texel = 8;
                type = GLC.GL_HALF_FLOAT;
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "[CgTextureCubemap] " + e.getMessage());
            }
        } else {
            CgImageData image = CgTextureIO.load(stacked);
            if (image != null) {
                pixels = image.pixels();
                w = image.width();
                h = image.height();
                texel = image.channels();
                format = pixelFormatForChannels(image.channels());
            }
        }
        if (pixels == null) {
            LOGGER.log(Level.WARNING, "[CgTextureCubemap] {0} would not load", stacked);
            if (width != 0) return;
            missing = true;
            width = height = 1;
            gpu.run(() -> {
                storage(1);
                applySpec();
            });
            return;
        }
        if (h != 6 * w) {
            throw new IllegalStateException(stacked + " is " + w + " x " + h + ": a cube's six faces stack as w x 6w");
        }
        int face = w * w * texel;
        ByteBuffer[] faces = new ByteBuffer[6];
        for (int i = 0; i < 6; i++) {
            ByteBuffer slice = pixels.duplicate();
            slice.position(i * face).limit((i + 1) * face);
            faces[i] = slice.slice().order(pixels.order());
        }
        missing = false;
        upload(w, faces, format, type);
    }

    @Override public int getTarget() { return GLC.GL_TEXTURE_CUBE_MAP; }

}
