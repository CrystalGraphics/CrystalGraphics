package com.crystalgraphics.platform.gl;

/**
 * Platform abstraction for OpenGL capability detection. Each platform provides
 * its own implementation that queries the appropriate capability structure
 * (LWJGL2 {@code ContextCapabilities}, LWJGL3 {@code GLCapabilities}, etc.).
 *
 * <p>{@link #probe()} must be called on the GL thread after context creation,
 * before any other method is invoked.</p>
 */
public interface CgCapabilityProbe {
    /**
     * Probe the current GL context for capabilities. Must be called once on the
     * GL thread after context creation. Idempotent — safe to call multiple times.
     */
    void probe();

    /** @return {@code true} if Core GL 3.0 framebuffer objects are supported */
    boolean isCoreFboSupported();

    /** @return {@code true} if the ARB_framebuffer_object extension is supported */
    boolean isArbFboSupported();

    /** @return {@code true} if the EXT_framebuffer_object extension is supported */
    boolean isExtFboSupported();

    /** @return {@code true} if vertex array objects (GL 3.0 / ARB_vertex_array_object) are supported */
    boolean isVaoSupported();

    /** @return {@code true} if shader storage buffer objects (GL 4.3 / ARB_shader_storage_buffer_object) are supported */
    boolean isSSBOSupported();

    /** @return {@code true} if texture buffer objects (GL 3.1 / ARB_texture_buffer_object) are supported */
    boolean isTBOSupported();

    /** @return {@code true} if the context is OpenGL 4.0 or later */
    boolean isOpenGL40();

    /** @return {@code true} if the context is OpenGL 4.3 or later */
    boolean isOpenGL43();

    /** @return {@code true} if ARB_sync (or GL 3.2+) is supported */
    boolean isARBSync();
}
