package com.crystalgraphics.platform;

import java.util.function.BiConsumer;

/**
 * Platform abstraction for GL context lifecycle events. Allows the core engine to
 * register callbacks for context creation, destruction, and window resize/fullscreen
 * toggle without depending on Minecraft or any platform-specific event bus.
 *
 * <p>All callbacks fire on the GL thread.</p>
 */
public interface CgLifecycleService {
    /**
     * Register a callback fired once after the GL context is created and all
     * capabilities have been probed. Safe to initialize GL resources here.
     *
     * @param callback the initialisation callback; must not be {@code null}
     */
    void registerContextInitCallback(Runnable callback);

    /**
     * Register a callback fired just before the GL context is destroyed.
     * Use to release any GL resources the engine owns.
     *
     * @param callback the teardown callback; must not be {@code null}
     */
    void registerContextDestroyCallback(Runnable callback);

    /**
     * Register a callback fired when the window is resized or toggled to/from fullscreen.
     * Parameters are the new framebuffer dimensions in pixels.
     *
     * @param callback a {@link BiConsumer} of (newWidth, newHeight); must not be {@code null}
     */
    void registerResizeCallback(BiConsumer<Integer, Integer> callback);
}
