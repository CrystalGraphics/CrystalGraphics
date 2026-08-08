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
                         List<CgShaderProblem> problems, Map<String, CgShaderType> outputTypes) {

        /** Backwards-compatible shape for callers with no interest in resolved types. */
        public Result(String code, List<String> includes, Map<Integer, String> lineOwners,
                      List<CgShaderProblem> problems) {
            this(code, includes, lineOwners, problems, Map.of());
        }

        public boolean ok() {
            return problems.isEmpty();
        }

        /**
         * The messages alone — derived, so it cannot drift from {@link #problems()}.
         *
         * <p>What a log line or a joined assertion wants. Everything that needs to point at the node uses
         * {@code problems()}.</p>
         */
        public List<String> errors() {
            List<String> messages = new ArrayList<>(problems.size());
            for (CgShaderProblem problem : problems) messages.add(problem.message());
            return messages;
        }

        /**
         * The type an emitted output variable settled on — keyed by the variable name the code uses,
         * e.g. {@code node_a1b2_Out}.
         *
         * <p><b>Only the compiler can answer this.</b> A dynamic port has no type until resolution has
         * looked at everything feeding it, so a caller that needs to wrap an output — a preview deciding
         * how to turn it into a colour — cannot re-derive it from the node declaration. Recording it
         * while emitting is free; reconstructing it afterwards means parsing the GLSL.</p>
         */
        @Nullable
        public CgShaderType typeOf(String variableName) {
            return outputTypes.get(variableName);
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
        List<CgShaderProblem> errors = new ArrayList<>();
        Set<String> includes = new LinkedHashSet<>();
        Map<Integer, String> lineOwners = new LinkedHashMap<>();
        StringBuilder code = new StringBuilder();

        if (rootId == null) {
            errors.add(CgShaderProblem.graph("The graph has no output node, so there is nothing to compile toward"));
            return new Result("", List.of(), Map.of(), errors);
        }

        wireImplicitDefaults(graph);

        List<CgShaderGraph.Instance> ordered;
        try {
            ordered = graph.orderedFrom(rootId);
        } catch (IllegalStateException cycle) {
            errors.add(CgShaderProblem.graph(cycle.getMessage()));
            return new Result("", List.of(), Map.of(), errors);
        }
        if (ordered.isEmpty()) {
            errors.add(CgShaderProblem.node(rootId, "No node with id '" + rootId + "' — nothing to compile"));
            return new Result("", List.of(), Map.of(), errors);
        }

        // nodeId -> portId -> the variable holding that output, and the type it settled on.
        Map<String, Map<String, String>> emittedOutputs = new HashMap<>();
        Map<String, Map<String, CgShaderType>> emittedTypes = new HashMap<>();
        // Variable name -> the type it was declared with. Flat, because that is how a caller refers to
        // it: by the name that appears in the emitted code.
        Map<String, CgShaderType> outputTypes = new LinkedHashMap<>();
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
                    errors.add(CgShaderProblem.port(instance.id(), port.id(),
                            "Node '" + instance.id() + "' output '" + port.id()
                            + "' is dynamic and nothing connected to it says what type it should be"));
                    type = CgShaderType.FLOAT;
                }
                declarations.append("    ").append(type.glsl()).append(' ').append(name).append(";\n");
                outputs.put(port.id(), name);
                outputTypes.put(name, type);
            }
            emittedOutputs.put(instance.id(), outputs);
            emittedTypes.put(instance.id(), resolved);

            // Compiled by another stage: its names are now known, which is all a downstream node needs.
            if (alreadyEmitted.contains(instance.id())) continue;

            String header = "    // " + instance.type().id() + " (" + instance.id() + ")\n";
            String body;
            try {
                // Property choices are resolved per instance, so a stale option in a stored document
                // becomes the node's default rather than a variant that does not exist.
                Map<String, String> chosen = new LinkedHashMap<>();
                for (CgShaderNodeProperty property : instance.type().properties()) {
                    chosen.put(property.id(), instance.propertyOr(property.id()));
                }
                body = instance.type().generateCode(
                        new CgNodeCodeContext(inputs, outputs, resolved, forPreview, chosen));
            } catch (RuntimeException broken) {
                // A broken node definition must name itself. Left to the driver this surfaces as a
                // syntax error in generated code the user never wrote.
                errors.add(CgShaderProblem.node(instance.id(),
                        "Node '" + instance.id() + "' (" + instance.type().id()
                        + ") failed to emit: " + broken.getMessage()));
                continue;
            }

            String chunk = header + declarations + indent(body);
            // split("\n", -1) on a chunk ending in a newline yields a trailing EMPTY element, and mapping
            // it claimed one line too many — the line immediately AFTER this node's code, which belongs to
            // whatever comes next. It stayed invisible for as long as the next line always happened to
            // mention this node's own variable (`fragColor = node_c_Out;` did), and surfaced the moment
            // the emitter began writing a line of its own between the last node and the output.
            String[] chunkLines = chunk.split("\n", -1);
            int emittedLines = chunk.endsWith("\n") ? chunkLines.length - 1 : chunkLines.length;
            for (int i = 0; i < emittedLines; i++) lineOwners.put(line++, instance.id());
            code.append(chunk);
        }

        return new Result(code.toString(), List.copyOf(includes), lineOwners, errors,
                Map.copyOf(outputTypes));
    }

    /**
     * Turns every unconnected, untouched port with an {@link CgShaderPort#implicitSource} into a real
     * connection — a genuine instance and a genuine {@link CgShaderGraph.Link}, mutated straight onto
     * {@code graph} — so every other stage of this compiler (type resolution, ordering, vertex/fragment
     * promotion) treats it exactly like a wire the user drew, because it now IS one.
     *
     * <h3>"Untouched" means no explicit literal was ever stored, not merely "no wire"</h3>
     * <p>{@link CgShaderGraph.Instance#inputValues} holds only what a user actually typed —
     * {@code ShaderGraphBridge.inputValuesOf} never pre-populates a field's default into the document, so
     * an untouched port has no entry there at all. That is the signal this reads directly, rather than
     * comparing against the port's own {@link CgShaderPort#defaultExpression} (which for an implicit port
     * is null anyway): a user is free to type a literal vec2 into a UV slot to pin it, and that must win
     * over the implicit source exactly the way a real connection would if one existed instead.</p>
     *
     * <h3>One shared instance per source, not one per consumer</h3>
     * <p>Matches the concept being ported: Unity's implicit UV is not five separate UV0 reads, it is the
     * same one. Sharing also means the second, third, ... consumer in a graph costs nothing extra — the
     * instance and its single output are emitted once regardless of how many ports read it.</p>
     *
     * <h3>Safe to call every time, on the same graph object</h3>
     * <p>Idempotent by construction: once a port is linked, {@link CgShaderGraph#linkInto} finds that
     * link on the next call and the whole branch is skipped, so repeated compiles of one graph — which is
     * the normal case, since {@code CgPreviewRenderer} compiles from many roots against one
     * {@code CgShaderGraph} — neither re-add the shared instance nor duplicate the link.</p>
     *
     * <h3>{@link CgShaderEmitter} has to call this BEFORE it assigns stages, and that is why it is not
     * private</h3>
     * <p>The claim above — that every later stage treats an implicit link exactly like a drawn one —
     * was false for the one stage that does not live in this class. <b>Vertex/fragment promotion is the
     * emitter's</b>, and it ran first: roots, the fragment chain and the vertex set were all computed
     * from the graph as handed in, which at that moment still had none of these instances in it. So a
     * synthesized node declaring {@link CgShaderDomain#VERTEX} was never hoisted, and got compiled into
     * whichever stage first reached it — the fragment one.</p>
     *
     * <p>What that emitted is invalid GLSL rather than merely wrong output: {@code cg_TexCoord0},
     * {@code cg_Normal} and {@code cg_Position} are <b>vertex attribute aliases</b>, so
     * {@code node_implicit_..._Out = cg_Normal;} inside {@code void fragment(...)} does not compile at
     * all. Every node with an implicit UV — twelve of them — was in this state whenever it was used
     * unwired in a real material, and it was invisible because the PREVIEW path does not go through here:
     * {@code CgPreviewEmitter} evaluates everything in the fragment stage against varyings it writes
     * itself, which is why the thumbnails were right the whole time and only the material was broken.</p>
     *
     * <p>Wiring first makes the instances ordinary graph members before any of that runs, so the existing
     * {@code domain() == VERTEX} hoist and {@code findVaryings} pick them up with no special case —
     * finally making the sentence at the top of this doc true.</p>
     */
    static void wireImplicitDefaults(CgShaderGraph graph) {
        // Snapshotted, not walked live: graph.instances() is an unmodifiable VIEW over the same map
        // graph.add() writes into below, and a fail-fast LinkedHashMap iterator throws
        // ConcurrentModificationException the moment that write lands mid-iteration — uncaught, since
        // CgPreviewEmitter.emit calls this (via compileFrom) with no try/catch around it, so it took the
        // whole render pipeline down rather than failing just the one preview.
        List<CgShaderGraph.Instance> instances = List.copyOf(graph.instances());
        for (CgShaderGraph.Instance instance : instances) {
            for (CgShaderPort port : instance.type().inputs()) {
                if (!port.hasImplicitSource()) continue;
                if (graph.linkInto(instance.id(), port.id()) != null) continue;
                if (instance.inputValues().get(port.id()) != null) continue;

                CgShaderNode source = port.implicitSource().get();
                String sourceId = implicitInstanceId(source);
                if (graph.instance(sourceId) == null) {
                    graph.add(new CgShaderGraph.Instance(sourceId, source, Map.of(), Map.of()));
                }
                graph.link(sourceId, port.implicitSourcePort(), instance.id(), port.id());
            }
        }
    }

    /** Deterministic and GLSL-legal — this ends up inside {@code node_<id>_<port>} variable names, so it
     * cannot carry the {@code :} / {@code /} a node type id like {@code cg:Input/Geometry/uv} does. Also
     * what {@link #implicitNarrowingSwizzle} recognizes an implicitly-synthesized link by. */
    private static String implicitInstanceId(CgShaderNode source) {
        return IMPLICIT_INSTANCE_PREFIX + source.id().replaceAll("[^A-Za-z0-9_]", "_");
    }

    /** Prefix every {@link #wireImplicitDefaults}-synthesized instance id carries. */
    private static final String IMPLICIT_INSTANCE_PREFIX = "implicit_";

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
                                          List<CgShaderProblem> errors) {
        CgShaderType want = resolved.getOrDefault(port.id(), port.type());
        CgShaderGraph.Link link = graph.linkInto(instance.id(), port.id());

        if (link == null) {
            String literal = instance.valueFor(port.id());
            if (literal == null) {
                errors.add(CgShaderProblem.port(instance.id(), port.id(),
                        "Node '" + instance.id() + "' input '" + port.id()
                        + "' is unconnected and has no default value"));
                return zeroOf(want);
            }
            return literal;
        }

        Map<String, String> upstream = emittedOutputs.get(link.fromNode());
        String variable = upstream == null ? null : upstream.get(link.fromPort());
        if (variable == null) {
            errors.add(CgShaderProblem.port(instance.id(), port.id(),
                    "Node '" + instance.id() + "' input '" + port.id() + "' is fed by '"
                    + link.fromNode() + "." + link.fromPort() + "', which emitted nothing"));
            return zeroOf(want);
        }

        CgShaderType have = emittedTypes.getOrDefault(link.fromNode(), Map.of()).get(link.fromPort());
        if (have == null || want == CgShaderType.DYNAMIC || have == want) return variable;
        if (!have.canFeed(want)) {
            // The one narrowing this compiler allows, and only here: a link wireImplicitDefaults
            // synthesized, never one the user drew. UV's own Out is vec4 — matching Unity's UV Out(4) —
            // but every consumer that implicitly reads it wants vec2, and "nothing demotes" (see
            // CgShaderType.canFeed) is a real, tested rule for user-drawn edges: picking components
            // silently is how a graph starts lying about what it computes, and that belongs in an
            // explicit Split node the user can see. An implicit link has no Split node to point at and
            // no user who drew it to be lying to — it is this compiler's own synthesis of exactly what
            // Unity's implicit UV0 already means, so the narrowing is correct here in a way it would not
            // be for a real wire.
            String narrowed = mayNarrow(port, link) ? narrowingSwizzle(variable, have, want) : null;
            if (narrowed != null) return narrowed;
            errors.add(CgShaderProblem.port(instance.id(), port.id(),
                    "Node '" + instance.id() + "' input '" + port.id() + "' wants " + want
                    + " but is fed " + have + " — connect-time validation should have refused this"));
            return zeroOf(want);
        }
        // The cast is the COMPILER's job. The editor permits float -> vec3, so without this the graph
        // looks legal and fails in the driver, with an error the user cannot act on.
        return have.promote(variable, want);
    }

    /**
     * {@code variable.xy} / {@code .x} — narrowing a wider implicit source down to what a consumer
     * wants, or {@code null} when {@code link} is not one {@link #wireImplicitDefaults} created, or the
     * conversion is not a plain truncation (narrowing a matrix or a sampler is nonsense either way).
     */
    /**
     * Whether truncating a wider value into this port is legitimate, which is true in exactly two places
     * and nowhere else.
     *
     * <p><b>A DYNAMIC port</b>, because its width was resolved from the graph rather than declared by
     * anyone: {@code CgShaderType.resolveDynamic} takes the narrowest non-scalar input, so
     * {@code Add(vec4, vec2)} is a vec2 node and the vec4 side must lose its z and w. Unity adapts the
     * same way, and refusing it here is what made that graph fail to compile at all.</p>
     *
     * <p><b>A link this compiler synthesized</b> — see {@link #wireImplicitDefaults}. The implicit UV
     * node's output is a vec4 (matching Unity's {@code UV Out(4)}) feeding ports that want a vec2.</p>
     *
     * <p>Everything else still refuses, which is the documented rule and worth keeping: a user-drawn edge
     * into a port whose type someone WROTE DOWN should not silently drop components. That belongs in an
     * explicit Split node the reader can see.</p>
     */
    private static boolean mayNarrow(CgShaderPort port, CgShaderGraph.Link link) {
        // Now true for ANY link, and the two cases above are kept in the doc because they are why the
        // machinery exists rather than why it fires.
        //
        // <b>This reverses the "everything else still refuses" rule stated above.</b> That rule held that
        // an edge into a port whose type someone wrote down should never silently drop components, and
        // that a Split node is where a truncation belongs. It is a defensible position; it is not Unity's,
        // and it is not what a graph author expects. There, a Vector4 dropped onto a Vector3 slot connects
        // and loses its w — `UV` into `Base Color` is the obvious case, and refusing it does not teach the
        // rule, it reads as the editor being broken.
        //
        // The editor agrees at connect time (`ShaderGraphBridge.GLSL_PROMOTION`), and the two MUST stay
        // in step: a wire the editor permits and the compiler then rejects is an error message about a
        // graph the user was invited to draw.
        return true;
    }

    @Nullable
    private static String narrowingSwizzle(String variable, CgShaderType have, CgShaderType want) {
        if (!have.isNumericVector() || !want.isNumericVector()) return null;
        if (have.components() <= want.components()) return null;
        String swizzle = "xyzw".substring(0, want.components());
        return variable + "." + swizzle;
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
                                                          List<CgShaderProblem> errors) {
        Map<String, CgShaderType> resolved = new LinkedHashMap<>();
        List<CgShaderType> evidence = new ArrayList<>();

        for (CgShaderPort port : instance.type().inputs()) {
            if (!port.isDynamic()) continue;
            CgShaderGraph.Link link = graph.linkInto(instance.id(), port.id());
            if (link == null) continue;
            CgShaderType upstream = emittedTypes.getOrDefault(link.fromNode(), Map.of()).get(link.fromPort());
            if (upstream != null) evidence.add(upstream);
        }

        CgShaderType dynamic = CgShaderType.resolveDynamic(evidence);
        if (dynamic == null) {
            errors.add(CgShaderProblem.node(instance.id(),
                    "Node '" + instance.id() + "' has dynamic ports fed by incompatible types "
                    + evidence + ", which cannot be reconciled"));
            // Still has to emit something rather than failing: float is the identity of the promotion
            // order and is what an unconnected literal will be. resolveDynamic already returns it for
            // "nothing decided the width", so reaching here means a genuine conflict, reported above.
            dynamic = CgShaderType.FLOAT;
        }

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
