package com.crystalgraphics.material.parse;

import com.crystalgraphics.api.material.CgMaterialDomain;
import com.crystalgraphics.api.material.CgVertexFactory;
import com.crystalgraphics.gpu.resource.CgEngineBufferRegistry;
import com.github.bsideup.jabel.Desugar;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The material format ({@code #domain} files, {@code shader-format.md}) lowered onto the parse of today's passes, as
 * Unreal's material translator pastes a material into its pass templates: a {@code #domain} source becomes the
 * {@code Pass { }} source it stands for, and {@link CgShaderParser} parses that. The compiler's generated {@code main}
 * is the same for both, so a converted shader compiles to the program its old form did.
 *
 * <ul>
 *   <li>{@code surface}: a Forward pass whose vertex fills a {@code CgVertex} from the factory, runs the material's
 *       {@code vertex()} and projects {@code v.world}; whose fragment runs {@code surface()} and hands each output it
 *       names to the engine's hook. A codeless Emissive pass where it names {@code emissive}, a Distortion pass where
 *       it names {@code refraction}. The render state, queue and tags come from {@code Material { }}.</li>
 *   <li>{@code global}: its {@code RenderState}, {@code vertex()} and {@code fragment()} as one Forward pass, unlit,
 *       unfogged and in its target's encoding.</li>
 *   <li>Each helper is compiled into the stages that call it, so one called only by {@code surface()} may use
 *       fragment-only built-ins.</li>
 * </ul>
 */
final class CgMaterialFormat {

    /** The material's {@code vertex()}, renamed so the pass template's own {@code vertex} is the one the parser finds. */
    static final String MATERIAL_VERTEX = "cg_MaterialVertex";

    /** The material's {@code surface()}, renamed. */
    static final String MATERIAL_SURFACE = "cg_MaterialSurface";

    /**
     * A lowered file.
     *
     * @param source    the {@code Pass { }} source it stands for
     * @param domain    what it is
     * @param maskClip  the {@code opacityMask} threshold: a number or a float property's name; null for the default
     * @param separate  whether it draws after every other translucent draw ({@code TranslucencyPass Separate})
     */
    @Desugar
    record Lowered(String source, CgMaterialDomain domain, @Nullable String maskClip, boolean separate) {
    }

    private enum Kind { DIRECTIVE, BLOCK, STRUCT, FUNCTION, DECLARATION }

    /** One top-level item: a {@code #} line, a block, a struct, a function or any other declaration. */
    @Desugar
    private record Item(Kind kind, String name, String text, String body) {
    }

    @Desugar
    private record Keyword(String name, List<String> values) {
    }

    private static final Set<String> BLOCKS = Set.of("Material", "Keywords", "Properties", "Buffers", "RenderState");
    private static final Set<String> OLD = Set.of("Pass", "Tags", "Queue", "SubShader");
    private static final Pattern STRUCT = Pattern.compile("^struct\\s+([A-Za-z_]\\w*)");
    private static final Pattern FUNCTION = Pattern.compile("^(?:[\\w\\[\\]]+\\s+)+?([A-Za-z_]\\w*)\\s*\\(");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_]\\w*");
    private static final Pattern NUMBER = Pattern.compile("[0-9]*\\.?[0-9]+(?:[eE][-+]?[0-9]+)?");
    private static final Pattern KEYWORD = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final Pattern KEYWORD_ENTRY = Pattern.compile(
            "([A-Za-z_]\\w*)\\s*(?:\\(\\s*\"[^\"]*\"\\s*\\))?\\s*(?:=\\s*([A-Za-z_]\\w*(?:\\s*\\|\\s*[A-Za-z_]\\w*)*))?");
    private static final Pattern BUFFER_ELEMENT = Pattern.compile("\\(\\s*\"[^\"]*\"\\s*,\\s*([A-Za-z_]\\w*)");
    private static final Pattern V2F_FIELD = Pattern.compile("^(?:(flat|noperspective|smooth)\\s+)?(\\w+)\\s+(\\w+)\\s*;$");
    private static final Pattern SURFACE_SIGNATURE = Pattern.compile(
            "^void\\s+surface\\s*\\(\\s*(?:in\\s+)?v2f\\s+\\w+\\s*,\\s*inout\\s+CgSurface\\s+(\\w+)\\s*\\)");
    private static final Pattern VERTEX_SIGNATURE = Pattern.compile(
            "^void\\s+vertex\\s*\\(\\s*inout\\s+CgVertex\\s+\\w+\\s*,\\s*out\\s+v2f\\s+\\w+\\s*\\)");
    private static final Pattern CG_SURFACE_PARAM = Pattern.compile("\\bCgSurface\\s+(\\w+)\\s*[,)]");
    private static final Pattern GLOBAL_VERTEX_SIGNATURE = Pattern.compile("^void\\s+vertex\\s*\\(\\s*out\\s+v2f\\s+\\w+\\s*\\)");
    private static final Pattern GLOBAL_FRAGMENT_SIGNATURE = Pattern.compile(
            "^void\\s+fragment\\s*\\(\\s*(?:in\\s+)?v2f\\s+\\w+\\s*,\\s*out\\s+\\w+\\s+\\w+\\s*\\)");
    private static final Pattern MESH_ATTRIBUTE = Pattern.compile("\\bcg_(?:Position|Normal|Color|TexCoord[0-3])\\b");

    /** The v2f fields a material with no {@code vertex()} has filled, and with what. */
    private static final Map<String, String[]> AUTO_FIELDS = Map.of(
            "uv0", new String[]{"vec2", "v.uv0"}, "uv1", new String[]{"vec2", "v.uv1"},
            "uv2", new String[]{"vec2", "v.uv2"}, "uv3", new String[]{"vec2", "v.uv3"},
            "color", new String[]{"vec4", "v.color"},
            "normal", new String[]{"vec3", "normalize(CG_NORMAL_MATRIX * v.normal)"},
            "world", new String[]{"vec3", "v.world"},
            "light", new String[]{"vec2", "CG_OBJECT_LIGHT"});

    /** A surface's outputs. */
    private static final Set<String> OUTPUTS = Set.of("baseColor", "opacity", "opacityMask", "emissive", "normal",
            "refraction", "refractionSplit", "refractionDepth", "pixelDepthOffset", "customData", "shadingModelId",
            "light", "volumeDepth");

    private static final List<String> SURFACE_PROPERTIES = List.of("BlendMode", "ShadingModel", "TranslucencyLighting",
            "Faces", "TranslucencyPass", "DepthTest", "DepthWrite", "DepthPrepass", "ClipDepth", "MaskClip", "Fog", "Media",
            "ColorSpace", "SceneColorMargin", "Sky", "PerDrawValues", "VertexFactory");

    private static final String SURFACE_DEFAULTS = """
            #if !defined(CG_VERTEX_STAGE)
            CgSurface cg_SurfaceDefaults() {
                CgSurface s;
                s.baseColor = vec3(1.0);
                s.opacity = 1.0;
                s.opacityMask = 1.0;
                s.emissive = vec3(0.0);
                s.normal = vec3(0.0, 1.0, 0.0);
                s.refraction = vec2(0.0);
                s.refractionSplit = 0.0;
                s.refractionDepth = cg_LinearEyeDepth(gl_FragCoord.z);
                s.pixelDepthOffset = 0.0;
                s.customData = vec4(0.0);
                s.shadingModelId = uvec2(1u, 0u);
                s.light = cg_Light;
                s.volumeDepth = 0.0;
                return s;
            }
            #endif
            """;

    private CgMaterialFormat() {}

    /** Whether {@code source} is in the material format: its first line, past comments, a {@code #domain}. */
    static boolean isDomainFile(String source) {
        String t = blankComments(source);
        int i = skipWs(t, 0);
        return t.startsWith("#domain", i);
    }

    /** {@code source}, a {@code #domain} file, as the old format's source. */
    static Lowered lower(String source, String path) {
        String t = blankComments(source);
        int start = skipWs(t, 0);
        int eol = eol(t, start);
        Matcher line = Pattern.compile("^#domain\\s+(\\w+)$").matcher(t.substring(start, eol).trim());
        if (!line.matches()) throw fail(path, "'#domain' takes one word: surface, post, ui, decal or global");
        CgMaterialDomain domain;
        try {
            domain = CgMaterialDomain.ofDirective(line.group(1));
        } catch (IllegalArgumentException e) {
            throw fail(path, "'" + t.substring(start, eol).trim() + "' is no domain: surface, post, ui, decal or global");
        }
        List<Item> items = scan(t, source, eol, path);
        return switch (domain) {
            case SURFACE -> lowerSurface(items, path);
            case GLOBAL -> lowerGlobal(items, path);
            case POST, UI, TEXT -> throw fail(path, "'#domain " + line.group(1) + "' is not parsed yet: it arrives with F4"
                    + " (renderer-milestones.md, P2.5)");
            case DECAL -> throw fail(path, "'#domain decal' has no renderer until R11");
        };
    }

    // ── surface ─────────────────────────────────────────────────────────────────

    private static Lowered lowerSurface(List<Item> items, String path) {
        Map<String, String> m = properties(block(items, "Material"), SURFACE_PROPERTIES, path);
        if (block(items, "RenderState") != null) {
            throw fail(path, "'RenderState' is a #domain global's: a surface material says how it draws in Material { }");
        }
        List<Item> code = code(items, path);
        Map<String, List<Item>> functions = functions(code);
        Item surface = only(functions, "surface", path);
        if (surface == null) throw fail(path, "'void surface(in v2f i, inout CgSurface s)' is missing: a surface material's outputs");
        Matcher sig = SURFACE_SIGNATURE.matcher(surface.text());
        if (!sig.find()) throw fail(path, "'surface(' is 'void surface(in v2f i, inout CgSurface s)'");
        Item vertex = only(functions, "vertex", path);
        if (vertex != null && !VERTEX_SIGNATURE.matcher(vertex.text()).find()) {
            throw fail(path, "'vertex(' is 'void vertex(inout CgVertex v, out v2f o)'");
        }
        if (functions.containsKey("fragment")) {
            throw fail(path, "'fragment(' is a #domain global's: a surface material writes surface()");
        }
        for (Item i : code) {
            if (i.kind() == Kind.FUNCTION && i.text().contains("gl_Position")) {
                throw fail(path, "'gl_Position' is the engine's in a surface material: vertex() moves v.world");
            }
        }

        // What it names
        Set<String> named = new LinkedHashSet<>();
        names(surface.body(), sig.group(1), named);
        for (Item f : code) {
            if (f == surface || f.kind() != Kind.FUNCTION) continue;
            Matcher p = CG_SURFACE_PARAM.matcher(f.text().substring(0, f.text().indexOf('{')));
            while (p.find()) names(f.body(), p.group(1), named);
        }
        named.retainAll(OUTPUTS);

        // Material { }
        String blendMode = choice(m, "BlendMode", "Opaque", path, "Opaque", "Masked", "Translucent", "AlphaComposite",
                "Additive", "Modulate", "Subtract");
        boolean masked = blendMode.equals("Masked");
        boolean translucent = !blendMode.equals("Opaque") && !masked;
        boolean unlit = choice(m, "ShadingModel", "WorldLit", path, "WorldLit", "Unlit").equals("Unlit");
        boolean surfaceLit = choice(m, "TranslucencyLighting", "Volume", path, "Volume", "Surface").equals("Surface");
        String faces = choice(m, "Faces", "Front", path, "Front", "Back", "Both");
        String pass = choice(m, "TranslucencyPass", "Standard", path, "Standard", "AfterDistortion", "Separate", "Overlay");
        String depthTest = choice(m, "DepthTest", "On", path, "On", "Off", "Strict");
        Boolean depthWrite = m.containsKey("DepthWrite") ? onOff(m, "DepthWrite", false, path) : null;
        boolean prepass = onOff(m, "DepthPrepass", false, path);
        boolean clipDepth = onOff(m, "ClipDepth", false, path);
        boolean fog = onOff(m, "Fog", true, path);
        boolean media = onOff(m, "Media", true, path);
        boolean linear = choice(m, "ColorSpace", "sRGB", path, "sRGB", "Linear").equals("Linear");
        boolean sky = onOff(m, "Sky", false, path);
        boolean rows = onOff(m, "PerDrawValues", false, path);
        String factoryName = m.getOrDefault("VertexFactory", CgVertexFactory.MESH);
        CgVertexFactory.Factory factory = CgVertexFactory.get(factoryName);
        if (factory == null) {
            throw fail(path, "'VertexFactory " + factoryName + "' is no registered factory: " + CgVertexFactory.names());
        }
        String maskClip = m.get("MaskClip");
        if (maskClip != null && !NUMBER.matcher(maskClip).matches() && !maskClip.matches("_[A-Za-z]\\w*")) {
            throw fail(path, "'MaskClip " + maskClip + "' is a number or a float parameter's name");
        }
        String margin = m.get("SceneColorMargin");

        boolean clips = masked || clipDepth || prepass;
        if (!translucent) {
            for (String k : List.of("DepthPrepass", "ClipDepth", "TranslucencyLighting", "TranslucencyPass")) {
                if (m.containsKey(k)) {
                    throw fail(path, "'" + k + "' is a translucent blend mode's, not BlendMode " + blendMode
                            + (k.equals("ClipDepth") ? ": Masked is an opaque material cut by opacityMask" : ""));
                }
            }
            for (String o : List.of("opacity", "refraction", "refractionSplit", "refractionDepth")) {
                if (named.contains(o)) {
                    throw fail(path, "'s." + o + "' is a translucent blend mode's: BlendMode " + blendMode + " is solid"
                            + (o.equals("opacity") ? " (Masked cuts with s.opacityMask)" : ""));
                }
            }
        }
        if (prepass && clipDepth) throw fail(path, "'DepthPrepass' and 'ClipDepth' are two ways to write depth: pick one");
        if (clips && depthWrite != null) {
            throw fail(path, "'DepthWrite' is decided by " + (masked ? "BlendMode Masked" : prepass ? "DepthPrepass" : "ClipDepth"));
        }
        if (maskClip != null && !clips) {
            throw fail(path, "'MaskClip' is the threshold of Masked, ClipDepth and DepthPrepass, and it has none of them");
        }
        if (named.contains("opacityMask") && !clips) {
            throw fail(path, "'s.opacityMask' cuts only under BlendMode Masked, ClipDepth or DepthPrepass");
        }
        if (surfaceLit && unlit) throw fail(path, "'TranslucencyLighting Surface' lights by the normal: not ShadingModel Unlit");
        if (named.contains("normal") && !surfaceLit) {
            throw fail(path, "'s.normal' lights only TranslucencyLighting Surface; an opaque surface is lit by its mesh's normal");
        }
        if (sky && translucent) throw fail(path, "'Sky On' draws first, behind everything: not a translucent blend mode");
        if (factory.placesNothing() && vertex == null) {
            throw fail(path, "'VertexFactory " + factoryName + "' supplies no vertices: write vertex(), assigning v.world");
        }
        boolean color = !translucent || named.contains("baseColor") || named.contains("opacity");
        if (!color && !named.contains("emissive") && !named.contains("refraction") && !named.contains("volumeDepth")
                && !clipDepth) {
            throw fail(path, "'surface(' names no output a translucent material draws: baseColor, opacity, emissive or refraction");
        }

        // The v2f, and how its fields are filled
        Item v2f = struct(items, "v2f");
        String v2fBody = v2f == null ? "" : v2f.body();
        StringBuilder autoFill = new StringBuilder();
        for (String decl : CgStructureParser.v2fDeclarations(v2fBody)) {
            Matcher f = V2F_FIELD.matcher(decl);
            if (!f.matches()) throw fail(path, "Invalid v2f field: '" + decl + "'");
            if (vertex != null) continue;
            String[] auto = AUTO_FIELDS.get(f.group(3));
            if (auto == null) {
                throw fail(path, "v2f field '" + f.group(3) + "' is filled by no one: write vertex(), or name it one of "
                        + AUTO_FIELDS.keySet());
            }
            if (!auto[0].equals(f.group(2))) throw fail(path, "v2f field '" + f.group(3) + "' is a " + auto[0]);
            autoFill.append("        o.").append(f.group(3)).append(" = ").append(auto[1]).append(";\n");
        }

        // The preamble: keywords, engine buffers, tags, queue
        StringBuilder out = new StringBuilder();
        out.append("#type ").append(factory.vertexType()).append('\n');
        keywordPragmas(out, keywords(block(items, "Keywords"), path), false);
        Set<String> uses = new LinkedHashSet<>(factory.engineBuffers());
        if (rows) uses.add(CgShaderParser.ROWS);
        if (namesGlobals(code)) uses.add("globals");
        refuseNamedBuffers(code, uses, path);
        for (String u : uses) out.append("#pragma cg_use ").append(u).append('\n');
        out.append("Tags { \"RenderType\" = \"").append(translucent ? "Transparent" : "Opaque").append('"')
           .append(" \"Lighting\" = \"").append(unlit ? "Unlit" : surfaceLit ? "Surface" : "Lit").append('"');
        if (!fog) out.append(" \"Fog\" = \"Off\"");
        if (!media) out.append(" \"Media\" = \"Off\"");
        if (linear) out.append(" \"ColorSpace\" = \"Linear\"");
        if (clipDepth || masked) out.append(" \"Depth\" = \"Clip\"");
        if (prepass) out.append(" \"Depth\" = \"Prepass\"");
        if (margin != null) out.append(" \"SceneColorMargin\" = \"").append(margin).append('"');
        out.append(" }\n");
        String queue = sky ? "Background" : masked ? "AlphaTest" : !translucent ? "Geometry"
                : pass.equals("AfterDistortion") ? "AfterDistortion" : pass.equals("Overlay") ? "Overlay" : "Transparent";
        out.append("Queue = \"").append(queue).append("\"\n");
        appendData(out, items);
        out.append("struct v2f {").append(v2fBody).append("};\n");

        // The Forward pass
        String blend = switch (blendMode) {
            case "Translucent" -> "SRC_ALPHA ONE_MINUS_SRC_ALPHA";
            case "AlphaComposite" -> "ONE ONE_MINUS_SRC_ALPHA";
            case "Additive", "Subtract" -> "ONE ONE";
            case "Modulate" -> "DST_COLOR ZERO";
            default -> "OFF";
        };
        String test = switch (depthTest) {
            case "Off" -> "ALWAYS";
            case "Strict" -> "LESS";
            default -> "LEQUAL";
        };
        String cull = switch (faces) {
            case "Back" -> "FRONT";
            case "Both" -> "OFF";
            default -> "BACK";
        };
        boolean writes = depthWrite != null ? depthWrite : !translucent;
        out.append("Pass {\n    Tags { \"LightMode\" = \"Forward\" }\n    RenderState {\n")
           .append("        Blend ").append(blend).append('\n');
        if (blendMode.equals("Subtract")) out.append("        BlendEquation REV_SUB\n");
        out.append("        DepthTest ").append(test).append('\n');
        if (!clips) out.append("        DepthWrite ").append(writes ? "ON" : "OFF").append('\n');
        out.append("        Cull ").append(cull).append('\n');
        if (!color) out.append("        ColorMask 0\n");
        out.append("    }\n");
        out.append("#include \"crystalgraphics:shaders/env/material.glsl\"\n").append(SURFACE_DEFAULTS);
        appendCode(out, code, vertex, surface, functions);
        out.append("    void vertex(out v2f o) {\n        CgVertex v;\n");
        for (String l : factory.fill().strip().split("\n")) out.append("        ").append(l.strip()).append('\n');
        if (vertex != null) out.append("        ").append(MATERIAL_VERTEX).append("(v, o);\n");
        out.append(autoFill);
        out.append("        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(v.world, 1.0);\n    }\n");
        out.append("    void fragment(in v2f i, out vec4 fragColor) {\n")
           .append("        CgSurface s = cg_SurfaceDefaults();\n")
           .append("        ").append(MATERIAL_SURFACE).append("(i, s);\n");
        if (named.contains("pixelDepthOffset")) out.append("        CG_PIXEL_DEPTH_OFFSET(s.pixelDepthOffset);\n");
        if (clips && named.contains("opacityMask")) out.append("        cg_Clip(s.opacityMask);\n");
        if (named.contains("light")) out.append("        cg_Light = s.light;\n");
        if (named.contains("emissive")) out.append("        CG_GLOW(s.emissive);\n");
        if (surfaceLit) out.append("        CG_SURFACE_NORMAL(s.normal);\n");
        if (named.contains("customData")) out.append("        CG_CUSTOM_DATA_OUT(s.customData);\n");
        if (named.contains("shadingModelId")) {
            out.append("        CG_SHADING_MODEL_OUT(s.shadingModelId.x, s.shadingModelId.y);\n");
        }
        if (named.contains("volumeDepth")) out.append("        CG_VOLUME_DEPTH(s.volumeDepth);\n");
        out.append("        fragColor = vec4(s.baseColor, s.opacity);\n    }\n}\n");

        // The glow: what the Forward pass draws, with its glow; adding, unless a straight-alpha blend weighs it
        if (named.contains("emissive")) {
            out.append("Pass {\n    Tags { \"LightMode\" = \"Emissive\" }\n    RenderState {\n")
               .append("        Blend ").append(blendMode.equals("Translucent") ? "SRC_ALPHA ONE_MINUS_SRC_ALPHA" : "ONE ONE")
               .append("\n        DepthTest ").append(test)
               .append("\n        DepthWrite OFF\n        Cull ").append(cull).append("\n    }\n}\n");
        }
        // The bend: the Forward pass's vertex, the offset from surface()
        if (named.contains("refraction")) {
            out.append("Pass {\n    Tags { \"LightMode\" = \"Distortion\" }\n    RenderState {\n");
            if (depthTest.equals("Off")) out.append("        DepthTest ALWAYS\n");
            out.append("        Cull ").append(cull).append("\n    }\n")
               .append("    void fragment(in v2f i, out vec4 offset) {\n")
               .append("        CgSurface s = cg_SurfaceDefaults();\n")
               .append("        ").append(MATERIAL_SURFACE).append("(i, s);\n")
               .append("        offset = CG_DISTORTION(s.refraction, s.refractionSplit, s.refractionDepth);\n    }\n}\n");
        }
        return new Lowered(out.toString(), CgMaterialDomain.SURFACE, maskClip, pass.equals("Separate"));
    }

    // ── global ──────────────────────────────────────────────────────────────────

    private static Lowered lowerGlobal(List<Item> items, String path) {
        Map<String, String> m = properties(block(items, "Material"), List.of("SceneColorMargin"), path);
        Item state = block(items, "RenderState");
        List<Item> code = code(items, path);
        Map<String, List<Item>> functions = functions(code);
        Item vertex = only(functions, "vertex", path), fragment = only(functions, "fragment", path);
        if (vertex == null || !GLOBAL_VERTEX_SIGNATURE.matcher(vertex.text()).find()) {
            throw fail(path, "'void vertex(out v2f o)' is a #domain global's vertex, writing gl_Position");
        }
        if (fragment == null || !GLOBAL_FRAGMENT_SIGNATURE.matcher(fragment.text()).find()) {
            throw fail(path, "'void fragment(in v2f i, out vec4 color)' is a #domain global's fragment");
        }
        if (functions.containsKey("surface")) throw fail(path, "'surface(' is a #domain surface material's");
        boolean mesh = false;
        for (Item i : code) mesh |= MESH_ATTRIBUTE.matcher(i.text()).find();

        StringBuilder out = new StringBuilder();
        out.append("#type ").append(mesh ? "spatial" : "none").append('\n');
        keywordPragmas(out, keywords(block(items, "Keywords"), path), true);
        Set<String> uses = namesGlobals(code) ? Set.of("globals") : Set.of();
        refuseNamedBuffers(code, uses, path);
        for (String u : uses) out.append("#pragma cg_use ").append(u).append('\n');
        out.append("Tags { \"RenderType\" = \"Opaque\" \"Lighting\" = \"Unlit\" \"Fog\" = \"Off\" \"Media\" = \"Off\"")
           .append(" \"ColorSpace\" = \"Target\"");
        if (m.containsKey("SceneColorMargin")) {
            out.append(" \"SceneColorMargin\" = \"").append(m.get("SceneColorMargin")).append('"');
        }
        out.append(" }\nQueue = \"Overlay\"\n");
        appendData(out, items);
        Item v2f = struct(items, "v2f");
        out.append("struct v2f {").append(v2f == null ? "" : v2f.body()).append("};\n");
        out.append("Pass {\n    Tags { \"LightMode\" = \"Forward\" }\n");
        if (state != null) out.append("    ").append(state.text()).append('\n');
        appendCode(out, code, vertex, fragment, functions);
        out.append(vertex.text()).append('\n').append(fragment.text()).append("\n}\n");
        return new Lowered(out.toString(), CgMaterialDomain.GLOBAL, null, false);
    }

    // ── shared ──────────────────────────────────────────────────────────────────

    /** The top-level items of {@code t} from {@code from}: {@code t} is {@code source} with its comments blanked. */
    private static List<Item> scan(String t, String source, int from, String path) {
        List<Item> items = new ArrayList<>();
        int n = t.length(), i = from;
        while (i < n) {
            char c = t.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '#') {
                int eol = eol(t, i);
                String line = t.substring(i, eol).trim();
                if (line.startsWith("#pragma")) {
                    throw fail(path, "'" + line + "' has no place in a #domain file: keywords are Keywords { },"
                            + " per-draw values PerDrawValues On, globals and the world's lights are found by name");
                }
                if (line.startsWith("#domain")) throw fail(path, "'#domain' is the first line, once");
                if (line.startsWith("#type")) throw fail(path, "'#type' is the old format's: a #domain file names its VertexFactory");
                if (line.startsWith("#version")) throw fail(path, "'#version' is the compiler's");
                items.add(new Item(Kind.DIRECTIVE, null, line, ""));
                i = eol;
                continue;
            }
            int w = i;
            while (w < n && (Character.isLetterOrDigit(t.charAt(w)) || t.charAt(w) == '_')) w++;
            String word = t.substring(i, w);
            if (word.isEmpty()) throw fail(path, "'" + t.substring(i, eol(t, i)).trim() + "' is no declaration");
            int k = skipWs(t, w);
            if (BLOCKS.contains(word) && k < n && t.charAt(k) == '{') {
                int close = CgStructureParser.matchBrace(t, k);
                for (Item seen : items) {
                    if (seen.kind() == Kind.BLOCK && seen.name().equals(word)) throw fail(path, "'" + word + "' twice");
                }
                items.add(new Item(Kind.BLOCK, word, source.substring(i, close + 1), t.substring(k + 1, close)));
                int s = skipWs(t, close + 1);
                i = s < n && t.charAt(s) == ';' ? s + 1 : close + 1;
                continue;
            }
            if (OLD.contains(word)) {
                throw fail(path, "'" + word + "' is the old format's: a #domain file has no passes, tags or queue;"
                        + " Material { } says how it draws");
            }
            int depth = 0, end = -1;
            for (int j = i; j < n && end < 0; j++) {
                char ch = t.charAt(j);
                if (ch == '{') {
                    depth++;
                } else if (ch == '}') {
                    if (--depth == 0) {
                        int s = skipWs(t, j + 1);
                        end = s < n && t.charAt(s) == ';' ? s + 1 : j + 1;
                    }
                } else if (ch == ';' && depth == 0) {
                    end = j + 1;
                }
            }
            if (end < 0) throw fail(path, "'" + t.substring(i, eol(t, i)).trim() + "' never ends");
            String text = t.substring(i, end);
            Matcher s = STRUCT.matcher(text), f = FUNCTION.matcher(text);
            int open = text.indexOf('{');
            if (s.find() && open >= 0) {
                items.add(new Item(Kind.STRUCT, s.group(1), text, text.substring(open + 1, text.lastIndexOf('}'))));
            } else if (text.endsWith("}") && f.find() && open > f.end() - 1) {
                items.add(new Item(Kind.FUNCTION, f.group(1), text, text.substring(open + 1, text.length() - 1)));
            } else {
                items.add(new Item(Kind.DECLARATION, null, text, ""));
            }
            i = end;
        }
        return items;
    }

    /** The code: every item but the blocks, the v2f and the structs a buffer holds, in order. */
    private static List<Item> code(List<Item> items, String path) {
        Set<String> elements = bufferElements(items);
        List<Item> out = new ArrayList<>();
        for (Item i : items) {
            if (i.kind() == Kind.BLOCK) continue;
            if (i.kind() == Kind.STRUCT && (i.name().equals("v2f") || elements.contains(i.name()))) continue;
            if (i.kind() == Kind.FUNCTION && (i.name().equals(MATERIAL_VERTEX) || i.name().equals(MATERIAL_SURFACE))) {
                throw fail(path, "'" + i.name() + "' is the engine's name");
            }
            out.add(i);
        }
        return out;
    }

    /** Properties, then the structs a buffer holds, then Buffers: where the old parser reads them. */
    private static void appendData(StringBuilder out, List<Item> items) {
        Item props = block(items, "Properties"), buffers = block(items, "Buffers");
        if (props != null) out.append(props.text()).append('\n');
        Set<String> elements = bufferElements(items);
        for (Item i : items) {
            if (i.kind() == Kind.STRUCT && elements.contains(i.name())) out.append(i.text()).append('\n');
        }
        if (buffers != null) out.append(buffers.text()).append('\n');
    }

    /**
     * The code in order, the material's two entry points renamed, each function compiled only into the stages that
     * reach it: a function {@code first} never reaches is fragment only, one {@code second} never reaches vertex only.
     */
    private static void appendCode(StringBuilder out, List<Item> code, @Nullable Item first, Item second,
                                   Map<String, List<Item>> functions) {
        Set<String> vertexReach = reach(functions, first), fragmentReach = reach(functions, second);
        boolean surface = second.name().equals("surface");
        for (Item i : code) {
            if (!surface && (i == first || i == second)) continue;   // a global's entry points follow the code
            String text = i.text();
            if (surface && i == first) text = text.replaceFirst("\\bvertex(\\s*\\()", MATERIAL_VERTEX + "$1");
            if (surface && i == second) text = text.replaceFirst("\\bsurface(\\s*\\()", MATERIAL_SURFACE + "$1");
            if (i.kind() != Kind.FUNCTION || vertexReach.contains(i.name()) && fragmentReach.contains(i.name())) {
                out.append(text).append('\n');
            } else if (vertexReach.contains(i.name())) {
                out.append("#if !defined(CG_FRAGMENT_STAGE)\n").append(text).append("\n#endif\n");
            } else {
                out.append("#if !defined(CG_VERTEX_STAGE)\n").append(text).append("\n#endif\n");
            }
        }
    }

    /** The functions {@code root} calls, itself included, by name. */
    private static Set<String> reach(Map<String, List<Item>> functions, @Nullable Item root) {
        Set<String> seen = new LinkedHashSet<>();
        if (root == null) return seen;
        Deque<String> todo = new ArrayDeque<>();
        todo.add(root.name());
        while (!todo.isEmpty()) {
            String name = todo.pop();
            if (!seen.add(name)) continue;
            for (Item f : functions.getOrDefault(name, List.of())) {
                Matcher id = IDENT.matcher(f.body());
                while (id.find()) if (functions.containsKey(id.group()) && !seen.contains(id.group())) todo.add(id.group());
            }
        }
        return seen;
    }

    private static Map<String, List<Item>> functions(List<Item> code) {
        Map<String, List<Item>> out = new LinkedHashMap<>();
        for (Item i : code) if (i.kind() == Kind.FUNCTION) out.computeIfAbsent(i.name(), k -> new ArrayList<>()).add(i);
        return out;
    }

    private static Item only(Map<String, List<Item>> functions, String name, String path) {
        List<Item> found = functions.get(name);
        if (found == null) return null;
        if (found.size() > 1) throw fail(path, "'" + name + "(' is written twice");
        return found.get(0);
    }

    /** Every field {@code param.<field>} names in {@code body}. */
    private static void names(String body, String param, Set<String> into) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(param) + "\\s*\\.\\s*(\\w+)").matcher(body);
        while (m.find()) into.add(m.group(1));
    }

    /**
     * A {@code #domain} file reaches an engine buffer through what draws it (its {@code VertexFactory},
     * {@code PerDrawValues On}, a {@code ui} {@code Primitive}), never by naming it: one it names and does not reach is
     * refused here, where the old format's check would ask for a {@code #pragma}.
     */
    private static void refuseNamedBuffers(List<Item> code, Set<String> reached, String path) {
        List<String> with = CgEngineBufferRegistry.withRequirements(new ArrayList<>(reached));
        StringBuilder all = new StringBuilder();
        for (Item i : code) all.append(i.text()).append('\n');
        for (String token : CgEngineBufferRegistry.tokens()) {
            if (with.contains(token)) continue;
            String macro = CgEngineBufferRegistry.get(token).macroName();
            String family = "CG_" + (macro.endsWith("_DATA") ? macro.substring(0, macro.length() - 5) : macro) + "_";
            if (all.indexOf(macro) >= 0 || all.indexOf(family) >= 0) {
                throw fail(path, "'" + family + "' is the engine's '" + token + "' buffer, which a #domain file reaches"
                        + " through what draws it (VertexFactory, PerDrawValues On, a ui Primitive), never by naming it");
            }
        }
    }

    private static boolean namesGlobals(List<Item> code) {
        for (Item i : code) if (i.text().contains("CG_GLOBAL_")) return true;
        return false;
    }

    private static Set<String> bufferElements(List<Item> items) {
        Set<String> out = new LinkedHashSet<>();
        Item buffers = block(items, "Buffers");
        if (buffers == null) return out;
        Matcher m = BUFFER_ELEMENT.matcher(buffers.body());
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static Item block(List<Item> items, String name) {
        for (Item i : items) if (i.kind() == Kind.BLOCK && i.name().equals(name)) return i;
        return null;
    }

    private static Item struct(List<Item> items, String name) {
        for (Item i : items) if (i.kind() == Kind.STRUCT && i.name().equals(name)) return i;
        return null;
    }

    /** A {@code Material { }} block's {@code Name Value} pairs, each name one of {@code allowed}. */
    private static Map<String, String> properties(@Nullable Item block, List<String> allowed, String path) {
        Map<String, String> out = new LinkedHashMap<>();
        if (block == null) return out;
        String[] tokens = block.body().trim().split("\\s+");
        if (tokens.length == 1 && tokens[0].isEmpty()) return out;
        if (tokens.length % 2 != 0) throw fail(path, "'Material' holds Name Value pairs: '" + block.body().trim() + "'");
        for (int i = 0; i < tokens.length; i += 2) {
            if (!allowed.contains(tokens[i])) {
                throw fail(path, "'" + tokens[i] + "' is no Material property here: " + allowed);
            }
            if (out.put(tokens[i], tokens[i + 1]) != null) throw fail(path, "'" + tokens[i] + "' twice");
        }
        return out;
    }

    private static String choice(Map<String, String> m, String key, String fallback, String path, String... values) {
        String v = m.getOrDefault(key, fallback);
        if (!List.of(values).contains(v)) throw fail(path, "'" + key + " " + v + "' is one of " + List.of(values));
        return v;
    }

    private static boolean onOff(Map<String, String> m, String key, boolean fallback, String path) {
        return choice(m, key, fallback ? "On" : "Off", path, "On", "Off").equals("On");
    }

    /** {@code Keywords { }}: {@code NAME ("label")} is on or off, {@code NAME ("label") = A | B} one of its values. */
    private static List<Keyword> keywords(@Nullable Item block, String path) {
        List<Keyword> out = new ArrayList<>();
        if (block == null) return out;
        String body = block.body();
        int at = skipWs(body, 0);
        Matcher m = KEYWORD_ENTRY.matcher(body);
        while (at < body.length()) {
            if (!m.find(at) || m.start() != at) {
                throw fail(path, "'" + body.substring(at, eol(body, at)).trim() + "' is no keyword:"
                        + " NAME (\"label\") or NAME (\"label\") = A | B");
            }
            List<String> values = new ArrayList<>();
            if (m.group(2) != null) for (String v : m.group(2).split("\\|")) values.add(v.trim());
            for (String name : withValues(m.group(1), values)) {
                if (!KEYWORD.matcher(name).matches()) throw fail(path, "'" + name + "' is no keyword: [A-Z][A-Z0-9_]*");
            }
            if (values.size() == 1) throw fail(path, "'" + m.group(1) + "' is a one-of of one value: make it on or off");
            out.add(new Keyword(m.group(1), values));
            at = skipWs(body, m.end());
        }
        return out;
    }

    private static List<String> withValues(String name, List<String> values) {
        if (values.isEmpty()) return List.of(name);
        List<String> out = new ArrayList<>();
        for (String v : values) out.add(name + "_" + v);
        return out;
    }

    /**
     * Each keyword a {@code cg_feature}; a one-of also a permutation dimension, so two of its values are refused and a
     * precache builds each. {@code switched}: every keyword is the renderer's to switch (a global's), so a precache
     * builds every combination.
     */
    private static void keywordPragmas(StringBuilder out, List<Keyword> keywords, boolean switched) {
        for (Keyword k : keywords) {
            for (String f : withValues(k.name(), k.values())) out.append("#pragma cg_feature ").append(f).append('\n');
        }
        for (Keyword k : keywords) {
            if (k.values().isEmpty() && !switched) continue;
            out.append("#pragma cg_permutation ").append(String.join("|", withValues(k.name(), k.values()))).append('\n');
        }
    }

    /** {@code source} with every comment blanked to spaces, newlines kept: offsets unchanged. */
    static String blankComments(String source) {
        char[] c = source.toCharArray();
        for (int i = 0; i < c.length; i++) {
            if (c[i] == '"') {
                for (i++; i < c.length && c[i] != '"' && c[i] != '\n'; i++) { }
            } else if (c[i] == '/' && i + 1 < c.length && c[i + 1] == '/') {
                for (; i < c.length && c[i] != '\n'; i++) c[i] = ' ';
            } else if (c[i] == '/' && i + 1 < c.length && c[i + 1] == '*') {
                for (; i < c.length && !(c[i] == '*' && i + 1 < c.length && c[i + 1] == '/'); i++) {
                    if (c[i] != '\n') c[i] = ' ';
                }
                if (i + 1 < c.length) c[i] = c[i + 1] = ' ';
                i++;
            }
        }
        return new String(c);
    }

    private static int skipWs(String t, int i) {
        while (i < t.length() && Character.isWhitespace(t.charAt(i))) i++;
        return i;
    }

    private static int eol(String t, int i) {
        int e = t.indexOf('\n', i);
        return e < 0 ? t.length() : e;
    }

    private static CgShaderParseException fail(String path, String why) {
        return new CgShaderParseException("[" + path + "] " + why);
    }
}
