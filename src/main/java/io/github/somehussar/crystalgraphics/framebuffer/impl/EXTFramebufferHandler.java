package io.github.somehussar.crystalgraphics.framebuffer.impl;

import io.github.somehussar.crystalgraphics.framebuffer.AbstractFramebuffer;
import io.github.somehussar.crystalgraphics.framebuffer.FramebufferHandler;
import io.github.somehussar.crystalgraphics.framebuffer.capabilities.FramebufferCapabilities;

/**
 * Class for handling frame buffers from the EXT extension.
 */
public class EXTFramebufferHandler extends FramebufferHandler {
    private static final EXTFramebufferHandler INSTANCE = new EXTFramebufferHandler();

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
