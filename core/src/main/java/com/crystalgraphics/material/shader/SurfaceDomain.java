package com.crystalgraphics.material.shader;

import com.crystalgraphics.api.material.CgMaterialDomain;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.api.material.CgVertexFactory;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgColorMask;
import com.crystalgraphics.api.state.CgCullState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.material.shader.syntax.CgCodeScan;
import com.crystalgraphics.material.shader.syntax.CgRenderStateGrammar;
import com.crystalgraphics.material.shader.syntax.CgSettingsGrammar;
import com.crystalgraphics.material.shader.syntax.CgShaderNames;
import com.crystalgraphics.material.shader.syntax.CgShaderSyntax;
import com.crystalgraphics.material.shader.syntax.CgShaderTokens;
import com.crystalgraphics.platform.gl.driver.GLC;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code #domain surface}: anything in the world. The material's {@code surface()} and optional {@code vertex()} are
 * pasted into its vertex factory's pass, as Unreal's material translator pastes a material into its pass templates:
 * a Forward pass whose vertex fills a {@code CgVertex} from the factory, runs the material's {@code vertex()} and
 * projects {@code v.world}, and whose fragment runs {@code surface()} and hands each output it names to the engine's
 * hook; a codeless Emissive pass where it names {@code emissive}, a Distortion pass where it names {@code refraction}.
 * Its render state, queue and lighting come from {@code Material { }}.
 */
final class SurfaceDomain {

    /** The material's {@code vertex()}, renamed: the pass's own {@code vertex} is the engine's. */
    static final String MATERIAL_VERTEX = SpecReader.MATERIAL_VERTEX;

    private static final List<String> SETTINGS = List.of("BlendMode", "ShadingModel", "TranslucencyLighting", "Faces",
            "TranslucencyPass", "DepthTest", "DepthWrite", "DepthPrepass", "ClipDepth", "MaskClip", "Fog", "Media",
            "ColorSpace", "SceneColorMargin", "Sky", "PerDrawValues", "VertexFactory");

    /** A surface's outputs. */
    private static final Set<String> OUTPUTS = Set.of("baseColor", "opacity", "opacityMask", "emissive", "normal",
            "refraction", "refractionSplit", "refractionDepth", "pixelDepthOffset", "customData", "shadingModelId",
            "light", "volumeDepth", "depth");

    /** The v2f fields a material with no {@code vertex()} has filled, their type and what fills them. */
    private static final Map<String, String[]> AUTO_FIELDS = new LinkedHashMap<>();

    static {
        AUTO_FIELDS.put("uv0", new String[]{"vec2", "v.uv0"});
        AUTO_FIELDS.put("uv1", new String[]{"vec2", "v.uv1"});
        AUTO_FIELDS.put("uv2", new String[]{"vec2", "v.uv2"});
        AUTO_FIELDS.put("uv3", new String[]{"vec2", "v.uv3"});
        AUTO_FIELDS.put("color", new String[]{"vec4", "v.color"});
        AUTO_FIELDS.put("normal", new String[]{"vec3", "v.normal"});
        AUTO_FIELDS.put("world", new String[]{"vec3", "v.world"});
        AUTO_FIELDS.put("light", new String[]{"vec2", "CG_OBJECT_LIGHT"});
    }

    /** What every surface starts from, before its factory's defaults and its own {@code surface()}. */
    private static final String SURFACE_DEFAULTS = """
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
            """;

    /** Every Distortion pass's blend, the target's encoding: offsets and split add, alpha keeps the nearest haze's closeness. */
    private static final CgBlendState DISTORTION_BLEND = new CgBlendState(true, GLC.GL_ONE, GLC.GL_ONE, GLC.GL_ONE,
            GLC.GL_ONE, GLC.GL_FUNC_ADD, GLC.GL_MAX);

    private SurfaceDomain() {}

