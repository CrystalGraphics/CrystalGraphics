package com.crystalgraphics.platform.service;

/** The host's viewport, for the core engine. A host draws through {@code CgGraphicsLifecycle.stage}, not here. */
public interface CgRenderingService {
    /** Returns the current viewport width in pixels. */
    int getDisplayWidth();
    /** Returns the current viewport height in pixels. */
    int getDisplayHeight();
}
