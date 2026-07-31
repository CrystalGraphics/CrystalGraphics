package com.crystalgraphics.shadergraph;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wraps compiled graph statements into a complete {@code .shader} source — the file
 * {@code CgMaterial.fromSource} then loads.
 *
 * <h3>The stage split, which is the whole of 6.3.4</h3>
 * <p>A graph has two stages, and which stage a node lands in is decided by <b>where it is reachable
 * from</b>, corrected by <b>what GLSL permits</b>:</p>
 * <ol>
 *   <li>Everything feeding {@code Position} is vertex-stage.</li>
 *   <li>Everything feeding {@code BaseColor} is fragment-stage — <em>unless</em> it declares
 *       {@link CgShaderDomain#VERTEX}, in which case it and everything it depends on move to the vertex
 *       stage and the value crosses as a <b>varying</b>.</li>
 *   <li>A value crossing that boundary becomes a {@code v2f} field, written in the vertex stage and read
 *       in the fragment stage. That is exactly what the hand-written format already does, so this is a
 *       mapping rather than an invention.</li>
 * </ol>
 *
 * <p><b>The asymmetry is the part that must not be smoothed over.</b> Vertex data reaches the fragment
 * stage through a varying; fragment data cannot reach the vertex stage at all, because the vertex shader
 * has already run. So a fragment-domain node feeding a vertex-domain one is refused with an error naming
 * both, not silently reordered.</p>
 */
public final class CgShaderEmitter {

    /** A value crossing from the vertex stage to the fragment stage. */
    public record Varying(String field, CgShaderType type, String vertexVariable) {
    }

    /** The emitted source, plus what it took to produce and what went wrong. */
    public record Result(String source, List<Varying> varyings, List<String> errors,
                         Map<Integer, String> lineOwners) {

        /** The shape for callers with no interest in which node wrote which line. */
        public Result(String source, List<Varying> varyings, List<String> errors) {
            this(source, varyings, errors, Map.of());
        }

        public boolean ok() {
            return errors.isEmpty();
        }

        /**
         * Which node emitted a given <b>line of {@link #source}</b>, 1-based, or null for scaffolding.
         *
         * <p>This is what turns a compile failure into something actionable. A driver reports a line in
         * generated code the user never wrote, and without a mapping the editor can only repeat it back;
         * with one it can say <em>which node</em> to go and look at.</p>
         *
         * <p>Note the difference from {@link CgGraphCompiler.Result#ownerOfLine}: that indexes the
         * statements the compiler produced, while this indexes the finished {@code .shader} file, which
         * has a preamble, a struct and two function bodies around them. The offset between the two is
         * exactly what made this worth recording at write time rather than reconstructing.</p>
         */
        @Nullable
        public String ownerOfLine(int line) {
            return lineOwners.get(line);
        }
    }

    private CgShaderEmitter() {
    }

    /**
     * Emits the complete {@code .shader} for {@code graph}, whose output node must be {@code master}.
     */
    public static Result emit(CgShaderGraph graph, CgMasterNode master) {
        List<String> errors = new ArrayList<>();
        String masterId = graph.outputId();
        if (masterId == null || graph.instance(masterId) == null) {
            errors.add("The graph's output node is not present, so there is nothing to emit toward");
            return new Result("", List.of(), errors);
        }

        String positionRoot = rootFeeding(graph, masterId, CgMasterNode.POSITION);
        String colourRoot = rootFeeding(graph, masterId, CgMasterNode.BASE_COLOR);

        // Stage assignment. A node feeding BaseColor that declares VERTEX has to be hoisted, together
        // with everything it depends on, or it would emit a vertex-only builtin into the fragment stage.
        Set<String> vertexSet = new LinkedHashSet<>(idsOf(graph, positionRoot));
        List<CgShaderGraph.Instance> colourChain = graph.orderedFrom(colourRoot);
        for (CgShaderGraph.Instance instance : colourChain) {
            if (instance.type().domain() == CgShaderDomain.VERTEX) {
                vertexSet.addAll(idsOf(graph, instance.id()));
            }
        }

        checkDomains(graph, colourChain, vertexSet, errors);

        // The vertex stage is rooted at Position, but must also emit everything hoisted out of the
        // colour chain — so it is rooted at whichever of the two actually has nodes, with the other
        // supplied as extra roots below.
        CgGraphCompiler.Result vertexCode = compileStage(graph, positionRoot, Set.of(), errors);
        List<CgGraphCompiler.Result> hoisted = new ArrayList<>();
        Set<String> emittedInVertex = new LinkedHashSet<>(idsOf(graph, positionRoot));
        for (CgShaderGraph.Instance instance : colourChain) {
            if (instance.type().domain() != CgShaderDomain.VERTEX) continue;
            if (emittedInVertex.contains(instance.id())) continue;
            CgGraphCompiler.Result part = CgGraphCompiler.compileFrom(
                    graph, instance.id(), false, Set.copyOf(emittedInVertex));
            errors.addAll(part.errors());
            hoisted.add(part);
            emittedInVertex.addAll(idsOf(graph, instance.id()));
        }

        Set<String> fragmentSet = new LinkedHashSet<>();
        for (CgShaderGraph.Instance instance : colourChain) {
            if (!vertexSet.contains(instance.id())) fragmentSet.add(instance.id());
        }
        // The vertex-stage nodes are SKIPPED rather than absent: the fragment stage still needs their
        // variable names in order to read them, and substituteVaryings then rewrites those reads.
        CgGraphCompiler.Result fragmentCode = compileStage(graph, colourRoot, vertexSet, errors);
        vertexCode = merge(vertexCode, hoisted);

        List<Varying> varyings = findVaryings(graph, vertexSet, fragmentSet);
        Map<Integer, String> lineOwners = new LinkedHashMap<>();
        String source = write(graph, master, masterId, lineOwners, vertexCode, fragmentCode, varyings,
                positionRoot, colourRoot);
        return new Result(source, varyings, errors, Map.copyOf(lineOwners));
    }

    /** The node feeding one of the master's inputs, or null when it is left at its default. */
    @Nullable
    private static String rootFeeding(CgShaderGraph graph, String masterId, String port) {
        CgShaderGraph.Link link = graph.linkInto(masterId, port);
        return link == null ? null : link.fromNode();
    }

    private static Set<String> idsOf(CgShaderGraph graph, @Nullable String root) {
        Set<String> ids = new LinkedHashSet<>();
        if (root == null) return ids;
        for (CgShaderGraph.Instance instance : graph.orderedFrom(root)) ids.add(instance.id());
        return ids;
    }

    /**
     * Compiles one stage by rooting the ordinary compiler and discarding what belongs to the other.
     *
     * <p>Reusing {@link CgGraphCompiler} rather than writing a second walk is the point: a stage is a
     * subgraph, and a subgraph is what {@code compileFrom} already emits.</p>
     */
    private static CgGraphCompiler.Result compileStage(CgShaderGraph graph, @Nullable String root,
                                                       Set<String> skip, List<String> errors) {
        if (root == null) {
            return new CgGraphCompiler.Result("", List.of(), Map.of(), List.of());
        }
        CgGraphCompiler.Result result = CgGraphCompiler.compileFrom(graph, root, false, skip);
        errors.addAll(result.errors());
        return result;
    }

    /** Joins the vertex root's code with each hoisted chain, in order. */
    private static CgGraphCompiler.Result merge(CgGraphCompiler.Result first,
                                                List<CgGraphCompiler.Result> rest) {
        if (rest.isEmpty()) return first;
        StringBuilder code = new StringBuilder(first.code());
        Set<String> includes = new LinkedHashSet<>(first.includes());
        Map<String, CgShaderType> outputTypes = new LinkedHashMap<>(first.outputTypes());
        for (CgGraphCompiler.Result part : rest) {
            code.append(part.code());
            includes.addAll(part.includes());
            // Carried through, not dropped: a hoisted chain declares variables the merged result is the
            // only remaining record of, and a caller asking what type one is would otherwise get null
            // for exactly the nodes that were moved.
            outputTypes.putAll(part.outputTypes());
        }
        return new CgGraphCompiler.Result(code.toString(), List.copyOf(includes),
                first.lineOwners(), List.of(), Map.copyOf(outputTypes));
    }

    /** A fragment-domain node feeding the vertex stage is impossible, not merely awkward. */
    private static void checkDomains(CgShaderGraph graph, List<CgShaderGraph.Instance> colourChain,
                                     Set<String> vertexSet, List<String> errors) {
        for (CgShaderGraph.Instance instance : colourChain) {
            if (!vertexSet.contains(instance.id())) continue;
            if (instance.type().domain() == CgShaderDomain.FRAGMENT) {
                errors.add("Node '" + instance.id() + "' (" + instance.type().id()
                        + ") is fragment-only but something in the vertex stage depends on it — the "
                        + "vertex shader has already run by the time a fragment value exists");
            }
        }
    }

    /**
     * Every vertex-stage value read by the fragment stage.
     *
     * <p>One {@code v2f} field each, named after the variable that produced it so the generated struct
     * is readable rather than {@code v0, v1, v2}.</p>
     */
    private static List<Varying> findVaryings(CgShaderGraph graph, Set<String> vertexSet,
                                              Set<String> fragmentSet) {
        Map<String, Varying> found = new LinkedHashMap<>();
        for (CgShaderGraph.Link link : graph.links()) {
            if (!vertexSet.contains(link.fromNode()) || !fragmentSet.contains(link.toNode())) continue;
            CgShaderGraph.Instance source = graph.instance(link.fromNode());
            if (source == null) continue;
            CgShaderPort port = source.type().port(link.fromPort());
            if (port == null) continue;
            String variable = "node_" + link.fromNode() + "_" + link.fromPort();
            found.putIfAbsent(variable, new Varying(
                    "v_" + link.fromNode() + "_" + link.fromPort(),
                    port.type() == CgShaderType.DYNAMIC ? CgShaderType.VEC4 : port.type(),
                    variable));
        }
        return List.copyOf(found.values());
    }

    // ── Writing the file ────────────────────────────────────────────────────

    private static String write(CgShaderGraph graph, CgMasterNode master, String masterId,
                                Map<Integer, String> lineOwners,
                                CgGraphCompiler.Result vertex, CgGraphCompiler.Result fragment,
                                List<Varying> varyings,
                                @Nullable String positionRoot, @Nullable String colourRoot) {
        StringBuilder out = new StringBuilder(1024);
        out.append("// Generated from a shader graph. Edits here are lost on the next compile.\n");
        out.append("#type ").append(master.vertexFormat()).append("\n\n");

        Set<String> includes = new LinkedHashSet<>(vertex.includes());
        includes.addAll(fragment.includes());
        for (String include : includes) {
            out.append("#include \"").append(include).append("\"\n");
        }
        if (!includes.isEmpty()) out.append('\n');

        out.append("Tags { \"RenderType\" = \"").append(master.renderType()).append("\" }\n");
        out.append("Queue = \"").append(master.queue()).append("\"\n\n");

        if (!master.shaderProperties().isEmpty()) {
            out.append("Properties {\n");
            for (CgMasterNode.Property property : master.shaderProperties()) {
                out.append("    ").append(property.name())
                        .append(" (\"").append(property.name()).append("\", ")
                        .append(property.type().propertyTypeName()).append(") = ")
                        .append(property.defaultValue()).append('\n');
            }
            out.append("}\n\n");
        }

        // The struct is always emitted, even when empty: the format's vertex/fragment signatures name
        // v2f unconditionally, so a graph with no varyings still needs the type to exist.
        out.append("struct v2f {\n");
        for (Varying varying : varyings) {
            out.append("    ").append(varying.type().glsl()).append(' ')
                    .append(varying.field()).append(";\n");
        }
        out.append("};\n\n");

        out.append("Pass {\n");
        out.append("    Tags { \"LightMode\" = \"Forward\" }\n\n");

        out.append("    void vertex(out v2f o) {\n");
        mapOwners(lineOwners, vertex.lineOwners(), nextLine(out));
        out.append(reindent(vertex.code()));
        out.append("        gl_Position = CG_MATRIX_MVP * vec4(")
                .append(expressionFor(graph, masterId, CgMasterNode.POSITION, positionRoot))
                .append(", 1.0);\n");
        for (Varying varying : varyings) {
            out.append("        o.").append(varying.field()).append(" = ")
                    .append(varying.vertexVariable()).append(";\n");
        }
        out.append("    }\n\n");

        out.append("    void fragment(in v2f i, out vec4 fragColor) {\n");
        mapOwners(lineOwners, fragment.lineOwners(), nextLine(out));
        out.append(reindent(substituteVaryings(fragment.code(), varyings)));
        out.append("        fragColor = ")
                .append(substituteVaryings(
                        expressionFor(graph, masterId, CgMasterNode.BASE_COLOR, colourRoot), varyings))
                .append(";\n");
        out.append("    }\n");
        out.append("}\n");
        return out.toString();
    }

    /** The expression the master reads for one of its inputs: an upstream variable, or its default. */
    private static String expressionFor(CgShaderGraph graph, String masterId, String port,
                                        @Nullable String root) {
        CgShaderGraph.Link link = graph.linkInto(masterId, port);
        if (link == null || root == null) {
            CgShaderGraph.Instance master = graph.instance(masterId);
            String value = master == null ? null : master.valueFor(port);
            return value == null ? "vec4(1.0)" : value;
        }
        return "node_" + link.fromNode() + "_" + link.fromPort();
    }

    /**
     * Rewrites references to vertex-stage variables into their {@code v2f} reads.
     *
     * <p>The fragment stage cannot see a vertex-stage local, so every use of one has to become
     * {@code i.<field>}. Doing it here rather than in the compiler keeps {@link CgGraphCompiler} free of
     * any notion of stages, which is what lets the same emitter serve previews.</p>
     */
    private static String substituteVaryings(String code, List<Varying> varyings) {
        String out = code;
        for (Varying varying : varyings) {
            out = out.replace(varying.vertexVariable(), "i." + varying.field());
        }
        return out;
    }

    /**
     * The compiler indents to one level; a pass body sits at two.
     *
     * <p><b>Preserves the line count exactly</b>, blank lines included. It used to drop empties, which
     * was invisible in the output and fatal to {@link Result#ownerOfLine}: every line after the first
     * blank one would be attributed to the wrong node, and the error would point at a plausible
     * neighbour rather than the culprit.</p>
     */
    private static String reindent(String code) {
        if (code.isEmpty()) return "";
        StringBuilder out = new StringBuilder(code.length() + 32);
        for (String line : code.split("\n", -1)) {
            if (!line.isEmpty()) out.append("    ").append(line);
            out.append('\n');
        }
        // split() with a trailing newline yields one empty trailing element, so the loop added a newline
        // the source did not have.
        if (code.endsWith("\n")) out.setLength(out.length() - 1);
        return out.toString();
    }

    /** The 1-based line number the next append will start on. */
    private static int nextLine(StringBuilder out) {
        int lines = 1;
        for (int i = 0; i < out.length(); i++) {
            if (out.charAt(i) == '\n') lines++;
        }
        return lines;
    }

    /**
     * Shifts a compiler-relative line map onto the finished file.
     *
     * <p>The compiler numbers the statements it produced from 1; those land partway down a file with a
     * preamble, a struct and a function signature above them. This is that offset, applied once per
     * stage — the reason the mapping is recorded while writing rather than reconstructed afterwards.</p>
     */
    private static void mapOwners(Map<Integer, String> into, Map<Integer, String> compilerOwners,
                                  int startLine) {
        for (Map.Entry<Integer, String> owned : compilerOwners.entrySet()) {
            into.put(startLine + owned.getKey() - 1, owned.getValue());
        }
    }
}
