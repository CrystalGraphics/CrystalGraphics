package com.crystalgraphics.material.shader;

import com.crystalgraphics.api.material.CgMaterialDomain;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.api.state.CgCullState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.material.shader.syntax.CgCodeScan;
import com.crystalgraphics.material.shader.syntax.CgRenderStateGrammar;
import com.crystalgraphics.material.shader.syntax.CgSettingsGrammar;
import com.crystalgraphics.material.shader.syntax.CgShaderSyntax;
import com.crystalgraphics.material.shader.syntax.CgShaderTokens;
import com.crystalgraphics.platform.gl.driver.GLC;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.post.CgPostPoint;
import com.github.bsideup.jabel.Desugar;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code #domain post}: over the whole screen, at a point in the frame. A Forward pass per {@code Step} (one for a lone
 * {@code surface()}), each but the last drawing into a texture of its own, round the screen-covering vertex and the
 * {@code CgScreen}/{@code CgPostSurface} around {@code surface()}, in linear light unless {@code ColorSpace Target}.
 */
final class PostDomain {

    private static final List<String> SETTINGS = List.of("Location", "SceneColorMargin", "ColorSpace");
    /** The sampler a post step's target is bound to: {@code _Step_bright}. */
    private static final String STEP_PREFIX = "_Step_";
    private static final List<String> POINTS = new ArrayList<>();

    static {
        // Where a post material may draw (CgPostMaterial): BEFORE_COMPOSITE feeds the composite
        for (CgPostPoint p : CgPostPoint.values()) if (p != CgPostPoint.BEFORE_COMPOSITE) POINTS.add(p.name());
    }

    private PostDomain() {}

    /** A step: its name (null for a lone {@code surface()}), its settings and its own code. */
    @Desugar
    private record Step(@Nullable String name, Map<String, CgSettingsGrammar.Setting> settings,
                        List<CgShaderSyntax.Item> code, int token) {
    }

