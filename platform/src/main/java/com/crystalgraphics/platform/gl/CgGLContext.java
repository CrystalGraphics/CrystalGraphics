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

    /** @return {@code true} if the context is OpenGL 3.0 or later */
    boolean OpenGL30();

    /** @return {@code true} if the context is OpenGL 3.2 or later */
    boolean OpenGL32();

    /** @return {@code true} if the context is OpenGL 3.3 or later */
    boolean OpenGL33();

    /** @return {@code true} if the context is OpenGL 4.0 or later */
    boolean OpenGL40();

    /** @return {@code true} if the context is OpenGL 4.2 or later */
    boolean OpenGL42();

    /** @return {@code true} if the context is OpenGL 4.3 or later */
    boolean OpenGL43();

    /** @return {@code true} if the context is OpenGL 4.4 or later */
    boolean OpenGL44();

    /** @return {@code true} if the context is OpenGL 4.6 or later */
    boolean OpenGL46();

    // ── Streaming: what CgStreamBuffer's tiers are chosen from ────────────────

    /** @return {@code true} if {@code GL_ARB_map_buffer_range} is supported */
    boolean GL_ARB_map_buffer_range();

    /** @return {@code true} if {@code GL_ARB_sync} is supported */
    boolean GL_ARB_sync();

    /** @return {@code true} if {@code GL_ARB_buffer_storage} is supported */
    boolean GL_ARB_buffer_storage();

    /**
     * @return {@code true} if {@code GL_ARB_shader_storage_buffer_object} is supported
     *         (independently of core GL 4.3 — {@link #OpenGL43()} covers the core path)
     */
    boolean GL_ARB_shader_storage_buffer_object();

    // ── What GL 3.3 absorbed: a 3.2 context with all four runs everything the floor promises ────
    //
    // Vanilla Minecraft 1.17 to 1.21.4 asks for a 3.2 core context, and NVIDIA hands back exactly 3.2.

    /** @return {@code true} if {@code GL_ARB_instanced_arrays} is supported */
    boolean GL_ARB_instanced_arrays();

    /** @return {@code true} if {@code GL_ARB_sampler_objects} is supported */
    boolean GL_ARB_sampler_objects();

    /** @return {@code true} if {@code GL_ARB_explicit_attrib_location} is supported */
    boolean GL_ARB_explicit_attrib_location();

    /** @return {@code true} if {@code GL_ARB_timer_query} is supported */
    boolean GL_ARB_timer_query();

    /**
     * @return {@code true} where mapping a buffer is a pointer and nothing more: the tracked backend, whose memory is
     *         mapped already. A GL driver's map is a round trip, which is why a small upload there goes through
     *         {@code glBufferSubData} instead, and on the tracked backend that copies the whole buffer if a frame in
     *         flight still reads it.
     */
    boolean mappingIsFree();

    /**
     * @return {@code true} if {@code GL_KHR_parallel_shader_compile} or {@code GL_ARB_parallel_shader_compile} is
     *         supported: a program's {@code GL_COMPLETION_STATUS_KHR} can be asked without waiting for its compile
     */
    boolean parallelShaderCompile();

    // ── Compute and GPU-driven draws: what CgCapabilities' compute tiers are chosen from ────────────
    //
    // Each extension apart from its core version: CgCapabilities joins the two.

    boolean GL_ARB_compute_shader();

    boolean GL_ARB_shader_image_load_store();

    boolean GL_ARB_draw_indirect();

    boolean GL_ARB_multi_draw_indirect();

    boolean GL_ARB_indirect_parameters();

    /** {@code gl_DrawID} and the base vertex and instance in a shader. */
    boolean GL_ARB_shader_draw_parameters();

    /** A first instance in a draw, an indirect command's included: below it the command's field must be 0. */
    boolean GL_ARB_base_instance();

    /** A transform-feedback stream drawn with its captured count. */
    boolean GL_ARB_transform_feedback2();

    boolean GL_KHR_shader_subgroup();

    /** Atomic adds on floats in storage buffers and images. */
    boolean GL_NV_shader_atomic_float();

    boolean GL_ARB_bindless_texture();

}
