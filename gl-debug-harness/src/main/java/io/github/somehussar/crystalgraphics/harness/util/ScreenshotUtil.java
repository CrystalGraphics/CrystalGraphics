package io.github.somehussar.crystalgraphics.harness.util;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.logging.Logger;

public final class ScreenshotUtil {

    private static final Logger LOGGER = Logger.getLogger(ScreenshotUtil.class.getName());

    public static void captureBackbuffer(int width, int height, String outputDir, String filename) {
        ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // GL reads bottom-up; flip vertically
                int srcY = height - 1 - y;
                int idx = (srcY * width + x) * 4;
                int r = pixels.get(idx) & 0xFF;
                int g = pixels.get(idx + 1) & 0xFF;
                int b = pixels.get(idx + 2) & 0xFF;
                int a = pixels.get(idx + 3) & 0xFF;
                image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        writePng(image, outputDir, filename);
    }

    public static void captureTexture(int textureId, int width, int height,
                               int internalFormat, String outputDir, String filename) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        if (internalFormat == GL30.GL_R8 || internalFormat == 0x1903 /* GL_RED */) {
            ByteBuffer buf = BufferUtils.createByteBuffer(width * height);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, 0x1903 /* GL_RED */, GL11.GL_UNSIGNED_BYTE, buf);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int val = buf.get(y * width + x) & 0xFF;
                    image.setRGB(x, y, 0xFF000000 | (val << 16) | (val << 8) | val);
                }
            }
        } else {
            // RGBA fallback
            ByteBuffer buf = BufferUtils.createByteBuffer(width * height * 4);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int idx = (y * width + x) * 4;
                    int r = buf.get(idx) & 0xFF;
                    int g = buf.get(idx + 1) & 0xFF;
                    int b = buf.get(idx + 2) & 0xFF;
                    int a = buf.get(idx + 3) & 0xFF;
                    image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                }
            }
        }

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        writePng(image, outputDir, filename);
    }

    public static void captureFboColorTexture(int fboId, int textureId,
                                       int width, int height,
                                       String outputDir, String filename) {
        // Read from the FBO directly via glReadPixels bound to the FBO
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fboId);
        ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // FBO readback is also bottom-up; flip vertically
                int srcY = height - 1 - y;
                int idx = (srcY * width + x) * 4;
                int r = pixels.get(idx) & 0xFF;
                int g = pixels.get(idx + 1) & 0xFF;
                int b = pixels.get(idx + 2) & 0xFF;
                int a = pixels.get(idx + 3) & 0xFF;
                image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        writePng(image, outputDir, filename);
    }

    private static void writePng(BufferedImage image, String outputDir, String filename) {
        File file = new File(outputDir, filename);
        try {
            ImageIO.write(image, "PNG", file);
            long size = file.length();
            LOGGER.info("[Harness] Wrote " + filename + " (" + size + " bytes) to " + file.getAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to write PNG: " + file.getAbsolutePath(), e);
        }
    }

    private ScreenshotUtil() { }
}
