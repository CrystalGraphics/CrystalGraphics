package com.crystalgraphics.material.parse;

import com.crystalgraphics.api.material.CgVertexFactory;
import com.crystalgraphics.util.io.CgIO;
import com.github.bsideup.jabel.Desugar;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The material format's templates, read from files the first time a material names one, as an {@code #include}
 * resolves: a vertex factory ({@code VertexFactory Sprites}) and a ui primitive ({@code Primitive Quad}). A bare name
 * is the engine's, a namespaced one a mod's:
 *
 * <pre>{@code
 * VertexFactory Sprites            ->  crystalgraphics:shaders/factories/Sprites.factory
 * VertexFactory mymod:Ribbons      ->  mymod:shaders/factories/Ribbons.factory
 * Primitive mymod:Ring             ->  mymod:shaders/primitives/Ring.primitive
 * }</pre>
 *
 * <p>A factory file, every block but {@code Factory} and {@code fill} optional:</p>
 *
 * <pre>{@code
 * Factory { VertexType none  Parameters cg_Ribbon }      // its program's #type; PlacesNothing On: vertex() places it
 * EngineBuffers { myribbons }                             // CgEngineBufferRegistry tokens its draws bind
 * Tags { "Ribbon" = "Flat" }                              // material-level tags its renderer reads
 * Properties { _Points ("Points", sampler2D) = "black" }  // bound by its draws; a material may not declare them
 * Declarations { #include "mymod:shaders/ribbon.glsl"  RibbonPoint cg_Ribbon; }
 * Parameters { float width;  flat int segment;  vec3 frame.normal; }   // what a fragment may read of cg_Ribbon
 * void fill(inout CgVertex v) { cg_Ribbon = ribbon_point(CG_VERTEX_ID); ... v.world = cg_Ribbon.world; }
 * void defaults(inout CgSurface s) { s.light = cg_Ribbon.light; }   // what the material starts from (optional)
 * }</pre>
 *
 * <p>{@code Factory { Fade cg_Vfx.presence }} fades all the material adds by that expression after {@code surface()},
 * by its blend mode, so no material applies it itself.</p>
 *
 * <p>A primitive file: {@code Primitive { Queue Transparent }}, {@code EngineBuffers}, {@code Declarations},
 * {@code Properties}, a {@code RenderState}, {@code struct v2f}, {@code vertex(out v2f o)} and
 * {@code fragment(in v2f i, out vec4 fragColor)}, which runs the material at {@code CG_SURFACE(input, s)}: a
 * {@code CgUiInput} and {@code CgUiSurface} it fills before and writes {@code fragColor} from after.</p>
 *
 * <ul>
 *   <li>{@code fill} is inlined into the generated vertex, after {@code CgVertex v} is declared; it sets every field,
 *       {@code world} last, and the parameters variable.</li>
 *   <li>{@code #pragma} lines in {@code Declarations} go to the program's preamble, the rest into each pass.</li>
 *   <li>A factory registered in Java ({@link CgVertexFactory#register}) is found before a file.</li>
 *   <li>{@link #forget()} drops what was read, so the next parse reads the files again: a shader reload.</li>
 * </ul>
 */
public final class CgFormatTemplates {

    /** A ui primitive: what {@code #domain ui} lowers around a material's {@code surface()}. */
    @Desugar
    record Primitive(String name, List<String> buffers, String pragmas, String declarations, String properties,
                     String queue, String renderState, String v2f, String vertex, String fragment) {
    }

    /** Where a primitive's fragment runs the material. */
    static final String SURFACE_CALL = "CG_SURFACE";

    private static final Set<String> FACTORY_BLOCKS = Set.of("Factory", "EngineBuffers", "Tags", "Properties",
            "Declarations", "Parameters");
    private static final Set<String> PRIMITIVE_BLOCKS = Set.of("Primitive", "EngineBuffers", "Properties",
            "Declarations", "RenderState");
    private static final Pattern FILL = Pattern.compile("^void\\s+fill\\s*\\(\\s*inout\\s+CgVertex\\s+v\\s*\\)");
    private static final Pattern DEFAULTS = Pattern.compile("^void\\s+defaults\\s*\\(\\s*inout\\s+CgSurface\\s+s\\s*\\)");
    private static final Pattern TAG = Pattern.compile("\"(\\w+)\"\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern FIELD = Pattern.compile(
            "^((?:flat\\s+|noperspective\\s+)?[A-Za-z_]\\w*)\\s+([A-Za-z_][\\w.]*)\\s*(?:\\[\\s*(\\d+)\\s*])?$");

    private static final Map<String, CgVertexFactory.Factory> FACTORIES = new HashMap<>();
    private static final Map<String, Primitive> PRIMITIVES = new HashMap<>();

    private CgFormatTemplates() {}

    /** Drops every template read from a file, so the next parse reads it again. */
    public static synchronized void forget() {
        FACTORIES.clear();
        PRIMITIVES.clear();
    }

    /** The factory {@code name} names, registered or from its file; null where neither exists. */
    @Nullable
    static synchronized CgVertexFactory.Factory factory(String name) {
        CgVertexFactory.Factory registered = CgVertexFactory.get(name);
        if (registered != null) return registered;
        if (FACTORIES.containsKey(name)) return FACTORIES.get(name);
        String file = location(name, "factories", "factory");
        String source = CgIO.loadSource(file);
        CgVertexFactory.Factory made = source == null ? null : parseFactory(name, source, file);
        FACTORIES.put(name, made);
        return made;
    }

    /** Every factory registered or read so far. */
    static synchronized List<CgVertexFactory.Factory> factories() {
        List<CgVertexFactory.Factory> out = new ArrayList<>(CgVertexFactory.all());
        for (CgVertexFactory.Factory f : FACTORIES.values()) if (f != null) out.add(f);
        return out;
    }

    /** The primitive {@code name} names, from its file; null where there is none. */
    @Nullable
    static synchronized Primitive primitive(String name) {
        if (PRIMITIVES.containsKey(name)) return PRIMITIVES.get(name);
        String file = location(name, "primitives", "primitive");
        String source = CgIO.loadSource(file);
        Primitive made = source == null ? null : parsePrimitive(name, source, file);
        PRIMITIVES.put(name, made);
        return made;
    }

    /** {@code name}'s file: {@code crystalgraphics:shaders/<dir>/<name>.<ext>}, or its own namespace's. */
    static String location(String name, String dir, String ext) {
        int colon = name.indexOf(':');
        String namespace = colon < 0 ? "crystalgraphics" : name.substring(0, colon);
        return namespace + ":shaders/" + dir + "/" + name.substring(colon + 1) + "." + ext;
    }

    private static CgVertexFactory.Factory parseFactory(String name, String source, String path) {
        List<CgMaterialFormat.Item> items = CgMaterialFormat.scan(CgMaterialFormat.blankComments(source), source, 0, path,
                FACTORY_BLOCKS);
        Map<String, String> settings = settings(items, "Factory", List.of("VertexType", "PlacesNothing", "Parameters", "Fade"),
                path);
        String type = settings.get("VertexType");
        if (type == null) throw CgMaterialFormat.fail(path, "'Factory { VertexType ... }' is missing: spatial, none, ...");
        CgMaterialFormat.Item fill = null, defaults = null;
        for (CgMaterialFormat.Item i : items) {
            if (i.kind() == CgMaterialFormat.Kind.FUNCTION && i.name().equals("fill") && FILL.matcher(i.text()).find()) {
                fill = i;
            } else if (i.kind() == CgMaterialFormat.Kind.FUNCTION && i.name().equals("defaults")
                    && DEFAULTS.matcher(i.text()).find()) {
                defaults = i;
            } else if (i.kind() != CgMaterialFormat.Kind.BLOCK) {
                throw CgMaterialFormat.fail(path, "a factory file holds its blocks, 'void fill(inout CgVertex v)' and"
                        + " 'void defaults(inout CgSurface s)': '" + i.text().lines().findFirst().orElse("").trim() + "'");
            }
        }
        if (fill == null) throw CgMaterialFormat.fail(path, "'void fill(inout CgVertex v)' is missing");
        CgVertexFactory.Builder b = CgVertexFactory.builder(name).vertexType(type).fill(fill.body())
                .buffers(tokens(items, "EngineBuffers").toArray(new String[0]))
                .declarations(body(items, "Declarations")).properties(body(items, "Properties"));
        if ("On".equals(settings.get("PlacesNothing"))) b.placesNothing();
        if (defaults != null) b.defaults(defaults.body());
        if (settings.containsKey("Fade")) b.fade(settings.get("Fade"));
        Matcher tag = TAG.matcher(body(items, "Tags"));
        while (tag.find()) b.tag(tag.group(1), tag.group(2));
        String variable = settings.get("Parameters");
        if (variable != null) b.parameters(variable, fields(body(items, "Parameters"), path));
        return b.build();
    }

    private static Primitive parsePrimitive(String name, String source, String path) {
        List<CgMaterialFormat.Item> items = CgMaterialFormat.scan(CgMaterialFormat.blankComments(source), source, 0, path,
                PRIMITIVE_BLOCKS);
        Map<String, String> settings = settings(items, "Primitive", List.of("Queue"), path);
        CgMaterialFormat.Item v2f = null, vertex = null, fragment = null;
        for (CgMaterialFormat.Item i : items) {
            if (i.kind() == CgMaterialFormat.Kind.STRUCT && i.name().equals("v2f")) v2f = i;
            else if (i.kind() == CgMaterialFormat.Kind.FUNCTION && i.name().equals("vertex")) vertex = i;
            else if (i.kind() == CgMaterialFormat.Kind.FUNCTION && i.name().equals("fragment")) fragment = i;
            else if (i.kind() != CgMaterialFormat.Kind.BLOCK) {
                throw CgMaterialFormat.fail(path, "a primitive file holds its blocks, struct v2f, vertex() and fragment(): '"
                        + i.text().lines().findFirst().orElse("").trim() + "'");
            }
        }
        if (v2f == null || vertex == null || fragment == null) {
            throw CgMaterialFormat.fail(path, "a primitive file writes struct v2f, vertex(out v2f o) and fragment(in v2f i, out vec4 fragColor)");
        }
        if (!fragment.body().contains(SURFACE_CALL + "(")) {
            throw CgMaterialFormat.fail(path, "fragment() runs the material at " + SURFACE_CALL + "(input, s)");
        }
        CgMaterialFormat.Item state = CgMaterialFormat.block(items, "RenderState");
        String declarations = body(items, "Declarations");
        return new Primitive(name, tokens(items, "EngineBuffers"), pragmas(declarations, true), pragmas(declarations, false),
                body(items, "Properties"), settings.getOrDefault("Queue", "Transparent"),
                state == null ? "" : state.text(), v2f.body(), vertex.text(), fragment.text());
    }

    /** {@code declarations}' {@code #pragma} lines ({@code pragma}), or every other line, each stripped. */
    static String pragmas(String declarations, boolean pragma) {
        StringBuilder out = new StringBuilder();
        for (String l : declarations.split("\n")) {
            String t = l.strip();
            if (!t.isEmpty() && t.startsWith("#pragma") == pragma) out.append(t).append('\n');
        }
        return out.toString();
    }

    private static Map<String, String> settings(List<CgMaterialFormat.Item> items, String block, List<String> allowed,
                                                String path) {
        CgMaterialFormat.Item b = CgMaterialFormat.block(items, block);
        return b == null ? new LinkedHashMap<>() : CgMaterialFormat.pairs(b.body(), allowed, block, path);
    }

    private static String body(List<CgMaterialFormat.Item> items, String block) {
        CgMaterialFormat.Item b = CgMaterialFormat.block(items, block);
        return b == null ? "" : b.body().strip();
    }

    private static List<String> tokens(List<CgMaterialFormat.Item> items, String block) {
        String body = body(items, block);
        return body.isEmpty() ? List.of() : List.of(body.split("\\s+"));
    }

    /** {@code Parameters { }}: {@code type name;}, the name dotted for a nested field, an array {@code vec4 d[4]}. */
    private static Map<String, String> fields(String body, String path) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String decl : CgMaterialFormat.blankComments(body).split(";")) {
            String d = decl.strip().replaceAll("\\s+", " ");
            if (d.isEmpty()) continue;
            Matcher m = FIELD.matcher(d);
            if (!m.matches()) throw CgMaterialFormat.fail(path, "'" + d + "' is no parameter: type name;");
            if (m.group(1).matches("(?:u?int|[iu]vec[234])") ) {
                throw CgMaterialFormat.fail(path, "'" + d + "' is an integer: flat " + m.group(1));
            }
            out.put(m.group(2), m.group(3) == null ? m.group(1) : m.group(1) + "[" + m.group(3) + "]");
        }
        return out;
    }
}
