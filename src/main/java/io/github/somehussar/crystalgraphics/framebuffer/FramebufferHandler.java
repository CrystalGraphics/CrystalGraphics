package io.github.somehussar.crystalgraphics.framebuffer;

import io.github.somehussar.crystalgraphics.RenderSystem;
import io.github.somehussar.crystalgraphics.framebuffer.capabilities.FramebufferCapabilities;
import io.github.somehussar.crystalgraphics.framebuffer.capabilities.FramebufferFeature;

import java.util.*;
import java.util.function.Supplier;

public abstract class FramebufferHandler {
    private boolean isInitialized = false;
    protected final EnumSet<FramebufferFeature> featuresSupported = EnumSet.noneOf(FramebufferFeature.class);

    static final Set<AbstractFramebuffer> createdFramebuffers = new HashSet<>();

    protected static AbstractFramebuffer currentBuffer = null;
    protected static Supplier<AbstractFramebuffer> wrappingMethod = () -> null;

    public static void registerWrappingMethod(Supplier<AbstractFramebuffer> wrapper) {
        wrappingMethod = wrapper;
    }

    public static AbstractFramebuffer getCurrentBuffer() {
        EnsureRenderSystemExists();

        if (currentBuffer != null)
            return currentBuffer;

        AbstractFramebuffer framebuffer = null;
        if (wrappingMethod != null)
            framebuffer = wrappingMethod.get();

        if (framebuffer == null)
            framebuffer = AbstractFramebuffer.DUMMY_BUFFER;

        return framebuffer;
    }


    public static void free() {
        List<AbstractFramebuffer> toRemove = new ArrayList<>(createdFramebuffers);
        for (AbstractFramebuffer framebuffer : toRemove) {
            framebuffer.delete();
        }
    }

    public static void EnsureRenderSystemExists() {
        if (!RenderSystem.hasInitialized()) {
            throw new IllegalStateException("Render system hasn't initialized yet. Cannot create frame buffers.");
        }
    }

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
