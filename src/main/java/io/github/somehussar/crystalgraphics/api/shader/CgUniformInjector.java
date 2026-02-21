package io.github.somehussar.crystalgraphics.api.shader;

/**
 * A callback that injects uniform values into a {@link CgShader}
 * during its bind phase.
 *
 * <p>Injectors are registered with
 * {@link io.github.somehussar.crystalgraphics.mc.shader.CgSystemUniformRegistry}
 * and executed in ascending priority order every time a managed shader binds.
 * Each injector should set only the uniforms it was registered for.</p>
 *
 * <p>The {@code uniformName} parameter contains the exact name the injector
 * was registered under. This allows a single lambda to serve multiple aliases:
 * the registry calls the same injector once per matching name, passing the
 * name each time.</p>
 *
 * <p>Implementations must be safe to call on every bind (potentially
 * every frame). They should be cheap and must not throw exceptions that
 * escape to the caller — the registry wraps each call in a try/catch
 * for safety, but injectors should still aim not to throw.</p>
 *
 * <p><strong>Not thread-safe.</strong> All calls occur on the render thread.</p>
 *
 * @see io.github.somehussar.crystalgraphics.mc.shader.CgSystemUniformRegistry
 */
public interface CgUniformInjector {

    /**
     * Injects uniform values into the given shader.
     *
     * <p>The shader is guaranteed to be compiled and bound when this method
     * is called. Implementations can use
     * {@link CgShader#getUniformLocation(String)} to look up uniform
     * locations and {@link CgShader#getProgram()} to access the
     * underlying {@link CgShaderProgram}
     * for setting uniform values.</p>
     *
     * @param shader      the currently bound managed shader (never null)
     * @param uniformName the uniform name this injector was registered under
     */
    void inject(CgShader shader, String uniformName);
}
