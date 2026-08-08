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

    /**
     * Whether the emitted shader lights its own output.
     *
     * <h3>{@link #PREVIEW_LIT} is viewport shading, not a lighting model</h3>
     * <p>This engine has no lighting — {@code CgFrameBlock} carries no light term and
     * {@code CgFrameData.hasDirectionalLight()} returns false. What {@code PREVIEW_LIT} adds is a fixed
     * key light baked into the generated source as constants, exactly the way a modelling tool's
     * "material preview" viewport differs from its final render. <b>No engine state is involved</b>, so
     * nothing here pre-empts what a real lighting model will eventually look like.</p>
     *
     * <p>It exists because an unlit sphere is a filled circle. Orientation, curvature and the whole point
     * of the shape menu are carried by shading, and mostly by the <em>specular</em> term — a view-dependent
     * highlight survives even a black base colour, which is why Unity's default preview still reads as a
     * sphere with its albedo set to black.</p>
     *
     * <p><b>The shading is never parameterised by the graph.</b> Roughness, light colour and intensity are
     * constants below and are deliberately not master ports. A port that visibly changes the preview and
     * does nothing in game is worse than an absent one: it looks like a working feature. That is the same
     * rule that keeps Metallic and Smoothness off {@link CgMasterNode#FRAGMENT_PORTS}.</p>
     */
    public enum Shading {
        /** What ships. The fragment output is the graph's, untouched. */
        UNLIT,
        /**
         * A preview with no key light — the graph's own output, sRGB-encoded on the way out.
         *
         * <h3>Why this is not just {@link #UNLIT}</h3>
         * <p>It was, and the two are not the same thing: {@code UNLIT} is <b>what ships</b>, and a shipped
         * material must write the linear values the graph computed. A preview is looked at, so it has to
         * write what a display expects — and writing linear numbers into an RGBA8 the compositor then
         * treats as sRGB crushes the mid-tones.</p>
         *
         * <p>The visible cost was the Main Preview and a node's own thumbnail disagreeing about the same
         * value: {@code CgPreviewEmitter} encodes and this did not, so a Fresnel ramp came out with a
         * noticeably darker middle and a tighter rim in one panel than the other, on one graph, at the
         * same instant. Linear 0.5 displays at 0.5 unencoded and at 0.735 encoded, which is most of the
         * midtone range.</p>
         */
        PREVIEW_UNLIT,
        /** Preview only — a fixed world-space key light over the graph's output, encoded as above. */
        PREVIEW_LIT;

        /** Whether this mode is drawn into an editor panel rather than into the world. */
        boolean isPreview() {
            return this != UNLIT;
        }
    }

    // ── Preview lighting constants, all tunable in one place ────────────────

    /**
     * Key light direction, in <b>world</b> space.
     *
     * <p>World-fixed rather than attached to the camera, and that is the whole reason this is legible: a
     * headlight rotates with you, so the shading barely changes as the mesh turns and rotation stays
     * invisible — which was the complaint. A world-fixed light sweeps its highlight across the surface as
     * you orbit, which is what actually reads as motion.</p>
     */
    private static final String PREVIEW_LIGHT_DIR = "normalize(vec3(-0.45, 0.72, 0.55))";

    /** Floor, so nothing faces fully into black. Also what keeps a dark base colour from vanishing. */
    private static final String PREVIEW_AMBIENT = "0.28";
    private static final String PREVIEW_KEY = "0.80";

    /** The term doing most of the work — see {@link Shading#PREVIEW_LIT}. */
    private static final String PREVIEW_SPECULAR = "0.30";
    private static final String PREVIEW_SHININESS = "48.0";

    /** Carries the world normal across for the preview's lighting. Prefixed so it cannot collide with a
     * graph-generated {@code v_<node>_<port>}. */
    private static final String PREVIEW_NORMAL_FIELD = "v_cg_preview_normal";

    /** A value crossing from the vertex stage to the fragment stage. */
    public record Varying(String field, CgShaderType type, String vertexVariable) {
    }

    /** The emitted source, plus what it took to produce and what went wrong. */
    public record Result(String source, List<Varying> varyings, List<CgShaderProblem> problems,
                         Map<Integer, String> lineOwners) {

        /** The shape for callers with no interest in which node wrote which line. */
        public Result(String source, List<Varying> varyings, List<CgShaderProblem> problems) {
            this(source, varyings, problems, Map.of());
        }

        public boolean ok() {
            return problems.isEmpty();
        }

        /**
         * The messages alone, for a display that has no use for the attribution.
         *
         * <p>Derived rather than stored: the problems are the truth and this is a projection of them, so
         * the two cannot drift. It is what the status line and the logs want.</p>
         */
        public List<String> errors() {
            List<String> messages = new ArrayList<>(problems.size());
            for (CgShaderProblem problem : problems) messages.add(problem.message());
            return messages;
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
        return emit(graph, master, Shading.UNLIT);
    }

    /** As above, in a given shading mode. @see Shading */
    public static Result emit(CgShaderGraph graph, CgMasterNode master, Shading shading) {
        List<CgShaderProblem> errors = new ArrayList<>();
        String masterId = graph.outputId();
        if (masterId == null || graph.instance(masterId) == null) {
            errors.add(CgShaderProblem.graph("The graph's output node is not present, so there is nothing to emit toward"));
            return new Result("", List.of(), errors);
        }

        // IMPLICIT DEFAULTS FIRST, before a single line below looks at the graph's shape.
        //
        // These are the instances CgGraphCompiler synthesizes for an unconnected port that binds to a
        // node rather than to a literal — every UV-consuming node's UV slot, and Fresnel's Normal and
        // ViewDir. The compiler used to be the only caller, which meant they did not exist yet when the
        // stage assignment below ran: a synthesized node declaring VERTEX was never hoisted, and its
        // vertex-attribute reads (cg_TexCoord0, cg_Normal, cg_Position) were emitted into the FRAGMENT
        // stage, where they are not declared and the shader does not compile.
        //
        // Invisible from the editor, because previews take a different path entirely — CgPreviewEmitter
        // evaluates everything in the fragment stage against varyings it writes itself. So every affected
        // node previewed perfectly and produced a material that would not build, which is the worst
        // arrangement of the two.
        //
        // Idempotent, so the compiler calling it again per stage costs nothing. See its own doc.
        CgGraphCompiler.wireImplicitDefaults(graph);

        // Each block contributes its own roots. Declared as data on the master rather than as a hard-coded
        // pair here, so a port added when a lighting model lands needs no change in this file.
        List<String> vertexRoots = rootsFeeding(graph, masterId, CgMasterNode.VERTEX_PORTS);
        List<String> fragmentRoots = rootsFeeding(graph, masterId, CgMasterNode.FRAGMENT_PORTS);

        // Stage assignment. A node feeding a FRAGMENT block port that declares VERTEX has to be hoisted,
        // together with everything it depends on, or it would emit a vertex-only builtin into the
        // fragment stage.
        Set<String> vertexSet = idsOfAll(graph, vertexRoots);
        List<CgShaderGraph.Instance> fragmentChain = orderedFromAll(graph, fragmentRoots);
        for (CgShaderGraph.Instance instance : fragmentChain) {
            if (instance.type().domain() == CgShaderDomain.VERTEX) {
                vertexSet.addAll(idsOf(graph, instance.id()));
            }
        }

        checkDomains(graph, fragmentChain, vertexSet, errors);

        // The vertex stage emits its own roots first, then everything hoisted out of the fragment chain.
        // Both are just extra roots, which is why one helper covers them.
        List<String> allVertexRoots = new ArrayList<>(vertexRoots);
        for (CgShaderGraph.Instance instance : fragmentChain) {
            if (instance.type().domain() == CgShaderDomain.VERTEX) allVertexRoots.add(instance.id());
        }
        CgGraphCompiler.Result vertexCode = compileRoots(graph, allVertexRoots, Set.of(), errors);

        Set<String> fragmentSet = new LinkedHashSet<>();
        for (CgShaderGraph.Instance instance : fragmentChain) {
            if (!vertexSet.contains(instance.id())) fragmentSet.add(instance.id());
        }
        // The vertex-stage nodes are SKIPPED rather than absent: the fragment stage still needs their
        // variable names in order to read them, and substituteVaryings then rewrites those reads.
        CgGraphCompiler.Result fragmentCode = compileRoots(graph, fragmentRoots, vertexSet, errors);

        // AFTER both stages compile, because only then is a fed variable's type known. @see #checkMasterPorts
        checkMasterPorts(graph, masterId, vertexCode, fragmentCode, errors);

        List<Varying> varyings = findVaryings(graph, masterId, vertexSet, fragmentSet);
        Map<Integer, String> lineOwners = new LinkedHashMap<>();
        String source = write(graph, master, masterId, lineOwners, vertexCode, fragmentCode,
                varyings, shading);
        return new Result(source, varyings, errors, Map.copyOf(lineOwners));
    }

    /** The upstream node for each of {@code ports} that is actually wired, in declaration order. */
    private static List<String> rootsFeeding(CgShaderGraph graph, String masterId,
                                             List<CgShaderPort> ports) {
        List<String> roots = new ArrayList<>();
        for (CgShaderPort port : ports) {
            String root = rootFeeding(graph, masterId, port.id());
            // Distinct: two master ports fed by the same node must not compile it twice.
            if (root != null && !roots.contains(root)) roots.add(root);
        }
        return roots;
    }

    private static Set<String> idsOfAll(CgShaderGraph graph, List<String> roots) {
        Set<String> ids = new LinkedHashSet<>();
        for (String root : roots) ids.addAll(idsOf(graph, root));
        return ids;
    }

    /** Dependency-ordered union of every chain, with each node appearing once. */
    private static List<CgShaderGraph.Instance> orderedFromAll(CgShaderGraph graph, List<String> roots) {
        Map<String, CgShaderGraph.Instance> ordered = new LinkedHashMap<>();
        for (String root : roots) {
            for (CgShaderGraph.Instance instance : graph.orderedFrom(root)) {
                ordered.putIfAbsent(instance.id(), instance);
            }
        }
        return List.copyOf(ordered.values());
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
    private static CgGraphCompiler.Result compileRoots(CgShaderGraph graph, List<String> roots,
                                                       Set<String> initialSkip,
                                                       List<CgShaderProblem> errors) {
        // Each root skips whatever the previous ones already emitted, or a node feeding two master ports
        // would be declared twice and the GLSL would not compile.
        Set<String> skip = new LinkedHashSet<>(initialSkip);
        List<CgGraphCompiler.Result> parts = new ArrayList<>();
        for (String root : roots) {
            if (root == null) continue;
            CgGraphCompiler.Result part = CgGraphCompiler.compileFrom(graph, root, false, Set.copyOf(skip));
            errors.addAll(part.problems());
            parts.add(part);
            skip.addAll(idsOf(graph, root));
        }
        if (parts.isEmpty()) return new CgGraphCompiler.Result("", List.of(), Map.of(), List.of());
        return merge(parts);
    }

    /**
     * Joins several compiled chains into one stage body.
     *
     * <p><b>Line owners are shifted, not dropped.</b> Each part numbers its own lines from 1, so the
     * second chain's map would otherwise overwrite the first's entries with completely unrelated nodes —
     * and the symptom is a compile error pointing confidently at a plausible neighbour. That was already
     * latent when only hoisted nodes could produce a second part; multiple master ports make it routine.</p>
     */
    private static CgGraphCompiler.Result merge(List<CgGraphCompiler.Result> parts) {
        if (parts.size() == 1) return parts.get(0);

        StringBuilder code = new StringBuilder();
        Set<String> includes = new LinkedHashSet<>();
        Map<String, CgShaderType> outputTypes = new LinkedHashMap<>();
        Map<Integer, String> lineOwners = new LinkedHashMap<>();
        int lineOffset = 0;
        for (CgGraphCompiler.Result part : parts) {
            for (Map.Entry<Integer, String> owned : part.lineOwners().entrySet()) {
                lineOwners.put(lineOffset + owned.getKey(), owned.getValue());
            }
            lineOffset += countLines(part.code());
            code.append(part.code());
            includes.addAll(part.includes());
            // Carried through, not dropped: a hoisted chain declares variables the merged result is the
            // only remaining record of, and a caller asking what type one is would otherwise get null
            // for exactly the nodes that were moved.
            outputTypes.putAll(part.outputTypes());
        }
        return new CgGraphCompiler.Result(code.toString(), List.copyOf(includes),
                Map.copyOf(lineOwners), List.of(), Map.copyOf(outputTypes));
    }

    /** Lines a code fragment occupies. A trailing newline ends the last line rather than starting one. */
    private static int countLines(String code) {
        if (code.isEmpty()) return 0;
        int lines = 0;
        for (int i = 0; i < code.length(); i++) {
            if (code.charAt(i) == '\n') lines++;
        }
        return code.endsWith("\n") ? lines : lines + 1;
    }

    /** A fragment-domain node feeding the vertex stage is impossible, not merely awkward. */
    private static void checkDomains(CgShaderGraph graph, List<CgShaderGraph.Instance> colourChain,
                                     Set<String> vertexSet, List<CgShaderProblem> errors) {
        for (CgShaderGraph.Instance instance : colourChain) {
            if (!vertexSet.contains(instance.id())) continue;
            if (instance.type().domain() == CgShaderDomain.FRAGMENT) {
                errors.add(CgShaderProblem.node(instance.id(),
                        "Node '" + instance.id() + "' (" + instance.type().id()
                        + ") is fragment-only but something in the vertex stage depends on it — the "
                        + "vertex shader has already run by the time a fragment value exists"));
            }
        }
    }

    /**
     * Every vertex-stage value read by the fragment stage.
     *
     * <p>One {@code v2f} field each, named after the variable that produced it so the generated struct
     * is readable rather than {@code v0, v1, v2}.</p>
     */
    private static List<Varying> findVaryings(CgShaderGraph graph, String masterId, Set<String> vertexSet,
                                              Set<String> fragmentSet) {
        Map<String, Varying> found = new LinkedHashMap<>();
        for (CgShaderGraph.Link link : graph.links()) {
            if (!vertexSet.contains(link.fromNode())) continue;
            // The MASTER is a fragment-side consumer too, and missing that is what made `UV` wired
            // straight into `Base Color` render as a flat white surface.
            //
            // The master is in neither set — it is the thing the sets are computed toward — so a link
            // ending on it matched nothing here, no varying was created, and the fragment body was left
            // referencing `node_uv_Out`, a local declared in the VERTEX function. That is an undefined
            // identifier, so the whole material failed to compile and fell back.
            //
            // Only its FRAGMENT-block ports count: a vertex node feeding `Position` is already in the
            // right stage and needs no crossing.
            boolean intoFragmentStage = fragmentSet.contains(link.toNode())
                    || (link.toNode().equals(masterId)
                        && CgMasterNode.blockOf(link.toPort()) == CgShaderDomain.FRAGMENT);
            if (!intoFragmentStage) continue;
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
                                List<Varying> varyings, Shading shading) {
        boolean lit = shading == Shading.PREVIEW_LIT;
        boolean preview = shading.isPreview();
        StringBuilder out = new StringBuilder(1024);
        out.append("// Generated from a shader graph. Edits here are lost on the next compile.\n");
        out.append("#type ").append(master.vertexFormat()).append("\n\n");

        Set<String> includes = new LinkedHashSet<>(vertex.includes());
        includes.addAll(fragment.includes());
        // For the encode below. Only in a preview — a shipped material must not carry an include it has
        // no call to, and the whole point of UNLIT is that its output is the graph's, untouched.
        if (preview) includes.add("crystalgraphics:shaders/lib/color.glsl");
        for (String include : includes) {
            out.append("#include \"").append(include).append("\"\n");
        }
        if (!includes.isEmpty()) out.append('\n');

        out.append("Tags { \"RenderType\" = \"").append(master.renderType()).append("\" }\n");
        out.append("Queue = \"").append(master.queue()).append("\"\n\n");

        if (!master.shaderProperties().isEmpty()) {
            out.append("Properties {\n");
            for (CgMasterNode.Property property : master.shaderProperties()) {
                // propertyDeclarationType, never propertyTypeName: a VEC3 property is DECLARED as vec4
                // because the parser bans vec3 over STD140 alignment, and is read back with a .xyz
                // suffix. See CgShaderType.propertyDeclarationType for why the pair lives there.
                out.append("    ").append(property.name())
                        .append(" (\"").append(property.name()).append("\", ")
                        .append(property.type().propertyDeclarationType()).append(") = ")
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
        // The preview's own normal, always present in lit mode — which also means the empty-struct guard
        // below can never fire while lit.
        if (lit) out.append("    vec3 ").append(PREVIEW_NORMAL_FIELD).append(";\n");
        if (varyings.isEmpty() && !lit) {
            // GLSL has no empty struct — `struct v2f { };` is a COMPILE ERROR, not an empty type.
            //
            // This shipped with 6.3.5 and was invisible for as long as nothing fed the emitter's output
            // to a driver: `CgShaderParser` accepts it happily (it is structurally a fine `.shader`), so
            // every test passed, and the source pane displayed it looking entirely correct. The first
            // thing to actually compile one was the main preview, which drew a white sphere — the
            // material's fallback — for a graph whose GLSL was right in every other respect.
            //
            // A single padding member is the whole fix. The alternative, omitting the struct when empty,
            // means the vertex/fragment signatures have to vary too, which is a second shape of generated
            // file for no gain.
            out.append("    float unused;\n");
        }
        out.append("};\n\n");

        out.append("Pass {\n");
        out.append("    Tags { \"LightMode\" = \"Forward\" }\n\n");

        out.append("    void vertex(out v2f o) {\n");
        mapOwners(lineOwners, vertex.lineOwners(), nextLine(out));
        out.append(reindent(vertex.code()));
        // Adapted like the fragment ports: a vec4 wired into Position would otherwise become
        // vec4(someVec4, 1.0). No varying substitution — this IS the vertex stage.
        out.append("        gl_Position = CG_MATRIX_MVP * vec4(")
                .append(adapted(graph, masterId, CgMasterNode.POSITION, vertex, null))
                .append(", 1.0);\n");
        for (Varying varying : varyings) {
            out.append("        o.").append(varying.field()).append(" = ")
                    .append(varying.vertexVariable()).append(";\n");
        }
        if (lit) {
            // The mesh's own normal, in world space. Deliberately NOT the master's Vertex block: there is
            // no Normal port, and giving one a consumer that exists only in the preview is exactly the
            // trap Shading's own doc warns about.
            out.append("        o.").append(PREVIEW_NORMAL_FIELD)
                    .append(" = CG_NORMAL_MATRIX * cg_Normal;\n");
        }
        out.append("    }\n\n");

        out.append("    void fragment(in v2f i, out vec4 fragColor) {\n");
        mapOwners(lineOwners, fragment.lineOwners(), nextLine(out));
        out.append(reindent(substituteVaryings(fragment.code(), varyings)));

        String baseColor = fragmentExpression(graph, masterId, CgMasterNode.BASE_COLOR, varyings,
                fragment, vertex);
        String alpha = fragmentExpression(graph, masterId, CgMasterNode.ALPHA, varyings, fragment, vertex);
        String clip = fragmentExpression(graph, masterId, CgMasterNode.ALPHA_CLIP_THRESHOLD, varyings,
                fragment, vertex);

        // Alpha is resolved into a local before the clip test so the expression is evaluated once — it may
        // be an arbitrary node chain, and writing it twice would duplicate whatever work produced it.
        out.append("        float cg_alpha = ").append(alpha).append(";\n");

        // Emitted only when the threshold is genuinely wired or non-zero. A constant `< 0.0` is dead code
        // every driver would strip, but it is also a `discard` sitting in the source of every shader in
        // the project, and `discard` is the single most misread instruction in a fragment body — someone
        // will eventually conclude their opaque shader is doing alpha testing.
        if (isClippingEnabled(graph, masterId, clip)) {
            out.append("        if (cg_alpha < ").append(clip).append(") discard;\n");
        }

        if (lit) {
            appendPreviewLighting(out, baseColor);
        } else {
            out.append("        fragColor = vec4(").append(displayed(baseColor, preview))
                    .append(", cg_alpha);\n");
        }
        out.append("    }\n");
        out.append("}\n");
        return out.toString();
    }

    /**
     * Blinn-Phong over the graph's colour, in <b>view space</b>.
     *
     * <h3>Why view space, when the light is world-fixed</h3>
     * <p>Both the normal and the world light direction are rotated into view space, which leaves the view
     * vector as a literal {@code (0,0,1)} — the camera is orthographic, so every pixel shares it. That
     * saves reconstructing a camera position out of the inverse view matrix, which {@code cg_env.glsl}
     * does not expose.</p>
     *
     * <p>The light stays world-fixed regardless: it is a world-space constant transformed by the view
     * matrix, so orbiting changes its direction <em>relative to the camera</em> and the highlight sweeps
     * across the surface. Baking the direction in view space instead would give a headlight, and the
     * shading would barely change as the mesh turned.</p>
     *
     * <p>Specular is added rather than multiplied into the base colour, which is what lets a black surface
     * still read as a curved one.</p>
     */
    private static void appendPreviewLighting(StringBuilder out, String baseColor) {
        out.append("        // Preview-only shading. Not a lighting model — see CgShaderEmitter.Shading.\n");
        out.append("        vec3 cg_n = normalize(mat3(cg_ViewMatrix) * i.")
                .append(PREVIEW_NORMAL_FIELD).append(");\n");
        out.append("        vec3 cg_l = normalize(mat3(cg_ViewMatrix) * ")
                .append(PREVIEW_LIGHT_DIR).append(");\n");
        // Orthographic: one view vector for every pixel.
        out.append("        vec3 cg_v = vec3(0.0, 0.0, 1.0);\n");
        out.append("        vec3 cg_h = normalize(cg_l + cg_v);\n");
        // Two-sided: a back-facing normal would otherwise leave a cap or a quad's reverse pitch black.
        out.append("        if (cg_n.z < 0.0) cg_n = -cg_n;\n");
        out.append("        float cg_ndl = max(dot(cg_n, cg_l), 0.0);\n");
        out.append("        float cg_ndh = max(dot(cg_n, cg_h), 0.0);\n");
        out.append("        vec3 cg_base = ").append(baseColor).append(";\n");
        out.append("        vec3 cg_lit = cg_base * (").append(PREVIEW_AMBIENT)
                .append(" + ").append(PREVIEW_KEY).append(" * cg_ndl)\n");
        out.append("                + vec3(").append(PREVIEW_SPECULAR).append(") * pow(cg_ndh, ")
                .append(PREVIEW_SHININESS).append(");\n");
        out.append("        fragColor = vec4(").append(displayed("cg_lit", true))
                .append(", cg_alpha);\n");
    }

    /**
     * An RGB expression as it should be WRITTEN, which is not the same as what the graph computed.
     *
     * <p>A shader's values are linear; a display is not. In a preview the result is looked at directly, so
     * it is encoded — the same call {@code CgPreviewEmitter} makes for a node thumbnail, and the reason
     * the two panels now agree about a value instead of the Main Preview coming out darker with a tighter
     * falloff. Alpha is deliberately untouched by the encode: it is a blend weight, not a colour.</p>
     *
     * <p>In a shipped material this is the identity. That is not an optimisation, it is the contract —
     * {@code UNLIT} means the fragment output is the graph's, and a material that silently applied a
     * transfer function would be lying about what it computes to everything downstream of it.</p>
     */
    private static String displayed(String rgb, boolean preview) {
        return preview ? "linear_to_srgb(" + rgb + ")" : rgb;
    }

    /** A master input as the fragment stage should read it — adapted to the port's type, then varyings
     * substituted. */
    private static String fragmentExpression(CgShaderGraph graph, String masterId, String port,
                                             List<Varying> varyings, CgGraphCompiler.Result stage,
                                             CgGraphCompiler.Result vertex) {
        return substituteVaryings(adapted(graph, masterId, port, stage, vertex), varyings);
    }

    /**
     * The master's input expression, converted to the type the master port declares.
     *
     * <p><b>The master narrows where an ordinary node would refuse to.</b> {@code CgGraphCompiler} allows
     * a truncating swizzle only into a {@code DYNAMIC} port or a link it synthesised itself, precisely so
     * that a user-drawn edge cannot silently drop components — but the master never goes through that
     * path at all, because it emits no code and so has no inputs for the compiler to resolve. Without
     * this, wiring a {@code vec4} into {@code BaseColor} (a {@code vec3} since the block gained a real
     * {@code Alpha}) produces {@code vec4(someVec4, cg_alpha)}, which fails in the driver rather than in
     * the editor.</p>
     *
     * <p>Allowing it here is the same carve-out the implicit-UV links already have, and rests on the same
     * reasoning: this is the compiler's own conversion at a boundary it defines, not a user's edge being
     * quietly reinterpreted. A graph that wired an RGBA colour into Base Color plainly meant its RGB.</p>
     */
    private static String adapted(CgShaderGraph graph, String masterId, String port,
                                  CgGraphCompiler.Result stage, @Nullable CgGraphCompiler.Result other) {
        MasterFeed feed = feedInto(graph, masterId, port, stage, other);
        if (feed == null) return expressionFor(graph, masterId, port);
        if (!feed.needsConversion() || !feed.isConvertible()) return feed.expression();

        if (feed.have().components() > feed.want().components()) {
            return feed.expression() + "." + "xyzw".substring(0, feed.want().components());
        }
        return feed.have().promote(feed.expression(), feed.want());
    }

    /**
     * What a master port is being fed and what it wants — the shared half of adapting and checking.
     *
     * <p>{@code null} when there is nothing to do: an unwired port (its literal is already written in the
     * right type), or a type this emitter cannot name on one side or the other.</p>
     *
     * @param expression the GLSL the port reads
     * @param have       the type that expression actually is
     * @param want       the type the port declares
     */
    private record MasterFeed(String expression, CgShaderType have, CgShaderType want) {

        boolean needsConversion() {
            return have != want;
        }

        /**
         * Whether the difference is one the emitter can write.
         *
         * <p>Numeric vectors adapt in both directions — the master narrows where an ordinary node would
         * refuse to, which the method above explains. Nothing else does: a sampler is not a colour and
         * there is no arithmetic that would make it one.</p>
         */
        boolean isConvertible() {
            return have.isNumericVector() && want.isNumericVector();
        }
    }

    /** @see MasterFeed */
    @Nullable
    private static MasterFeed feedInto(CgShaderGraph graph, String masterId, String port,
                                       CgGraphCompiler.Result stage,
                                       @Nullable CgGraphCompiler.Result other) {
        if (graph.linkInto(masterId, port) == null) return null;
        String expression = expressionFor(graph, masterId, port);

        CgShaderType want = declaredTypeOf(port);
        // BOTH stages, and the fallback is the whole point. A vertex-domain node feeding a fragment port
        // — `UV` wired straight into `Base Color` is the everyday case — is hoisted into the vertex stage
        // and crosses as a varying, so its variable was declared over THERE and the fragment stage's own
        // type map knows nothing about it. Looking in one stage silently produced `have == null`, which
        // adapted() reads as "nothing to convert": the vec4 was written into `vec4(..., cg_alpha)` whole,
        // which is a GLSL error, and the material fell back to white.
        //
        // The tell was that inserting any fragment-stage node in between — a Fraction, say — fixed it,
        // because then the variable really was declared in the stage being asked.
        CgShaderType have = stage.typeOf(expression);
        if (have == null && other != null) have = other.typeOf(expression);
        if (want == null || have == null) return null;
        return new MasterFeed(expression, have, want);
    }

    /**
     * Reports every master port fed something it cannot be adapted from.
     *
     * <p><b>The master is the one place with no type checking at all</b>, and that is structural rather
     * than an oversight: {@code CgGraphCompiler} validates a link when it resolves the consuming node's
     * inputs, and the master emits no code, so it has no inputs to resolve and never reaches that path.
     * Ordinary edges have been checked since 6.3.3; this is the hole left beside them.</p>
     *
     * <p>What it cost: wiring a texture into {@code Base Color} emitted
     * {@code vec4(someSampler2D, cg_alpha)}. That is not a graph the editor refuses — it is a
     * {@code .shader} that <b>parses</b>, fails in the driver, and shows up as a white material with the
     * real complaint buried in a GL log. Reporting it here puts the message where the mistake is.</p>
     *
     * <p>Checked <b>after</b> both stages compile, because that is when a variable's type is known — and
     * across both, since a vertex-domain node feeding a fragment port declares its variable in the other
     * stage. @see #feedInto</p>
     */
    private static void checkMasterPorts(CgShaderGraph graph, String masterId,
                                         CgGraphCompiler.Result vertex, CgGraphCompiler.Result fragment,
                                         List<CgShaderProblem> errors) {
        checkMasterPorts(graph, masterId, CgMasterNode.VERTEX_PORTS, vertex, null, errors);
        checkMasterPorts(graph, masterId, CgMasterNode.FRAGMENT_PORTS, fragment, vertex, errors);
    }

    private static void checkMasterPorts(CgShaderGraph graph, String masterId,
                                         List<CgShaderPort> ports, CgGraphCompiler.Result stage,
                                         @Nullable CgGraphCompiler.Result other,
                                         List<CgShaderProblem> errors) {
        for (CgShaderPort port : ports) {
            MasterFeed feed = feedInto(graph, masterId, port.id(), stage, other);
            if (feed == null || !feed.needsConversion() || feed.isConvertible()) continue;
            errors.add(CgShaderProblem.port(masterId, port.id(),
                    "Cannot feed " + feed.have().glsl() + " into " + port.id()
                    + ", which is " + feed.want().glsl()));
        }
    }

    @Nullable
    private static CgShaderType declaredTypeOf(String portId) {
        for (CgShaderPort port : CgMasterNode.FRAGMENT_PORTS) {
            if (port.id().equals(portId)) return port.type();
        }
        for (CgShaderPort port : CgMasterNode.VERTEX_PORTS) {
            if (port.id().equals(portId)) return port.type();
        }
        return null;
    }

    /**
     * Whether the alpha-clip branch is worth writing at all.
     *
     * <p>Wired means yes, whatever it resolves to. Unwired means it is a literal, and only a non-zero one
     * can ever discard anything.</p>
     */
    private static boolean isClippingEnabled(CgShaderGraph graph, String masterId, String threshold) {
        if (graph.linkInto(masterId, CgMasterNode.ALPHA_CLIP_THRESHOLD) != null) return true;
        try {
            return Float.parseFloat(threshold.trim()) > 0f;
        } catch (NumberFormatException notALiteral) {
            // An engine expression or something we cannot evaluate here — assume it matters. Emitting a
            // branch that turns out to be dead is free; skipping one that was not is a silent behaviour
            // change with nothing to search for.
            return true;
        }
    }

    /** The expression the master reads for one of its inputs: an upstream variable, or its default. */
    private static String expressionFor(CgShaderGraph graph, String masterId, String port) {
        CgShaderGraph.Link link = graph.linkInto(masterId, port);
        if (link != null) return "node_" + link.fromNode() + "_" + link.fromPort();

        CgShaderGraph.Instance master = graph.instance(masterId);
        String authored = master == null ? null : master.valueFor(port);
        if (authored != null) return authored;

        // The port's own declared default, rather than a constant written here. There used to be a
        // hard-coded `vec4(1.0)`, which was right for exactly as long as every master port was a vec4.
        String declared = CgMasterNode.defaultExpressionFor(port);
        return declared == null ? "0.0" : declared;
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
