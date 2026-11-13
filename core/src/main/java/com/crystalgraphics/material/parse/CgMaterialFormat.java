package com.crystalgraphics.material.parse;

import com.crystalgraphics.api.material.CgMaterialDomain;
import com.crystalgraphics.api.material.CgVertexFactory;
import com.crystalgraphics.gpu.resource.CgEngineBufferRegistry;
import com.crystalgraphics.render.post.CgPostPoint;
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

    /** The prefix of a v2f field passing a factory parameter to the fragment: {@code _cg_in_Particle_color}. */
    static final String PASSED = "_cg_in_";

    /**
     * A lowered file.
     *
     * @param source    the {@code Pass { }} source it stands for
     * @param domain    what it is
     * @param maskClip  the {@code opacityMask} threshold: a number or a float property's name; null for the default
     * @param separate  whether it draws after every other translucent draw ({@code TranslucencyPass Separate})
     */
    @Desugar
    record Lowered(String source, CgParsedShader.Format format) {
    }

    enum Kind { DIRECTIVE, BLOCK, STEP, STRUCT, FUNCTION, DECLARATION }

    /** One top-level item: a {@code #} line, a block, a struct, a function or any other declaration. */
    @Desugar
    record Item(Kind kind, String name, String text, String body) {
    }

    @Desugar
    private record Keyword(String name, List<String> values, boolean switched) {
    }

    private static final Set<String> BLOCKS = Set.of("Material", "Keywords", "Properties", "Buffers", "RenderState",
            "EngineBuffers");
    private static final Set<String> OLD = Set.of("Pass", "Tags", "Queue", "SubShader");
    private static final Pattern STRUCT = Pattern.compile("^struct\\s+([A-Za-z_]\\w*)");
    private static final Pattern FUNCTION = Pattern.compile("^(?:[\\w\\[\\]]+\\s+)+?([A-Za-z_]\\w*)\\s*\\(");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_]\\w*");
    private static final Pattern NUMBER = Pattern.compile("[0-9]*\\.?[0-9]+(?:[eE][-+]?[0-9]+)?");
    private static final Pattern KEYWORD = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final Pattern KEYWORD_ENTRY = Pattern.compile(
            "([A-Za-z_]\\w*)\\s*(?:\\(\\s*\"[^\"]*\"\\s*(,\\s*switched\\s*)?\\))?\\s*"
                    + "(?:=\\s*([A-Za-z_]\\w*(?:\\s*\\|\\s*[A-Za-z_]\\w*)*))?");
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
    private static final Pattern PROPERTY_NAME = Pattern.compile("([A-Za-z_]\\w*)\\s*\\(\\s*\"");
    /** The space before a property entry that follows another on its line: {@code ... = 1  _Strength ("...}. */
    private static final Pattern ENTRY_START = Pattern.compile("(?<=\\S)[ \\t]+(?=[A-Za-z_]\\w*\\s*\\(\\s*\")");
    private static final Pattern ARRAY_TYPE = Pattern.compile("(\\w+)\\[(\\d+)]");
    private static final Pattern POST_SURFACE_SIGNATURE = Pattern.compile(
            "^void\\s+surface\\s*\\(\\s*(?:in\\s+)?CgScreen\\s+\\w+\\s*,\\s*inout\\s+CgPostSurface\\s+(\\w+)\\s*\\)");
    private static final Pattern CG_POST_PARAM = Pattern.compile("\\bCgPostSurface\\s+(\\w+)\\s*[,)]");
    private static final Pattern UI_SURFACE_SIGNATURE = Pattern.compile(
            "^void\\s+surface\\s*\\(\\s*(?:in\\s+)?CgUiInput\\s+\\w+\\s*,\\s*inout\\s+CgUiSurface\\s+\\w+\\s*\\)");
    /** {@code CG_STEP(name, uv)} or {@code CG_STEP_SIZE(name)}: an earlier step's target, its name group 2. */
    private static final Pattern STEP_READ = Pattern.compile("\\bCG_STEP(_SIZE)?\\s*\\(\\s*(\\w+)\\s*[,)]");
    private static final Pattern STEP_SAMPLE = Pattern.compile("\\bCG_STEP\\s*\\(\\s*(\\w+)\\s*,");
    private static final Pattern STEP_SIZE = Pattern.compile("\\bCG_STEP_SIZE\\s*\\(\\s*(\\w+)\\s*\\)");
    /** The sampler a post step's target is bound to: {@code _Step_bright}. */
    private static final String STEP_PREFIX = "_Step_";
    private static final List<String> POST_POINTS = new ArrayList<>();

    static {
        // Where a post material may draw (CgPostMaterial): BEFORE_COMPOSITE feeds the composite
        for (CgPostPoint p : CgPostPoint.values()) if (p != CgPostPoint.BEFORE_COMPOSITE) POST_POINTS.add(p.name());
    }

    /** The v2f fields a material with no {@code vertex()} has filled, and with what. */
    private static final Map<String, String[]> AUTO_FIELDS = Map.of(
            "uv0", new String[]{"vec2", "v.uv0"}, "uv1", new String[]{"vec2", "v.uv1"},
            "uv2", new String[]{"vec2", "v.uv2"}, "uv3", new String[]{"vec2", "v.uv3"},
            "color", new String[]{"vec4", "v.color"},
            "normal", new String[]{"vec3", "v.normal"},
            "world", new String[]{"vec3", "v.world"},
            "light", new String[]{"vec2", "CG_OBJECT_LIGHT"});

    /** A surface's outputs. */
    private static final Set<String> OUTPUTS = Set.of("baseColor", "opacity", "opacityMask", "emissive", "normal",
            "refraction", "refractionSplit", "refractionDepth", "pixelDepthOffset", "customData", "shadingModelId",
            "light", "volumeDepth", "depth");

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
                s.depth = cg_LinearEyeDepth(gl_FragCoord.z);
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

    /** {@code source}, a {@code #domain} file, as the passes it lowers onto. */
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
        List<Item> items = scan(t, source, eol, path, BLOCKS);
        if (domain != CgMaterialDomain.POST) {
            for (Item i : items) if (i.kind() == Kind.STEP) throw fail(path, "'Step' is a #domain post material's");
        }
        if (domain != CgMaterialDomain.GLOBAL && block(items, "EngineBuffers") != null) {
            throw fail(path, "'EngineBuffers' is a #domain global's: a material reaches engine buffers through what draws"
                    + " it (VertexFactory, PerDrawValues On, a ui Primitive)");
        }
        return switch (domain) {
            case SURFACE -> lowerSurface(items, path);
            case GLOBAL -> lowerGlobal(items, path);
            case POST -> lowerPost(items, path);
            case UI -> lowerUi(items, path);
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
        List<CgVertexFactory.Factory> listed = new ArrayList<>();
        for (String name : m.getOrDefault("VertexFactory", CgVertexFactory.MESH).split(",")) {
            CgVertexFactory.Factory f = CgFormatTemplates.factory(name);
            if (f == null) {
                throw fail(path, "'VertexFactory " + name + "' is no factory: none registered, and no "
                        + CgFormatTemplates.location(name, "factories", "factory"));
            }
            listed.add(f);
        }
        CgVertexFactory.Factory factory = CgFormatTemplates.combine(listed, path);
        String factoryName = factory.name();
        // What the factory's defaults write is written: its hooks run (a particle's light)
        Set<String> started = new LinkedHashSet<>();
        names(factory.defaults(), "s", started);
        started.retainAll(OUTPUTS);
        named.addAll(started);
        if (named.contains("depth") && named.contains("pixelDepthOffset")) {
            throw fail(path, "'s.depth' and 's.pixelDepthOffset' both set the depth: s.depth is where it stands, the offset"
                    + " a push from its own");
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
            if (f.group(3).startsWith(PASSED)) throw fail(path, "v2f field '" + f.group(3) + "' takes the engine's prefix");
            if (vertex != null) continue;
            String[] auto = AUTO_FIELDS.get(f.group(3));
            if (auto == null) {
                throw fail(path, "v2f field '" + f.group(3) + "' is filled by no one: write vertex(), or name it one of "
                        + AUTO_FIELDS.keySet());
            }
            if (!auto[0].equals(f.group(2))) throw fail(path, "v2f field '" + f.group(3) + "' is a " + auto[0]);
            autoFill.append("        o.").append(f.group(3)).append(" = ").append(auto[1]).append(";\n");
        }

        // The factory's parameters surface() reads, each passed as an interpolator
        refuseOtherParameters(code, factory, path);
        StringBuilder passOut = new StringBuilder(), passIn = new StringBuilder();
        for (String[] p : passedParameters(factory, functions, surface)) {
            v2fBody += "\n    " + p[0] + " " + p[1] + ";";
            passOut.append("        o.").append(p[1]).append(" = ").append(p[2]).append(";\n");
            passIn.append("        ").append(p[2]).append(" = i.").append(p[1]).append(";\n");
        }

        // The preamble: keywords, engine buffers, tags, queue
        StringBuilder out = new StringBuilder();
        out.append("#type ").append(factory.vertexType()).append('\n')
           .append(CgFormatTemplates.pragmas(factory.declarations(), true));
        keywordPragmas(out, keywords(block(items, "Keywords"), path));
        Set<String> uses = new LinkedHashSet<>(factory.engineBuffers());
        if (rows) uses.add(CgShaderParser.ROWS);
        if (namesGlobals(code)) uses.add("globals");
        refuseNamedBuffers(code, uses, path);
        for (String u : uses) out.append("#pragma cg_use ").append(u).append('\n');
        out.append("Tags { \"RenderType\" = \"").append(sky ? "Background" : translucent ? "Transparent" : "Opaque").append('"')
           .append(" \"Lighting\" = \"").append(unlit ? "Unlit" : surfaceLit ? "Surface" : "Lit").append('"');
        if (!fog) out.append(" \"Fog\" = \"Off\"");
        if (!media) out.append(" \"Media\" = \"Off\"");
        if (linear) out.append(" \"ColorSpace\" = \"Linear\"");
        if (clipDepth || masked) out.append(" \"Depth\" = \"Clip\"");
        if (prepass) out.append(" \"Depth\" = \"Prepass\"");
        if (margin != null) out.append(" \"SceneColorMargin\" = \"").append(margin).append('"');
        factory.tags().forEach((k, v) -> out.append(" \"").append(k).append("\" = \"").append(v).append('"'));
        out.append(" }\n");
        String queue = sky ? "Background" : masked ? "AlphaTest" : !translucent ? "Geometry"
                : pass.equals("AfterDistortion") ? "AfterDistortion" : pass.equals("Overlay") ? "Overlay" : "Transparent";
        out.append("Queue = \"").append(queue).append("\"\n");
        appendData(out, items, factory.properties(), "VertexFactory " + factory.name(), path);
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
        out.append(CgFormatTemplates.pragmas(factory.declarations(), false));
        appendCode(out, code, vertex, surface, functions);
        out.append("    void vertex(out v2f o) {\n        CgVertex v;\n");
        for (String l : factory.fill().strip().split("\n")) out.append("        ").append(l.strip()).append('\n');
        if (vertex != null) out.append("        ").append(MATERIAL_VERTEX).append("(v, o);\n");
        out.append(autoFill).append(passOut);
        out.append("        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(v.world, 1.0);\n");
        // A sky stands at the far plane, behind everything whatever its mesh's size, as Unreal's sky materials do
        if (sky) out.append("        gl_Position.z = CG_FAR_PLANE_Z(gl_Position.w);\n");
        out.append("    }\n");
        // What the factory starts the surface from, and how it fades what the material adds
        StringBuilder start = new StringBuilder("        CgSurface s = cg_SurfaceDefaults();\n");
        for (String l : factory.defaults().strip().split("\n")) if (!l.isBlank()) start.append("        ").append(l.strip()).append('\n');
        String run = "        " + MATERIAL_SURFACE + "(i, s);\n" + fade(factory.fade(), blendMode);
        out.append("    void fragment(in v2f i, out vec4 fragColor) {\n").append(passIn).append(start).append(run);
        if (named.contains("pixelDepthOffset")) out.append("        CG_PIXEL_DEPTH_OFFSET(s.pixelDepthOffset);\n");
        if (named.contains("depth")) out.append("        gl_FragDepth = cg_WindowDepth(s.depth);\n");
        if (clips && named.contains("opacityMask")) out.append("        cg_Clip(s.opacityMask);\n");
        if (named.contains("light")) out.append("        cg_Light = s.light;\n");
        if (named.contains("emissive")) out.append("        CG_GLOW(s.emissive);\n");
        if (surfaceLit) out.append("        CG_SURFACE_NORMAL(s.normal);\n");
        if (named.contains("customData")) out.append("        CG_CUSTOM_DATA_OUT(s.customData);\n");
        if (named.contains("shadingModelId")) {
            out.append("        CG_SHADING_MODEL_OUT(s.shadingModelId.x, s.shadingModelId.y);\n");
        }
        if (named.contains("volumeDepth")) out.append("        CG_VOLUME_DEPTH(s.volumeDepth);\n");
        // Added light leaves the target's alpha as it was, as Unreal's BLEND_Additive does (alpha Zero, One)
        boolean adds = blendMode.equals("Additive") || blendMode.equals("Subtract");
        out.append("        fragColor = vec4(s.baseColor, ").append(adds ? "0.0" : "s.opacity").append(");\n    }\n}\n");

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
               .append("    void fragment(in v2f i, out vec4 offset) {\n").append(passIn).append(start).append(run)
               .append("        offset = CG_DISTORTION(s.refraction, s.refractionSplit, s.refractionDepth);\n    }\n}\n");
        }
        return new Lowered(out.toString(),
                new CgParsedShader.Format(CgMaterialDomain.SURFACE, maskClip, pass.equals("Separate"), null));
    }

    /**
     * A factory's fade of all the material adds, by its blend mode: what a straight-alpha blend weighs is its opacity, a
     * premultiplied one its colour and opacity, an added one its colour; a multiplied one fades toward white. The glow
     * and the bend always.
     */
    private static String fade(@Nullable String expression, String blendMode) {
        if (expression == null) return "";
        StringBuilder out = new StringBuilder("        float _cg_fade = ").append(expression).append(";\n")
                .append("        s.emissive *= _cg_fade;\n        s.refraction *= _cg_fade;\n");
        switch (blendMode) {
            case "Translucent" -> out.append("        s.opacity *= _cg_fade;\n");
            case "AlphaComposite" -> out.append("        s.baseColor *= _cg_fade;\n        s.opacity *= _cg_fade;\n");
            case "Additive", "Subtract" -> out.append("        s.baseColor *= _cg_fade;\n");
            case "Modulate" -> out.append("        s.baseColor = mix(vec3(1.0), s.baseColor, _cg_fade);\n");
            default -> { }
        }
        return out.toString();
    }

    // ── global ──────────────────────────────────────────────────────────────────

    private static Lowered lowerGlobal(List<Item> items, String path) {
        Map<String, String> m = properties(block(items, "Material"),
                List.of("SceneColorMargin", "ColorSpace", "Queue", "Media"), path);
        // What its colour is: Target, written as it means, by default; sRGB decoded into a linear scene, Linear encoded
        // onto an encoded target
        String colorSpace = choice(m, "ColorSpace", "Target", path, "Target", "sRGB", "Linear");
        // Where a consumer's own mesh pass sorts among the world's draws, and whether the smoke in front dims it
        String queue = choice(m, "Queue", "Overlay", path, "Background", "Geometry", "AlphaTest", "Transparent",
                "AfterDistortion", "Overlay");
        boolean media = onOff(m, "Media", false, path);
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
        keywordPragmas(out, keywords(block(items, "Keywords"), path));
        // The engine's own passes name the engine buffers they read: no factory or primitive draws them
        Set<String> uses = new LinkedHashSet<>();
        Item engine = block(items, "EngineBuffers");
        if (engine != null && !engine.body().isBlank()) uses.addAll(List.of(engine.body().trim().split("\\s+")));
        if (namesGlobals(code)) uses.add("globals");
        for (String u : uses) {
            if (CgEngineBufferRegistry.get(u) == null) throw fail(path, "'" + u + "' is no engine buffer: " + CgEngineBufferRegistry.tokens());
        }
        refuseNamedBuffers(code, uses, path);
        for (String u : uses) out.append("#pragma cg_use ").append(u).append('\n');
        out.append("Tags { \"RenderType\" = \"Opaque\" \"Lighting\" = \"Unlit\" \"Fog\" = \"Off\"")
           .append(media ? "" : " \"Media\" = \"Off\"").append(" \"ColorSpace\" = \"").append(colorSpace).append('"');
        if (m.containsKey("SceneColorMargin")) {
            out.append(" \"SceneColorMargin\" = \"").append(m.get("SceneColorMargin")).append('"');
        }
        out.append(" }\nQueue = \"").append(queue).append("\"\n");
        appendData(out, items, "", "", path);
        Item v2f = struct(items, "v2f");
        out.append("struct v2f {").append(v2f == null ? "" : v2f.body()).append("};\n");
        out.append("Pass {\n    Tags { \"LightMode\" = \"Forward\" }\n");
        if (state != null) out.append("    ").append(state.text()).append('\n');
        appendCode(out, code, vertex, fragment, functions);
        out.append(vertex.text()).append('\n').append(fragment.text()).append("\n}\n");
        return new Lowered(out.toString(), new CgParsedShader.Format(CgMaterialDomain.GLOBAL, null, false, null));
    }

    // ── post ────────────────────────────────────────────────────────────────────

    private static Lowered lowerPost(List<Item> items, String path) {
        Map<String, String> m = properties(block(items, "Material"), List.of("Location", "SceneColorMargin", "ColorSpace"), path);
        // Linear light by default; Target works on the stored values, as an effect on the picture's encoding does
        boolean linear = choice(m, "ColorSpace", "Linear", path, "Linear", "Target").equals("Linear");
        String location = m.get("Location");
        if (location != null && !POST_POINTS.contains(location)) {
            throw fail(path, "'Location " + location + "' is one of " + POST_POINTS
                    + (location.equals(CgPostPoint.BEFORE_COMPOSITE.name()) ? ": BEFORE_COMPOSITE feeds the composite, and"
                    + " takes the engine's own passes" : ""));
        }
        if (block(items, "RenderState") != null) {
            throw fail(path, "'RenderState' is the engine's in a post material, which draws the whole screen");
        }
        List<Item> code = code(items, path);
        Map<String, List<Item>> functions = functions(code);
        for (String engine : List.of("vertex", "fragment")) {
            if (functions.containsKey(engine)) throw fail(path, "'" + engine + "(' is the engine's in a post material: write surface()");
        }
        for (Item i : code) {
            if (STEP_READ.matcher(i.text()).find()) throw fail(path, "'CG_STEP' reads an earlier step: in a Step's own code");
        }
        List<Item> steps = new ArrayList<>();
        for (Item i : items) if (i.kind() == Kind.STEP) steps.add(i);
        Item single = only(functions, "surface", path);
        if (steps.isEmpty() == (single == null)) {
            throw fail(path, steps.isEmpty() ? "'void surface(in CgScreen i, inout CgPostSurface s)' is missing"
                    : "a post material with Steps writes surface() in each Step, not beside them");
        }

        // Each step's settings and code; one surface() is a step of its own
        List<String> names = new ArrayList<>();
        List<Map<String, String>> settings = new ArrayList<>();
        List<List<Item>> stepCode = new ArrayList<>();
        if (single != null) {
            names.add(null);
            settings.add(Map.of());
            stepCode.add(List.of());
        }
        for (Item step : steps) {
            String body = blankComments(step.body());
            int paren = body.indexOf('(');
            int split = paren < 0 ? body.length() : body.lastIndexOf('\n', paren) + 1;
            Map<String, String> s = pairs(body.substring(0, split), List.of("Target", "Scale"), "Step " + step.name(), path);
            if (names.contains(step.name())) throw fail(path, "'Step " + step.name() + "' twice");
            boolean last = step == steps.get(steps.size() - 1);
            if (last && !s.isEmpty()) throw fail(path, "'Step " + step.name() + "' is the last, drawn onto the scene: no Target or Scale");
            if (!last && !s.containsKey("Target")) {
                throw fail(path, "'Step " + step.name() + "' needs a Target (a CgFormat, as RGBA16_FLOAT): only the last draws onto the scene");
            }
            List<Item> own = code(scan(body, step.body(), split, path, Set.of()), path);
            for (Item i : own) {
                Matcher read = STEP_READ.matcher(i.text());
                while (read.find()) {
                    if (!names.contains(read.group(2))) {
                        throw fail(path, "'Step " + step.name() + "' reads '" + read.group(2) + "', which is no earlier step");
                    }
                }
            }
            names.add(step.name());
            settings.add(s);
            stepCode.add(own);
        }

        String decode = linear ? "cg_PostDecode" : "";
        StringBuilder out = new StringBuilder("#type none\n");
        keywordPragmas(out, keywords(block(items, "Keywords"), path));
        List<Item> all = new ArrayList<>(code);
        stepCode.forEach(all::addAll);
        Set<String> uses = namesGlobals(all) ? Set.of("globals") : Set.of();
        refuseNamedBuffers(all, uses, path);
        for (String u : uses) out.append("#pragma cg_use ").append(u).append('\n');
        out.append("Tags { \"RenderType\" = \"Transparent\" \"Lighting\" = \"Unlit\" \"Fog\" = \"Off\" \"ColorSpace\" = \"Target\"");
        // One triangle over the target, each pixel reading its own unless it says how far past it reads
        out.append(" \"SceneColorMargin\" = \"").append(m.getOrDefault("SceneColorMargin", "0.001")).append('"');
        out.append(" }\nQueue = \"Overlay\"\n");
        StringBuilder targets = new StringBuilder();
        for (int s = 0; s < names.size() - 1; s++) {
            targets.append(STEP_PREFIX).append(names.get(s)).append(" (\"Step ").append(names.get(s))
                   .append("\", sampler2D) = \"black\"\n    ");
        }
        appendData(out, items, targets.toString(), "the post material's steps", path);
        out.append("struct v2f { vec2 uv; };\n");

        for (int s = 0; s < names.size(); s++) {
            List<Item> passCode = new ArrayList<>(code);
            passCode.addAll(stepCode.get(s));
            Map<String, List<Item>> passFunctions = functions(passCode);
            Item surface = only(passFunctions, "surface", path);
            if (surface == null) throw fail(path, "'Step " + names.get(s) + "' writes no surface()");
            Matcher sig = POST_SURFACE_SIGNATURE.matcher(surface.text());
            if (!sig.find()) throw fail(path, "'surface(' is 'void surface(in CgScreen i, inout CgPostSurface s)'");
            Set<String> reached = reach(passFunctions, surface);
            boolean readsColor = false, namesOpacity = false;
            for (String f : reached) {
                for (Item fn : passFunctions.get(f)) {
                    String param = f.equals("surface") ? sig.group(1) : null;
                    Matcher p = param != null ? null : CG_POST_PARAM.matcher(fn.text().substring(0, fn.text().indexOf('{')));
                    List<String> params = new ArrayList<>();
                    if (param != null) params.add(param);
                    while (p != null && p.find()) params.add(p.group(1));
                    for (String q : params) {
                        readsColor |= reads(fn.body(), q, "color");
                        namesOpacity |= Pattern.compile("\\b" + Pattern.quote(q) + "\\s*\\.\\s*opacity\\b").matcher(fn.body()).find();
                    }
                }
            }
            out.append("Pass {\n    Tags { \"LightMode\" = \"Forward\"");
            if (names.get(s) != null) out.append(" \"Name\" = \"").append(names.get(s)).append('"');
            Map<String, String> set = settings.get(s);
            if (set.containsKey("Target")) {
                out.append(" \"Target\" = \"").append(STEP_PREFIX).append(names.get(s)).append('"')
                   .append(" \"Format\" = \"").append(set.get("Target")).append('"');
            }
            if (set.containsKey("Scale")) out.append(" \"Scale\" = \"").append(set.get("Scale")).append('"');
            out.append(" }\n    RenderState {\n        Blend OFF\n        DepthTest ALWAYS\n        DepthWrite OFF\n        Cull OFF\n    }\n")
               .append("#include \"crystalgraphics:shaders/lib/post/post_material.glsl\"\n")
               .append("#include \"crystalgraphics:shaders/env/material.glsl\"\n");
            StringBuilder body = new StringBuilder();
            appendCode(body, passCode, null, surface, passFunctions);
            String sized = STEP_SIZE.matcher(body).replaceAll("textureSize(" + STEP_PREFIX + "$1, 0)");
            out.append(STEP_SAMPLE.matcher(sized).replaceAll("texture(" + STEP_PREFIX + "$1,"));
            out.append("    void vertex(out v2f o) {\n")
               .append("        o.uv = post_fullscreen_uv(CG_VERTEX_ID);\n")
               .append("        gl_Position = vec4(o.uv * 2.0 - 1.0, 0.0, 1.0);\n    }\n")
               .append("    void fragment(in v2f i, out vec4 fragColor) {\n")
               .append("        CgScreen _cg_screen;\n        _cg_screen.uv = i.uv;\n        _cg_screen.pixel = gl_FragCoord.xy;\n")
               .append("        CgPostSurface s;\n")
               .append("        s.color = ").append(readsColor ? decode + "(CG_SCENE_COLOR(i.uv).rgb)" : "vec3(0.0)").append(";\n")
               .append("        s.opacity = 1.0;\n")
               .append("        ").append(MATERIAL_SURFACE).append("(_cg_screen, s);\n");
            // Linear light in and out: the step writing the scene encodes it where the scene is stored sRGB; a step
            // into its own target keeps linear
            String result = namesOpacity ? "mix(" + decode + "(CG_SCENE_COLOR(i.uv).rgb), s.color, s.opacity)" : "s.color";
            if (linear && !set.containsKey("Target")) result = "cg_PostEncode(" + result + ")";
            out.append("        fragColor = vec4(").append(result).append(", 1.0);\n    }\n}\n");
        }
        return new Lowered(out.toString(), new CgParsedShader.Format(CgMaterialDomain.POST, null, false, location));
    }

    /** Whether {@code body} reads {@code param.field}: names it anywhere but as the target of a plain assignment. */
    private static boolean reads(String body, String param, String field) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(param) + "\\s*\\.\\s*" + field + "\\b(\\s*=(?!=))?").matcher(body);
        while (m.find()) if (m.group(1) == null) return true;
        return false;
    }

    // ── ui ──────────────────────────────────────────────────────────────────────

    private static Lowered lowerUi(List<Item> items, String path) {
        Map<String, String> m = properties(block(items, "Material"), List.of("Primitive", "ColorSpace"), path);
        // sRGB, decoded into a linear scene, by default; Target where the material converts itself (glass over a backdrop)
        String colorSpace = choice(m, "ColorSpace", "sRGB", path, "sRGB", "Target");
        String kind = m.getOrDefault("Primitive", "Quad");
        CgFormatTemplates.Primitive primitive = CgFormatTemplates.primitive(kind);
        if (primitive == null) {
            throw fail(path, "'Primitive " + kind + "' has no file: " + CgFormatTemplates.location(kind, "primitives", "primitive")
                    + " (the engine's: Quad, Curve, Text)");
        }
        if (block(items, "RenderState") != null) {
            throw fail(path, "'RenderState' is the primitive's in a ui material: premultiplied, over what is drawn before it");
        }
        List<Item> code = code(items, path);
        Map<String, List<Item>> functions = functions(code);
        for (String engine : List.of("vertex", "fragment")) {
            if (functions.containsKey(engine)) throw fail(path, "'" + engine + "(' is the primitive's in a ui material: write surface()");
        }
        if (struct(items, "v2f") != null) throw fail(path, "'struct v2f' is the primitive's in a ui material: surface() takes CgUiInput");
        Item surface = only(functions, "surface", path);
        if (surface == null || !UI_SURFACE_SIGNATURE.matcher(surface.text()).find()) {
            throw fail(path, "'void surface(in CgUiInput i, inout CgUiSurface s)' is a ui material's");
        }
        for (Item i : code) {
            if (i.kind() == Kind.FUNCTION && i.text().contains("gl_Position")) throw fail(path, "'gl_Position' is the primitive's in a ui material");
        }

        StringBuilder out = new StringBuilder("#type none\n").append(primitive.pragmas());
        keywordPragmas(out, keywords(block(items, "Keywords"), path));
        Set<String> uses = new LinkedHashSet<>(primitive.buffers());
        if (namesGlobals(code)) uses.add("globals");
        refuseNamedBuffers(code, uses, path);
        for (String u : uses) out.append("#pragma cg_use ").append(u).append('\n');
        out.append("Tags { \"RenderType\" = \"Transparent\"")
           .append(colorSpace.equals("Target") ? " \"ColorSpace\" = \"Target\"" : "")
           .append(" }\nQueue = \"").append(primitive.queue()).append("\"\n");
        appendData(out, items, primitive.properties(), "Primitive " + kind, path);
        out.append("struct v2f {").append(primitive.v2f()).append("};\n");
        out.append("Pass {\n    Tags { \"LightMode\" = \"Forward\" }\n").append(primitive.renderState()).append('\n')
           .append("#include \"crystalgraphics:shaders/env/material.glsl\"\n").append(primitive.declarations());
        appendCode(out, code, null, surface, functions);
        out.append(primitive.vertex()).append('\n')
           .append(primitive.fragment().replace(CgFormatTemplates.SURFACE_CALL + "(", MATERIAL_SURFACE + "(")).append("\n}\n");
        return new Lowered(out.toString(), new CgParsedShader.Format(CgMaterialDomain.UI, null, false, null));
    }

    // ── shared ──────────────────────────────────────────────────────────────────

    /** The top-level items of {@code t} from {@code from}: {@code t} is {@code source} with its comments blanked. */
    static List<Item> scan(String t, String source, int from, String path, Set<String> blocks) {
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
                if (line.startsWith("#type")) throw fail(path, "'#type' is not written in a .shader: a #domain file names its VertexFactory");
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
            if (blocks.contains(word) && k < n && t.charAt(k) == '{') {
                int close = CgStructureParser.matchBrace(t, k);
                for (Item seen : items) {
                    if (seen.kind() == Kind.BLOCK && seen.name().equals(word)) throw fail(path, "'" + word + "' twice");
                }
                items.add(new Item(Kind.BLOCK, word, source.substring(i, close + 1), t.substring(k + 1, close)));
                int s = skipWs(t, close + 1);
                i = s < n && t.charAt(s) == ';' ? s + 1 : close + 1;
                continue;
            }
            if (word.equals("Step")) {
                // Step name { Target FORMAT Scale s  void surface(...) { } }: a post material's step
                Matcher name = IDENT.matcher(t);
                if (!name.find(k) || name.start() != k) throw fail(path, "'Step' takes a name: Step bright { ... }");
                int open = skipWs(t, name.end());
                if (open >= n || t.charAt(open) != '{') throw fail(path, "'Step " + name.group() + "' takes a { } block");
                int close = CgStructureParser.matchBrace(t, open);
                items.add(new Item(Kind.STEP, name.group(), source.substring(i, close + 1), source.substring(open + 1, close)));
                i = close + 1;
                continue;
            }
            if (OLD.contains(word)) {
                throw fail(path, "'" + word + "' is not written in a .shader: a #domain file has no passes, tags or queue;"
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
            if (i.kind() == Kind.BLOCK || i.kind() == Kind.STEP) continue;
            if (i.kind() == Kind.STRUCT && (i.name().equals("v2f") || elements.contains(i.name()))) continue;
            if (i.kind() == Kind.FUNCTION && (i.name().equals(MATERIAL_VERTEX) || i.name().equals(MATERIAL_SURFACE))) {
                throw fail(path, "'" + i.name() + "' is the engine's name");
            }
            out.add(i);
        }
        return out;
    }

    /**
     * Properties ({@code added}, the engine's for {@code owner}, after the material's), then the structs a buffer holds,
     * then Buffers: where the old parser reads them.
     */
    private static void appendData(StringBuilder out, List<Item> items, String added, String owner, String path) {
        Item props = block(items, "Properties"), buffers = block(items, "Buffers");
        added = added.strip();
        if (!added.isEmpty() && props != null) {
            Matcher own = PROPERTY_NAME.matcher(props.body());
            Set<String> theirs = new LinkedHashSet<>();
            for (Matcher m = PROPERTY_NAME.matcher(added); m.find(); ) theirs.add(m.group(1));
            while (own.find()) {
                if (theirs.contains(own.group(1))) throw fail(path, "'" + own.group(1) + "' is " + owner + "'s: the engine binds it");
            }
        }
        // One entry a line, as the old parser reads them: a format file may write several on one. What the engine binds
        // first, in its order: a grid's atlas is bound by position.
        String own = props == null ? "" : ENTRY_START.matcher(props.body().strip()).replaceAll("\n    ");
        if (!own.isEmpty() || !added.isEmpty()) {
            out.append("Properties {\n    ").append(ENTRY_START.matcher(added).replaceAll("\n    "))
               .append(own.isEmpty() || added.isEmpty() ? "" : "\n    ").append(own).append("\n}\n");
        }
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
     * refused here, naming what would reach it.
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

    /**
     * The factory's parameter fields {@code surface()} and its helpers name, each {@code {v2f type, v2f field,
     * expression}}: a field a dotted name reaches, or that reaches it ({@code cg_Tube.ring} passes every ring field,
     * {@code cg_Particle.color.rgb} the colour); an array field one interpolator an element.
     */
    private static List<String[]> passedParameters(CgVertexFactory.Factory factory, Map<String, List<Item>> functions,
                                                   Item surface) {
        List<String[]> out = new ArrayList<>();
        String variable = factory.parameters();
        if (variable == null) return out;
        Set<String> chains = new LinkedHashSet<>();
        Pattern chain = Pattern.compile("\\b" + Pattern.quote(variable) + "\\b((?:\\s*\\.\\s*\\w+)*)");
        boolean whole = false;   // passed whole, as to cg_dynamic_parameter(cg_Particle, ...)
        List<String> read = new ArrayList<>();
        for (String name : reach(functions, surface)) for (Item f : functions.get(name)) read.add(f.body());
        // The factory's own defaults and fade read them in the fragment too
        read.add(factory.defaults());
        if (factory.fade() != null) read.add(factory.fade());
        for (String body : read) {
            Matcher m = chain.matcher(body);
            while (m.find()) {
                if (m.group(1).isEmpty()) whole = true;
                else chains.add(m.group(1).replaceAll("\\s", "").substring(1));
            }
        }
        for (Map.Entry<String, String> field : factory.parameterFields().entrySet()) {
            String k = field.getKey();
            boolean named = whole;
            for (String c : chains) named |= c.equals(k) || c.startsWith(k + ".") || k.startsWith(c + ".");
            if (!named) continue;
            String v2f = PASSED + variable.replaceFirst("^cg_", "") + "_" + k.replace('.', '_');
            Matcher array = ARRAY_TYPE.matcher(field.getValue());
            if (array.matches()) {
                for (int e = 0; e < Integer.parseInt(array.group(2)); e++) {
                    out.add(new String[]{array.group(1), v2f + "_" + e, variable + "." + k + "[" + e + "]"});
                }
            } else {
                out.add(new String[]{field.getValue(), v2f, variable + "." + k});
            }
        }
        return out;
    }

    /** A factory's parameters named under another factory: they hold nothing there. */
    private static void refuseOtherParameters(List<Item> code, CgVertexFactory.Factory factory, String path) {
        StringBuilder all = new StringBuilder();
        for (Item i : code) all.append(i.text()).append('\n');
        Map<String, List<String>> owners = new LinkedHashMap<>();
        for (CgVertexFactory.Factory f : CgFormatTemplates.factories()) {
            if (f.parameters() != null && !f.parameters().equals(factory.parameters())) {
                owners.computeIfAbsent(f.parameters(), k -> new ArrayList<>()).add(f.name());
            }
        }
        owners.forEach((variable, names) -> {
            if (Pattern.compile("\\b" + Pattern.quote(variable) + "\\b").matcher(all).find()) {
                throw fail(path, "'" + variable + "' is what VertexFactory " + String.join(" or ", names)
                        + " passes, not " + factory.name());
            }
        });
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

    static Item block(List<Item> items, String name) {
        for (Item i : items) if (i.kind() == Kind.BLOCK && i.name().equals(name)) return i;
        return null;
    }

    private static Item struct(List<Item> items, String name) {
        for (Item i : items) if (i.kind() == Kind.STRUCT && i.name().equals(name)) return i;
        return null;
    }

    /** A {@code Material { }} block's {@code Name Value} pairs, each name one of {@code allowed}. */
    private static Map<String, String> properties(@Nullable Item block, List<String> allowed, String path) {
        return block == null ? new LinkedHashMap<>() : pairs(block.body(), allowed, "Material", path);
    }

    /** {@code body}'s {@code Name Value} pairs, each name one of {@code allowed}; {@code what} names the block. */
    static Map<String, String> pairs(String body, List<String> allowed, String what, String path) {
        Map<String, String> out = new LinkedHashMap<>();
        // A list is one value: VertexFactory Mesh, Tube
        String[] tokens = body.trim().replaceAll("\\s*,\\s*", ",").split("\\s+");
        if (tokens.length == 1 && tokens[0].isEmpty()) return out;
        if (tokens.length % 2 != 0) throw fail(path, "'" + what + "' holds Name Value pairs: '" + body.trim() + "'");
        for (int i = 0; i < tokens.length; i += 2) {
            if (!allowed.contains(tokens[i])) throw fail(path, "'" + tokens[i] + "' is no " + what + " setting here: " + allowed);
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

    /**
     * {@code Keywords { }}: {@code NAME ("label")} is on or off, {@code NAME ("label") = A | B} one of its values;
     * {@code NAME ("label", switched)} the renderer's to switch at each draw.
     */
    private static List<Keyword> keywords(@Nullable Item block, String path) {
        List<Keyword> out = new ArrayList<>();
        if (block == null) return out;
        String body = block.body();
        int at = skipWs(body, 0);
        Matcher m = KEYWORD_ENTRY.matcher(body);
        while (at < body.length()) {
            if (!m.find(at) || m.start() != at) {
                throw fail(path, "'" + body.substring(at, eol(body, at)).trim() + "' is no keyword:"
                        + " NAME (\"label\"), NAME (\"label\", switched) or NAME (\"label\") = A | B");
            }
            List<String> values = new ArrayList<>();
            if (m.group(3) != null) for (String v : m.group(3).split("\\|")) values.add(v.trim());
            for (String name : withValues(m.group(1), values)) {
                if (!KEYWORD.matcher(name).matches()) throw fail(path, "'" + name + "' is no keyword: [A-Z][A-Z0-9_]*");
            }
            if (values.size() == 1) throw fail(path, "'" + m.group(1) + "' is a one-of of one value: make it on or off");
            out.add(new Keyword(m.group(1), values, m.group(2) != null));
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
     * Each keyword a {@code cg_feature}; a one-of, and a keyword marked {@code switched}, also a permutation dimension,
     * so a precache builds each value (and two of a one-of's are refused).
     */
    private static void keywordPragmas(StringBuilder out, List<Keyword> keywords) {
        for (Keyword k : keywords) {
            for (String f : withValues(k.name(), k.values())) out.append("#pragma cg_feature ").append(f).append('\n');
        }
        for (Keyword k : keywords) {
            if (k.values().isEmpty() && !k.switched()) continue;
            out.append("#pragma cg_permutation ").append(String.join("|", withValues(k.name(), k.values()))).append('\n');
        }
    }

    /** A factory's or primitive's {@code Keywords { }}, lowered as a material's. */
    static String keywordLines(List<Item> items, String path) {
        StringBuilder out = new StringBuilder();
        keywordPragmas(out, keywords(block(items, "Keywords"), path));
        return out.toString();
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

    static CgShaderParseException fail(String path, String why) {
        return new CgShaderParseException("[" + path + "] " + why);
    }
}
