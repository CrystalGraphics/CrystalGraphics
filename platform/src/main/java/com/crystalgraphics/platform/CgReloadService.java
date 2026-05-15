package com.crystalgraphics.platform;

/**
 * Platform abstraction for hot-reload events (e.g. F3+T in Minecraft, or resource
 * pack changes). Registered callbacks are fired when the player triggers a resource
 * reload. Callbacks are safe to invoke from any thread.
 */
public interface CgReloadService {
    /**
     * Register a callback to be invoked when a resource reload is triggered.
     *
     * @param callback the reload callback; must not be {@code null}
     */
    void registerReloadCallback(Runnable callback);
}