    static CgShaderSpec read(CgShaderSyntax file) {
        CgShaderTokens t = file.tokens();
        Map<String, CgSettingsGrammar.Setting> m = SpecReader.material(file, SETTINGS);
        // Linear light by default; Target works on the stored values, as an effect on the picture's encoding does
        boolean linear = SpecReader.choice(file, m, "ColorSpace", "Linear", "Linear", "Target").equals("Linear");
        CgSettingsGrammar.Setting location = m.get("Location");
        if (location != null && !POINTS.contains(location.value())) {
            throw t.error(location.valueToken(), "'Location " + location.value() + "' is one of " + POINTS
                    + (location.value().equals(CgPostPoint.BEFORE_COMPOSITE.name()) ? ": BEFORE_COMPOSITE feeds the"
                    + " composite, and takes the engine's own passes" : ""));
        }
        CgShaderSyntax.Block state = file.block("RenderState");
        if (state != null) throw t.error(state.first(), "'RenderState' is the engine's in a post material, which draws the whole screen");
        List<CgShaderSyntax.Item> code = SpecReader.code(file, file.items());
        Map<String, List<CgShaderSyntax.Function>> functions = SpecReader.functions(code);
        for (String engine : List.of("vertex", "fragment")) {
            if (functions.containsKey(engine)) {
                throw t.error(functions.get(engine).get(0).first(), "'" + engine + "(' is the engine's in a post material: write surface()");
            }
        }
        for (CgShaderSyntax.Item i : code) {
            if (readsStep(file, i) != null) throw t.error(i.first(), "'CG_STEP' reads an earlier step: in a Step's own code");
        }
        List<CgShaderSyntax.Step> written = new ArrayList<>();
        for (CgShaderSyntax.Item i : file.items()) if (i instanceof CgShaderSyntax.Step s) written.add(s);
        CgShaderSyntax.Function single = SpecReader.only(file, functions, "surface");
        if (written.isEmpty() == (single == null)) {
            throw t.error(written.isEmpty() ? file.domainToken() : written.get(0).first(), written.isEmpty()
                    ? "'void surface(in CgScreen i, inout CgPostSurface s)' is missing"
                    : "a post material with Steps writes surface() in each Step, not beside them");
        }

        // Each step's settings and code; one surface() is a step of its own
        List<Step> steps = new ArrayList<>();
        if (single != null) steps.add(new Step(null, Map.of(), List.of(), single.first()));
        List<String> names = new ArrayList<>();
        for (CgShaderSyntax.Step step : written) {
            Map<String, CgSettingsGrammar.Setting> s = CgSettingsGrammar.read(t, step.open() + 1, step.settingsEnd(),
                    List.of("Target", "Scale"), "Step " + step.name());
            if (names.contains(step.name())) throw t.error(step.first(), "'Step " + step.name() + "' twice");
            boolean last = step == written.get(written.size() - 1);
            if (last && !s.isEmpty()) {
                throw t.error(step.first(), "'Step " + step.name() + "' is the last, drawn onto the scene: no Target or Scale");
            }
            if (!last && !s.containsKey("Target")) {
                throw t.error(step.first(), "'Step " + step.name() + "' needs a Target (a CgFormat, as RGBA16_FLOAT): only"
                        + " the last draws onto the scene");
            }
            List<CgShaderSyntax.Item> own = SpecReader.code(file, step.items());
            for (CgShaderSyntax.Item i : own) {
                String read = readsStep(file, i);
                if (read != null && !names.contains(read)) {
                    throw t.error(i.first(), "'Step " + step.name() + "' reads '" + read + "', which is no earlier step");
                }
            }
            names.add(step.name());
            steps.add(new Step(step.name(), s, own, step.first()));
        }

        SpecBuilder spec = new SpecBuilder(file, CgMaterialDomain.POST);
        spec.vertexType = "none";
        spec.materialKeywords();
        List<CgShaderSyntax.Item> all = new ArrayList<>(code);
        for (Step s : steps) all.addAll(s.code());
        if (SpecReader.namesGlobals(file, all)) spec.uses.add("globals");
        SpecReader.refuseNamedBuffers(file, all, spec.uses);
        spec.renderType = "Transparent";
        spec.lighting = CgShaderSpec.Lighting.UNLIT;
        spec.fog = false;
        spec.colorSpace = CgShaderSpec.ColorSpace.TARGET;
        // One triangle over the target, each pixel reading its own unless it says how far past it reads
        spec.sceneColorMarginText = m.containsKey("SceneColorMargin") ? m.get("SceneColorMargin").value() : "0.001";
        spec.queue = CgRenderQueue.OVERLAY;
        spec.postLocation = location == null ? null : location.value();
        StringBuilder targets = new StringBuilder();
        for (int s = 0; s < steps.size() - 1; s++) {
            String n = steps.get(s).name();
            targets.append(STEP_PREFIX).append(n).append(" (\"Step ").append(n).append("\", sampler2D) = \"black\"\n");
        }
        spec.templateProperties = targets.toString();
        spec.templateOwner = "the post material's steps";

        CgRenderState rs = CgRenderStateGrammar.state().blendOff().depthTest(GLC.GL_ALWAYS).depthWrite(false)
                .cull(CgCullState.NONE).build();
        String decode = linear ? "cg_PostDecode" : "";
        List<CgVarying> uv = List.of(new CgVarying("vec2", "uv", ""));
        for (Step step : steps) {
            List<CgShaderSyntax.Item> passItems = new ArrayList<>(code);
            passItems.addAll(step.code());
            Map<String, List<CgShaderSyntax.Function>> passFunctions = SpecReader.functions(passItems);
            CgShaderSyntax.Function surface = SpecReader.only(file, passFunctions, "surface");
            if (surface == null) throw t.error(step.token(), "'Step " + step.name() + "' writes no surface()");
            List<String> sig = SpecReader.signature(file, surface, "void", "surface", "(", "in?", "CgScreen", "*", ",",
                    "inout", "CgPostSurface", "*", ")");
            if (sig == null) throw t.error(surface.first(), "'surface(' is 'void surface(in CgScreen i, inout CgPostSurface s)'");
            boolean readsColor = false, namesOpacity = false;
            for (String f : SpecReader.reach(file, passFunctions, surface)) {
                for (CgShaderSyntax.Function fn : passFunctions.get(f)) {
                    List<String> params = fn == surface ? List.of(sig.get(1)) : SpecReader.parameters(file, fn, "CgPostSurface");
                    CgCodeScan body = SpecReader.body(file, fn);
                    for (String q : params) {
                        readsColor |= body.reads(q, "color");
                        namesOpacity |= body.namesMember(q, "opacity");
                    }
                }
            }
            String name = step.name() != null ? step.name() : "Pass0";
            if (step.settings().containsKey("Target")) {
                spec.postTargets.add(new CgShaderSpec.PostTarget(name, STEP_PREFIX + step.name(),
                        scale(file, step.settings().get("Scale"), name), format(file, step.settings().get("Target"), name)));
            }
            SpecReader.PassCode passCode = new SpecReader.PassCode();
            passCode.directive("#include \"crystalgraphics:shaders/env/material/post.glsl\"");
            passCode.directive("#include \"crystalgraphics:shaders/env/material/surface.glsl\"");
            SpecReader.PassCode body = new SpecReader.PassCode();
            body.material(file, passItems, null, surface, true);
            for (String d : body.directives) passCode.directive(steps(d));
            for (CgShaderPass.Code c : body.code) passCode.code(steps(c.text()), c.stages());
            String vertexBody = "\n        o.uv = post_fullscreen_uv(CG_VERTEX_ID);\n"
                    + "        gl_Position = vec4(o.uv * 2.0 - 1.0, 0.0, 1.0);\n    ";
            // Linear light in and out: the step writing the scene encodes it where the scene is stored sRGB; a step
            // into its own target keeps linear
            String result = namesOpacity ? "mix(" + decode + "(CG_SCENE_COLOR(i.uv).rgb), s.color, s.opacity)" : "s.color";
            if (linear && !step.settings().containsKey("Target")) result = "cg_PostEncode(" + result + ")";
            String fragmentBody = "\n        CgScreen _cg_screen;\n        _cg_screen.uv = i.uv;\n"
                    + "        _cg_screen.pixel = gl_FragCoord.xy;\n        CgPostSurface s;\n"
                    + "        s.color = " + (readsColor ? decode + "(CG_SCENE_COLOR(i.uv).rgb)" : "vec3(0.0)") + ";\n"
                    + "        s.opacity = 1.0;\n"
                    + "        " + SpecReader.MATERIAL_SURFACE + "(_cg_screen, s);\n"
                    + "        fragColor = vec4(" + result + ", 1.0);\n    ";
            spec.pass(new CgShaderPass(CgRenderPassVariant.FORWARD, name, rs, uv, passCode.directives, passCode.code,
                    vertexBody, fragmentBody, "fragColor", false));
            spec.text.append(SpecReader.text(passCode, vertexBody, fragmentBody));
        }
        return spec.build();
    }

