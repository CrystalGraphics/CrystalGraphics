package io.github.somehussar.crystalgraphics.framebuffer;

import io.github.somehussar.crystalgraphics.RenderSystem;
import io.github.somehussar.crystalgraphics.framebuffer.capabilities.FramebufferCapabilities;
import io.github.somehussar.crystalgraphics.framebuffer.capabilities.FramebufferFeature;

import java.util.EnumSet;

public abstract class FramebufferHandler {
    private boolean isInitialized = false;
    protected final EnumSet<FramebufferFeature> featuresSupported = EnumSet.noneOf(FramebufferFeature.class);

    public static void EnsureRenderSystemExists() {
        if (!RenderSystem.hasInitialized()) {
            throw new IllegalStateException("Render system hasn't initialized yet. Cannot create frame buffers.");
        }
    }

//    protected boolean packedDepthStencil = false;

    public final boolean isSupported(FramebufferCapabilities caps) {
        ensureInitialized();
        if (availableInCurrentContext())
            return featuresSupported.containsAll(caps.getAll());

        return false;
    }

    protected final void ensureInitialized() {
        EnsureRenderSystemExists();
        if (!isInitialized) {
            handleInitialization();
            isInitialized = true;
        }
    }


    /**
     * Handle the creation of an FBO implementation.
     * @param caps What feature-set does the FBO have to support.
     * @param width Width in pixels
     * @param height Height in pixels
     * @return Frame buffer implementation with a given feature set.
     */
    public abstract AbstractFramebuffer create(FramebufferCapabilities caps, int width, int height);

    /**
     * Checks the available capabilities to tell the handler what features this FBO implementation supports.
     */
    protected abstract void handleInitialization();


    /**
     * @return Checks if there is even extension support for this FBO system.
     */
    public abstract boolean availableInCurrentContext();
}
