package com.crystalgraphics.shadergraph;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What a shader graph terminates in: the stage outputs, and the settings a {@code .shader} carries that
 * no ordinary node has anywhere to put.
 *
 * <h3>Two jobs, and the second is why this is a class rather than another template</h3>
 * <p>Its ports are the stage outputs, declared per block — {@link #VERTEX_PORTS} and
 * {@link #FRAGMENT_PORTS}, which is Unity's Master Stack split. But a {@code .shader} also carries
 * {@code #type}, {@code Queue}, {@code Tags} and
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

    /** Fragment-stage output: the surface colour, <b>without</b> alpha. @see #ALPHA */
    public static final String BASE_COLOR = "BaseColor";

    /** Fragment-stage output: opacity. Only visible with a blending {@code RenderState}. */
    public static final String ALPHA = "Alpha";

    /** Fragment-stage output: {@code discard} below this alpha. Zero disables it. */
    public static final String ALPHA_CLIP_THRESHOLD = "AlphaClipThreshold";

    /**
     * The vertex block's ports, in the order an editor should draw them.
     *
     * <p>Position falls back to the mesh's own vertex, which is an ENGINE expression — there is nothing
     * for a user to type there, so it gets no inline editor.</p>
     */
    public static final List<CgShaderPort> VERTEX_PORTS = List.of(
            CgShaderPort.engineDefault(POSITION, CgShaderType.VEC3, "cg_Position"));

    /**
     * The fragment block's ports, in the order an editor should draw them.
     *
     * <h3>Why this list is so much shorter than Unity's</h3>
     * <p>Unity's Fragment block also carries Metallic, Smoothness, Ambient Occlusion, Emission and a
     * tangent-space Normal. <b>Every one of them is consumed by a lighting model, and this engine has
     * none</b> — {@code CgFrameBlock} carries view, projection, time and resolution and not one light
     * term, and {@code CgFrameData.hasDirectionalLight()} returns false with "deferred to v2" written
     * beside it. A port whose only consumer does not exist accepts a wire, displays a value, changes no
     * pixel, and gives no clue which of those three it is failing at; that is strictly worse than not
     * offering it, because absence is at least legible.</p>
     *
     * <p>Emission is the one worth spelling out, since it looks unlit-friendly: it means "colour added
     * <em>after</em> lighting", and with no lighting everything is already emissive — Emission and
     * BaseColor would be two ports summed with neither attenuated, which is the same port twice.</p>
     *
     * <p>{@link #ALPHA} and {@link #ALPHA_CLIP_THRESHOLD} are here precisely because they are the two that
     * <em>are</em> consumable today: a {@code .shader} can declare
     * {@code Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA}, {@code Queue} has {@code Transparent} and
     * {@code AlphaTest}, and {@code CgTransparentRenderer} actually runs.</p>
     *
     * <p><b>This is a queue, not a graveyard.</b> The rejected ports come back the day a lighting model
     * lands, which is why the blocks are declared as data here rather than implied by a hard-coded pair
     * of names in {@link CgShaderEmitter} — adding Smoothness later should be one entry and one line.</p>
     */
    public static final List<CgShaderPort> FRAGMENT_PORTS = List.of(
            CgShaderPort.input(BASE_COLOR, CgShaderType.VEC3, "vec3(1.0, 1.0, 1.0)"),
            CgShaderPort.input(ALPHA, CgShaderType.FLOAT, "1.0"),
            // Zero, not a small epsilon: clipping must be OFF unless asked for, and `< 0.0` is never true
            // for an alpha that has itself been clamped, so the emitted branch costs nothing when unused.
            CgShaderPort.input(ALPHA_CLIP_THRESHOLD, CgShaderType.FLOAT, "0.0"));

    private static final List<CgShaderPort> PORTS =
            Stream.concat(VERTEX_PORTS.stream(), FRAGMENT_PORTS.stream()).collect(Collectors.toList());

    /**
     * Which stage a master port belongs to — the split the whole emitter turns on.
     *
     * <p>{@link CgShaderDomain#ANY} for anything unrecognised rather than a throw: an unknown port on the
     * master is a caller error the compiler already reports by other means, and blowing up inside a
     * stage-assignment walk turns a clear message into a stack trace.</p>
     */
    /** The GLSL a master input falls back to when nothing is wired and nothing was authored. */
    @javax.annotation.Nullable
    public static String defaultExpressionFor(String portId) {
        for (CgShaderPort port : PORTS) {
            if (port.id().equals(portId)) return port.defaultExpression();
        }
        return null;
    }

    public static CgShaderDomain blockOf(String portId) {
        for (CgShaderPort port : VERTEX_PORTS) {
            if (port.id().equals(portId)) return CgShaderDomain.VERTEX;
        }
        for (CgShaderPort port : FRAGMENT_PORTS) {
            if (port.id().equals(portId)) return CgShaderDomain.FRAGMENT;
        }
        return CgShaderDomain.ANY;
    }

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

    /**
     * Forgets every declared property.
     *
     * <p>For a caller that re-declares the whole set before each emit, which is what a graph editor
     * does: the master is the <b>compiler's</b> object rather than storage, so without this, two
     * documents compiled through one master would leave each other's uniforms behind and a shader would
     * declare properties its graph never asked for.</p>
     */
    public CgMasterNode clearShaderProperties() {
        properties.clear();
        return this;
    }
}