    static CgShaderSpec read(CgShaderSyntax file) {
        CgShaderTokens t = file.tokens();
        String path = file.text().path();
        Map<String, CgSettingsGrammar.Setting> m = SpecReader.material(file, SETTINGS);
        CgShaderSyntax.Block state = file.block("RenderState");
        if (state != null) {
            throw t.error(state.first(), "'RenderState' is a #domain global's: a surface material says how it draws in"
                    + " Material { }");
        }
        List<CgShaderSyntax.Item> code = SpecReader.code(file, file.items());
        Map<String, List<CgShaderSyntax.Function>> functions = SpecReader.functions(code);
        CgShaderSyntax.Function surface = SpecReader.only(file, functions, "surface");
        if (surface == null) {
            throw t.error(file.domainToken(), "'void surface(in v2f i, inout CgSurface s)' is missing: a surface material's outputs");
        }
        List<String> sig = SpecReader.signature(file, surface, "void", "surface", "(", "in?", "v2f", "*", ",", "inout",
                "CgSurface", "*", ")");
        if (sig == null) throw t.error(surface.first(), "'surface(' is 'void surface(in v2f i, inout CgSurface s)'");
        CgShaderSyntax.Function vertex = SpecReader.only(file, functions, "vertex");
        if (vertex != null && SpecReader.signature(file, vertex, "void", "vertex", "(", "inout", "CgVertex", "*", ",",
                "out", "v2f", "*", ")") == null) {
            throw t.error(vertex.first(), "'vertex(' is 'void vertex(inout CgVertex v, out v2f o)'");
        }
        if (functions.containsKey("fragment")) {
            throw t.error(functions.get("fragment").get(0).first(), "'fragment(' is a #domain global's: a surface material"
                    + " writes surface()");
        }
        for (CgShaderSyntax.Item i : code) {
            if (i instanceof CgShaderSyntax.Function && SpecReader.scan(file, i).names("gl_Position")) {
                throw t.error(i.first(), "'gl_Position' is the engine's in a surface material: vertex() moves v.world");
            }
        }

        // What it names
        Set<String> named = new LinkedHashSet<>(SpecReader.body(file, surface).members(sig.get(1)));
        for (CgShaderSyntax.Item i : code) {
            if (!(i instanceof CgShaderSyntax.Function f) || f == surface) continue;
            for (String p : SpecReader.parameters(file, f, "CgSurface")) SpecReader.body(file, f).members(p, named);
        }
        named.retainAll(OUTPUTS);

        // Material { }
        String blendMode = SpecReader.choice(file, m, "BlendMode", "Opaque", "Opaque", "Masked", "Translucent",
                "AlphaComposite", "Additive", "Modulate", "Subtract");
        boolean masked = blendMode.equals("Masked");
        boolean translucent = !blendMode.equals("Opaque") && !masked;
        boolean unlit = SpecReader.choice(file, m, "ShadingModel", "WorldLit", "WorldLit", "Unlit").equals("Unlit");
        boolean surfaceLit = SpecReader.choice(file, m, "TranslucencyLighting", "Volume", "Volume", "Surface").equals("Surface");
        String faces = SpecReader.choice(file, m, "Faces", "Front", "Front", "Back", "Both");
        String pass = SpecReader.choice(file, m, "TranslucencyPass", "Standard", "Standard", "AfterDistortion", "Separate", "Overlay");
        String depthTest = SpecReader.choice(file, m, "DepthTest", "On", "On", "Off", "Strict");
        Boolean depthWrite = m.containsKey("DepthWrite") ? SpecReader.onOff(file, m, "DepthWrite", false) : null;
        boolean prepass = SpecReader.onOff(file, m, "DepthPrepass", false);
        boolean clipDepth = SpecReader.onOff(file, m, "ClipDepth", false);
        boolean fog = SpecReader.onOff(file, m, "Fog", true);
        boolean media = SpecReader.onOff(file, m, "Media", true);
        boolean linear = SpecReader.choice(file, m, "ColorSpace", "sRGB", "sRGB", "Linear").equals("Linear");
        boolean sky = SpecReader.onOff(file, m, "Sky", false);
        boolean rows = SpecReader.onOff(file, m, "PerDrawValues", false);
        CgSettingsGrammar.Setting listedSetting = m.get("VertexFactory");
        int factoryToken = listedSetting == null ? file.domainToken() : listedSetting.valueToken();
        List<CgVertexFactory.Factory> listed = new ArrayList<>();
        for (String name : (listedSetting == null ? List.of(CgVertexFactory.MESH) : listedSetting.values())) {
            CgVertexFactory.Factory f = CgShaderTemplates.factory(name);
            if (f == null) {
                throw t.error(factoryToken, "'VertexFactory " + name + "' is no factory: none registered, and no "
                        + CgShaderTemplates.location(name, "factories", "factory"));
            }
            listed.add(f);
        }
        FactoryUse factory = FactoryUse.of(listed, t, factoryToken);
        // What the factory's defaults write is written: its hooks run (a particle's light)
        Set<String> started = CgCodeScan.of(factory.defaults()).members("s");
        started.retainAll(OUTPUTS);
        named.addAll(started);
        if (named.contains("depth") && named.contains("pixelDepthOffset")) {
            throw t.error(surface.first(), "'s.depth' and 's.pixelDepthOffset' both set the depth: s.depth is where it"
                    + " stands, the offset a push from its own");
        }
        CgSettingsGrammar.Setting maskSetting = m.get("MaskClip");
        String maskClip = maskSetting == null ? null : maskSetting.value();
        if (maskClip != null && !number(maskClip) && !(maskClip.length() > 1 && maskClip.charAt(0) == '_'
                && Character.isLetter(maskClip.charAt(1)))) {
            throw t.error(maskSetting.valueToken(), "'MaskClip " + maskClip + "' is a number or a float parameter's name");
        }

        boolean clips = masked || clipDepth || prepass;
        if (!translucent) {
            for (String k : List.of("DepthPrepass", "ClipDepth", "TranslucencyLighting", "TranslucencyPass")) {
                if (m.containsKey(k)) {
                    throw t.error(m.get(k).keyToken(), "'" + k + "' is a translucent blend mode's, not BlendMode " + blendMode
                            + (k.equals("ClipDepth") ? ": Masked is an opaque material cut by opacityMask" : ""));
                }
            }
            for (String o : List.of("opacity", "refraction", "refractionSplit", "refractionDepth")) {
                if (named.contains(o)) {
                    throw t.error(surface.first(), "'s." + o + "' is a translucent blend mode's: BlendMode " + blendMode
                            + " is solid" + (o.equals("opacity") ? " (Masked cuts with s.opacityMask)" : ""));
                }
            }
        }
        if (prepass && clipDepth) {
            throw t.error(m.get("ClipDepth").keyToken(), "'DepthPrepass' and 'ClipDepth' are two ways to write depth: pick one");
        }
        if (clips && depthWrite != null) {
            throw t.error(m.get("DepthWrite").keyToken(), "'DepthWrite' is decided by "
                    + (masked ? "BlendMode Masked" : prepass ? "DepthPrepass" : "ClipDepth"));
        }
        if (maskClip != null && !clips) {
            throw t.error(maskSetting.keyToken(), "'MaskClip' is the threshold of Masked, ClipDepth and DepthPrepass, and it"
                    + " has none of them");
        }
        if (named.contains("opacityMask") && !clips) {
            throw t.error(surface.first(), "'s.opacityMask' cuts only under BlendMode Masked, ClipDepth or DepthPrepass");
        }
        if (surfaceLit && unlit) {
            throw t.error(m.get("TranslucencyLighting").keyToken(), "'TranslucencyLighting Surface' lights by the normal:"
                    + " not ShadingModel Unlit");
        }
        if (named.contains("normal") && !surfaceLit) {
            throw t.error(surface.first(), "'s.normal' lights only TranslucencyLighting Surface; an opaque surface is lit"
                    + " by its mesh's normal");
        }
        if (sky && translucent) throw t.error(m.get("Sky").keyToken(), "'Sky On' draws first, behind everything: not a translucent blend mode");
        if (factory.placesNothing() && vertex == null) {
            throw t.error(factoryToken, "'VertexFactory " + factory.name() + "' supplies no vertices: write vertex(),"
                    + " assigning v.world");
        }
        boolean color = !translucent || named.contains("baseColor") || named.contains("opacity");
        if (!color && !named.contains("emissive") && !named.contains("refraction") && !named.contains("volumeDepth")
                && !clipDepth) {
            throw t.error(surface.first(), "'surface(' names no output a translucent material draws: baseColor, opacity,"
                    + " emissive or refraction");
        }

        // The v2f, and how its fields are filled
        List<Varyings.Field> fields = Varyings.fields(file, file.struct("v2f"));
        StringBuilder autoFill = new StringBuilder();
        for (Varyings.Field f : fields) {
            if (f.name().startsWith(CgShaderNames.PASSED)) {
                throw t.error(f.token(), "v2f field '" + f.name() + "' takes the engine's prefix");
            }
            if (vertex != null) continue;
            String[] auto = AUTO_FIELDS.get(f.name());
            if (auto == null) {
                throw t.error(f.token(), "v2f field '" + f.name() + "' is filled by no one: write vertex(), or name it one of "
                        + AUTO_FIELDS.keySet());
            }
            if (!auto[0].equals(f.type())) throw t.error(f.token(), "v2f field '" + f.name() + "' is a " + auto[0]);
            autoFill.append("        o.").append(f.name()).append(" = ").append(auto[1]).append(";\n");
        }
        List<CgVarying> varyings = new ArrayList<>(Varyings.check(file, fields, false));

        // The factory's parameters surface() reads, each passed as an interpolator
        refuseOtherParameters(file, code, factory);
        StringBuilder passOut = new StringBuilder(), passIn = new StringBuilder();
        for (String[] p : passedParameters(file, factory, functions, surface)) {
            String type = p[0], qualifier = "";
            int space = type.lastIndexOf(' ');
            if (space > 0) {
                qualifier = type.substring(0, space).trim();
                type = type.substring(space + 1);
            }
            varyings.add(new CgVarying(type, p[1], qualifier));
            passOut.append("        o.").append(p[1]).append(" = ").append(p[2]).append(";\n");
            passIn.append("        ").append(p[2]).append(" = i.").append(p[1]).append(";\n");
        }

        // The material
        SpecBuilder spec = new SpecBuilder(file, CgMaterialDomain.SURFACE);
        spec.vertexType = factory.vertexType();
        factory.declareKeywords(spec, path);
        spec.materialKeywords();
        spec.uses.addAll(factory.engineBuffers());
        if (rows && !spec.uses.contains(CgShaderSpec.ROWS)) spec.uses.add(CgShaderSpec.ROWS);
        if (SpecReader.namesGlobals(file, code) && !spec.uses.contains("globals")) spec.uses.add("globals");
        SpecReader.refuseNamedBuffers(file, code, spec.uses);
        spec.renderType = sky ? "Background" : translucent ? "Transparent" : "Opaque";
        spec.lighting = unlit ? CgShaderSpec.Lighting.UNLIT : surfaceLit ? CgShaderSpec.Lighting.SURFACE : CgShaderSpec.Lighting.LIT;
        spec.fog = fog;
        spec.media = media;
        spec.colorSpace = linear ? CgShaderSpec.ColorSpace.LINEAR : CgShaderSpec.ColorSpace.SRGB;
        spec.depthMode = clipDepth || masked ? CgShaderSpec.DepthMode.CLIP
                : prepass ? CgShaderSpec.DepthMode.PREPASS : CgShaderSpec.DepthMode.NONE;
        spec.sceneColorMarginText = m.containsKey("SceneColorMargin") ? m.get("SceneColorMargin").value() : null;
        spec.tube = tube(file, factory, factoryToken);
        spec.queue = sky ? CgRenderQueue.BACKGROUND : masked ? CgRenderQueue.ALPHA_TEST : !translucent ? CgRenderQueue.GEOMETRY
                : pass.equals("AfterDistortion") ? CgRenderQueue.AFTER_DISTORTION
                : pass.equals("Overlay") ? CgRenderQueue.OVERLAY : CgRenderQueue.TRANSPARENT;
        spec.maskClip = maskClip;
        spec.separateTranslucency = pass.equals("Separate");
        spec.templateProperties = factory.properties();
        spec.templateOwner = "VertexFactory " + factory.name();

        // The Forward pass
        int test = depthTest.equals("Off") ? GLC.GL_ALWAYS : depthTest.equals("Strict") ? GLC.GL_LESS : GLC.GL_LEQUAL;
        CgCullState cull = faces.equals("Back") ? CgCullState.FRONT : faces.equals("Both") ? CgCullState.NONE : CgCullState.BACK;
        CgRenderStateGrammar.State forwardState = CgRenderStateGrammar.state();
        switch (blendMode) {
            case "Translucent" -> forwardState.blend(GLC.GL_SRC_ALPHA, GLC.GL_ONE_MINUS_SRC_ALPHA);
            case "AlphaComposite" -> forwardState.blend(GLC.GL_ONE, GLC.GL_ONE_MINUS_SRC_ALPHA);
            case "Additive", "Subtract" -> forwardState.blend(GLC.GL_ONE, GLC.GL_ONE);
            case "Modulate" -> forwardState.blend(GLC.GL_DST_COLOR, GLC.GL_ZERO);
            default -> forwardState.blendOff();
        }
        if (blendMode.equals("Subtract")) forwardState.equation(GLC.GL_FUNC_REVERSE_SUBTRACT);
        forwardState.depthTest(test);
        if (!clips) forwardState.depthWrite(depthWrite != null ? depthWrite : !translucent);
        forwardState.cull(cull);
        if (!color) forwardState.colorMask(new CgColorMask(false, false, false, false, -1));

        SpecReader.PassCode passCode = new SpecReader.PassCode();
        passCode.directive("#include \"crystalgraphics:shaders/env/material/surface.glsl\"");
        passCode.code(SURFACE_DEFAULTS, CgShaderPass.FRAGMENT);
        passCode.glsl(factory.declarations(), path);
        passCode.material(file, code, vertex, surface, true);

        StringBuilder vertexBody = new StringBuilder("\n        CgVertex v;\n");
        for (String l : factory.fill().strip().split("\n")) vertexBody.append("        ").append(l.strip()).append('\n');
        if (vertex != null) vertexBody.append("        ").append(MATERIAL_VERTEX).append("(v, o);\n");
        vertexBody.append(autoFill).append(passOut)
                  .append("        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(v.world, 1.0);\n");
        // A sky stands at the far plane, behind everything whatever its mesh's size, as Unreal's sky materials do
        if (sky) vertexBody.append("        gl_Position.z = CG_FAR_PLANE_Z(gl_Position.w);\n");

        // What the factory starts the surface from, and how it fades what the material adds
        StringBuilder start = new StringBuilder("        CgSurface s = cg_SurfaceDefaults();\n");
        for (String l : factory.defaults().strip().split("\n")) if (!l.isBlank()) start.append("        ").append(l.strip()).append('\n');
        String run = "        " + SpecReader.MATERIAL_SURFACE + "(i, s);\n" + fade(factory.fade(), blendMode);
        StringBuilder fragmentBody = new StringBuilder("\n").append(passIn).append(start).append(run);
        if (named.contains("pixelDepthOffset")) fragmentBody.append("        CG_PIXEL_DEPTH_OFFSET(s.pixelDepthOffset);\n");
        if (named.contains("depth")) fragmentBody.append("        gl_FragDepth = cg_WindowDepth(s.depth);\n");
        if (clips && named.contains("opacityMask")) fragmentBody.append("        cg_Clip(s.opacityMask);\n");
        if (named.contains("light")) fragmentBody.append("        cg_Light = s.light;\n");
        if (named.contains("emissive")) fragmentBody.append("        CG_GLOW(s.emissive);\n");
        if (surfaceLit) fragmentBody.append("        CG_SURFACE_NORMAL(s.normal);\n");
        if (named.contains("customData")) fragmentBody.append("        CG_CUSTOM_DATA_OUT(s.customData);\n");
        if (named.contains("shadingModelId")) {
            fragmentBody.append("        CG_SHADING_MODEL_OUT(s.shadingModelId.x, s.shadingModelId.y);\n");
        }
        if (named.contains("volumeDepth")) fragmentBody.append("        CG_VOLUME_DEPTH(s.volumeDepth);\n");
        // Added light leaves the target's alpha as it was, as Unreal's BLEND_Additive does (alpha Zero, One)
        boolean adds = blendMode.equals("Additive") || blendMode.equals("Subtract");
        fragmentBody.append("        fragColor = vec4(s.baseColor, ").append(adds ? "0.0" : "s.opacity").append(");\n    ");

        CgShaderPass forward = new CgShaderPass(CgRenderPassVariant.FORWARD, "Pass0", forwardState.build(), varyings,
                passCode.directives, passCode.code, vertexBody.toString(), fragmentBody.toString(), "fragColor", false);
        spec.pass(forward);
        spec.text.append(SpecReader.text(passCode, forward.vertexBody(), forward.fragmentBody()));

        // The glow: what the Forward pass draws, with its glow; adding, unless a straight-alpha blend weighs it
        if (named.contains("emissive")) {
            CgRenderStateGrammar.State glow = CgRenderStateGrammar.state();
            if (blendMode.equals("Translucent")) glow.blend(GLC.GL_SRC_ALPHA, GLC.GL_ONE_MINUS_SRC_ALPHA);
            else glow.blend(GLC.GL_ONE, GLC.GL_ONE);
            glow.depthTest(test).depthWrite(false).cull(cull);
            spec.pass(new CgShaderPass(CgRenderPassVariant.EMISSIVE, "Emissive", glow.build(), forward.varyings(),
                    forward.directives(), forward.code(), forward.vertexBody(), forward.fragmentBody(), forward.output(), true));
        }
        // The bend: the Forward pass's vertex, the offset from surface()
        if (named.contains("refraction")) {
            CgRenderStateGrammar.State bend = CgRenderStateGrammar.state();
            if (depthTest.equals("Off")) bend.depthTest(GLC.GL_ALWAYS);
            bend.cull(cull);
            CgRenderState bent = bend.build().withBlend(DISTORTION_BLEND);
            String offset = "\n" + passIn + start + run
                    + "        offset = CG_DISTORTION(s.refraction, s.refractionSplit, s.refractionDepth);\n    ";
            spec.pass(new CgShaderPass(CgRenderPassVariant.DISTORTION, "Distortion", bent, forward.varyings(),
                    forward.directives(), forward.code(), forward.vertexBody(), offset, "offset", false));
            spec.text.append(offset).append('\n');
        }
        return spec.build();
    }

