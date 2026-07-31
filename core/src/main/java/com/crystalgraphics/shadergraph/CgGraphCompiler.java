package com.crystalgraphics.shadergraph;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a {@link CgShaderGraph} into GLSL statements, and into a complete {@code .shader} source.
 *
 * <h3>The whole design in one line</h3>
 * <p>Walk the graph in dependency order; for each node, resolve its inputs to variable names or
 * literals, declare its outputs, and ask it for a snippet. The node is handed finished answers and
 * returns text — it never sees the graph, so it cannot namespace wrongly or depend on emission order.
 * That inversion is Godot's, and it is why this class is short.</p>
 *
 * <h3>Deterministic by construction</h3>
 * <p>The same graph must emit byte-identical source, or content-hash keying in
 * {@code CgMaterialShaderRegistry.getOrCreateGenerated} is worthless and every reopen recompiles.
 * Ordering comes from the topological walk, includes from a {@code LinkedHashSet}, and nothing here
 * iterates a {@code HashMap}.</p>
 */
public final class CgGraphCompiler {

    /** Everything a compile produced, plus what went wrong. */
    public record Result(String code, List<String> includes, Map<Integer, String> lineOwners,
                         List<String> errors) {

        public boolean ok() {
            return errors.isEmpty();
        }

        /**
         * Which node emitted a given line of {@link #code}, 1-based.
         *
         * <p>This is what makes a driver error actionable. A GLSL compile failure reports a line in
         * generated source the user never wrote, and without this map the editor can only repeat it.
         * Building it while emitting is nearly free; reconstructing it afterwards is impossible.</p>
         */
        @Nullable
        public String ownerOfLine(int line) {
            return lineOwners.get(line);
        }
    }

    private CgGraphCompiler() {
    }

    /**
     * Compiles the graph rooted at its output node.
     *
     * @see #compileFrom(CgShaderGraph, String, boolean)
     */
    public static Result compile(CgShaderGraph graph) {
        return compileFrom(graph, graph.outputId(), false);
    }

    /**
     * Compiles the subgraph feeding {@code rootId}.
     *
     * <p><b>A preview is this, with a different root.</b> Not a second compiler and not a second code
     * path — the same walk, the same emitter, the same node implementations, rooted somewhere else and
     * told so. Godot's {@code p_for_preview} is the same decision.</p>
     *
     * @param forPreview passed through to every node, for the few that emit something cheaper
     */
    public static Result compileFrom(CgShaderGraph graph, @Nullable String rootId, boolean forPreview) {
        return compileFrom(graph, rootId, forPreview, Set.of());
    }

    /**
     * As {@link #compileFrom(CgShaderGraph, String, boolean)}, but treating {@code alreadyEmitted} as
     * having been compiled elsewhere.
     *
     * <p>Those nodes contribute <b>names but no code</b>: downstream nodes still resolve their inputs to
     * the right variables, while nothing is emitted twice. That is what makes a two-stage shader
     * possible without a second compiler — the fragment stage needs to know what the vertex stage called
     * its outputs, so it can read them, but must not re-emit a vertex attribute it cannot access.</p>
     *
     * <p>Getting this wrong is not subtle in its cause and is very subtle on screen: without the skip
     * set the emitter duplicated every hoisted node into the fragment stage, so {@code cg_Position}
     * appeared in a fragment body and the shader simply would not compile.</p>
     */
    public static Result compileFrom(CgShaderGraph graph, @Nullable String rootId, boolean forPreview,
                                     Set<String> alreadyEmitted) {
        List<String> errors = new ArrayList<>();
        Set<String> includes = new LinkedHashSet<>();
        Map<Integer, String> lineOwners = new LinkedHashMap<>();
        StringBuilder code = new StringBuilder();

        if (rootId == null) {
            errors.add("The graph has no output node, so there is nothing to compile toward");
            return new Result("", List.of(), Map.of(), errors);
        }

        List<CgShaderGraph.Instance> ordered;
        try {
            ordered = graph.orderedFrom(rootId);
        } catch (IllegalStateException cycle) {
            errors.add(cycle.getMessage());
            return new Result("", List.of(), Map.of(), errors);
        }
        if (ordered.isEmpty()) {
            errors.add("No node with id '" + rootId + "' — nothing to compile");
            return new Result("", List.of(), Map.of(), errors);
        }

        // nodeId -> portId -> the variable holding that output, and the type it settled on.
        Map<String, Map<String, String>> emittedOutputs = new HashMap<>();
        Map<String, Map<String, CgShaderType>> emittedTypes = new HashMap<>();
        int line = 1;

        for (CgShaderGraph.Instance instance : ordered) {
            includes.addAll(instance.type().includes());

            Map<String, CgShaderType> resolved = resolveTypes(graph, instance, emittedTypes, errors);
            Map<String, String> inputs = new LinkedHashMap<>();
            Map<String, String> outputs = new LinkedHashMap<>();

            for (CgShaderPort port : instance.type().inputs()) {
                inputs.put(port.id(), inputExpression(graph, instance, port, resolved,
                        emittedOutputs, emittedTypes, errors));
            }

            StringBuilder declarations = new StringBuilder();
            for (CgShaderPort port : instance.type().outputs()) {
                String name = variableName(instance.id(), port.id());
                CgShaderType type = resolved.get(port.id());
                if (type == null || type == CgShaderType.DYNAMIC) {
                    errors.add("Node '" + instance.id() + "' output '" + port.id()
                            + "' is dynamic and nothing connected to it says what type it should be");
                    type = CgShaderType.FLOAT;
                }
                declarations.append("    ").append(type.glsl()).append(' ').append(name).append(";\n");
                outputs.put(port.id(), name);
            }
            emittedOutputs.put(instance.id(), outputs);
            emittedTypes.put(instance.id(), resolved);

            // Compiled by another stage: its names are now known, which is all a downstream node needs.
            if (alreadyEmitted.contains(instance.id())) continue;

            String header = "    // " + instance.type().id() + " (" + instance.id() + ")\n";
            String body;
            try {
                body = instance.type().generateCode(
                        new CgNodeCodeContext(inputs, outputs, resolved, forPreview));
            } catch (RuntimeException broken) {
                // A broken node definition must name itself. Left to the driver this surfaces as a
                // syntax error in generated code the user never wrote.
                errors.add("Node '" + instance.id() + "' (" + instance.type().id()
                        + ") failed to emit: " + broken.getMessage());
                continue;
            }

            String chunk = header + declarations + indent(body);
            for (String ignored : chunk.split("\n", -1)) lineOwners.put(line++, instance.id());
            code.append(chunk);
        }

        return new Result(code.toString(), List.copyOf(includes), lineOwners, errors);
    }

