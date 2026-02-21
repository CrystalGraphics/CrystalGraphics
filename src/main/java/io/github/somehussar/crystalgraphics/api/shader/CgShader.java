package io.github.somehussar.crystalgraphics.api.shader;

import io.github.somehussar.crystalgraphics.api.CgShaderProgram;

import java.util.function.Consumer;

/**
 * A managed shader handle that wraps a {@link CgShaderProgram} with
 * lifecycle management: compilation, failure tracking, and reload support.
 *
 * <p>Managed shaders provide safe semantics for modpack environments:</p>
 * <ul>
 *   <li>Compilation failures set {@link #isCompiled()} to {@code false}
 *       and log the error — they never throw to the caller.</li>
 *   <li>When {@code compiled == false}, {@link #bind()} is a pure no-op
 *       that does not mutate GL state.</li>
 *   <li>{@link #markDirty()} schedules a recompile on the next
 *       {@link #bind()} call, avoiding mid-render program swaps.</li>
 * </ul>
 */
public interface CgShader {

    /**
     * Returns whether the shader is currently compiled and ready for use.
     *
     * @return {@code true} if a valid program exists; {@code false} if
     *         compilation has not been attempted, or the last attempt failed
     */
    boolean isCompiled();

    /**
     * Binds the compiled shader program for rendering.
     *
     * <p>If the shader is marked dirty, recompilation is attempted first.
     * If {@link #isCompiled()} is {@code false} (either before first compile
     * or after a failed compile/reload), this method is a no-op: it does
     * <strong>not</strong> force {@code glUseProgram(0)} and does not mutate
     * GL state in any way.</p>
     */
    void bind();

    /**
     * Unbinds the shader program by binding program 0.
     *
     * <p>If {@link #isCompiled()} is {@code false}, this is a no-op.</p>
     */
    void unbind();

    /**
     * Marks the shader as dirty, scheduling a recompile on the next
     * {@link #bind()} call.
     *
     * <p>This is the preferred way to trigger a reload: it avoids deleting
     * and swapping programs mid-render by deferring the recompile to a safe
     * point (the start of the next bind).</p>
     */
    void markDirty();

    /**
     * Returns the underlying {@link CgShaderProgram}, or {@code null} if
     * the shader has not been successfully compiled.
     *
     * <p>Callers should prefer {@link #bind()}/{@link #unbind()} over
     * direct program access.  This accessor exists for uniform location
     * queries and other low-level operations.</p>
     *
     * @return the compiled program, or {@code null}
     */
    CgShaderProgram getProgram();

    /**
     * Returns the cached uniform location for the given name in the
     * currently compiled program.
     *
     * <p>Locations are cached per compiled program instance and
     * automatically invalidated when the program is recompiled, cleared,
     * or deleted.  This method is the preferred way for patch layers and
     * external callers to obtain uniform locations without repeated raw
     * {@code glGetUniformLocation} calls.</p>
     *
     * <p>If the shader is not compiled ({@link #isCompiled()} returns
     * {@code false}), this method returns {@code -1}.</p>
     *
     * @param name the uniform name as declared in the GLSL source
     * @return the uniform location (non-negative), or {@code -1} if
     *         the shader is not compiled or the uniform does not exist
     * @throws IllegalArgumentException if {@code name} is null
     */
    int getUniformLocation(String name);

    /**
     * Returns the cache key that identifies this managed shader.
     *
     * @return the cache key (never null)
     */
    CgShaderCacheKey getCacheKey();

    /**
     * Binds this shader and returns a scope that automatically restores the
     * previously active shader program when the scope closes.
     *
     * <p>If the shader is not compiled, returns a no-op scope that does nothing.
     * Otherwise:
     * <ol>
     *   <li>Saves the currently active program</li>
     *   <li>Binds this shader's program</li>
     *   <li>Applies accumulated bindings</li>
     *   <li>Returns a scope that restores the saved program on close</li>
     * </ol></p>
     *
     * <p>Use with try-with-resources for safe, exception-proof binding:</p>
     * <pre>{@code
     * try (CgShaderScope scope = shader.bindScoped()) {
     *     // render with shader
     * }
     * // shader is unbound here
     * }</pre>
     *
     * @return a closeable scope that restores the previous program state, or a no-op if not compiled
     * @see CgShader#bind()
     * @see CgShader#bindScoped(CgScopeRestoreOption...)
     */
    CgShaderScope bindScoped();

    /**
     * Binds this shader and returns a scope that restores GL state according to
     * the provided options.
     *
     * <p>If no options are provided or {@link CgScopeRestoreOption#FULL_BINDINGS}
     * is not present, behaves identically to {@link #bindScoped()}.</p>
     *
     * <p>If {@link CgScopeRestoreOption#FULL_BINDINGS} is present, saves the entire
     * GL state (framebuffer, texture bindings, active texture unit, and shader program)
     * via {@link io.github.somehussar.crystalgraphics.gl.state.CgStateBoundary} and
     * restores it on scope close. This is more expensive but necessary when rendering
     * to off-screen framebuffers or complex multi-pass effects.</p>
     *
     * @param options optional restore scope options (if any contain FULL_BINDINGS, full state is saved)
     * @return a closeable scope that restores the appropriate GL state, or a no-op if not compiled
     * @see CgScopeRestoreOption#FULL_BINDINGS
     */
    CgShaderScope bindScoped(CgScopeRestoreOption... options);

    /**
     * Returns the bindings container for this shader.
     *
     * <p>Bindings accumulated in this container are applied automatically every
     * time {@link #bind()} or {@link #bindScoped()} is called. This is the
     * recommended way to set per-frame or persistent uniforms/samplers.</p>
     *
     * <p>The same bindings instance is returned across multiple calls; modifications
     * persist across bind invocations.</p>
     *
     * @return the shader's bindings container (never null)
     * @see CgShaderBindings
     */
    CgShaderBindings bindings();

    /**
     * Writes ephemeral bindings via the supplied consumer and returns {@code this}
     * for chaining into {@link #bind()} or {@link #bindScoped()}.
     *
     * <p>Ephemeral bindings are applied on the very next {@link #bind()} /
     * {@link #bindScoped()} call and then automatically cleared, so they never
     * bleed into subsequent frames. Use {@link #bindings()} for persistent
     * per-shader state instead.</p>
     *
     * <pre>{@code
     * try (CgShaderScope s = shader.applyBindings(b -> {
     *     b.set1f("noiseSize", outline.noiseSize);
     *     b.argbColor("innerColor", outline.innerColor);
     * }).bindScoped()) {
     *     // render
     * }
     * }</pre>
     *
     * @param consumer receives the ephemeral {@link CgShaderBindings} to populate
     * @return {@code this} for chaining
     */
    CgShader applyBindings(Consumer<CgShaderBindings> consumer);

    // ── Lifecycle ─────────────────────────────────────────────────────

    /**
     * Deletes the underlying program (if any) and releases resources.
     *
     * <p>After this call, {@link #isCompiled()} returns {@code false} and
     * the handle should not be reused.</p>
     */
    void delete();
}
