package io.github.somehussar.crystalgraphics.gl.texture;

import io.github.somehussar.crystalgraphics.api.texture.CgTexture;
import io.github.somehussar.crystalgraphics.api.texture.CgTextureSpec;
import io.github.somehussar.crystalgraphics.util.io.CgTextureIO;
import io.github.somehussar.crystalgraphics.util.io.CgTextureIO.CgImageData;

import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;

/**
 * 2D GL texture (target {@code GL_TEXTURE_2D}). The single concrete impl of
 * {@link CgTexture} for 2D textures — owns one GL texture id, allocated via
 * {@link #create(String, CgTextureSpec)} or {@link #createEmpty(int, int, CgTextureSpec)}
 * and released by {@link #delete()}.
 *
 * <p>Failure during creation always cleans up any partially allocated GL ids
 * (failure-atomic).</p>
 */
public final class CgTexture2D extends CgTextureAbstract {

    // ── GL constants ────────────────────────────────────────────────
    private static final int GL_TEXTURE_2D = 0x0DE1;

    private CgTexture2D(int textureId, int width, int height, CgTextureSpec spec) {
        super(textureId, width, height, spec);
    }

    /**
     * Loads a 2D texture from an asset path using the default
     * {@link CgTextureSpec#RGBA8_LINEAR} spec.
     */
    public static CgTexture2D create(String path) {
        return create(path, CgTextureSpec.RGBA8_LINEAR);
    }

    /**
     * Loads an image from {@code path} and uploads it as a 2D texture.
     *
     * @throws IllegalArgumentException if loading the image failed
     */
    public static CgTexture2D create(String path, CgTextureSpec spec) {
        CgImageData data = CgTextureIO.load(path);
        if (data == null) {
            throw new IllegalArgumentException("Failed to load texture: " + path);
        }
        return uploadFromBuffer(data.width(), data.height(), data.pixels(), spec,
                pixelFormatForChannels(data.channels()), GL_UNSIGNED_BYTE);
    }

    public static CgTexture2D createEmpty(int width, int height, CgTextureSpec spec) {
        return uploadFromBuffer(width, height, null, spec,
                spec.getFormat().getPixelFormat(), spec.getFormat().getPixelType());
    }

    private static CgTexture2D uploadFromBuffer(int width, int height, ByteBuffer pixels,
                                                CgTextureSpec spec, int uploadPixelFormat, int uploadPixelType) {
        int id = GL11.glGenTextures();
        try {
            GL11.glBindTexture(GL_TEXTURE_2D, id);
            GL11.glTexImage2D(GL_TEXTURE_2D, 0,
                    spec.getFormat().getInternalFormat(), width, height, 0,
                    uploadPixelFormat, uploadPixelType, pixels);
            spec.applyTo(GL_TEXTURE_2D);
            if (spec.getMipmaps() != null && spec.getMipmaps().isEnabled()) {
                CgTextureSpec.generateMipmaps(GL_TEXTURE_2D);
            }
            GL11.glBindTexture(GL_TEXTURE_2D, 0);
            return new CgTexture2D(id, width, height, spec);
        } catch (RuntimeException e) {
            GL11.glBindTexture(GL_TEXTURE_2D, 0);
            GL11.glDeleteTextures(id);
            throw e;
        }
    }

    /**
     * Re-uploads pixel data, replacing the current image.
     * Use {@link CgTextureIO#load(String path)} for {@link CgImageData} creation.
     */
    public void upload(CgImageData image) {
        checkNotDeleted();
        GL11.glBindTexture(GL_TEXTURE_2D, textureId);
        GL11.glTexImage2D(GL_TEXTURE_2D, 0,
                spec.getFormat().getInternalFormat(), image.width(), image.height(), 0,
                pixelFormatForChannels(image.channels()), GL_UNSIGNED_BYTE, image.pixels());
        this.width = image.width();
        this.height = image.height();
        GL11.glBindTexture(GL_TEXTURE_2D, 0);
    }

    @Override public int getTarget() { return GL_TEXTURE_2D; }
}
