package com.crystalgraphics.material.shader;

import com.crystalgraphics.api.material.CgMaterialDomain;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgColorMask;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.platform.gl.driver.GLC;

import javax.annotation.Nullable;

/**
 * A blended surface's Responsive pass, generated from its Forward pass where it is first asked for: 1 into the responsive
 * target wherever the draw covers (Unreal's Responsive AA, its stencil bit). Temporal AA takes a quarter of the new frame
 * there, so what blends over the scene and writes no velocity, a particle above all, leaves no trail. The vertex is its
 * Forward vertex; the fragment runs its Forward fragment and keeps the texels it covers: its opacity, or for an additive
 * or modulating blend how far its colour moves what is behind.
 *
 * <pre>{@code
 * CgShaderPass responsive = spec.pass(CgRenderPassVariant.RESPONSIVE);   // null for a solid surface, or no Forward pass
 * }</pre>
 */
final class ResponsivePass {

    static final String NAME = CgRenderPassVariant.RESPONSIVE.lightModeName();
    private static final CgColorMask R = new CgColorMask(true, false, false, false, -1);
    /** Less than this the draw leaves what is behind it as it was: a soft edge's fade, not the particle. */
    private static final String COVERS = "0.02";

    private ResponsivePass() {}

    /** {@code spec}'s Responsive pass, or null where it has none: a surface that does not blend, or not a surface. */
    @Nullable
    static CgShaderPass of(CgShaderSpec spec) {
        if (spec.domain() != CgMaterialDomain.SURFACE) return null;
        CgShaderPass forward = spec.pass(CgRenderPassVariant.FORWARD);
        if (forward == null) return null;
        CgBlendState blend = forward.state().getBlend();
        if (blend == null || !blend.enabled()) return null;

        CgDepthState depth = forward.state().getDepth();
        CgRenderState state = forward.state().withBlend(CgBlendState.DISABLED)
                .withDepth(new CgDepthState(depth == null || depth.test(), false, depth == null ? GLC.GL_LEQUAL : depth.compareFunc()))
                .withColorMask(R);
        String c = forward.output();
        // The Forward fragment writes straight colour and opacity, whatever the blend
        String covers = blend.dstRgb() == GLC.GL_ONE ? "max(max(" + c + ".r, " + c + ".g), " + c + ".b) * " + c + ".a"
                : blend.srcRgb() == GLC.GL_DST_COLOR ? "max(max(abs(1.0 - " + c + ".r), abs(1.0 - " + c + ".g)), abs(1.0 - "
                        + c + ".b)) * " + c + ".a"
                : c + ".a";
        String fragment = "\n        vec4 " + c + ";" + forward.fragmentBody() + "\n        if (" + covers + " < " + COVERS
                + ") discard;\n        responsive = vec4(1.0);\n    ";
        return new CgShaderPass(CgRenderPassVariant.RESPONSIVE, NAME, state, forward.varyings(), forward.directives(),
                forward.code(), forward.vertexBody(), fragment, "responsive", false);
    }
}
