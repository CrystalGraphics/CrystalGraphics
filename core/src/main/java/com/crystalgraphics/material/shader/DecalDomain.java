package com.crystalgraphics.material.shader;

import com.crystalgraphics.api.material.CgMaterialDomain;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.api.state.CgCullState;
import com.crystalgraphics.material.shader.syntax.CgRenderStateGrammar;
import com.crystalgraphics.material.shader.syntax.CgSettingsGrammar;
import com.crystalgraphics.material.shader.syntax.CgShaderNames;
import com.crystalgraphics.material.shader.syntax.CgShaderSyntax;
import com.crystalgraphics.material.shader.syntax.CgShaderTokens;
import com.crystalgraphics.platform.gl.driver.GLC;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code #domain decal}: a material projected onto what is behind a box, Unreal's deferred decal material. A decal
 * component draws its box ({@code CgDecalComponent}); each pixel of it reads the scene's depth, finds the point there in
 * the box's space and, inside the box, runs {@code surface()} with the decal's uv (Unreal's, along the box's local X:
 * {@code DecalCommon.ush}), the point and the decal's facing. The surface is lit forward where the mode carries colour
 * ({@code ShadingModel DefaultLit}, the default) and blended into the scene by its {@code DecalBlendMode}, as Unreal's
 * forward renderer draws decals over what it cannot shade again. Its opacity is faded by its component's lifetime and
 * screen size ({@code CG_OBJECT_SPARE.xy}).
 *
 * <pre>{@code
 * #domain decal
 * Material { DecalBlendMode DBufferColorNormalRoughness }
 * Properties { _Crack ("Crack", sampler2D) = "white" }
 * struct v2f { vec2 uv0; };
 * void surface(in v2f i, inout CgSurface s) {
 *     vec4 c = texture(_Crack, i.uv0);
 *     s.baseColor = c.rgb;
 *     s.opacity = c.a;
 *     s.roughness = 0.8;
 * }
 * }</pre>
 */
final class DecalDomain {

    private static final List<String> SETTINGS = List.of("DecalBlendMode", "ShadingModel", "Fog");

    /** The modes carrying colour; Unreal's colourless DBuffer modes and Normal re-shade a surface, which needs a G-buffer. */
    private static final List<String> MODES = List.of("Translucent", "Stain", "Emissive", "AlphaComposite", "DBufferColor",
            "DBufferColorNormal", "DBufferColorRoughness", "DBufferColorNormalRoughness");
    private static final List<String> REFUSED = List.of("Normal", "DBufferNormal", "DBufferRoughness",
            "DBufferNormalRoughness", "AmbientOcclusion", "Volumetric");

    /** What a decal's v2f may name, filled from its projection: the decal's uv, the point, the decal's facing. */
    private static final Map<String, String[]> FIELDS = Map.of(
            "uv0", new String[]{"vec2", "_cg_decalUv"},
            "world", new String[]{"vec3", "_cg_decalWorld"},
            "normal", new String[]{"vec3", "_cg_decalFacing"});

    /** Rows of the box's world-to-local, passed flat from the vertex: an inverse once a vertex, not once a pixel. */
    private static final String[] TO_DECAL = {CgShaderNames.PASSED + "toDecal0", CgShaderNames.PASSED + "toDecal1",
            CgShaderNames.PASSED + "toDecal2", CgShaderNames.PASSED + "toDecal3"};

    /** Decals draw in their own pass after the opaque scene, sorted by their components' sort order, then material. */
    static final int QUEUE = CgRenderQueue.GEOMETRY + 100;

    private DecalDomain() {}

