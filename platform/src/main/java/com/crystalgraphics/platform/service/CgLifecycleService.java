package com.crystalgraphics.platform.service;

/**
 * Platform abstraction for GL context lifecycle events. The platform implementation
 * calls these methods directly on the GL thread — no callback registration needed.
 *
 * <p>All methods are invoked on the GL thread.</p>
 */
public interface CgLifecycleService {
    /** Called by the platform on the GL thread once the context is created and sized. */
    void onContextInit(int width, int height);
    /** Called by the platform on the GL thread before the context is destroyed. */
    void onContextDestroy();
    /** Called by the platform on the GL thread when the viewport dimensions change. */
    void onResize(int width, int height);
}
