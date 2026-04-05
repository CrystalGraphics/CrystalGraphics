package io.github.somehussar.crystalgraphics.harness.tool;

import io.github.somehussar.crystalgraphics.harness.config.HarnessConfig;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.HarnessScene;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.IntBuffer;
import java.util.logging.Logger;

/**
 * Agent-debug tool #4: Dump current GL state to a structured report file.
 *
 * <p>Also registered as a diagnostic mode ("gl-state-dump") in the scene registry.</p>
 */
public  class GlStateDumper implements HarnessScene {

    private static final Logger LOGGER = Logger.getLogger(GlStateDumper.class.getName());

    @Override
    public void run(HarnessContext ctx, String outputDir) {
        run(ctx, outputDir, null);
    }

    void run(HarnessContext ctx, String outputDir, HarnessConfig config) {
        String filename = "gl-state-dump.txt";
        File outFile = new File(outputDir, filename);

        try {
            PrintWriter pw = new PrintWriter(new FileWriter(outFile));
            try {
                pw.println("=== GL State Dump ===");
                pw.println("Timestamp: " + System.currentTimeMillis());
                pw.println();

                pw.println("-- Context Info --");
                pw.println("GL_VERSION:  " + GL11.glGetString(GL11.GL_VERSION));
                pw.println("GL_VENDOR:   " + GL11.glGetString(GL11.GL_VENDOR));
                pw.println("GL_RENDERER: " + GL11.glGetString(GL11.GL_RENDERER));
                pw.println("GL_SHADING_LANGUAGE_VERSION: " + GL11.glGetString(GL20.GL_SHADING_LANGUAGE_VERSION));
                pw.println();

                pw.println("-- Viewport --");
                IntBuffer viewport = BufferUtils.createIntBuffer(16);
                GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
                pw.println("GL_VIEWPORT: [" + viewport.get(0) + ", " + viewport.get(1)
                        + ", " + viewport.get(2) + ", " + viewport.get(3) + "]");
                pw.println();

                pw.println("-- Bindings --");
                pw.println("GL_CURRENT_PROGRAM: " + GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM));
                pw.println("GL_FRAMEBUFFER_BINDING: " + GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING));
                pw.println("GL_DRAW_FRAMEBUFFER_BINDING: " + GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING));
                pw.println("GL_READ_FRAMEBUFFER_BINDING: " + GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING));
                pw.println("GL_TEXTURE_BINDING_2D: " + GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D));
                pw.println("GL_VERTEX_ARRAY_BINDING: " + GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING));
                pw.println();

                pw.println("-- State Flags --");
                pw.println("GL_BLEND: " + GL11.glIsEnabled(GL11.GL_BLEND));
                pw.println("GL_DEPTH_TEST: " + GL11.glIsEnabled(GL11.GL_DEPTH_TEST));
                pw.println("GL_CULL_FACE: " + GL11.glIsEnabled(GL11.GL_CULL_FACE));
                pw.println("GL_SCISSOR_TEST: " + GL11.glIsEnabled(GL11.GL_SCISSOR_TEST));
                pw.println("GL_STENCIL_TEST: " + GL11.glIsEnabled(GL11.GL_STENCIL_TEST));
                pw.println();

                pw.println("-- Limits --");
                pw.println("GL_MAX_TEXTURE_SIZE: " + GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE));
                pw.println("GL_MAX_DRAW_BUFFERS: " + GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS));
                pw.println("GL_MAX_VERTEX_ATTRIBS: " + GL11.glGetInteger(GL20.GL_MAX_VERTEX_ATTRIBS));
                pw.println("GL_MAX_TEXTURE_IMAGE_UNITS: " + GL11.glGetInteger(GL20.GL_MAX_TEXTURE_IMAGE_UNITS));
                pw.println();

                pw.println("-- Errors --");
                int errorCount = 0;
                int err;
                while ((err = GL11.glGetError()) != GL11.GL_NO_ERROR) {
                    pw.println("GL_ERROR: 0x" + Integer.toHexString(err)
                            + " (" + GlErrorChecker.errorName(err) + ")");
                    errorCount++;
                    if (errorCount > 32) break; // safety valve
                }
                if (errorCount == 0) {
                    pw.println("No GL errors pending.");
                }

                pw.println();
                pw.println("=== End GL State Dump ===");
            } finally {
                pw.close();
            }

            LOGGER.info("[GlStateDumper] Wrote state dump to " + outFile.getAbsolutePath()
                    + " (" + outFile.length() + " bytes)");

        } catch (IOException e) {
            throw new RuntimeException("Failed to write GL state dump: " + outFile.getAbsolutePath(), e);
        }
    }
}