    /**
     * A factory's fade of all the material adds, by its blend mode: a straight-alpha blend weighs its opacity, a
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

    /** The tube geometry its factory's {@code "Tube"} tag asks for; null for the chunked tube or none. */
    @Nullable
    private static CgShaderSpec.Tube tube(CgShaderSyntax file, FactoryUse factory, int at) {
        String tag = factory.tags().get("Tube");
        if (tag == null) return null;
        if (tag.equals("Volume")) return CgShaderSpec.Tube.VOLUME;
        if (tag.equals("Closed")) return CgShaderSpec.Tube.CLOSED;
        throw file.tokens().error(at, "Tube is \"Volume\" or \"Closed\", not \"" + tag + "\"");
    }

    /**
     * The factory's parameter fields {@code surface()} and its helpers name, each {@code {v2f type, v2f field,
     * expression}}: a field a dotted name reaches, or that reaches it ({@code cg_Tube.ring} passes every ring field,
     * {@code cg_Particle.color.rgb} the colour); an array field one interpolator an element.
     */
    private static List<String[]> passedParameters(CgShaderSyntax file, FactoryUse factory,
                                                   Map<String, List<CgShaderSyntax.Function>> functions,
                                                   CgShaderSyntax.Function surface) {
        List<String[]> out = new ArrayList<>();
        String variable = factory.parameters();
        if (variable == null) return out;
        Set<String> chains = new LinkedHashSet<>();
        boolean whole = false;   // passed whole, as to cg_dynamic_parameter(cg_Particle, ...)
        List<CgCodeScan> read = new ArrayList<>();
        for (String name : SpecReader.reach(file, functions, surface)) {
            for (CgShaderSyntax.Function f : functions.get(name)) read.add(SpecReader.body(file, f));
        }
        // The factory's own defaults and fade read them in the fragment too
        read.add(CgCodeScan.of(factory.defaults()));
        if (factory.fade() != null) read.add(CgCodeScan.of(factory.fade()));
        for (CgCodeScan body : read) {
            for (String c : body.chains(variable)) {
                if (c.isEmpty()) whole = true;
                else chains.add(c);
            }
        }
        for (Map.Entry<String, String> field : factory.parameterFields().entrySet()) {
            String k = field.getKey();
            boolean named = whole;
            for (String c : chains) named |= c.equals(k) || c.startsWith(k + ".") || k.startsWith(c + ".");
            if (!named) continue;
            String v2f = CgShaderNames.PASSED + variable.replaceFirst("^cg_", "") + "_" + k.replace('.', '_');
            String type = field.getValue();
            int bracket = type.indexOf('[');
            if (bracket > 0 && type.endsWith("]")) {
                int n = Integer.parseInt(type.substring(bracket + 1, type.length() - 1));
                for (int e = 0; e < n; e++) out.add(new String[]{type.substring(0, bracket), v2f + "_" + e, variable + "." + k + "[" + e + "]"});
            } else {
                out.add(new String[]{type, v2f, variable + "." + k});
            }
        }
        return out;
    }

    /** A factory's parameters named under another factory: they hold nothing there. */
    private static void refuseOtherParameters(CgShaderSyntax file, List<CgShaderSyntax.Item> code, FactoryUse factory) {
        Map<String, List<String>> owners = new LinkedHashMap<>();
        for (CgVertexFactory.Factory f : CgShaderTemplates.factories()) {
            if (f.parameters() != null && !f.parameters().equals(factory.parameters())) {
                owners.computeIfAbsent(f.parameters(), k -> new ArrayList<>()).add(f.name());
            }
        }
        for (Map.Entry<String, List<String>> e : owners.entrySet()) {
            for (CgShaderSyntax.Item i : code) {
                if (SpecReader.scan(file, i).names(e.getKey())) {
                    throw file.tokens().error(i.first(), "'" + e.getKey() + "' is what VertexFactory "
                            + String.join(" or ", e.getValue()) + " passes, not " + factory.name());
                }
            }
        }
    }

    private static boolean number(String s) {
        try {
            Float.parseFloat(s);
            return !s.isEmpty() && (Character.isDigit(s.charAt(0)) || s.charAt(0) == '.');
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
