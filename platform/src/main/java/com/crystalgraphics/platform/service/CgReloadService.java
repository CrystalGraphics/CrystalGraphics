package com.crystalgraphics.platform.service;

/**
 * Platform abstraction for hot-reload events (e.g. F3+T in Minecraft, or resource
 * pack changes). The platform calls {@link #onReload} directly — no callback
 * registration needed. Safe to invoke from any thread.
 */
public interface CgReloadService {
    /** Called by the platform when the player triggers a resource reload. */
    void onReload();
}
