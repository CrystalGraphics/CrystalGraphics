package com.crystalgraphics.shadergraph;

import java.util.List;
import java.util.Set;

/**
 * A node <b>type</b>: what ports it has, and how it turns them into GLSL.
 *
 * <h3>The inversion, taken from Godot</h3>
 * <p>A node is handed the <em>already-resolved variable names</em> for its inputs and outputs and
 * returns a snippet. It never sees the graph, never learns what it is connected to, and therefore
 * <b>cannot get namespacing wrong</b> — Godot's {@code generate_code(mode, type, id, input_vars,
 * output_vars, for_preview)} is the same shape, and it is the single most valuable thing to port from
 * either reference implementation.</p>
 *
 * <h3>Why this is an interface when most nodes are data</h3>
 * <p>The first draft of the plan said node types should be declarative JSON and nothing else. Both
 * Godot and Unity define their built-ins in code, and the reason is not inertia: <b>dynamic port
 * types</b>. {@code Add} accepts any vector width and produces the widest, which no fixed declaration
 * expresses — and the alternative is four nodes per operation.</p>
 *
 * <p>So this is an interface, and {@link CgTemplateShaderNode} is the declarative implementation that
 * covers the large majority. A node needing real behaviour implements this directly. Both references
 * also ship a data path for user extension (Godot's {@code VisualShaderNodeExpression}, Unity's Custom
 * Function Node), which is what the template class is.</p>
 */
public interface CgShaderNode {

    /** Stable type id — {@code "cg:math/multiply"}. What a document stores and a library keys on. */
    String id();

    /** Human label for the editor. Defaults to the id, so a node is never nameless. */
    default String label() {
        return id();
    }

    /** Every port, inputs and outputs, in declaration order. */
    List<CgShaderPort> ports();

    /**
     * Compile-time choices this node offers — the dropdowns the editor draws in its body.
     *
     * <p>Empty for almost everything. See {@link CgShaderNodeProperty} for why these are not simply
     * extra input ports with defaults.</p>
     */
    default List<CgShaderNodeProperty> properties() {
        return List.of();
    }

    /** The property with this id, or null. */
    default CgShaderNodeProperty property(String propertyId) {
        for (CgShaderNodeProperty property : properties()) {
            if (property.id().equals(propertyId)) return property;
        }
        return null;
    }

    /**
     * Which stage this node may be emitted into. {@link CgShaderDomain#ANY} unless GLSL forbids one —
     * a derivative or a discard is fragment-only, reading a vertex attribute is vertex-only.
     *
     * <p>Declared rather than inferred because the compiler cannot see inside the emitted GLSL, and the
     * cost of guessing wrong is a shader that compiles on one vendor and is refused by another. This
     * engine has already shipped that bug once, in {@code sdf.glsl}.</p>
     */
    default CgShaderDomain domain() {
        return CgShaderDomain.ANY;
    }

    /**
     * What this node's preview thumbnail is drawn on.
     *
     * <p>{@link CgPreviewGeometry#INHERIT} for almost everything — a node that produces a colour does not
     * care, and should take the answer from whatever is feeding it. Declare {@link
     * CgPreviewGeometry#SPHERE} only for genuinely spatial outputs (position, normal, view direction),
     * where a flat quad would show one uniform colour and tell the user nothing.</p>
     *
     * <p>The declaration <b>propagates downstream</b>, so declaring it once on Position is what makes
     * every node fed by a Position preview as a sphere too. See {@link CgPreviewGeometry#resolve}.</p>
     */
    default CgPreviewGeometry previewGeometry() {
        return CgPreviewGeometry.INHERIT;
    }

    /**
     * Whether this node's output is worth drawing a thumbnail of.
     *
     * <p>False for a <b>constant</b>. A Float or a Vector 4 previews as a flat field of exactly the colour
     * its own knobs already state, so the thumbnail is a large, expensive restatement of the numbers
     * sitting immediately above it — and it makes the node several times taller than the values it holds.
     * Unity draws none on these for the same reason.</p>
     *
     * <p>Everything else defaults to true: the moment a node <em>computes</em> something, the picture is
     * the fastest way to see what it computed.</p>
     */
    default boolean showsPreview() {
        return true;
    }

    /**
     * Whether {@link #generateCode} emits something different, and fragment-safe, when
     * {@code ctx.forPreview()}.
     *
     * <p>Only matters for a {@link CgShaderDomain#VERTEX} node. A preview evaluates the whole chain in
     * the fragment stage, so a node reading {@code cg_Position} would emit a vertex attribute into a
     * fragment body — which the preview emitter refuses <em>unless</em> the node says it has a form that
     * reads the preview's varying instead.</p>
     */
    default boolean hasPreviewForm() {
        return false;
    }

    /**
     * The GLSL this node contributes, with every {@code {Port}} already replaced by a real variable
     * name or literal.
     *
     * <p>Statements, not an expression: a node assigns to its outputs. The compiler has already
     * declared them.</p>
     */
    String generateCode(CgNodeCodeContext ctx);

    /**
     * Resource paths this node's code needs {@code #include}d — {@code "crystalgraphics:shaders/lib/noise.glsl"}.
     *
     * <p><b>Declared rather than inferred.</b> Working out which functions a body actually calls means
     * parsing GLSL, which this project deliberately does not do — and a regex approximation would
     * silently drop a function referenced through a macro, surfacing as a link error in whichever
     * variant nobody compiled. Unity's Custom Function "File mode" declares its include the same way.</p>
     *
     * <p>The compiler emits the union across the nodes actually present, once each, so a graph with ten
     * noise nodes includes {@code noise.glsl} once and a graph with none does not include it at all.</p>
     */
    default Set<String> includes() {
        return Set.of();
    }

    // ── Convenience ─────────────────────────────────────────────────────────

    default List<CgShaderPort> inputs() {
        return ports().stream().filter(CgShaderPort::isInput).toList();
    }

    default List<CgShaderPort> outputs() {
        return ports().stream().filter(CgShaderPort::isOutput).toList();
    }

    /** The port with this id, or null. */
    default CgShaderPort port(String portId) {
        for (CgShaderPort port : ports()) {
            if (port.id().equals(portId)) return port;
        }
        return null;
    }

    /** Whether any port resolves from context — the flag that decides whether a node needs the
     * resolution pass at all. */
    default boolean hasDynamicPorts() {
        return ports().stream().anyMatch(CgShaderPort::isDynamic);
    }
}