    static CgShaderSpec read(CgShaderSyntax file) {
        CgShaderTokens t = file.tokens();
        Map<String, CgSettingsGrammar.Setting> m = SpecReader.material(file, SETTINGS);
        CgShaderSyntax.Block state = file.block("RenderState");
        if (state != null) throw t.error(state.first(), "'RenderState' is the decal's: its DecalBlendMode says how it blends");
        List<CgShaderSyntax.Item> code = SpecReader.code(file, file.items());
        Map<String, List<CgShaderSyntax.Function>> functions = SpecReader.functions(code);
        for (String engine : List.of("vertex", "fragment")) {
            if (functions.containsKey(engine)) {
                throw t.error(functions.get(engine).get(0).first(), "'" + engine + "(' is the decal's: a decal material"
                        + " writes surface(), its v2f filled from the projection");
            }
        }
        CgShaderSyntax.Function surface = SpecReader.only(file, functions, "surface");
        if (surface == null) {
            throw t.error(file.domainToken(), "'void surface(in v2f i, inout CgSurface s)' is missing: a decal material's outputs");
        }
        List<String> sig = SpecReader.signature(file, surface, "void", "surface", "(", "in?", "v2f", "*", ",", "inout",
                "CgSurface", "*", ")");
        if (sig == null) throw t.error(surface.first(), "'surface(' is 'void surface(in v2f i, inout CgSurface s)'");

        CgSettingsGrammar.Setting modeSetting = m.get("DecalBlendMode");
        String mode = modeSetting == null ? "Translucent" : modeSetting.value();
        if (REFUSED.contains(mode)) {
            throw t.error(modeSetting.valueToken(), "'DecalBlendMode " + mode + "' re-shades the surface under it, which a"
                    + " forward renderer cannot: it needs the deferred lights' G-buffer");
        }
        if (!MODES.contains(mode)) throw t.error(modeSetting.valueToken(), "'DecalBlendMode " + mode + "' is none of " + MODES);
        boolean unlit = SpecReader.choice(file, m, "ShadingModel", "DefaultLit", "DefaultLit", "Unlit").equals("Unlit");
        boolean fog = SpecReader.onOff(file, m, "Fog", true);

        // What it names, against what its mode writes (Unreal's decal blend modes' active outputs)
        Set<String> named = new LinkedHashSet<>(SpecReader.body(file, surface).members(sig.get(1)));
        Set<String> writes = writes(mode, unlit);
        for (String o : named) {
            if (SurfaceDomain.isOutput(o) && !writes.contains(o)) {
                throw t.error(surface.first(), "'s." + o + "' is not written by DecalBlendMode " + mode
                        + (unlit ? ", ShadingModel Unlit" : "") + ": it writes " + writes);
            }
        }

        List<Varyings.Field> fields = Varyings.fields(file, file.struct("v2f"));
        StringBuilder fill = new StringBuilder();
        for (Varyings.Field f : fields) {
            String[] from = FIELDS.get(f.name());
            if (from == null) throw t.error(f.token(), "v2f field '" + f.name() + "' is none a decal fills: " + FIELDS.keySet());
            if (!from[0].equals(f.type())) throw t.error(f.token(), "v2f field '" + f.name() + "' is a " + from[0]);
            fill.append("            i.").append(f.name()).append(" = ").append(from[1]).append(";\n");
        }
        // Its v2f is filled in the fragment, from the projection: what crosses the stages is the box's inverse alone
        List<CgVarying> varyings = new ArrayList<>(Varyings.check(file, fields, false));
        for (String row : TO_DECAL) varyings.add(new CgVarying("vec4", row, "flat"));

        SpecBuilder spec = new SpecBuilder(file, CgMaterialDomain.DECAL);
        spec.vertexType = "spatial";
        spec.materialKeywords();
        if (SpecReader.namesGlobals(file, code) && !spec.uses.contains("globals")) spec.uses.add("globals");
        SpecReader.refuseNamedBuffers(file, code, spec.uses);
        spec.renderType = "Transparent";
        spec.lighting = CgShaderSpec.Lighting.UNLIT;
        spec.fog = fog;
        spec.media = fog;
        spec.colorSpace = CgShaderSpec.ColorSpace.LINEAR;
        spec.queue = QUEUE;

        // Its box's back faces, untested: every pixel the box covers, the camera inside it or not
        CgRenderStateGrammar.State drawn = CgRenderStateGrammar.state();
        switch (mode) {
            case "Emissive" -> drawn.blend(GLC.GL_ONE, GLC.GL_ONE);
            case "Stain" -> drawn.blend(GLC.GL_DST_COLOR, GLC.GL_ZERO);
            case "AlphaComposite" -> drawn.blend(GLC.GL_ONE, GLC.GL_ONE_MINUS_SRC_ALPHA);
            default -> drawn.blend(GLC.GL_SRC_ALPHA, GLC.GL_ONE_MINUS_SRC_ALPHA);
        }
        drawn.depthTest(GLC.GL_ALWAYS).depthWrite(false).cull(CgCullState.FRONT);

        SpecReader.PassCode passCode = new SpecReader.PassCode();
        passCode.directive("#include \"crystalgraphics:shaders/env/material/surface.glsl\"");
        passCode.code("#include \"crystalgraphics:shaders/lib/material/decal.glsl\"\n", CgShaderPass.FRAGMENT);
        passCode.code(SurfaceDomain.SURFACE_DEFAULTS, CgShaderPass.FRAGMENT);
        if (!unlit) passCode.code(SurfaceDomain.DEFAULT_LIT_INCLUDE, CgShaderPass.FRAGMENT);
        passCode.material(file, code, null, surface, true);

        StringBuilder vertex = new StringBuilder("\n        gl_Position = cg_ProjMatrix * cg_ViewMatrix * CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);\n")
                .append("        mat4 _cg_toDecal = inverse(CG_OBJECT_TO_WORLD);\n");
        for (int r = 0; r < 4; r++) vertex.append("        o.").append(TO_DECAL[r]).append(" = _cg_toDecal[").append(r).append("];\n");

        String toDecal = "mat4(i." + TO_DECAL[0] + ", i." + TO_DECAL[1] + ", i." + TO_DECAL[2] + ", i." + TO_DECAL[3] + ")";
        StringBuilder fragment = new StringBuilder("\n")
                .append("        vec2 _cg_decalUv; vec3 _cg_decalWorld, _cg_decalFacing;\n")
                .append("        float _cg_fade = cg_DecalProject(").append(toDecal).append(", _cg_decalUv, _cg_decalWorld, _cg_decalFacing);\n")
                .append("        {\n            v2f i;\n").append(fill)
                .append("            CgSurface s = cg_SurfaceDefaults();\n")
                .append("            s.normal = _cg_decalFacing;\n")
                .append("            ").append(SpecReader.MATERIAL_SURFACE).append("(i, s);\n")
                .append("            float a = s.opacity * _cg_fade;\n");
        String colour = unlit ? "vec3(0.0)" : "cg_default_lit(s, _cg_decalWorld, false)";
        switch (mode) {
            case "Emissive" -> fragment.append("            fragColor = vec4(s.emissive * a, 0.0);\n");
            case "Stain" -> fragment.append("            fragColor = vec4(mix(vec3(1.0), s.baseColor, a), 1.0);\n");
            case "AlphaComposite" -> fragment.append("            fragColor = vec4((").append(colour).append(" + s.emissive) * a, a);\n");
            default -> fragment.append("            fragColor = vec4(").append(colour)
                    .append(writes.contains("emissive") ? " + s.emissive" : "").append(", a);\n");
        }
        fragment.append("        }\n    ");

        CgShaderPass forward = new CgShaderPass(CgRenderPassVariant.FORWARD, "Pass0", drawn.build(), varyings,
                passCode.directives, passCode.code, vertex.toString(), fragment.toString(), "fragColor", false);
        spec.pass(forward);
        spec.text.append(SpecReader.text(passCode, forward.vertexBody(), forward.fragmentBody()));
        return spec.build();
    }

    /** The outputs a mode writes: Unreal's decal blend modes' active material inputs. */
    private static Set<String> writes(String mode, boolean unlit) {
        Set<String> w = new LinkedHashSet<>(List.of("opacity"));
        switch (mode) {
            case "Emissive" -> w.add("emissive");
            case "Stain" -> w.add("baseColor");
            case "Translucent", "AlphaComposite" -> w.addAll(List.of("baseColor", "metallic", "specular", "roughness",
                    "normal", "emissive"));
            default -> {
                w.add("baseColor");
                if (mode.contains("Normal")) w.add("normal");
                if (mode.contains("Roughness")) w.addAll(List.of("metallic", "specular", "roughness"));
            }
        }
        if (unlit && !mode.equals("Stain")) {
            w.removeAll(List.of("baseColor", "metallic", "specular", "roughness", "normal"));
            w.add("emissive");
        }
        return w;
    }
}
