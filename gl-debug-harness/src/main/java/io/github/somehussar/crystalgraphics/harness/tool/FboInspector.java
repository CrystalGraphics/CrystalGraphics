package io.github.somehussar.crystalgraphics.harness.tool;

import io.github.somehussar.crystalgraphics.harness.util.ScreenshotUtil;
import org.lwjgl.opengl.GL30;

import java.util.logging.Logger;

/**
 * Agent-debug tool #3: Dump FBO color attachments to PNG files.
 */
public final class FboInspector {

    private static final Logger LOGGER = Logger.getLogger(FboInspector.class.getName());

    /**
     * Dump the color attachment of an FBO to a PNG.
     *
     * @param fboId     FBO id
     * @param width     attachment width
     * @param height    attachment height
     * @param outputDir output directory
     * @param filename  output filename
     */
    public static void dumpColorAttachment(int fboId, int width, int height,
                                           String outputDir, String filename) {
        LOGGER.info("[FboInspector] Dumping FBO " + fboId
                + " color attachment (" + width + "x" + height + ") -> " + filename);

        // We need the color texture attached to this FBO
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fboId);

        // Query the attached texture
        int attachedTex = GL30.glGetFramebufferAttachmentParameteri(
                GL30.GL_READ_FRAMEBUFFER,
                GL30.GL_COLOR_ATTACHMENT0,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);

        if (attachedTex != 0) {
            ScreenshotUtil.captureFboColorTexture(fboId, attachedTex,
                    width, height, outputDir, filename);
        } else {
            LOGGER.warning("[FboInspector] FBO " + fboId + " has no color attachment");
        }
    }

    private FboInspector() {
    }
}
