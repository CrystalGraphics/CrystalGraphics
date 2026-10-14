package com.crystalgraphics.gl.texture;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.gpu.CgUploadLease;
import com.crystalgraphics.gpu.CgUploads;
import com.crystalgraphics.util.io.CgTextureIO;
import com.crystalgraphics.util.io.CgTextureIO.CgImageData;

import lombok.Getter;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlScope;

import java.nio.ByteBuffer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Cubemap GL texture (target {@code CgGL.GL_TEXTURE_CUBE_MAP = 0x8513}). Single
 * concrete impl of {@link CgTexture} for cubemaps.
 *
 * <p>A cubemap is six square 2D images uploaded to six discrete face targets
 * ({@code CgGL.GL_TEXTURE_CUBE_MAP_POSITIVE_X} through
 * {@code CgGL.GL_TEXTURE_CUBE_MAP_NEGATIVE_Z}, GL constants 0x8515..0x851A). All
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
 *   <li>{@link #createEmpty(int, CgTextureSpec)} — empty faces; caller owns lifecycle.</li>
 * </ul>
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

    /** Six face targets in canonical order: +X, -X, +Y, -Y, +Z, -Z. */
    private static final int[] FACE_TARGETS = {
            CgGL.GL_TEXTURE_CUBE_MAP_POSITIVE_X, CgGL.GL_TEXTURE_CUBE_MAP_NEGATIVE_X,
            CgGL.GL_TEXTURE_CUBE_MAP_POSITIVE_Y, CgGL.GL_TEXTURE_CUBE_MAP_NEGATIVE_Y,
            CgGL.GL_TEXTURE_CUBE_MAP_POSITIVE_Z, CgGL.GL_TEXTURE_CUBE_MAP_NEGATIVE_Z
    };

    /** Source face paths for reload; {@code null} for createDirect and createEmpty. */
    @Getter private final String[] sourcePaths;

    private final Consumer<CgUploadLease> landing = this::land;

    private CgTextureCubemap(int textureId, int size, CgTextureSpec spec, String[] sourcePaths) {
        super(textureId, size, size, spec);
        this.sourcePaths = sourcePaths;
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
        checkUsable();
        int size = faces[0].width();
        int format = pixelFormatForChannels(faces[0].channels());
        this.width = size;
        this.height = size;
        gpu.run(() -> storage(size));
        for (int i = 0; i < 6; i++) uploadFace(i, 0, 0, 0, size, size, faces[i].pixels(), format, GL_UNSIGNED_BYTE);
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
        try (CgGlScope restore = gpu.restoring()) {
            CgGL.glBindTexture(CgGL.GL_TEXTURE_CUBE_MAP, textureId);
            try (CgTightUnpack ignored = CgTightUnpack.begin()) {
                CgGL.glTexSubImage2D(FACE_TARGETS[face], level, x, y, width, height, pixelFormat, pixelType, pixels);
            } finally {
                CgGL.glBindTexture(CgGL.GL_TEXTURE_CUBE_MAP, 0);
            }
        }
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
        CgGL.glBindTexture(CgGL.GL_TEXTURE_CUBE_MAP, textureId);
        try (CgTightUnpack ignored = CgTightUnpack.begin()) {
            lease.texSubImage2D(FACE_TARGETS[lease.z()]);
        } finally {
            CgGL.glBindTexture(CgGL.GL_TEXTURE_CUBE_MAP, 0);
        }
    }

    /** Level 0 of every face at {@code size}, the texture made first if it has no id yet. */
    private void storage(int size) {
        boolean made = textureId == 0;
        if (made) textureId = CgGL.glGenTextures();
        CgGL.glBindTexture(CgGL.GL_TEXTURE_CUBE_MAP, textureId);
        try {
            for (int face : FACE_TARGETS) {
                CgGL.glTexImage2D(face, 0, spec.getGlInternalFormat(), size, size, 0, spec.getGlBaseFormat(),
                        spec.getGlType(), (ByteBuffer) null);
            }
        } catch (RuntimeException e) {
            if (made) {
                CgGL.glDeleteTextures(textureId);
                textureId = 0;
            }
            throw e;
        } finally {
            CgGL.glBindTexture(CgGL.GL_TEXTURE_CUBE_MAP, 0);
        }
    }

    /** The spec's filters and wrap, and its mipmaps from what the faces hold now. */
    private void applySpec() {
        CgGL.glBindTexture(CgGL.GL_TEXTURE_CUBE_MAP, textureId);
        try {
            spec.applyTo(CgGL.GL_TEXTURE_CUBE_MAP);
        } finally {
            CgGL.glBindTexture(CgGL.GL_TEXTURE_CUBE_MAP, 0);
        }
    }

    private static void checkFace(int face) {
        if (face < POSITIVE_X || face > NEGATIVE_Z) throw new IllegalArgumentException("face " + face + " of 0 to 5");
    }

    // ── Files ─────────────────────────────────────────────────────────

    @Override
    protected void load() {
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

    @Override public int getTarget() { return CgGL.GL_TEXTURE_CUBE_MAP; }

}
