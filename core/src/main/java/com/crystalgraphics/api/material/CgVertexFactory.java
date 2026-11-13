package com.crystalgraphics.api.material;

import com.github.bsideup.jabel.Desugar;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What draws a {@code #domain surface} material, and so where its vertices come from: Unreal's vertex factory. A
 * material names one ({@code Material { VertexFactory Sprites }}, {@code Mesh} by default); the engine compiles its
 * program against that factory's vertex format and fills the {@code CgVertex} its {@code vertex()} receives.
 *
 * <p>The guide, with what each factory hands a material and a file of your own: {@code docs/MATERIAL_FORMAT.md}
 * § 5.</p>
 *
 * <p>A factory is a file, found by its name the first time a material names it: {@code Sprites} is
 * {@code crystalgraphics:shaders/factories/Sprites.factory}, {@code mymod:Ribbons} is
 * {@code mymod:shaders/factories/Ribbons.factory} (its shape: {@code CgFormatTemplates}). The engine's are files:
 * {@code Mesh}, {@code Procedural}, {@code Sprites}, {@code MeshParticles}, {@code Tube}, {@code TubeClosed},
 * {@code TubeVolume}, {@code VfxMesh}, {@code Billboard}, {@code Sequence}, {@code Ribbons}, {@code PathRibbons}. This class registers one made in Java, which a file of the same name never replaces:</p>
 *
 * <pre>{@code
 * // A factory built at run time, registered before any material naming it is parsed:
 * CgVertexFactory.register(CgVertexFactory.builder("Ribbons")
 *         .vertexType("none")
 *         .buffers("myribbons")                                    // engine buffer tokens its draws bind
 *         .declarations("#include \"mymod:shaders/ribbon.glsl\"\nRibbonPoint cg_Ribbon;")
 *         .fill("""
 *                 cg_Ribbon = ribbon_point(CG_VERTEX_ID);
 *                 v.position = cg_Ribbon.local; v.normal = cg_Ribbon.normal; v.color = vec4(1.0);
 *                 v.uv0 = cg_Ribbon.uv; v.uv1 = v.uv0; v.uv2 = v.uv0; v.uv3 = v.uv0;
 *                 v.world = cg_Ribbon.world;
 *                 """)
 *         .parameters("cg_Ribbon", Map.of("width", "float", "segment", "flat int"))
 *         .build());
 * }</pre>
 *
 * <p>A material then reads the factory's parameters by name, in {@code vertex()} and {@code surface()} alike; each
 * field {@code surface()} names is passed to the fragment as an interpolator, and only those:</p>
 *
 * <pre>{@code
 * Material { VertexFactory Ribbons }
 * void surface(in v2f i, inout CgSurface s) { s.baseColor = vec3(cg_Ribbon.width); }
 * }</pre>
 *
 * <p>A material drawn by several factories lists them, the first the base; each other is compiled under its usage
 * keyword, which the material's {@code vertex()} and {@code surface()} branch on:</p>
 *
 * <pre>{@code
 * Material { VertexFactory Mesh, Tube }            // Tube's file: Factory { ... Usage FX_ON_TUBE }
 * void vertex(inout CgVertex v, out v2f o) {
 * #ifdef FX_ON_TUBE
 *     o.along = cg_Tube.ring.arc;
 * #else
 *     o.along = v.uv0.x;
 * #endif
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Something must draw it: a factory's file or registration says what it reads (buffers, properties, tags); the
 *       renderer that binds them is Java (the particle and tube factories' are the VFX engine's).</li>
 *   <li>{@code fill} runs in the vertex stage with {@code CgVertex v} declared; it sets every field, {@code world}
 *       last, and the parameters struct. The vertex format's attributes ({@code cg_Position}, ...), the engine's tokens'
 *       macros and the declarations are in scope.</li>
 *   <li>{@code v.normal} is world-space, {@code v.world} camera-relative.</li>
 *   <li>A parameter field typed {@code vec4[4]} passes as four interpolators; an integer field must be {@code flat}.</li>
 *   <li>A name registered twice throws.</li>
 *   <li>Factories listed together share a vertex type; at most one has parameters.</li>
 * </ul>
 */
public final class CgVertexFactory {

    /**
     * One factory; made with {@link #builder(String)}.
     *
     * @param name            what {@code VertexFactory} names
     * @param vertexType      the {@code CgVertexFormat} key its program reads: {@code spatial} for a mesh, {@code none}
     *                        where nothing is fetched
     * @param fill            GLSL statements filling {@code CgVertex v} (and the parameters) in the vertex stage
     * @param placesNothing   whether the material must place each vertex itself, with a {@code vertex()} assigning
     *                        {@code v.world}
     * @param engineBuffers   the engine buffers ({@code CgEngineBufferRegistry} tokens) its draws bind
     * @param declarations    GLSL at file scope, both stages, before the material's code: includes, the parameters
     *                        struct's variable
     * @param properties      {@code Properties { }} entries its draws bind (a material may not declare them again)
     * @param tags            material-level tags its renderer reads (a tube's {@code "Tube"})
     * @param parameters      the variable a material reads its parameters from ({@code cg_Particle}); null for none
     * @param parameterFields each field of it a fragment may read, dotted for a nested one, to its v2f type
     * @param defaults        GLSL statements on {@code CgSurface s} in the fragment, before the material's
     *                        {@code surface()}: what it starts from under this factory (a particle's light)
     * @param fade            an expression fading all the material adds, applied after {@code surface()} by its blend
     *                        mode ({@code cg_Vfx.presence}); null for none
     * @param usage           the keyword its draws compile the material under ({@code FX_ON_TUBE}): what a material on
     *                        several factories branches on with {@code #ifdef}; null for none
     */
    @Desugar
    public record Factory(String name, String vertexType, String fill, boolean placesNothing,
                          List<String> engineBuffers, String declarations, String properties, Map<String, String> tags,
                          @Nullable String parameters, Map<String, String> parameterFields, String defaults,
                          @Nullable String fade, @Nullable String usage) {
        public Factory {
            engineBuffers = Collections.unmodifiableList(new ArrayList<>(engineBuffers));
            tags = Collections.unmodifiableMap(new LinkedHashMap<>(tags));
            parameterFields = Collections.unmodifiableMap(new LinkedHashMap<>(parameterFields));
        }
    }

    /** Builds a {@link Factory}: {@code vertexType} and {@code fill} are required. */
    public static final class Builder {
        private final String name;
        private String vertexType, fill, declarations = "", properties = "", parameters, defaults = "", fade, usage;
        private boolean placesNothing;
        private final List<String> buffers = new ArrayList<>();
        private final Map<String, String> tags = new LinkedHashMap<>(), fields = new LinkedHashMap<>();

        private Builder(String name) {
            this.name = name;
        }

        /** The {@code CgVertexFormat} key its program reads: {@code spatial}, or {@code none} where nothing is fetched. */
        public Builder vertexType(String type) {
            this.vertexType = type;
            return this;
        }

        /** GLSL statements filling {@code CgVertex v} and the parameters, in the vertex stage. */
        public Builder fill(String glsl) {
            this.fill = glsl;
            return this;
        }

        /** The material must place each vertex itself: a {@code vertex()} assigning {@code v.world}. */
        public Builder placesNothing() {
            this.placesNothing = true;
            return this;
        }

        /** Engine buffer tokens its draws bind. */
        public Builder buffers(String... tokens) {
            buffers.addAll(List.of(tokens));
            return this;
        }

        /** GLSL at file scope in both stages, before the material's code. */
        public Builder declarations(String glsl) {
            this.declarations = glsl;
            return this;
        }

        /** {@code Properties { }} entries its draws bind, as a {@code .shader} writes them. */
        public Builder properties(String entries) {
            this.properties = entries;
            return this;
        }

        /** A material-level tag its renderer reads. */
        public Builder tag(String key, String value) {
            tags.put(key, value);
            return this;
        }

        /** The parameters variable and each field a fragment may read, to its v2f type ({@code "flat int"}). */
        public Builder parameters(String variable, Map<String, String> fields) {
            this.parameters = variable;
            this.fields.putAll(fields);
            return this;
        }

        /** GLSL statements on {@code CgSurface s}, before the material's {@code surface()}. */
        public Builder defaults(String glsl) {
            this.defaults = glsl;
            return this;
        }

        /** An expression fading all the material adds, by its blend mode, after {@code surface()}. */
        public Builder fade(String expression) {
            this.fade = expression;
            return this;
        }

        /** The keyword its draws compile the material under: {@code FX_ON_TUBE}. */
        public Builder usage(String keyword) {
            this.usage = keyword;
            return this;
        }

        public Factory build() {
            if (vertexType == null || fill == null) throw new IllegalStateException(name + ": vertexType and fill are required");
            return new Factory(name, vertexType, fill, placesNothing, buffers, declarations, properties, tags, parameters,
                    fields, defaults, fade, usage);
        }
    }

    /** A mesh component's vertices: static, instanced or skinned. */
    public static final String MESH = "Mesh";

    /** No mesh: a draw of a vertex count, each vertex placed by the material's {@code vertex()}. */
    public static final String PROCEDURAL = "Procedural";

    private static final Map<String, Factory> FACTORIES = new LinkedHashMap<>();

    private CgVertexFactory() {}

    /** A builder for a factory named {@code name}. */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** Registers {@code factory} under its name. */
    public static synchronized void register(Factory factory) {
        if (FACTORIES.putIfAbsent(factory.name(), factory) != null) {
            throw new IllegalStateException("vertex factory '" + factory.name() + "' is already registered");
        }
    }

    /** The factory registered under that name, or null: a file's is found by the parse, not here. */
    @Nullable
    public static synchronized Factory get(String name) {
        return FACTORIES.get(name);
    }

    /** Every registered factory, in registration order. */
    public static synchronized List<Factory> all() {
        return List.copyOf(FACTORIES.values());
    }
}
