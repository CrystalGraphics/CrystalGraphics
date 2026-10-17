package com.crystalgraphics.util.io;

import com.github.bsideup.jabel.Desugar;


import javax.imageio.ImageIO;

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
 * hard-coding assumptions. Rows top first ({@link #FOLLOW_MC_CONVENTION}, Minecraft's), else bottom first.</p>
 *
 * <p>Returns {@code null} on any failure (logged at WARNING).</p>
 */
public final class CgTextureIO {

    private static final Logger LOGGER = Logger.getLogger(CgTextureIO.class.getName());
    public static boolean FOLLOW_MC_CONVENTION = true;

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

        try (InputStream stream = in) {
            BufferedImage src = ImageIO.read(stream);
            if (src == null) {
                LOGGER.log(Level.WARNING, "CgTextureIO: ImageIO returned null for path " + path);
                return null;
            }
            return convertNative(src);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "CgTextureIO: error decoding " + path, t);
            return null;
        }
    }

    /**
     * Converts a {@link BufferedImage} into a direct {@link ByteBuffer} preserving
     * the source's native channel count (1 = grayscale, 3 = RGB, 4 = RGBA).
     * Rows top first unless {@link #FOLLOW_MC_CONVENTION} is off. Always 8-bit per channel (GL_UNSIGNED_BYTE).
     */
    private static CgImageData convertNative(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int channels = determineChannelCount(src.getColorModel());

        ByteBuffer buf = ByteBuffer.allocateDirect(w * h * channels).order(ByteOrder.nativeOrder());
        int[] rowPixels = new int[w];

        for (int i = 0; i < h; i++) {
            // Determine which row to read based on the Y-axis convention
            int y = FOLLOW_MC_CONVENTION ? i : (h - 1 - i);

            // Extract directly into our reusable row array
            src.getRGB(0, y, w, 1, rowPixels, 0, w);

            // Write the converted bytes to the buffer
            writeRowToBuffer(buf, rowPixels, w, channels);
        }

        buf.flip();
        return new CgImageData(buf, w, h, channels);
    }

    /**
     * Determines the target channel count based on the source image's ColorModel.
     */
    private static int determineChannelCount(ColorModel cm) {
        if (cm.getNumColorComponents() == 1 && !cm.hasAlpha()) {
            return 1;
        } else if (!cm.hasAlpha()) {
            return 3;
        }
        return 4;
    }

    /**
     * Parses ARGB integers and writes the requested channels directly into the ByteBuffer.
     */
    private static void writeRowToBuffer(ByteBuffer buf, int[] rowPixels, int width, int channels) {
        if (channels == 4) {
            for (int x = 0; x < width; x++) {
                int p = rowPixels[x];
                buf.put((byte) ((p >> 16) & 0xFF)); // R
                buf.put((byte) ((p >> 8) & 0xFF));  // G
                buf.put((byte) (p & 0xFF));         // B
                buf.put((byte) ((p >> 24) & 0xFF)); // A
            }
        } else if (channels == 3) {
            for (int x = 0; x < width; x++) {
                int p = rowPixels[x];
                buf.put((byte) ((p >> 16) & 0xFF)); // R
                buf.put((byte) ((p >> 8) & 0xFF));  // G
                buf.put((byte) (p & 0xFF));         // B
            }
        } else {
            // Grayscale: In ARGB space, R=G=B, so we just grab the Red channel
            for (int x = 0; x < width; x++) {
                buf.put((byte) ((rowPixels[x] >> 16) & 0xFF));
            }
        }
    }
    /**
     * Decoded image: direct {@link ByteBuffer} + dimensions + channel count (1, 3, or 4).
     * Pixels are 8-bit, tightly packed, bottom-left origin (GL convention).
     */
    @Desugar
    public record CgImageData(ByteBuffer pixels, int width, int height, int channels) {
    }
}
