package com.crystalgraphics.api.shader;

import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.gl.shader.CgShaderFactory;
import com.crystalgraphics.platform.gl.state.CgGlScope;

import java.util.List;
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
     * Binds this shader and returns a scope that restores the previously bound program on close.
     * Equivalent to {@code bindScoped(CgGlSlot.PROGRAM)}. Returns a no-op scope if not compiled.
     *
     * <p>Use with try-with-resources for safe, exception-proof binding:</p>
     * <pre>{@code
     * try (CgGlScope scope = shader.bindScoped()) {
     *     // render with shader
     * }
     * // shader is unbound here
     * }</pre>
     *
     * @return a closeable scope that restores the previous program state, or a no-op if not compiled
     * @see CgShader#bind()
     * @see CgShader#bindScoped(CgGlSlot...)
     */
    CgGlScope bindScoped();

    /**
     * Binds this shader and captures the specified GL state slots, restoring them on close.
     * Returns a no-op scope if not compiled. Behaves like {@link #bindScoped()} if no slots given.
     *
     * @param slots the GL state domains to capture and restore on scope close
     * @return a closeable scope that restores the appropriate GL state, or a no-op if not compiled
     */
    CgGlScope bindScoped(CgGlSlot... slots);

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
     * try (CgGlScope s = shader.applyBindings(b -> {
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

    // ── Source mutation ────────────────────────────────────────────────

    /**
     * Replaces the inline GLSL source for both shader stages and marks the
     * shader dirty, scheduling a recompile on the next {@link #bind()} call.
     *
     * <p>Both sources are updated atomically — partial updates are not
     * supported because vertex and fragment source must always be consistent
     * (e.g. matching {@code out}/{@code in} interface blocks).</p>
     *
     * <p>This method is the primary mutation point for node-graph codegen
     * workflows: generate a new vert/frag pair, push it here, and the shader
     * recompiles on the next frame boundary.</p>
     *
     * <p>Calling this on a path-based shader (created via
     * {@link CgShaderFactory#load})
     * overrides the file-based source permanently for the lifetime of this
     * handle — the paths are no longer consulted on subsequent recompiles.</p>
     *
     * @param vertexSource   new GLSL vertex shader source; must not be {@code null}
     * @param fragmentSource new GLSL fragment shader source; must not be {@code null}
     * @return {@code this} for chaining
     * @throws NullPointerException if either argument is {@code null}
     */
    CgShader setSource(String vertexSource, String fragmentSource);

    // ── Preprocessing ─────────────────────────────────────────────────

    /**
     * Attaches a preprocessor to this shader and marks it dirty, scheduling
     * a recompile on the next {@link #bind()} call.
     *
     * <p>The preprocessor is applied to the raw source at recompile time,
     * resolving {@code #include} directives, injecting {@code #define} lines,
     * and applying any header text. Pass {@code null} to clear the preprocessor
     * and recompile from raw source only.</p>
     *
     * @param pp the preprocessor to attach, or {@code null} to clear
     * @return {@code this} for chaining
     */
    CgShader preprocess(CgShaderPreprocessor pp);

    // ── Lifecycle ─────────────────────────────────────────────────────

    /**
     * Returns the error message from the last failed compilation attempt,
     * or {@code null} if the shader has never failed to compile or has not
     * been compiled yet.
     *
     * <p>The returned string contains the GL info log captured at the point
     * of failure (shader compile log or link log, whichever applies).
     * On a successful compile, this method always returns {@code null}.</p>
     *
     * <p>This method never throws; it always returns {@code null} or a
     * non-null string.</p>
     *
     * @return the compile/link error message from the last failed attempt,
     *         or {@code null} if the last compile succeeded or has not been
     *         attempted yet
     */
    String getLastCompileError();

    /**
     * Returns all active uniforms introspected from the compiled program.
     *
     * <p>Active uniforms are those reported by the GL driver via
     * {@code glGetActiveUniform} — uniforms that are referenced in the
     * linked program and have not been optimised away.  Built-in
     * {@code gl_*} uniforms are excluded from the result.</p>
     *
     * <p>Returns an empty list if the shader is not compiled
     * ({@link #isCompiled()} returns {@code false}).</p>
     *
     * @return an unmodifiable list of active uniforms; never {@code null}
     * @see CgActiveUniform
     */
    List<CgActiveUniform> getActiveUniforms();

    /**
     * Forces immediate recompilation from current sources.
     *
     * <p>Equivalent to calling {@link #markDirty()} and then immediately triggering
     * the recompile that would normally happen on the next {@link #bind()} call.
     * Safe to call from any context — if compilation fails, {@link #isCompiled()}
     * returns {@code false} and the error is available via {@link #getLastCompileError()}.
     * On success, the old program is replaced in-place; the {@link CgShader} object
     * reference remains stable.</p>
     *
     * <p>Use this instead of recreating the shader object when you need an immediate
     * relink rather than a deferred one.</p>
     */
    void recompile();

    /**
     * Deletes the underlying program (if any) and releases resources.
     *
     * <p>After this call, {@link #isCompiled()} returns {@code false} and
     * the handle should not be reused.</p>
     */
    void delete();
}
