package io.github.somehussar.crystalgraphics.gl.state;

/**
 * Identifies the OpenGL call family (API path) through which a state-mutating
 * GL operation was performed.
 *
 * <p>Different mods, drivers, and Minecraft itself may bind framebuffers or
 * shader programs via different LWJGL entry points (Core GL30, ARB extensions,
 * EXT extensions, or Minecraft's {@code OpenGlHelper} wrapper).  Knowing
 * <em>which</em> entry point was used is essential for correctly restoring
 * state, because each family uses different method signatures and enum
 * constants.</p>
 *
 * <p>This enum is used by {@link GLStateMirror} to tag every tracked binding
 * with the family that produced it.</p>
 *
 * @see GLStateMirror
 */
public enum CallFamily {

    /**
     * Framebuffer bind via {@code GL30.glBindFramebuffer}.
     * Core OpenGL 3.0 path &mdash; preferred on hardware that supports it.
     */
    CORE_GL30,

    /**
     * Framebuffer bind via {@code ARBFramebufferObject.glBindFramebuffer}.
     * ARB extension path &mdash; semantically identical to Core GL30 but
     * routed through the ARB extension entry point.
     */
    ARB_FBO,

    /**
     * Framebuffer bind via {@code EXTFramebufferObject.glBindFramebufferEXT}.
     * Legacy EXT extension path &mdash; uses {@code *EXT}-suffixed methods and
     * constants, and lacks some features available in Core/ARB (e.g., separate
     * draw/read targets).
     */
    EXT_FBO,

    /**
     * Framebuffer bind via Minecraft's {@code OpenGlHelper.func_153171_g}
     * wrapper.  This is the obfuscated name for
     * {@code OpenGlHelper.bindFramebuffer}, which internally delegates to one
     * of the other framebuffer families depending on detected GL capabilities.
     */
    OPENGLHELPER_WRAPPER,

    /**
     * Shader program bind via {@code GL20.glUseProgram}.
     * Core OpenGL 2.0 shader path.
     */
    CORE_GL20,

    /**
     * Shader program bind via {@code ARBShaderObjects.glUseProgramObjectARB}.
     * ARB shader objects extension path &mdash; used on drivers that expose
     * shader support as an ARB extension rather than core GL20.
     */
    ARB_SHADER_OBJECTS,

    /**
     * Family not yet observed.  This is the default state before any
     * intercepted call has been recorded for a given binding slot.
     */
    UNKNOWN;

    /**
     * Returns whether this family represents a framebuffer binding operation.
     *
     * @return {@code true} for {@link #CORE_GL30}, {@link #ARB_FBO},
     *         {@link #EXT_FBO}, and {@link #OPENGLHELPER_WRAPPER};
     *         {@code false} otherwise
     */
    public boolean isFramebufferFamily() {
        return this == CORE_GL30 || this == ARB_FBO || this == EXT_FBO || this == OPENGLHELPER_WRAPPER;
    }

    /**
     * Returns whether this family represents a shader program binding operation.
     *
     * @return {@code true} for {@link #CORE_GL20} and
     *         {@link #ARB_SHADER_OBJECTS}; {@code false} otherwise
     */
    public boolean isShaderFamily() {
        return this == CORE_GL20 || this == ARB_SHADER_OBJECTS;
    }
}
