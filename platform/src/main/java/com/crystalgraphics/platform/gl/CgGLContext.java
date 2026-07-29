package com.crystalgraphics.platform.gl;

/**
 * Platform abstraction for OpenGL capability detection. Each platform provides
 * its own implementation that queries the appropriate runtime capability structure
 * (LWJGL2 {@code ContextCapabilities}, LWJGL3 {@code GLCapabilities}, etc.).
 *
 * <p>{@link #probe()} must be called on the GL thread after context creation,
 * before any other method is invoked.</p>
 *
 * <p>Consumed by {@code CgCapabilities.detectUncached()} in {@code core/} to build
 * the rich immutable capability snapshot. Nothing else in {@code core/} should
 * call this interface directly — use {@code CgCapabilities.detect()} instead.</p>
 *
 * <p>Registered via {@code CgCapabilities.init(CgGLContext)} during platform boot,
 * mirroring the {@code CgGL.init(CgGLBackend)} pattern.</p>
 */
public interface CgGLContext {

    /**
     * Probe the current GL context for capabilities. Must be called once on the
     * GL thread after context creation. Idempotent — safe to call multiple times.
     */
    void probe();

    // ── GL version tiers ──────────────────────────────────────────────────────

    /** @return {@code true} if the context is OpenGL 2.0 or later (core shader support) */
    boolean OpenGL20();

    /** @return {@code true} if the context is OpenGL 3.0 or later */
    boolean OpenGL30();

    /** @return {@code true} if the context is OpenGL 3.1 or later */
    boolean OpenGL31();

    /** @return {@code true} if the context is OpenGL 3.2 or later */
    boolean OpenGL32();

    /** @return {@code true} if the context is OpenGL 3.3 or later */
    boolean OpenGL33();

    /** @return {@code true} if the context is OpenGL 4.0 or later */
    boolean OpenGL40();

    /** @return {@code true} if the context is OpenGL 4.3 or later */
    boolean OpenGL43();

    // ── Framebuffer extensions ────────────────────────────────────────────────

    /** @return {@code true} if {@code GL_ARB_framebuffer_object} is supported */
    boolean GL_ARB_framebuffer_object();

    /** @return {@code true} if {@code GL_EXT_framebuffer_object} is supported */
    boolean GL_EXT_framebuffer_object();

    // ── Shader extensions ─────────────────────────────────────────────────────

    /** @return {@code true} if {@code GL_ARB_shader_objects} is supported */
    boolean GL_ARB_shader_objects();

    // ── Depth / stencil extensions ────────────────────────────────────────────

    /** @return {@code true} if {@code GL_EXT_packed_depth_stencil} is supported */
    boolean GL_EXT_packed_depth_stencil();

    /** @return {@code true} if {@code GL_NV_packed_depth_stencil} is supported */
    boolean GL_NV_packed_depth_stencil();

    /** @return {@code true} if {@code GL_ARB_depth_texture} is supported */
    boolean GL_ARB_depth_texture();

    // ── Buffer / VAO extensions ───────────────────────────────────────────────

    /** @return {@code true} if {@code GL_ARB_vertex_array_object} is supported */
    boolean GL_ARB_vertex_array_object();

    /** @return {@code true} if {@code GL_ARB_map_buffer_range} is supported */
    boolean GL_ARB_map_buffer_range();

    /** @return {@code true} if {@code GL_ARB_sync} is supported */
    boolean GL_ARB_sync();

    // ── Instancing extensions ─────────────────────────────────────────────────

    /** @return {@code true} if {@code GL_ARB_draw_instanced} is supported */
    boolean GL_ARB_draw_instanced();

    /** @return {@code true} if {@code GL_ARB_instanced_arrays} is supported */
    boolean GL_ARB_instanced_arrays();

    // ── Shader buffer extensions ──────────────────────────────────────────────

    /**
     * @return {@code true} if {@code GL_ARB_shader_storage_buffer_object} is supported
     *         (independently of core GL 4.3 — {@link #OpenGL43()} covers the core path)
     */
    boolean GL_ARB_shader_storage_buffer_object();

    /**
     * @return {@code true} if {@code GL_ARB_sampler_objects} is supported.
     *         This extension is promoted to core in OpenGL 3.3.
     */
    boolean GL_ARB_sampler_objects();
    
       /**
     * @return {@code true} if {@code GL_ARB_timer_query} is supported.
     *         This extension is promoted to core in OpenGL 3.3.
     */
    boolean GL_ARB_timer_query();
}
