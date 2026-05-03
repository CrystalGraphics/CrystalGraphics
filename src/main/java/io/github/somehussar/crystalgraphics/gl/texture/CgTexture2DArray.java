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
 * 2D-array GL texture (target {@code GL_TEXTURE_2D_ARRAY = 0x8C1A}). Single
 * concrete impl of {@link CgTexture} for 2D-arrays.
 *
 * <p>Each "layer" of the array is a 2D image of identical width/height. All
 * layers share the same pixel format, mipmap config, and sampler params.
 * Storage is allocated via {@link GL12#glTexImage3D} and per-layer upload
 * happens via {@link GL12#glTexSubImage3D} (the {@code GL_TEXTURE_2D_ARRAY}
 * target itself requires a GL30-capable context).</p>
 */
public final class CgTexture2DArray extends CgTextureAbstract {

    // ── GL constants ────────────────────────────────────────────────
    private static final int GL_TEXTURE_2D_ARRAY = 0x8C1A;

    // ── Type-specific state ─────────────────────────────────────────
    @Getter private final int depth;

    private CgTexture2DArray(int textureId, int width, int height, int depth, CgTextureSpec spec) {
        super(textureId, width, height, spec);
        this.depth = depth;
    }

    /**
     * Loads multiple images as layers of a 2D-array texture using
     * {@link CgTextureSpec#RGBA8_LINEAR}.
     */
    public static CgTexture2DArray create(String... paths) {
        return create(CgTextureSpec.RGBA8_LINEAR, paths);
    }

    /**
     * Creates a 2D-array texture from a list of image paths.
     *
     * @throws IllegalArgumentException if {@code paths} is empty, any layer
     *         fails to load, or layers have mismatching dimensions
     */
    public static CgTexture2DArray create(CgTextureSpec spec, String... paths) {
        if (paths == null || paths.length == 0) {
            throw new IllegalArgumentException("paths must not be empty");
        }

        // Load all images up-front so we fail before allocating any GL state.
        CgImageData[] images = new CgImageData[paths.length];
        for (int i = 0; i < paths.length; i++) {
            images[i] = CgTextureIO.load(paths[i]);
            if (images[i] == null) {
                throw new IllegalArgumentException("Failed to load layer " + i + ": " + paths[i]);
            }
        }

        // Validate uniform dimensions — array textures require identical layer sizes.
        int w = images[0].width();
        int h = images[0].height();
        for (int i = 1; i < images.length; i++) {
            if (images[i].width() != w || images[i].height() != h) {
                throw new IllegalArgumentException("All layers must be same size. Layer 0: " + w + "x" + h
                        + ", layer " + i + ": " + images[i].width() + "x" + images[i].height());
            }
        }

        int id = GL11.glGenTextures();
        try {
            GL11.glBindTexture(GL_TEXTURE_2D_ARRAY, id);
            int internalFormat = spec.getFormat().getInternalFormat();
            int uploadPixelFormat = pixelFormatForChannels(images[0].channels());

            GL12.glTexImage3D(GL_TEXTURE_2D_ARRAY, 0,
                    internalFormat, w, h, paths.length, 0,
                    uploadPixelFormat, GL_UNSIGNED_BYTE, (ByteBuffer) null);

            for (int i = 0; i < images.length; i++) {
                GL12.glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0,
                        0, 0, i, w, h, 1,
                        uploadPixelFormat, GL_UNSIGNED_BYTE, images[i].pixels());
            }

            spec.applyTo(GL_TEXTURE_2D_ARRAY);

            if (spec.getMipmaps() != null && spec.getMipmaps().isEnabled()) {
                CgTextureSpec.generateMipmaps(GL_TEXTURE_2D_ARRAY);
            }

            GL11.glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
            return new CgTexture2DArray(id, w, h, paths.length, spec);
        } catch (RuntimeException e) {
            // Failure-atomic cleanup
            GL11.glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
            GL11.glDeleteTextures(id);
            throw e;
        }
    }

    @Override public int getTarget() { return GL_TEXTURE_2D_ARRAY; }
}
