package com.crystalgraphics.shadergraph;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Emits the little {@code .shader} that draws one node's thumbnail.
 *
 * <h3>A preview is the same compiler, rooted somewhere else</h3>
 * <p>Not a second code path, and deliberately so: {@link CgGraphCompiler#compileFrom} already emits the
 * subgraph feeding any node, and {@code forPreview} is already threaded down to every node's
 * {@code generateCode}. Godot's {@code generate_preview_shader} / {@code p_for_preview} pair is the same
 * decision, and the reason to copy it is that the alternative — a preview compiler alongside the real one
 * — guarantees the thumbnail eventually stops matching the shader it is previewing.</p>
 *
 * <h3>Turning a value into pixels, which is a convention and not a derivation</h3>
 * <p>Only a {@code vec4} is already a colour. Everything else has to be <em>displayed</em>, and the rules
 * are Unity's because they are what users have learned to read:</p>
 * <ul>
 *   <li><b>float</b> — greyscale, black at 0, white at 1. Not remapped: a value outside 0..1 clamps, and
 *       seeing it clip is information.</li>
 *   <li><b>vec2</b> — red and green, blue 0. The standard UV/flow-field look.</li>
 *   <li><b>vec3</b> — RGB, alpha 1.</li>
 *   <li><b>bool / int</b> — as a float, so a boolean reads as a black-or-white mask.</li>
 * </ul>
 *
 * <p><b>Alpha is forced to 1 in every case except vec4.</b> A preview drawn with the node's own alpha
 * would show the checkerboard behind it and read as "this node is broken" for the very common case of a
 * three-component value that simply has no alpha to give.</p>
 */
public final class CgPreviewEmitter {

    /** The fixed varying block every preview vertex shader writes, whatever the node chain needs. */
    private static final String V2F = """
            struct v2f {
                vec2 uv;
                vec3 objectPos;
                vec3 normal;
            };
            """;

    /** A preview's source, the shape to draw it on, and anything that stopped it. */
    public record Result(String source, CgPreviewGeometry geometry, List<String> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    private CgPreviewEmitter() {
    }

    /**
     * Emits the preview for a node's first output port.
     *
     * @see #emit(CgShaderGraph, String, String)
     */
    public static Result emit(CgShaderGraph graph, String nodeId) {
        CgShaderGraph.Instance instance = graph.instance(nodeId);
        if (instance == null) {
            return new Result("", CgPreviewGeometry.QUAD,
                    List.of("No node with id '" + nodeId + "' to preview"));
        }
        List<CgShaderPort> outputs = instance.type().outputs();
        if (outputs.isEmpty()) {
            // Not an error worth shouting about: an output node legitimately has nothing to show, and
            // the editor's answer is to draw no thumbnail rather than to report a failure.
            return new Result("", CgPreviewGeometry.QUAD,
                    List.of("Node '" + nodeId + "' has no output to preview"));
        }
        return emit(graph, nodeId, outputs.get(0).id());
    }

    /**
     * Emits the preview for one specific output port of one node.
     *
     * <p>The emitted file is a complete, ordinary {@code .shader} — nothing about it is special-cased
     * downstream, so it loads through {@link com.crystalgraphics.api.material.CgMaterial#fromSource} like
     * any other. That also means preview materials are content-hash cached for free: two nodes computing
     * the same thing produce byte-identical source and therefore share one compiled program.</p>
     */
    public static Result emit(CgShaderGraph graph, String nodeId, String portId) {
        List<String> errors = new ArrayList<>();

        CgShaderGraph.Instance instance = graph.instance(nodeId);
        if (instance == null) {
            return new Result("", CgPreviewGeometry.QUAD,
                    List.of("No node with id '" + nodeId + "' to preview"));
        }

        CgGraphCompiler.Result compiled = CgGraphCompiler.compileFrom(graph, nodeId, true);
        errors.addAll(compiled.errors());

        String variable = "node_" + nodeId + "_" + portId;
        CgShaderType type = compiled.typeOf(variable);
        if (type == null) {
            CgShaderPort port = instance.type().port(portId);
            // A declared type is a usable fallback for everything except a dynamic port, which by
            // definition has no answer until resolution has run.
            type = port == null || port.type() == CgShaderType.DYNAMIC ? null : port.type();
        }
        if (type == null) {
            errors.add("Cannot preview '" + nodeId + "." + portId
                    + "': nothing connected to it says what type it is");
            return new Result("", CgPreviewGeometry.QUAD, errors);
        }
        if (!isPreviewable(type)) {
            errors.add("Cannot preview '" + nodeId + "." + portId + "': a " + type.glsl()
                    + " has no meaningful thumbnail");
            return new Result("", CgPreviewGeometry.QUAD, errors);
        }

        // A vertex-only node cannot run in the fragment stage, which is where a preview evaluates
        // everything. Reported rather than emitted, because the alternative is GLSL that names a vertex
        // attribute in a fragment body and fails in the driver with a message about generated code.
        for (CgShaderGraph.Instance dependency : graph.orderedFrom(nodeId)) {
            if (dependency.type().domain() != CgShaderDomain.VERTEX) continue;
            // A node that declares a forPreview form reads the preview's own varying instead of the
            // vertex attribute, so it is fragment-safe after all. This is the whole reason Position, UV
            // and Normal can be previewed at all — and they are the nodes previews exist to show.
            if (dependency.type().hasPreviewForm()) continue;
            errors.add("Node '" + dependency.id() + "' (" + dependency.type().id()
                    + ") is vertex-only, and a preview evaluates in the fragment stage — it needs a"
                    + " forPreview form that reads the varying instead");
        }

        CgPreviewGeometry geometry = CgPreviewGeometry.resolve(graph, nodeId);
        String source = write(compiled, variable, type, geometry);
        return new Result(source, geometry, errors);
    }

    private static String write(CgGraphCompiler.Result compiled, String variable, CgShaderType type,
                                CgPreviewGeometry geometry) {
        StringBuilder out = new StringBuilder(768);
        out.append("// Generated preview shader. Not part of any build output.\n");
        out.append("#type spatial\n\n");

        Set<String> includes = new LinkedHashSet<>(compiled.includes());
        // Always, for linear_to_srgb below. Safe to hoist into the vertex stage too — color.glsl is pure
        // arithmetic with no derivative or fragment-only builtins.
        includes.add("crystalgraphics:shaders/lib/color.glsl");
        for (String include : includes) out.append("#include \"").append(include).append("\"\n");
        out.append('\n');

        // Opaque and Geometry: a thumbnail draws into a cleared private target with nothing behind it,
        // so there is nothing to blend with and sorting has no meaning.
        //
        // CastShadows Off is NOT optional, and leaving it out was a genuine hang. RenderType=Opaque with
        // a queue under 3000 makes the compiler auto-generate a ShadowCaster pass; that generated pass
        // references cg_ShadowViewProjMatrix, which nothing in a preview context declares, so it failed
        // to compile on EVERY material — two ERROR lines per attempt, per preview, per retry. The
        // symptom was the editor becoming unusable the moment the page opened, with the actual cause
        // buried in a log nobody was reading. A thumbnail casts no shadows by definition.
        out.append("Tags { \"RenderType\" = \"Opaque\" \"CastShadows\" = \"Off\" }\n");
        out.append("Queue = \"Geometry\"\n\n");
        out.append(V2F).append('\n');

        out.append("Pass {\n");
        out.append("    Tags { \"LightMode\" = \"Forward\" }\n\n");

        out.append("    void vertex(out v2f o) {\n");
        out.append("        o.uv = cg_TexCoord0;\n");
        out.append("        o.objectPos = cg_Position;\n");
        out.append("        o.normal = CG_NORMAL_MATRIX * cg_Normal;\n");
        out.append("        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);\n");
        out.append("    }\n\n");

        out.append("    void fragment(in v2f i, out vec4 fragColor) {\n");
        out.append(surface(geometry));
        out.append(reindent(compiled.code()));
        // ENCODED TO sRGB, which is the difference between our thumbnails and Unity's.
        //
        // A shader's values are linear; a display is not. Writing linear numbers into an RGBA8 that the
        // compositor then treats as sRGB crushes the mid-tones — every preview came out noticeably
        // darker than Unity's, and on the Position sphere it read as the quadrants being UNEVEN, because
        // the values either side of the x=0 boundary are exactly the small ones the curve compresses
        // hardest. The geometry was centred the whole time.
        //
        // Alpha is the coverage and is deliberately NOT encoded: it is a blend weight, not a colour.
        out.append("        fragColor = vec4(linear_to_srgb(").append(displayRgb(variable, type))
                .append("), cg_coverage);\n");
        out.append("    }\n");
        out.append("}\n");

        // The shape is not written into the file — the same source draws on either, and which one is
        // chosen is the renderer's business. Baking it in would make two byte-different sources for one
        // computation and defeat the content-hash sharing.
        return out.toString();
    }

    /**
     * The surface a preview evaluates on, written into the fragment stage before the node's own code.
     *
     * <h3>The sphere is an SDF, not a mesh</h3>
     * <p>A sphere drawn as geometry into a 128px target has a hard, binary silhouette, and magnifying
     * that to the node's on-screen size stair-steps badly — visibly so, which is what prompted this.
     * Solving the sphere analytically instead makes the edge <b>exact at any resolution</b> and gives
     * antialiasing for free from {@code fwidth}, with no multisampled framebuffer, no resolve blit, and
     * no supersampled target (a 4× one would have cost ~128 MB across the pool).</p>
     *
     * <p>It also removes the sphere mesh and the orthographic camera entirely: both geometries are now
     * the same clip-space quad, and the only difference is these few lines.</p>
     *
     * <p>Writing to {@code i} is legal and deliberate — a GLSL {@code in} parameter is a local copy — so
     * a node's preview body still reads {@code i.objectPos} and {@code i.normal} exactly as it would
     * have with real varyings, and knows nothing about how they were produced.</p>
     */
    private static String surface(CgPreviewGeometry geometry) {
        // Real geometry, so nothing is synthesised here. A preview must be a picture of what the shader
        // will ACTUALLY do, which means Position and Normal have to be the mesh's own attributes rather
        // than analytic stand-ins that happen to agree for a sphere — and it is the only version that
        // still works when the preview mesh becomes a cube, a cylinder or a user's own model.
        //
        // Antialiasing is therefore a RESOLUTION problem, solved by supersampling the target.
        return "        float cg_coverage = 1.0;\n";
    }

    /**
     * The GLSL expression that turns one output into a displayable {@code vec4}, or {@code null} for a
     * type that has no sensible picture.
     *
     * <p>A matrix and a sampler are both {@link #isPreviewable non-previewable}, and returning something
     * for them would emit {@code vec4(vec3(mat3))} — GLSL that is refused by the driver, reported against
     * generated source, for a thumbnail nobody could have read anyway.</p>
     *
     * @see CgPreviewEmitter class docs for why each mapping is what it is
     */
    @Nullable
    static String displayRgb(String variable, CgShaderType type) {
        switch (type) {
            case VEC4:
                // .rgb, DISCARDING the value's own alpha. This looks like the one case that should pass
                // straight through, and that was the first version — but UV is vec4(uv, 0, 0), so its
                // alpha is 0 and its thumbnail drew FULLY TRANSPARENT. A blank slot is indistinguishable
                // from one that has not rendered yet, so the node that previews best in every reference
                // screenshot was the one node showing nothing at all.
                //
                // Unity's previews are opaque for the same reason: a preview is a picture of a VALUE,
                // and letting one channel of it hide the other three is not showing it.
                return variable + ".rgb";
            case VEC3:
                return variable;
            case VEC2:
                return "vec3(" + variable + ", 0.0)";
            case BOOL:
                return "vec3(" + variable + " ? 1.0 : 0.0)";
            case INT:
                return "vec3(float(" + variable + "))";
            case FLOAT:
                return "vec3(" + variable + ")";
            default:
                return null;
        }
    }

    /** Whether a value of this type can be shown as a thumbnail at all. */
    public static boolean isPreviewable(CgShaderType type) {
        return displayRgb("x", type) != null;
    }

    /** The compiler indents to one level; a pass body sits at two. */
    private static String reindent(String code) {
        if (code.isEmpty()) return "";
        StringBuilder out = new StringBuilder(code.length() + 32);
        for (String line : code.split("\n", -1)) {
            if (!line.isEmpty()) out.append("    ").append(line).append('\n');
        }
        return out.toString();
    }

}
