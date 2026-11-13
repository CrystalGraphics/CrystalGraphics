package com.crystalgraphics.material.shader;

import com.crystalgraphics.api.material.CgMaterialDomain;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.material.shader.syntax.CgKeywordGrammar;
import com.crystalgraphics.material.shader.syntax.CgSettingsGrammar;
import com.crystalgraphics.material.shader.syntax.CgShaderSyntax;
import com.crystalgraphics.material.shader.syntax.CgShaderTokens;

import java.util.List;
import java.util.Map;

/**
 * {@code #domain ui}: an element on a canvas. The material's {@code surface()} runs inside its {@code Primitive}'s
 * fragment at {@code CG_SURFACE(input, s)}; the primitive's file brings its buffers, keywords, declarations,
 * properties, queue, render state, v2f and vertex, and finishes the pixel (coverage, clip, opacity, premultiplying).
 */
final class UiDomain {

    private static final List<String> SETTINGS = List.of("Primitive", "ColorSpace");

    private UiDomain() {}

    static CgShaderSpec read(CgShaderSyntax file) {
        CgShaderTokens t = file.tokens();
        String path = file.text().path();
        Map<String, CgSettingsGrammar.Setting> m = SpecReader.material(file, SETTINGS);
        // sRGB, decoded into a linear scene, by default; Target where the material converts itself (glass over a backdrop)
        String colorSpace = SpecReader.choice(file, m, "ColorSpace", "sRGB", "sRGB", "Target");
        CgSettingsGrammar.Setting kindSetting = m.get("Primitive");
        String kind = kindSetting == null ? "Quad" : kindSetting.value();
        CgShaderTemplates.Primitive primitive = CgShaderTemplates.primitive(kind);
        if (primitive == null) {
            throw t.error(kindSetting == null ? file.domainToken() : kindSetting.valueToken(), "'Primitive " + kind
                    + "' has no file: " + CgShaderTemplates.location(kind, "canvas/primitives", "primitive")
                    + " (the engine's: Quad, Curve, Text)");
        }
        CgShaderSyntax.Block state = file.block("RenderState");
        if (state != null) {
            throw t.error(state.first(), "'RenderState' is the primitive's in a ui material: premultiplied, over what is"
                    + " drawn before it");
        }
        List<CgShaderSyntax.Item> code = SpecReader.code(file, file.items());
        Map<String, List<CgShaderSyntax.Function>> functions = SpecReader.functions(code);
        for (String engine : List.of("vertex", "fragment")) {
            if (functions.containsKey(engine)) {
                throw t.error(functions.get(engine).get(0).first(), "'" + engine + "(' is the primitive's in a ui material:"
                        + " write surface()");
            }
        }
        CgShaderSyntax.Struct v2f = file.struct("v2f");
        if (v2f != null) throw t.error(v2f.first(), "'struct v2f' is the primitive's in a ui material: surface() takes CgUiInput");
        CgShaderSyntax.Function surface = SpecReader.only(file, functions, "surface");
        if (surface == null || SpecReader.signature(file, surface, "void", "surface", "(", "in?", "CgUiInput", "*", ",",
                "inout", "CgUiSurface", "*", ")") == null) {
            throw t.error(surface == null ? file.domainToken() : surface.first(),
                    "'void surface(in CgUiInput i, inout CgUiSurface s)' is a ui material's");
        }
        for (CgShaderSyntax.Item i : code) {
            if (i instanceof CgShaderSyntax.Function && SpecReader.scan(file, i).names("gl_Position")) {
                throw t.error(i.first(), "'gl_Position' is the primitive's in a ui material");
            }
        }

        SpecBuilder spec = new SpecBuilder(file, CgMaterialDomain.UI);
        spec.vertexType = "none";
        if (!primitive.keywords().isBlank()) spec.keywords(CgKeywordGrammar.read(primitive.keywords(), primitive.path()), false);
        spec.materialKeywords();
        for (String b : primitive.buffers()) if (!spec.uses.contains(b)) spec.uses.add(b);
        if (SpecReader.namesGlobals(file, code) && !spec.uses.contains("globals")) spec.uses.add("globals");
        SpecReader.refuseNamedBuffers(file, code, spec.uses);
        spec.renderType = "Transparent";
        spec.colorSpace = colorSpace.equals("Target") ? CgShaderSpec.ColorSpace.TARGET : CgShaderSpec.ColorSpace.SRGB;
        spec.queue = CgRenderQueue.fromName(primitive.queue());
        spec.templateProperties = primitive.properties();
        spec.templateOwner = "Primitive " + kind;

        SpecReader.PassCode passCode = new SpecReader.PassCode();
        passCode.directive("#include \"crystalgraphics:shaders/env/material/surface.glsl\"");
        passCode.glsl(primitive.declarations(), primitive.path());
        passCode.material(file, code, null, surface, true);
        List<CgVarying> varyings = Varyings.check(primitive.file(), Varyings.fields(primitive.file(), primitive.v2f()), true);
        spec.pass(new CgShaderPass(CgRenderPassVariant.FORWARD, "Pass0", primitive.state(), varyings, passCode.directives,
                passCode.code, primitive.vertexBody(), primitive.fragmentBody(), primitive.output(), false));
        spec.text.append(SpecReader.text(passCode, primitive.vertexBody(), primitive.fragmentBody()));
        return spec.build();
    }
}
