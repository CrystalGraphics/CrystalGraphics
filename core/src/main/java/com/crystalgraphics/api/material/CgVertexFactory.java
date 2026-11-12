package com.crystalgraphics.api.material;

import com.github.bsideup.jabel.Desugar;

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
 * <pre>{@code
 * // A factory of a mod's own, registered before any material naming it is parsed:
 * CgVertexFactory.register(new CgVertexFactory.Factory("Ribbons", "none",
 *         "v.position = ribbon_position(CG_VERTEX_ID); v.normal = vec3(0.0, 1.0, 0.0); v.color = vec4(1.0);\n"
 *         + "v.uv0 = ribbon_uv(CG_VERTEX_ID); v.uv1 = v.uv0; v.uv2 = v.uv0; v.uv3 = v.uv0;\n"
 *         + "v.world = (CG_OBJECT_TO_WORLD * vec4(v.position, 1.0)).xyz;",
 *         false, List.of("myribbons")));
 * }</pre>
 *
 * <ul>
 *   <li>The engine registers {@code Mesh} and {@code Procedural}; the VFX engine registers its particle and tube
 *       factories.</li>
 *   <li>{@code fill} runs in the vertex stage with {@code CgVertex v} declared; it sets every field, {@code world}
 *       last. The vertex format's attributes ({@code cg_Position}, ...) and the engine's tokens' macros are in scope.</li>
 *   <li>A name registered twice throws.</li>
 * </ul>
 */
public final class CgVertexFactory {

    /**
     * One factory.
     *
     * @param name          what {@code VertexFactory} names
     * @param vertexType    the {@code CgVertexFormat} key its program reads: {@code spatial} for a mesh, {@code none}
     *                      where nothing is fetched
     * @param fill          GLSL statements filling {@code CgVertex v} in the vertex stage
     * @param placesNothing whether the material must place each vertex itself, with a {@code vertex()} assigning
     *                      {@code v.world}
     * @param engineBuffers the engine buffers ({@code CgEngineBufferRegistry} tokens) its draws bind
     */
    @Desugar
    public record Factory(String name, String vertexType, String fill, boolean placesNothing,
                          List<String> engineBuffers) {
        public Factory {
            engineBuffers = Collections.unmodifiableList(new ArrayList<>(engineBuffers));
        }
    }

    /** A mesh component's vertices: static, instanced or skinned. */
    public static final String MESH = "Mesh";

    /** No mesh: a draw of a vertex count, each vertex placed by the material's {@code vertex()}. */
    public static final String PROCEDURAL = "Procedural";

    private static final Map<String, Factory> FACTORIES = new LinkedHashMap<>();

    static {
        register(new Factory(MESH, "spatial", """
                v.position = cg_Position;
                v.normal = cg_Normal;
                v.color = cg_Color;
                v.uv0 = cg_TexCoord0;
                v.uv1 = cg_TexCoord1;
                v.uv2 = cg_TexCoord2;
                v.uv3 = cg_TexCoord3;
                v.world = (CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0)).xyz;
                """, false, List.of()));
        register(new Factory(PROCEDURAL, "none", """
                v.position = vec3(0.0);
                v.normal = vec3(0.0, 1.0, 0.0);
                v.color = vec4(1.0);
                v.uv0 = vec2(0.0);
                v.uv1 = vec2(0.0);
                v.uv2 = vec2(0.0);
                v.uv3 = vec2(0.0);
                v.world = vec3(0.0);
                """, true, List.of()));
    }

    private CgVertexFactory() {}

    /** Registers {@code factory} under its name. */
    public static synchronized void register(Factory factory) {
        if (FACTORIES.putIfAbsent(factory.name(), factory) != null) {
            throw new IllegalStateException("vertex factory '" + factory.name() + "' is already registered");
        }
    }

    /** The factory of that name, or null. */
    public static synchronized Factory get(String name) {
        return FACTORIES.get(name);
    }

    /** Every registered factory's name, in registration order. */
    public static synchronized List<String> names() {
        return List.copyOf(FACTORIES.keySet());
    }
}
