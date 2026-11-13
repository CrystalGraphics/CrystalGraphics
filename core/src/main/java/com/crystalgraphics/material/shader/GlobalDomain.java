package com.crystalgraphics.material.shader;

import com.crystalgraphics.api.material.CgMaterialDomain;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.gpu.resource.CgEngineBufferRegistry;
import com.crystalgraphics.material.shader.syntax.CgCodeScan;
import com.crystalgraphics.material.shader.syntax.CgRenderStateGrammar;
import com.crystalgraphics.material.shader.syntax.CgSettingsGrammar;
import com.crystalgraphics.material.shader.syntax.CgShaderSyntax;
import com.crystalgraphics.material.shader.syntax.CgShaderTokens;

import java.util.List;
import java.util.Map;

/**
 * {@code #domain global}: one of the engine's own passes, or a consumer's own mesh pass. Its {@code RenderState},
 * {@code vertex()} and {@code fragment()} are one Forward pass, unlit, unfogged and in its target's encoding; it names
 * the engine buffers it reads, since nothing else draws it.
 */
final class GlobalDomain {

    private static final List<String> SETTINGS = List.of("SceneColorMargin", "ColorSpace", "Queue", "Media");
    private static final String[] MESH = {"cg_Position", "cg_Normal", "cg_Color", "cg_TexCoord0", "cg_TexCoord1",
            "cg_TexCoord2", "cg_TexCoord3"};

    private GlobalDomain() {}

    static CgShaderSpec read(CgShaderSyntax file) {
        CgShaderTokens t = file.tokens();
        String path = file.text().path();
        Map<String, CgSettingsGrammar.Setting> m = SpecReader.material(file, SETTINGS);
        // Target, written as it means, by default; sRGB decoded into a linear scene, Linear encoded onto an encoded target
        String colorSpace = SpecReader.choice(file, m, "ColorSpace", "Target", "Target", "sRGB", "Linear");
        // Where a consumer's own mesh pass sorts among the world's draws, and whether the smoke in front dims it
        String queue = SpecReader.choice(file, m, "Queue", "Overlay", "Background", "Geometry", "AlphaTest", "Transparent",
                "AfterDistortion", "Overlay");
        boolean media = SpecReader.onOff(file, m, "Media", false);
        List<CgShaderSyntax.Item> code = SpecReader.code(file, file.items());
        Map<String, List<CgShaderSyntax.Function>> functions = SpecReader.functions(code);
        CgShaderSyntax.Function vertex = SpecReader.only(file, functions, "vertex");
        CgShaderSyntax.Function fragment = SpecReader.only(file, functions, "fragment");
        if (vertex == null || SpecReader.signature(file, vertex, "void", "vertex", "(", "out", "v2f", "*", ")") == null) {
            throw t.error(vertex == null ? file.domainToken() : vertex.first(),
                    "'void vertex(out v2f o)' is a #domain global's vertex, writing gl_Position");
        }
        if (fragment == null || SpecReader.signature(file, fragment, "void", "fragment", "(", "in?", "v2f", "*", ",",
                "out", "vec4", "*", ")") == null) {
            throw t.error(fragment == null ? file.domainToken() : fragment.first(),
                    "'void fragment(in v2f i, out vec4 color)' is a #domain global's fragment");
        }
        if (functions.containsKey("surface")) {
            throw t.error(functions.get("surface").get(0).first(), "'surface(' is a #domain surface material's");
        }
        boolean mesh = false;
        for (CgShaderSyntax.Item i : code) {
            CgCodeScan s = SpecReader.scan(file, i);
            for (String a : MESH) mesh |= s.names(a);
        }

        SpecBuilder spec = new SpecBuilder(file, CgMaterialDomain.GLOBAL);
        spec.vertexType = mesh ? "spatial" : "none";
        spec.materialKeywords();
        // The engine's own passes name the engine buffers they read: no factory or primitive draws them
        CgShaderSyntax.Block engine = file.block("EngineBuffers");
        for (int i = engine == null ? 0 : engine.open() + 1; engine != null && i < engine.close(); i++) {
            int first = i;
            while (i + 1 < engine.close() && t.touchesNext(i)) i++;
            String token = t.slice(first, i);
            if (CgEngineBufferRegistry.get(token) == null) {
                throw t.error(first, "'" + token + "' is no engine buffer: " + CgEngineBufferRegistry.tokens());
            }
            if (!spec.uses.contains(token)) spec.uses.add(token);
        }
        if (SpecReader.namesGlobals(file, code) && !spec.uses.contains("globals")) spec.uses.add("globals");
        SpecReader.refuseNamedBuffers(file, code, spec.uses);
        spec.renderType = "Opaque";
        spec.lighting = CgShaderSpec.Lighting.UNLIT;
        spec.fog = false;
        spec.media = media;
        spec.colorSpace = colorSpace.equals("Target") ? CgShaderSpec.ColorSpace.TARGET
                : colorSpace.equals("Linear") ? CgShaderSpec.ColorSpace.LINEAR : CgShaderSpec.ColorSpace.SRGB;
        spec.sceneColorMarginText = m.containsKey("SceneColorMargin") ? m.get("SceneColorMargin").value() : null;
        spec.queue = CgRenderQueue.fromName(queue);

        CgShaderSyntax.Block state = file.block("RenderState");
        CgRenderState rs = state == null ? CgRenderState.DEFAULT : CgRenderStateGrammar.read(t, state.open() + 1, state.close());
        SpecReader.PassCode passCode = new SpecReader.PassCode();
        passCode.material(file, code, vertex, fragment, false);
        String vertexBody = vertex.close() > vertex.open() + 1 ? t.plain(vertex.open() + 1, vertex.close() - 1) : "";
        String fragmentBody = fragment.close() > fragment.open() + 1 ? t.plain(fragment.open() + 1, fragment.close() - 1) : "";
        spec.pass(new CgShaderPass(CgRenderPassVariant.FORWARD, "Pass0", rs,
                Varyings.check(file, Varyings.fields(file, file.struct("v2f")), false), passCode.directives, passCode.code,
                vertexBody, fragmentBody, SpecReader.output(file, fragment), false));
        spec.text.append(SpecReader.text(passCode, vertexBody, fragmentBody));
        return spec.build();
    }
}
