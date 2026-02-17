package io.github.somehussar.crystalgraphics;

/**
 * Very high-level abstraction of required render capabilities.
 * Lower level handling is done elsewhere.
 */
public enum RenderCapability {
    // General OpenGL capabilities
    INSTANCED_RENDERING,
    VERTEX_ARRAY_OBJECTS,


    // FBO Capabilities
    FRAMEBUFFER_OBJECTS,
    MULTIPLE_RENDER_TARGETS,
    STENCIL_BUFFERS,

    // Shader capabilities
    BASIC_SHADER_SUPPORT;
}
