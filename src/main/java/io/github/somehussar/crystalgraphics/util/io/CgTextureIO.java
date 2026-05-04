package io.github.somehussar.crystalgraphics.util.io;

import com.github.bsideup.jabel.Desugar;
import io.github.somehussar.crystalgraphics.api.texture.CgTextureSpec;
import io.github.somehussar.crystalgraphics.gl.texture.CgTexture2D;
import org.apache.commons.io.IOUtils;

import javax.imageio.ImageIO;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Static helper for loading image files into direct {@link ByteBuffer}s,
 * preserving the source image's native channel count.
 *
 * <p>Path resolution is delegated entirely to {@link CgIO#openStream(String)}.
 * Supported channel counts: 1 (grayscale), 3 (RGB), 4 (RGBA). The returned
 * {@link CgImageData} carries the actual {@code channels} value so callers can
 * derive the correct {@code GL_RED / GL_RGB / GL_RGBA} upload format without
 * hard-coding assumptions. Bottom-left row order (GL convention).</p>
 *
 * <p>Returns {@code null} on any failure (logged at WARNING).</p>
 */
public final class CgTextureIO {

    private static final Logger LOGGER = Logger.getLogger(CgTextureIO.class.getName());

    private CgTextureIO() {
        // no instances
    }

    /**
     * Loads an image, decodes it, and returns a direct RGBA {@link ByteBuffer}
     * along with width/height. Returns {@code null} on failure.
     *
     * @param path asset path
     * @return decoded image + dimensions, or {@code null} on failure
     */
    public static CgImageData load(String path) {
        InputStream in = CgIO.openStream(path);
        if (in == null) {
            LOGGER.log(Level.WARNING, "CgTextureIO: failed to open stream for path " + path);
            return null;
        }

        try {
            BufferedImage src = ImageIO.read(in);
            if (src == null) {
                LOGGER.log(Level.WARNING, "CgTextureIO: ImageIO returned null for path " + path);
                return null;
            }
            return convertNative(src);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "CgTextureIO: error decoding " + path, t);
            return null;
        } finally {
            IOUtils.closeQuietly(in);
        }
    }

    /**
     * Converts a {@link BufferedImage} into a direct {@link ByteBuffer} preserving
     * the source's native channel count (1 = grayscale, 3 = RGB, 4 = RGBA).
     * Bottom-left row order (GL convention). Always 8-bit per channel (GL_UNSIGNED_BYTE).
     */
    private static CgImageData convertNative(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();

        ColorModel cm = src.getColorModel();
        final int channels;
        if (cm.getNumColorComponents() == 1 && !cm.hasAlpha()) channels = 1;
        else if (!cm.hasAlpha()) channels = 3;
        else channels = 4;
        

        BufferedImage argb = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = argb.createGraphics();
        try {
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }

        int[] pixels = new int[w * h];
        argb.getRGB(0, 0, w, h, pixels, 0, w);

        ByteBuffer buf = ByteBuffer.allocateDirect(w * h * channels).order(ByteOrder.nativeOrder());

        // OpenGL origin is bottom-left; BufferedImage origin is top-left.
        for (int y = h - 1; y >= 0; y--) {
            for (int x = 0; x < w; x++) {
                int p = pixels[y * w + x];
                byte r = (byte) ((p >> 16) & 0xFF);
                if (channels == 1) {
                    buf.put(r);
                } else {
                    byte g2 = (byte) ((p >> 8) & 0xFF);
                    byte b = (byte) (p & 0xFF);
                    buf.put(r).put(g2).put(b);
                    if (channels == 4) {
                        buf.put((byte) ((p >> 24) & 0xFF));
                    }
                }
            }
        }
        buf.flip();

        return new CgImageData(buf, w, h, channels);
    }

    /**
     * Generates the 8×8 purple/black checkerboard fallback texture.
     * Purple = (255, 0, 255, 255), Black = (0, 0, 0, 255).
     * Uses {@link CgTextureSpec#RGBA8_NEAREST} so the pattern stays crisp at any scale.
     */
    public static CgTexture2D createFallback() {
        final int size = 8;
        ByteBuffer pixels = ByteBuffer.allocateDirect(size * size * 4);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean purple = ((x + y) & 1) == 0;
                pixels.put((byte) (purple ? 0xFF : 0x00)); // R
                pixels.put((byte) 0x00);                   // G
                pixels.put((byte) (purple ? 0xFF : 0x00)); // B
                pixels.put((byte) 0xFF);                   // A
            }
        }
        pixels.flip();
        return CgTexture2D.createFromPixels(size, size, pixels, CgTextureSpec.RGBA8_NEAREST);
    }

    /**
     * Decoded image: direct {@link ByteBuffer} + dimensions + channel count (1, 3, or 4).
     * Pixels are 8-bit, tightly packed, bottom-left origin (GL convention).
     */
    @Desugar
    public record CgImageData(ByteBuffer pixels, int width, int height, int channels) {
    }
}
