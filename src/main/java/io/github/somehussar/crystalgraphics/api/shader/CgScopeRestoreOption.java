package io.github.somehussar.crystalgraphics.api.shader;

/**
 * Modular opt-in options for expanding what GL state a {@link CgShaderScope}
 * restores when it closes.
 *
 * <p>By default, {@link CgShader#bindScoped()} only saves and restores
 * the current shader program (program-only restore). Callers that need
 * additional state protection can pass one or more {@code CgScopeRestoreOption}
 * values to {@link CgShader#bindScoped(CgScopeRestoreOption...)} to
 * opt in to broader state capture and restoration.</p>
 *
 * <h3>Example</h3>
 * <pre>{@code
 * // Default: program-only restore
 * try (CgShaderScope scope = shader.bindScoped()) {
 *     // ...
 * }
 *
 * // Opt-in: also save/restore FBO, textures, and active texture unit
 * try (CgShaderScope scope = shader.bindScoped(CgScopeRestoreOption.FULL_BINDINGS)) {
 *     // ...
 * }
 * }</pre>
 *
 * <h3>Extensibility</h3>
 * <p>New restore modules can be added as additional enum constants without
 * breaking existing callers that use the default (no-arg) overload.</p>
 *
 * @see CgShader#bindScoped()
 * @see CgShader#bindScoped(CgScopeRestoreOption...)
 */
public enum CgScopeRestoreOption {

    /**
     * Expands restore scope to include FBO bindings, texture bindings, and
     * the active texture unit, in addition to the shader program.
     *
     * <p>When this option is specified, the scope uses
     * {@link io.github.somehussar.crystalgraphics.gl.state.CgStateBoundary#save()}
     * and
     * {@link io.github.somehussar.crystalgraphics.gl.state.CgStateBoundary#restore(
     * io.github.somehussar.crystalgraphics.gl.state.CgStateSnapshot)}
     * to capture and restore the full tracked binding state (FBO draw/read,
     * shader program, texture bindings per unit, active texture unit).</p>
     */
    FULL_BINDINGS;
}
