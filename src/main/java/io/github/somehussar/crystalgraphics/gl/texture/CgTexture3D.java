package io.github.somehussar.crystalgraphics.gl.texture;

import io.github.somehussar.crystalgraphics.api.texture.CgTexture;
import io.github.somehussar.crystalgraphics.api.texture.CgTextureSpec;
import io.github.somehussar.crystalgraphics.util.io.CgTextureIO;
import io.github.somehussar.crystalgraphics.util.io.CgTextureIO.CgImageData;

import lombok.Getter;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.nio.ByteBuffer;

/**
 * 3D GL texture (target {@code GL_TEXTURE_3D = 0x806F}). Single concrete impl
 * of {@link CgTexture} for 3D textures.
 *
 * <p>Each "slice" along the R axis is a 2D image of identical width/height.
 * Unlike a 2D-array, slices can be filtered linearly across the depth axis,
 * making 3D textures useful for volumetric data and 3D LUTs.</p>
 */
public final class CgTexture3D extends CgTextureAbstract {

    // ── GL constants ────────────────────────────────────────────────
    private static final int GL_TEXTURE_3D = 0x806F;

    // ── Type-specific state ─────────────────────────────────────────
    @Getter private final int depth;

    private CgTexture3D(int textureId, int width, int height, int depth, CgTextureSpec spec) {
        super(textureId, width, height, spec);
        this.depth = depth;
    }

    /**
     * Loads multiple images as slices of a 3D texture using
     * {@link CgTextureSpec#RGBA8_LINEAR}.
     */
    public static CgTexture3D create(String... paths) {
        return create(CgTextureSpec.RGBA8_LINEAR, paths);
    }

    /**
     * Creates a 3D texture from a list of slice paths.
     *
     * @throws IllegalArgumentException if {@code paths} is empty, any slice
     *         fails to load, or slices have mismatching dimensions
     */
    public static CgTexture3D create(CgTextureSpec spec, String... paths) {
        if (paths == null || paths.length == 0) {
            throw new IllegalArgumentException("paths must not be empty");
        }

        // Load all slices first so we don't allocate GL state on a doomed call.
        CgImageData[] images = new CgImageData[paths.length];
        for (int i = 0; i < paths.length; i++) {
            images[i] = CgTextureIO.load(paths[i]);
            if (images[i] == null) {
                throw new IllegalArgumentException("Failed to load slice " + i + ": " + paths[i]);
            }
        }

        // Validate uniform dimensions — every slice in a 3D texture must match.
        int w = images[0].width();
        int h = images[0].height();
        for (int i = 1; i < images.length; i++) {
            if (images[i].width() != w || images[i].height() != h) {
                throw new IllegalArgumentException("All slices must be same size. Slice 0: " + w + "x" + h
                        + ", slice " + i + ": " + images[i].width() + "x" + images[i].height());
            }
        }

        int id = GL11.glGenTextures();
        try {
            GL11.glBindTexture(GL_TEXTURE_3D, id);
            int internalFormat = spec.getFormat().getInternalFormat();
            int uploadPixelFormat = pixelFormatForChannels(images[0].channels());

            GL12.glTexImage3D(GL_TEXTURE_3D, 0,
                    internalFormat, w, h, paths.length, 0,
                    uploadPixelFormat, GL_UNSIGNED_BYTE, (ByteBuffer) null);

            for (int i = 0; i < images.length; i++) {
                GL12.glTexSubImage3D(GL_TEXTURE_3D, 0,
                        0, 0, i, w, h, 1,
                        uploadPixelFormat, GL_UNSIGNED_BYTE, images[i].pixels());
            }

            spec.applyTo(GL_TEXTURE_3D);

            if (spec.getMipmaps() != null && spec.getMipmaps().isEnabled()) {
                CgTextureSpec.generateMipmaps(GL_TEXTURE_3D);
            }

            GL11.glBindTexture(GL_TEXTURE_3D, 0);
            return new CgTexture3D(id, w, h, paths.length, spec);
        } catch (RuntimeException e) {
            // Failure-atomic cleanup
            GL11.glBindTexture(GL_TEXTURE_3D, 0);
            GL11.glDeleteTextures(id);
            throw e;
        }
    }

    @Override public int getTarget() { return GL_TEXTURE_3D; }
}
