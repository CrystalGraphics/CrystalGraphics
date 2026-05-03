package io.github.somehussar.crystalgraphics.gl.texture;

import io.github.somehussar.crystalgraphics.api.texture.CgTexture;
import io.github.somehussar.crystalgraphics.api.texture.CgTextureSpec;
import io.github.somehussar.crystalgraphics.util.io.CgTextureIO;
import io.github.somehussar.crystalgraphics.util.io.CgTextureIO.CgImageData;

import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;

/**
 * Cubemap GL texture (target {@code GL_TEXTURE_CUBE_MAP = 0x8513}). Single
 * concrete impl of {@link CgTexture} for cubemaps.
 *
 * <p>A cubemap is six square 2D images uploaded to six discrete face targets
 * ({@code GL_TEXTURE_CUBE_MAP_POSITIVE_X} through
 * {@code GL_TEXTURE_CUBE_MAP_NEGATIVE_Z}, GL constants 0x8515..0x851A). All
 * faces must be the same size and share the same pixel format, mipmap config,
 * and sampler params. Storage and upload happen via {@link GL11#glTexImage2D}
 * once per face target. Sampler parameters are applied to
 * {@code GL_TEXTURE_CUBE_MAP} after all faces are uploaded.</p>
 */
public final class CgTextureCubemap extends CgTextureAbstract {

    // ── GL constants ────────────────────────────────────────────────
    private static final int GL_TEXTURE_CUBE_MAP            = 0x8513;
    private static final int GL_TEXTURE_CUBE_MAP_POSITIVE_X = 0x8515;
    private static final int GL_TEXTURE_CUBE_MAP_NEGATIVE_X = 0x8516;
    private static final int GL_TEXTURE_CUBE_MAP_POSITIVE_Y = 0x8517;
    private static final int GL_TEXTURE_CUBE_MAP_NEGATIVE_Y = 0x8518;
    private static final int GL_TEXTURE_CUBE_MAP_POSITIVE_Z = 0x8519;
    private static final int GL_TEXTURE_CUBE_MAP_NEGATIVE_Z = 0x851A;

    /** Six face targets in canonical order: +X, -X, +Y, -Y, +Z, -Z. */
    private static final int[] FACE_TARGETS = {
            GL_TEXTURE_CUBE_MAP_POSITIVE_X, GL_TEXTURE_CUBE_MAP_NEGATIVE_X,
            GL_TEXTURE_CUBE_MAP_POSITIVE_Y, GL_TEXTURE_CUBE_MAP_NEGATIVE_Y,
            GL_TEXTURE_CUBE_MAP_POSITIVE_Z, GL_TEXTURE_CUBE_MAP_NEGATIVE_Z
    };

    private CgTextureCubemap(int textureId, int size, CgTextureSpec spec) {
        super(textureId, size, size, spec);
    }

    /**
     * Creates a cubemap from six face image paths. All faces must be the same
     * square size.
     *
     * @throws IllegalArgumentException if any path fails to load, or if faces
     *         have mismatching dimensions, or if any face is non-square
     */
    public static CgTextureCubemap create(CgTextureSpec spec,
                                          String posX, String negX,
                                          String posY, String negY,
                                          String posZ, String negZ) {
        String[] paths = { posX, negX, posY, negY, posZ, negZ };

        // Load all six faces up-front so we fail before allocating any GL state.
        CgImageData[] images = new CgImageData[6];
        for (int i = 0; i < 6; i++) {
            images[i] = CgTextureIO.load(paths[i]);
            if (images[i] == null) {
                throw new IllegalArgumentException("Failed to load cubemap face " + i + ": " + paths[i]);
            }
        }

        // Validate uniform square dimensions — cubemaps require all six faces
        // to be the same size and each face must be square.
        int size = images[0].width();
        if (images[0].height() != size) {
            throw new IllegalArgumentException("Cubemap face 0 must be square. Got: "
                    + size + "x" + images[0].height());
        }
        for (int i = 1; i < 6; i++) {
            if (images[i].width() != size || images[i].height() != size) {
                throw new IllegalArgumentException("All cubemap faces must be the same square size ("
                        + size + "x" + size + "). Face " + i + " is "
                        + images[i].width() + "x" + images[i].height());
            }
        }

        return uploadFaces(size, images, spec);
    }

    public static CgTextureCubemap createEmpty(int size, CgTextureSpec spec) {
        if (size <= 0) {
            throw new IllegalArgumentException("Cubemap size must be positive, got: " + size);
        }
        return uploadFaces(size, null, spec);
    }

    private static CgTextureCubemap uploadFaces(int size, CgImageData[] images, CgTextureSpec spec) {
        int id = GL11.glGenTextures();
        try {
            GL11.glBindTexture(GL_TEXTURE_CUBE_MAP, id);
            int internalFormat = spec.getFormat().getInternalFormat();
            int uploadPixelFormat = (images != null)
                    ? pixelFormatForChannels(images[0].channels())
                    : spec.getFormat().getPixelFormat();
            int uploadPixelType = (images != null) ? GL_UNSIGNED_BYTE : spec.getFormat().getPixelType();

            for (int i = 0; i < 6; i++) {
                ByteBuffer pixels = (images != null) ? images[i].pixels() : null;
                GL11.glTexImage2D(FACE_TARGETS[i], 0,
                        internalFormat, size, size, 0,
                        uploadPixelFormat, uploadPixelType, pixels);
            }

            spec.applyTo(GL_TEXTURE_CUBE_MAP);

            if (spec.getMipmaps() != null && spec.getMipmaps().isEnabled()) {
                CgTextureSpec.generateMipmaps(GL_TEXTURE_CUBE_MAP);
            }

            GL11.glBindTexture(GL_TEXTURE_CUBE_MAP, 0);
            return new CgTextureCubemap(id, size, spec);
        } catch (RuntimeException e) {
            // Failure-atomic cleanup: never leak the GL texture id.
            GL11.glBindTexture(GL_TEXTURE_CUBE_MAP, 0);
            GL11.glDeleteTextures(id);
            throw e;
        }
    }

    @Override public int getTarget() { return GL_TEXTURE_CUBE_MAP; }
}
