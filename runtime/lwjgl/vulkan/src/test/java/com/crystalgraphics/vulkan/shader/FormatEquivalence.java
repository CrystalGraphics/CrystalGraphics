package com.crystalgraphics.vulkan.shader;

import com.crystalgraphics.api.material.CgBlendMode;
import com.crystalgraphics.api.material.CgMaterialDomain;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgCullState;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.state.CgStencilState;
import com.crystalgraphics.material.CgMaterialProperty;
import com.crystalgraphics.material.parse.CgMaterialShaderCompiler;
import com.crystalgraphics.material.parse.CgParsedPass;
import com.crystalgraphics.material.parse.CgParsedShader;
import com.crystalgraphics.material.parse.CgShaderParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Whether a material-format file means what the old-format file it stands for meant: the same vertex format, queue,
 * lighting, keywords, engine buffers, properties, and pass by pass the same render state as drawn and the same
 * interpolators. What {@code MaterialFormatTest} holds each shape to, and what the migration (P2.5 F5) holds each
 * converted shader to.
 *
 * <ul>
 *   <li>A render state slot a pass leaves undeclared reads as its default.</li>
 *   <li>{@code post}: depth and cull are not compared, as {@code CgPostMaterial} draws every pass depthless, its one
 *       triangle facing the camera.</li>
 *   <li>{@code ui}: the v2f is the primitive's, not compared.</li>
 *   <li>{@code global}: queue and render type are not compared: the engine draws its own passes by name, never sorted.</li>
 *   <li>The code is not compared: a build proves it compiles, a gate that it draws the same.</li>
 * </ul>
 */
final class FormatEquivalence {

    private FormatEquivalence() {}

    /**
     * Every way {@code now} differs from {@code was}; empty when it means the same. {@code sameBlend} false: they are
     * drawn under different canvas states, {@code now} premultiplied.
     */
    static List<String> differences(CgParsedShader was, CgParsedShader now, boolean sameBlend) {
        List<String> out = new ArrayList<>();
        CgMaterialDomain domain = now.domain();
        boolean ui = domain == CgMaterialDomain.UI, post = domain == CgMaterialDomain.POST,
                global = domain == CgMaterialDomain.GLOBAL;
        if (domain == null) out.add("not in the format");
        diff(out, "vertex type", was.shaderType(), now.shaderType());
        if (!global) {
            diff(out, "queue", was.renderQueue(), now.renderQueue());
            diff(out, "render type", was.renderType(), now.renderType());
        }
        diff(out, "lit", was.lit(), now.lit());
        diff(out, "surface lit", was.surfaceLit(), now.surfaceLit());
        diff(out, "fogged", was.fogged(), now.fogged());
        diff(out, "mediated", was.mediated(), now.mediated());
        diff(out, "linear colour", was.linearColor(), now.linearColor());
        diff(out, "linear light", was.linearLight(), now.linearLight());
        diff(out, "depth mode", was.depthMode(), now.depthMode());
        diff(out, "scene colour margin", was.sceneColorMargin(), now.sceneColorMargin());
        diff(out, "keywords", new TreeSet<>(was.featureNames()), new TreeSet<>(now.featureNames()));
        diff(out, "permutations", was.permutations().dimensions(), now.permutations().dimensions());
        diff(out, "engine buffers", new TreeSet<>(was.engineBuffers()), new TreeSet<>(now.engineBuffers()));
        diff(out, "buffers", was.buffers().size(), now.buffers().size());
        diff(out, "properties", new TreeSet<>(names(was.properties())), new TreeSet<>(names(now.properties())));
        diff(out, "tube", was.tube(), now.tube());
        diff(out, "post targets", was.postTargets(), now.postTargets());
        diff(out, "pass count", was.passes().size(), now.passes().size());
        if (was.passes().size() != now.passes().size()) return out;
        for (int p = 0; p < was.passes().size(); p++) {
            CgParsedPass a = was.passes().get(p), b = now.passes().get(p);
            String pass = a.lightMode() + " ";
            diff(out, "pass " + p, a.lightMode(), b.lightMode());
            CgRenderState ra = a.renderState(), rb = b.renderState();
            if (sameBlend) {
                diff(out, pass + "blend", slot(ra.getBlend(), CgBlendState.DISABLED), slot(rb.getBlend(), CgBlendState.DISABLED));
            } else {
                diff(out, pass + "premultiplied", CgBlendMode.PREMULTIPLIED.state(), rb.getBlend());
            }
            if (!post) {
                diff(out, pass + "depth", slot(ra.getDepth(), CgDepthState.TEST_WRITE), slot(rb.getDepth(), CgDepthState.TEST_WRITE));
                diff(out, pass + "cull", slot(ra.getCull(), CgCullState.BACK), slot(rb.getCull(), CgCullState.BACK));
            }
            diff(out, pass + "stencil", slot(ra.getStencil(), CgStencilState.DISABLED), slot(rb.getStencil(), CgStencilState.DISABLED));
            diff(out, pass + "writes nothing", ra.writesNothing(), rb.writesNothing());
            if (!ui) diff(out, pass + "v2f", CgShaderParser.parseV2fFields(a), authored(b));
        }
        diff(out, "glow merge", CgMaterialShaderCompiler.emissionMerge(was), CgMaterialShaderCompiler.emissionMerge(now));
        return out;
    }

    /** The v2f a material authored: its own fields, not the factory parameters the lowering passes. */
    static List<CgShaderParser.V2fField> authored(CgParsedPass pass) {
        List<CgShaderParser.V2fField> out = new ArrayList<>();
        for (CgShaderParser.V2fField f : CgShaderParser.parseV2fFields(pass)) if (!f.name().startsWith("_cg_")) out.add(f);
        return out;
    }

    private static void diff(List<String> out, String what, Object was, Object now) {
        if (!Objects.equals(was, now)) out.add(what + ": was " + was + ", now " + now);
    }

    /** A render state's slot as drawn: one a pass leaves undeclared takes the default. */
    private static <T> T slot(T declared, T fallback) {
        return declared != null ? declared : fallback;
    }

    private static List<String> names(List<CgMaterialProperty> props) {
        List<String> out = new ArrayList<>();
        for (CgMaterialProperty p : props) out.add(p.getName() + ":" + p.getType());
        return out;
    }
}
