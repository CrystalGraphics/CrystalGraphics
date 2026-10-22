package com.crystalgraphics.gl.buffer.shader;

import com.crystalgraphics.api.material.CgParameterCollection;
import com.crystalgraphics.gl.render.CgClipTable;
import com.crystalgraphics.gl.render.CgShapeTable;
import com.crystalgraphics.gl.render.CgQuadRenderer;
import com.crystalgraphics.gl.render.CgVectorRenderer;
import com.crystalgraphics.render.property.CgPalette;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
 * {@code CgQuadRenderer}'s shared buffer, which is only valid once {@code CgGraphicsLifecycle.initContext}
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
        register("palette", CgPalette::buffer, CgPalette.MACRO_NAME,
                "crystalgraphics:shaders/env/buffer/palette.glsl");
        // A quad or curve is positioned through its spatial node, so either brings the palette with it.
        register("quad", CgQuadRenderer::instanceBuffer, CgQuadRenderer.MACRO_NAME,
                "crystalgraphics:shaders/env/buffer/quad.glsl", "palette");
        register("curve", CgVectorRenderer::instanceBuffer, CgVectorRenderer.MACRO_NAME,
                "crystalgraphics:shaders/env/buffer/curve.glsl", "palette");
        register("clip", CgClipTable::buffer, CgClipTable.MACRO_NAME,
                "crystalgraphics:shaders/env/buffer/clip.glsl", "palette");
        register("shape", CgShapeTable::buffer, CgShapeTable.MACRO_NAME,
                "crystalgraphics:shaders/env/buffer/shape.glsl");
        // A particle is read through the culled, sorted order Range writes, so the order comes with it.
        register("particle_order", CgParticleOrderBuffer::buffer, CgParticleOrderBuffer.MACRO_NAME, null);
        // A beam draw's noise points, for the strip factory a particle program gets beside its own.
        register("particle_beams", CgParticleBeamBuffer::buffer, CgParticleBeamBuffer.MACRO_NAME, null);
        // The sprite factory's settings, the renderer's to set per draw: a port never declares them.
        registerWithProperties("particle", CgParticleBuffer::buffer, CgParticleBuffer.MACRO_NAME,
                "crystalgraphics:shaders/env/buffer/particle.glsl", """
                    _cg_Facing ("FX_FACE_* + 8 * FX_ALIGN_*: Niagara's facing and alignment", float) = 0
                    _cg_FacingVector ("The CUSTOM facing's vector, Niagara's Particles.SpriteFacing", vec4) = (1, 0, 0, 0)
                    _cg_AlignmentVector ("The CUSTOM alignment's vector, Niagara's Particles.SpriteAlignment", vec4) = (1, 0, 0, 0)
                    _cg_Stretch ("ALIGNED facing: how much longer per block a second; 0 unstretched, as Niagara", float) = 0
                    _cg_MaxStretch ("ALIGNED facing: longest, in widths", float) = 4.0
                    _cg_Size ("The drawn extent, a sprite's or a strip's width, times this; never the simulated size", float) = 1
                    _cg_CameraOffset ("Drawn this many blocks toward the eye, Niagara's Particles.CameraOffset", float) = 0
                    _cg_RotationBias ("Radians added to each sprite's rotation: Cascade's axis lock bias", float) = 0
                    _cg_Drawn ("Drawn slots its pool writes: dynamic parameters 0-3 a bit each, 32 sub-image, 64 camera offset", float) = 0
                    _cg_SubImage ("CgVfxFlipbook: columns (a table's frames), rows (0: a table), blends, random start", vec4) = (1, 1, 1, 0)
                    _cg_SubUV ("CgVfxFlipbook: mode, first frame, last frame (-1: the last), rate", vec4) = (0, 0, -1, 0)
                    _cg_SubImages ("CgVfxFlipbook: a rect table, a rect a texel", sampler2D) = "white"
                    """, "particle_order", "particle_beams");
        register("rows", CgPropertyRows::buffer, CgPropertyRows.MACRO_NAME,
                "crystalgraphics:shaders/env/buffer/rows.glsl");
        registerGenerated("globals", CgParameterCollectionBuffer::buffer, CgParameterCollectionBuffer.MACRO_NAME,
                "crystalgraphics:shaders/env/buffer/globals.glsl", CgParameterCollection::glsl);
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
        register(token, buffer, macroName, null);
    }

    /**
     * As {@link #register(String, Supplier, String)}, with a GLSL file of macros written against the
     * buffer — what {@code CG_QUAD_*} is to {@code QUAD_DATA}.
     *
     * <p>The compiler emits an {@code #include} of it immediately after the buffer's own declaration,
     * so the macros always have the struct to read and a shader that never declares the token never
     * pays for either. Convention is {@code shaders/env/buffer/<token>.glsl}; a mod's own buffer gets
     * the same treatment by passing its own path.</p>
     *
     * @param envPath a {@code CgIO} resource path, or null for a buffer whose declaration is all there
     *                is to say
     */
    public static synchronized void register(String token, Supplier<CgShaderBuffer> buffer, String macroName,
                                             String envPath, String... requires) {
        put(new Provider(token, buffer, macroName, envPath, List.of(requires), null, false, null));
    }

    /**
     * As {@link #register(String, Supplier, String, String, String...)}, with properties every shader declaring the
     * token takes as its own: a {@code Properties { }} body, each name with the reserved {@code _cg_} prefix. Under
     * {@code cg_use rows} they are row properties like any other, so whoever draws sets them per draw.
     *
     * <pre>{@code
     * CgEngineBufferRegistry.registerWithProperties("myfactory", MyBuffer::buffer, "MY_DATA",
     *         "mymod:shaders/env/myfactory.glsl", "_cg_Spin (\"Turns a second\", float) = 0");
     * }</pre>
     */
    public static synchronized void registerWithProperties(String token, Supplier<CgShaderBuffer> buffer,
                                                           String macroName, String envPath, String properties,
                                                           String... requires) {
        put(new Provider(token, buffer, macroName, envPath, List.of(requires), null, false, properties));
    }

    /**
     * As {@link #register(String, Supplier, String, String, String...)}, with GLSL made from what is registered when a
     * shader compiles, appended after the env file: {@code globals}' place for each parameter. A shader reading it is
     * tracked by {@link Provider#generatedPath()}, so {@code CgAssetReloader.changed} on that path recompiles it. No
     * executor owns its binding, so each draw's snapshot binds it ({@link Provider#perDraw()}).
     */
    public static synchronized void registerGenerated(String token, Supplier<CgShaderBuffer> buffer, String macroName,
                                                      String envPath, Supplier<String> generated) {
        put(new Provider(token, buffer, macroName, envPath, List.of(), generated, true, null));
    }

    private static void put(Provider provider) {
        for (String required : provider.requires()) {
            if (!PROVIDERS.containsKey(required)) {
                throw new IllegalStateException("cg_use token '" + provider.token() + "' requires unregistered '"
                        + required + "'");
            }
        }
        if (PROVIDERS.putIfAbsent(provider.token(), provider) != null) {
            throw new IllegalStateException("cg_use token '" + provider.token() + "' is already registered");
        }
    }

    /**
     * {@code declared} with every token a declared one requires, each placed before the first that needs it, so its
     * declaration and macros come first. Unknown tokens pass through for the parser to report.
     */
    public static synchronized List<String> withRequirements(List<String> declared) {
        List<String> out = new ArrayList<>(declared.size() + 1);
        for (String token : declared) addWithRequirements(token, out);
        return out;
    }

    private static void addWithRequirements(String token, List<String> out) {
        if (out.contains(token)) return;
        Provider provider = PROVIDERS.get(token);
        if (provider != null) for (String required : provider.requires()) addWithRequirements(required, out);
        out.add(token);
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
    /**
     * @param envPath   GLSL macros written against this buffer, included right after its declaration,
     *                  or null when the declaration is all there is
     * @param generated GLSL made at each compile, after the env file, or null
     * @param perDraw   bound by each draw's binding snapshot, as a material's own attached buffer is, rather than left to
     *                  an executor: a texture-buffer unit does not survive from the upload to the draw
     * @param properties a {@code Properties { }} body every declaring shader takes, names {@code _cg_}-prefixed, or null
     */
    public record Provider(String token, Supplier<CgShaderBuffer> buffer, String macroName, String envPath,
                           List<String> requires, Supplier<String> generated, boolean perDraw, String properties) {

        /** What a shader reading {@link #generated} is tracked by: no file, a name for {@code CgAssetReloader}. */
        public String generatedPath() {
            return "crystalgraphics:shaders/env/buffer/" + token + ".generated";
        }
    }
}