    /** The earlier step an item reads with {@code CG_STEP(name, uv)} or {@code CG_STEP_SIZE(name)}, or null. */
    @Nullable
    private static String readsStep(CgShaderSyntax file, CgShaderSyntax.Item i) {
        CgShaderTokens t = file.tokens();
        for (int k = i.first(); k <= i.last(); k++) {
            if ((t.isIdent(k, "CG_STEP") || t.isIdent(k, "CG_STEP_SIZE")) && t.is(k + 1, '(') && t.isIdent(k + 2)
                    && (t.is(k + 3, ',') || t.is(k + 3, ')'))) {
                return t.text(k + 2);
            }
        }
        return null;
    }

    /** {@code CG_STEP(name, uv)} as {@code texture(_Step_name, uv)}, {@code CG_STEP_SIZE(name)} as its size. */
    private static String steps(String glsl) {
        if (!glsl.contains("CG_STEP")) return glsl;
        StringBuilder out = new StringBuilder(glsl.length() + 32);
        int i = 0, n = glsl.length();
        while (i < n) {
            int at = glsl.indexOf("CG_STEP", i);
            if (at < 0 || at > 0 && (Character.isLetterOrDigit(glsl.charAt(at - 1)) || glsl.charAt(at - 1) == '_')) {
                int stop = at < 0 ? n : at + 7;
                out.append(glsl, i, stop);
                i = stop;
                continue;
            }
            boolean size = glsl.startsWith("CG_STEP_SIZE", at);
            int j = at + (size ? 12 : 7);
            int k = skip(glsl, j);
            if (k >= n || glsl.charAt(k) != '(') {
                out.append(glsl, i, j);
                i = j;
                continue;
            }
            int nameStart = skip(glsl, k + 1), nameEnd = nameStart;
            while (nameEnd < n && (Character.isLetterOrDigit(glsl.charAt(nameEnd)) || glsl.charAt(nameEnd) == '_')) nameEnd++;
            int after = skip(glsl, nameEnd);
            char want = size ? ')' : ',';
            if (nameEnd == nameStart || after >= n || glsl.charAt(after) != want) {
                out.append(glsl, i, j);
                i = j;
                continue;
            }
            String step = glsl.substring(nameStart, nameEnd);
            out.append(glsl, i, at).append(size ? "textureSize(" + STEP_PREFIX + step + ", 0)" : "texture(" + STEP_PREFIX + step + ",");
            i = after + 1;
        }
        return out.toString();
    }

    private static int skip(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    /** A step's {@code Target}: a colour {@code CgFormat}'s name. */
    private static CgFormat format(CgShaderSyntax file, CgSettingsGrammar.Setting target, String step) {
        try {
            CgFormat f = CgFormat.valueOf(target.value());
            if (!f.hasDepth() && !f.hasStencil() && !f.compressed()) return f;
        } catch (IllegalArgumentException ignored) {
            // named below
        }
        throw file.tokens().error(target.valueToken(), "'Step " + step + "' has 'Target " + target.value()
                + "': a colour CgFormat's name, as RGBA16_FLOAT");
    }

    /** A step's {@code Scale}: its share of the screen on each side, in (0, 1]; 1 when absent. */
    private static float scale(CgShaderSyntax file, @Nullable CgSettingsGrammar.Setting scale, String step) {
        if (scale == null) return 1f;
        float s;
        try {
            s = Float.parseFloat(scale.value());
        } catch (NumberFormatException e) {
            s = Float.NaN;
        }
        if (!(s > 0f && s <= 1f)) {
            throw file.tokens().error(scale.valueToken(), "'Step " + step + "' has 'Scale " + scale.value()
                    + "': a share of the screen, above 0 and at most 1");
        }
        return s;
    }
}
