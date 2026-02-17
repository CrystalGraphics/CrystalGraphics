package io.github.somehussar.crystalgraphics.framebuffer.impl;

import io.github.somehussar.crystalgraphics.framebuffer.AbstractFramebuffer;
import io.github.somehussar.crystalgraphics.framebuffer.FramebufferHandler;
import io.github.somehussar.crystalgraphics.framebuffer.capabilities.FramebufferCapabilities;

/**
 * Class for handling OpenGL 3.0 Core frame buffers.
 */
public class CoreFramebufferHandler extends FramebufferHandler {
    private static final CoreFramebufferHandler INSTANCE = new CoreFramebufferHandler();

    public static FramebufferHandler get() {
        EnsureRenderSystemExists();
        return INSTANCE;
    }

    @Override
    public AbstractFramebuffer create(FramebufferCapabilities caps, int width, int height) {
        return null;
    }

    @Override
    protected void handleInitialization() {

    }

    @Override
    public boolean availableInCurrentContext() {
        return false;
    }
}
