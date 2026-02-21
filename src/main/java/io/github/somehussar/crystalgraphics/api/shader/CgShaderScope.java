package io.github.somehussar.crystalgraphics.api.shader;

/**
 * A scoped binding context for a managed shader.
 *
 * <p>This interface is returned by {@link CgShader#bindScoped()} and
 * provides automatic scope-based binding and restoration of shader state.
 * It implements {@code AutoCloseable} to support try-with-resources patterns.</p>
 *
 * <p>Typical usage:</p>
 * <pre>{@code
 * try (CgShaderScope scope = shader.bindScoped()) {
 *     // Render with this shader...
 *     // ... draw calls ...
 * }
 * // Previous program is automatically restored here
 * }</pre>
 *
 * <p>If the shader is not compiled, the scope is a pure no-op.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Not thread-safe. Must be used on the render thread.</p>
 */
public interface CgShaderScope extends AutoCloseable {

    /**
     * Closes the scope and restores the previous program.
     *
     * <p>This method is called automatically when exiting a try-with-resources
     * block. It is safe to call multiple times (subsequent calls are no-ops).</p>
     */
    @Override
    void close();
}
