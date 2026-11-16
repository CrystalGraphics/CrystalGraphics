package com.crystalgraphics.material.shader;

import com.crystalgraphics.material.shader.syntax.CgShaderSyntax;
import com.crystalgraphics.material.shader.syntax.CgShaderTokens;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A surface's vertex stage as of last frame, for its Velocity pass: its factory's {@code fill} and every function its
 * {@code vertex()} reaches, written again under last frame's names (Unreal's previous-frame material compile,
 * {@code bCompilingPreviousFrame}, and {@code VertexFactoryGetPreviousWorldPosition}). The copy ends in
 * {@code vec3 _cg_previousWorld()}, the vertex's world position last frame.
 *
 * <pre>{@code
 * spec.previousVertex = PreviousFrame.code(file, code, functions, vertex, factory.fill(), path);   // SurfaceDomain
 * o.then = cg_PreviousClip(_cg_previousWorld());                                                   // VelocityPass
 * }</pre>
 *
 * <ul>
 *   <li>Only names a material writes are renamed: an engine library function or macro reads its own names where it is
 *       defined, so one reading a name of {@link #BINDINGS} takes the value as an argument instead
 *       ({@code PreviousFrameReachTest}).</li>
 *   <li>A frame record keeps no previous model: its copy reads this frame's, which is why a frame record never draws
 *       velocity.</li>
 * </ul>
 */
final class PreviousFrame {

    /** The vertex's world position last frame. */
    static final String WORLD = "_cg_previousWorld";
    /** What a function of the copy is named: its own name and this. */
    static final String SUFFIX = "_cgPrevious";

    /** Each name a material's vertex code reads that differs between frames, and last frame's (cg_env.glsl). */
    static final Map<String, String> BINDINGS = Map.of(
            "CG_OBJECT_TO_WORLD", "CG_PREVIOUS_OBJECT_TO_WORLD",
            "CG_OBJECT_POSITION", "CG_PREVIOUS_OBJECT_POSITION",
            "cg_Position", "cg_PreviousPosition",
            "cg_Time", "cg_PreviousTime",
            "CG_TIME_VEC4", "cg_PreviousTime",
            "CG_TIME", "CG_PREVIOUS_TIME");

    /** The names of {@link #BINDINGS} that are time: vertex code reading one moves its vertices by itself. */
    private static final Set<String> TIME = Set.of("cg_Time", "CG_TIME_VEC4", "CG_TIME");

    private PreviousFrame() {}

    /** The copy: {@code code}'s functions {@code vertex} reaches, then {@code _cg_previousWorld} running {@code fill} and it. */
    static String code(CgShaderSyntax file, List<CgShaderSyntax.Item> code,
                       Map<String, List<CgShaderSyntax.Function>> functions, @Nullable CgShaderSyntax.Function vertex,
                       String fill, String path) {
        Set<String> reach = SpecReader.reach(file, functions, vertex);
        Map<String, String> words = new HashMap<>(BINDINGS);
        for (String name : reach) words.put(name, name + SUFFIX);
        if (vertex != null) words.put(vertex.name(), SpecReader.MATERIAL_VERTEX + SUFFIX);

        StringBuilder sb = new StringBuilder(1024);
        CgShaderTokens t = file.tokens();
        for (CgShaderSyntax.Item i : code) {
            if (i instanceof CgShaderSyntax.Function f && reach.contains(f.name())) {
                sb.append(t.plain(i.first(), i.last(), words)).append('\n');
            }
        }
        sb.append("vec3 ").append(WORLD).append("() {\n    CgVertex v;\n");
        CgShaderTokens body = CgShaderTokens.lex(fill, path);
        if (body.count() > 0) sb.append("    ").append(body.plain(0, body.count() - 1, words)).append('\n');
        if (vertex != null) {
            sb.append("    v2f o;\n    ").append(SpecReader.MATERIAL_VERTEX).append(SUFFIX).append("(v, o);\n");
        }
        return sb.append("    return v.world;\n}\n").toString();
    }

    /** Whether {@code vertex}'s reach or {@code fill} reads time: its vertices move standing still (Unreal's WPO velocity). */
    static boolean animates(CgShaderSyntax file, Map<String, List<CgShaderSyntax.Function>> functions,
                            @Nullable CgShaderSyntax.Function vertex, String fill, String path) {
        for (String name : SpecReader.reach(file, functions, vertex)) {
            for (CgShaderSyntax.Function f : functions.get(name)) {
                for (String id : SpecReader.body(file, f).identifiers()) if (TIME.contains(id)) return true;
            }
        }
        CgShaderTokens body = CgShaderTokens.lex(fill, path);
        for (int i = 0; i < body.count(); i++) if (body.isIdent(i) && TIME.contains(body.text(i))) return true;
        return false;
    }
}
