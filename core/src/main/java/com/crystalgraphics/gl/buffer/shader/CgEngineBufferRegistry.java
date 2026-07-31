package com.crystalgraphics.gl.buffer.shader;

import com.crystalgraphics.gl.render.CgCurveRenderer;
import com.crystalgraphics.gl.render.CgQuadRenderer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Engine-provided shader buffers a {@code .shader} can opt into with {@code #pragma cg_use <token>}.
 *
 * <h3>Why an opt-in registry rather than {@code cg_env.glsl}</h3>
 * <p>{@code CgFrameBlock} and {@code CgObjectDataBuffer} are declared unconditionally in
 * {@code cg_env.glsl} because effectively every shader wants them. A quad-instance buffer is not in
 * that class — only a small minority of shaders draw through {@code CgQuadRenderer} — so declaring it
 * everywhere would spend a texture unit on the TBO path for shaders that never read it.</p>
 *
 * <p>The alternative that was in use before this registry — every caller remembering to call
 * {@code CgQuadRenderer.attachTo(material)} in Java — put the requirement arbitrarily far from the
 * shader that actually creates it, and failed in a way that pointed at the wrong thing entirely: the
 * buffer is only attached on a material's first {@code useMaterial()}, so anything that compiled the
 * shader earlier (notably {@code CgMaterial.enableKeyword}, which recompiles on the spot when the
 * shader has not been parsed yet) produced GLSL with {@code QUAD_DATA} undeclared. The resulting
 * compile failure left the parsed feature list empty, and surfaced as
 * {@code "Keyword 'X' is not declared as #pragma cg_feature in this shader"} — naming a pragma that
 * was present and correct. Declaring the dependency in the shader removes the ordering question
 * altogether: the buffer is attached as part of parsing, before anything can compile.</p>
 *
 * <h3>Built-ins are seeded here, by method reference</h3>
 * <p>Providers hold a {@link Supplier}, not a buffer, and the built-in {@code quad} entry below is
 * seeded with {@code CgQuadRenderer::instanceBuffer}. A method reference does <em>not</em> trigger
 * that class's static initialization, and {@code MACRO_NAME} is a compile-time constant that gets
 * inlined — so seeding the token costs nothing and, crucially, does not allocate
 * {@code CgQuadRenderer}'s shared buffer, which is only valid once {@code CgRenderPipeline.init()}
 * has run. The supplier is invoked at attach time, when a GL context exists.</p>
 *
 * <p>That laziness is why the table lives here rather than each provider registering from its own
 * static initializer. Self-registration only takes effect once something has touched the provider
 * class, which nothing guarantees before a shader naming the token is parsed — {@code initContext}
 * itself compiles {@code text.shader}, which declares {@code cg_use quad}. Fixing that needs an
 * explicit "initialize this class now" call at exactly the right point in startup, i.e. correctness
 * coupled to init order. Seeding here has no ordering requirement at all: this class initializes on
 * the parser's first lookup, which is precisely when a token is needed.</p>
 */
public final class CgEngineBufferRegistry {

    /** Insertion-ordered so error messages list tokens in a stable, predictable order. */
    private static final Map<String, Provider> PROVIDERS = new LinkedHashMap<>();

    static {
        register("quad", CgQuadRenderer::instanceBuffer, CgQuadRenderer.MACRO_NAME);
        register("curve", CgCurveRenderer::instanceBuffer, CgCurveRenderer.MACRO_NAME);
    }

    private CgEngineBufferRegistry() {}

    /**
     * Registers an engine buffer under {@code token}, usable as {@code #pragma cg_use <token>}.
     *
     * <p>Intended for engine subsystems; a mod adding its own shared buffer can use it too, as long
     * as it registers before any shader referencing the token is parsed.</p>
     *
     * @param token     the name shaders will write in the pragma
     * @param buffer    resolved lazily, at attach time — pass a method reference so registration
     *                  does not force the owning class to initialize
     * @param macroName the {@code attach()} macro the shader's GLSL refers to (e.g. {@code QUAD_DATA})
     * @throws IllegalStateException if {@code token} is already registered
     */
    public static synchronized void register(String token, Supplier<CgShaderBuffer> buffer, String macroName) {
        Provider existing = PROVIDERS.putIfAbsent(token, new Provider(token, buffer, macroName));
        if (existing != null) {
            throw new IllegalStateException("cg_use token '" + token + "' is already registered");
        }
    }

    /** The provider for {@code token}, or {@code null} if none is registered. */
    public static Provider get(String token) {
        return PROVIDERS.get(token);
    }

    /** Every registered token, for error messages. */
    public static Set<String> tokens() {
        return Collections.unmodifiableSet(PROVIDERS.keySet());
    }

    /**
     * One engine buffer a shader can request.
     *
     * @param token     the name used in {@code #pragma cg_use <token>}
     * @param buffer    resolved lazily — see the class doc for why this is not the buffer itself
     * @param macroName the {@code attach()} macro the shader's GLSL refers to (e.g. {@code QUAD_DATA})
     */
    public record Provider(String token, Supplier<CgShaderBuffer> buffer, String macroName) {
    }
}
