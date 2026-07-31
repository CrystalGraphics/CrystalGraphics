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