    /**
     * The GLSL expression an input port reads.
     *
     * <p>Three cases, and the point is that the node sees no difference between them: a connected input
     * is the upstream variable (cast if it needs promoting), an unconnected one is its literal, and a
     * missing one is an error rather than a zero. A node never branches on connectedness.</p>
     */
    private static String inputExpression(CgShaderGraph graph, CgShaderGraph.Instance instance,
                                          CgShaderPort port, Map<String, CgShaderType> resolved,
                                          Map<String, Map<String, String>> emittedOutputs,
                                          Map<String, Map<String, CgShaderType>> emittedTypes,
                                          List<String> errors) {
        CgShaderType want = resolved.getOrDefault(port.id(), port.type());
        CgShaderGraph.Link link = graph.linkInto(instance.id(), port.id());

        if (link == null) {
            String literal = instance.valueFor(port.id());
            if (literal == null) {
                errors.add("Node '" + instance.id() + "' input '" + port.id()
                        + "' is unconnected and has no default value");
                return zeroOf(want);
            }
            return literal;
        }

        Map<String, String> upstream = emittedOutputs.get(link.fromNode());
        String variable = upstream == null ? null : upstream.get(link.fromPort());
        if (variable == null) {
            errors.add("Node '" + instance.id() + "' input '" + port.id() + "' is fed by '"
                    + link.fromNode() + "." + link.fromPort() + "', which emitted nothing");
            return zeroOf(want);
        }

        CgShaderType have = emittedTypes.getOrDefault(link.fromNode(), Map.of()).get(link.fromPort());
        if (have == null || want == CgShaderType.DYNAMIC || have == want) return variable;
        if (!have.canFeed(want)) {
            errors.add("Node '" + instance.id() + "' input '" + port.id() + "' wants " + want
                    + " but is fed " + have + " — connect-time validation should have refused this");
            return zeroOf(want);
        }
        // The cast is the COMPILER's job. The editor permits float -> vec3, so without this the graph
        // looks legal and fails in the driver, with an error the user cannot act on.
        return have.promote(variable, want);
    }

    /**
     * Resolves every port of one instance to a concrete type.
     *
     * <p>A node's dynamic ports resolve <b>together</b>, to the widest concrete type reaching any of
     * them: {@code Add(float, vec3)} is a vec3 throughout, so the narrow side promotes and the output
     * matches. Resolving each port independently would make {@code Add} return a float when its first
     * input happened to be one.</p>
     */
    private static Map<String, CgShaderType> resolveTypes(CgShaderGraph graph,
                                                          CgShaderGraph.Instance instance,
                                                          Map<String, Map<String, CgShaderType>> emittedTypes,
                                                          List<String> errors) {
        Map<String, CgShaderType> resolved = new LinkedHashMap<>();
        List<CgShaderType> evidence = new ArrayList<>();

        for (CgShaderPort port : instance.type().inputs()) {
            if (!port.isDynamic()) continue;
            CgShaderGraph.Link link = graph.linkInto(instance.id(), port.id());
            if (link == null) continue;
            CgShaderType upstream = emittedTypes.getOrDefault(link.fromNode(), Map.of()).get(link.fromPort());
            if (upstream != null) evidence.add(upstream);
        }

        CgShaderType dynamic = CgShaderType.widest(evidence);
        if (dynamic == null && !evidence.isEmpty()) {
            errors.add("Node '" + instance.id() + "' has dynamic ports fed by incompatible types "
                    + evidence + ", which cannot be reconciled");
        }
        // A dynamic node with nothing connected still has to emit something rather than failing: a
        // float is the identity of the promotion order and is what an unconnected literal will be.
        if (dynamic == null) dynamic = CgShaderType.FLOAT;

        for (CgShaderPort port : instance.type().ports()) {
            resolved.put(port.id(), port.isDynamic() ? dynamic : port.type());
        }
        return resolved;
    }

    /** {@code node_<instance>_<port>} — unique by construction, because instance ids are unique and
     * already legal GLSL identifiers. */
    private static String variableName(String instanceId, String portId) {
        return "node_" + instanceId + "_" + portId;
    }

    private static String zeroOf(CgShaderType type) {
        return type.isNumericVector() ? type.glsl() + "(0.0)" : type.glsl() + "(0)";
    }

    private static String indent(String body) {
        if (body.isEmpty()) return "";
        StringBuilder out = new StringBuilder(body.length() + 16);
        for (String line : body.split("\n", -1)) {
            if (!line.isEmpty()) out.append("    ").append(line);
            out.append('\n');
        }
        // split() with a trailing newline leaves one empty element, which would double the blank line.
        if (body.endsWith("\n")) out.setLength(out.length() - 1);
        return out.toString();
    }
}
