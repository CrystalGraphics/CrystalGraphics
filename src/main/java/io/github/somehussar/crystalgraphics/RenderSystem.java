package io.github.somehussar.crystalgraphics;

import io.github.somehussar.crystalgraphics.framebuffer.FramebufferHandler;

public class RenderSystem {

    private static boolean initialized = false;

    public static void initialize() {
        initialized = true;
    }

    public static void deinitialize() {
        initialized = false;
        FramebufferHandler.free();
    }

    /**
     * @return Check if an OpenGL context has been initialized.
     */
    public static boolean hasInitialized() {
        return initialized;
    }
}
