package com.crystalgraphics.shadergraph;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a shader graph terminates in: the stage outputs, and the settings a {@code .shader} carries that
 * no ordinary node has anywhere to put.
 *
 * <h3>Two jobs, and the second is why this is a class rather than another template</h3>
 * <p>Its ports are the stage outputs — {@code Position} for the vertex stage, {@code BaseColor} for the
 * fragment stage. But a {@code .shader} also carries {@code #type}, {@code Queue}, {@code Tags} and
 * {@code Properties}, and a graph has had nowhere to keep them. Unity's Master Stack is the same idea:
 * the terminus is also the settings panel.</p>
 *
 * <p>That answers the blackboard question 6.2.5 deferred without inventing a second concept — graph-level
 * settings live on the node that represents the graph's result.</p>
 *
 * <h3>Its ports have stages, and that is what drives the split</h3>
 * <p>{@code Position} is vertex-stage and {@code BaseColor} is fragment-stage <em>by definition</em>, so
 * rooting a compile at one or the other is what tells {@link CgShaderEmitter} which stage it is emitting.
 * The domain rules are not applied to the master; they radiate <b>from</b> it.</p>
 */
public final class CgMasterNode implements CgShaderNode {

    /** Vertex-stage output: object-space position. Defaults to the vertex attribute, so a graph that
     * touches only colour still transforms correctly. */
    public static final String POSITION = "Position";

    /** Fragment-stage output: the colour written to the render target. */
    public static final String BASE_COLOR = "BaseColor";

    // Position falls back to the mesh's own vertex, which is an ENGINE expression — there is nothing for
    // a user to type there, so it gets no inline editor. BaseColor's fallback is an ordinary literal and
    // is editable like any other.
    private static final List<CgShaderPort> PORTS = List.of(
            CgShaderPort.engineDefault(POSITION, CgShaderType.VEC3, "cg_Position"),
            CgShaderPort.input(BASE_COLOR, CgShaderType.VEC4, "vec4(1.0, 1.0, 1.0, 1.0)"));

    /** One entry of the generated {@code Properties} block. */
    public record Property(String name, CgShaderType type, String defaultValue) {
    }

    private String vertexFormat = "spatial";
    private String renderType = "Opaque";
    private String queue = "Geometry";
    private final Map<String, Property> properties = new LinkedHashMap<>();

    @Override public String id() { return "cg:master"; }
    @Override public String label() { return "Output"; }
    @Override public List<CgShaderPort> ports() { return PORTS; }

    /**
     * Emits nothing.
     *
     * <p>The master's inputs become {@code gl_Position} and {@code fragColor}, written by
     * {@link CgShaderEmitter} because only it knows which stage is being emitted. A node cannot know
     * that — it is handed resolved names and nothing else, which is the whole point of the inversion.</p>
     */
    @Override
    public String generateCode(CgNodeCodeContext ctx) {
        return "";
    }

    // ── Graph-level settings ────────────────────────────────────────────────

    /** The {@code #type} line — the vertex format the graph draws with. */
    public CgMasterNode vertexFormat(String value) {
        this.vertexFormat = value;
        return this;
    }

    /** {@code Tags { "RenderType" = ... }}. Drives shadow auto-generation. */
    public CgMasterNode renderType(String value) {
        this.renderType = value;
        return this;
    }

    /** {@code Queue = "..."} — Background, Geometry, AlphaTest, Transparent, Overlay. */
    public CgMasterNode queue(String value) {
        this.queue = value;
        return this;
    }

    /**
     * Declares a shader {@code Property} the graph exposes — a value the material sets at runtime rather
     * than one baked into the GLSL.
     *
     * @param type the wire type; the token written is its {@link CgShaderType#propertyTypeName()}, which
     *             is not always the GLSL name — {@code bool} is spelled {@code boolean} there
     */
    public CgMasterNode property(String name, CgShaderType type, String defaultValue) {
        if (type.propertyTypeName() == null) {
            throw new IllegalArgumentException("Type " + type + " cannot be a shader property");
        }
        properties.put(name, new Property(name, type, defaultValue));
        return this;
    }

    public String vertexFormat() { return vertexFormat; }
    public String renderType() { return renderType; }
    public String queue() { return queue; }

    /**
     * The material's {@code Properties { }} block — declared uniforms, in declaration order.
     *
     * <p>Named {@code shaderProperties} rather than {@code properties} to keep it apart from
     * {@link CgShaderNode#properties()}, which is a completely different thing: those are the
     * <b>editor dropdowns</b> that select which GLSL a node emits, whereas these are runtime uniforms
     * the finished material exposes. The two collided the moment nodes gained dropdowns, and the name
     * that had to move is this one — a node property is the more general concept.</p>
     */
    public Collection<Property> shaderProperties() {
        return properties.values();
    }
}
