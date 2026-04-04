package io.github.somehussar.crystalgraphics.harness;

import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.ContextAttribs;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.PixelFormat;

import java.util.logging.Logger;

final class HarnessContext {

    private static final Logger LOGGER = Logger.getLogger(HarnessContext.class.getName());

    static final int WIDTH = 800;
    static final int HEIGHT = 600;

    private final String glVersion;
    private final String glVendor;
    private final String glRenderer;

    private HarnessContext(String glVersion, String glVendor, String glRenderer) {
        this.glVersion = glVersion;
        this.glVendor = glVendor;
        this.glRenderer = glRenderer;
    }

    static HarnessContext create() {
        try {
            Display.setDisplayMode(new DisplayMode(WIDTH, HEIGHT));
            Display.setTitle("CrystalGraphics Debug Harness");

            // Request OpenGL 3.0 forward-compatible context
            ContextAttribs attribs = new ContextAttribs(3, 0)
                    .withForwardCompatible(false);

            Display.create(new PixelFormat(), attribs);
        } catch (LWJGLException e) {
            throw new RuntimeException("Failed to create OpenGL 3.0 context: " + e.getMessage(), e);
        }

        String glVersion = GL11.glGetString(GL11.GL_VERSION);
        String glVendor = GL11.glGetString(GL11.GL_VENDOR);
        String glRenderer = GL11.glGetString(GL11.GL_RENDERER);

        LOGGER.info("[Harness] GL Version:  " + glVersion);
        LOGGER.info("[Harness] GL Vendor:   " + glVendor);
        LOGGER.info("[Harness] GL Renderer: " + glRenderer);

        return new HarnessContext(glVersion, glVendor, glRenderer);
    }

    void destroy() {
        Display.destroy();
        LOGGER.info("[Harness] Display destroyed.");
    }

    String getGlVersion() { return glVersion; }
    String getGlVendor() { return glVendor; }
    String getGlRenderer() { return glRenderer; }
}
