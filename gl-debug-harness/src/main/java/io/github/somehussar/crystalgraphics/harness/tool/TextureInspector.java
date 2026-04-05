package io.github.somehussar.crystalgraphics.harness.tool;

import io.github.somehussar.crystalgraphics.harness.util.ScreenshotUtil;

import java.util.logging.Logger;

/**
 * Agent-debug tool #2: Capture an arbitrary GL texture to a PNG file.
 */
public class TextureInspector {

    private static final Logger LOGGER = Logger.getLogger(TextureInspector.class.getName());

    /**
     * Capture a texture by its GL id to a PNG file.
     *
     * @param textureId      GL texture id
     * @param width          texture width
     * @param height         texture height
     * @param internalFormat GL internal format (e.g., GL_R8, GL_RGBA8)
     * @param outputDir      output directory
     * @param filename       output filename
     */
    public static void capture(int textureId, int width, int height,
                        int internalFormat, String outputDir, String filename) {
        LOGGER.info("[TextureInspector] Capturing texture " + textureId
                + " (" + width + "x" + height + ") format=0x"
                + Integer.toHexString(internalFormat) + " -> " + filename);
        ScreenshotUtil.captureTexture(textureId, width, height,
                internalFormat, outputDir, filename);
    }

    private TextureInspector() { }
}
