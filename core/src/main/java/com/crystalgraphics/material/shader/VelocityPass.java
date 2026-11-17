package com.crystalgraphics.material.shader;

import com.crystalgraphics.api.material.CgMaterialDomain;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgColorMask;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.material.shader.syntax.CgShaderNames;
import com.crystalgraphics.platform.gl.driver.GLC;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * A surface's Velocity pass, generated from its Forward pass where it is first asked for, never compiled with the
 * material: Unreal's velocity shaders, which the velocity pass compiles per material it draws. The vertex is its Forward
 * vertex, then last frame's world position from the material's vertex stage compiled as of last frame
 * ({@link PreviousFrame}); the fragment writes the clip-space difference in {@code lib/velocity.glsl}'s encoding, after
 * the Forward fragment's cut where it clips (masked). A blended surface has one with {@code OutputVelocity On} alone
 * (Unreal's translucent velocity pass): it writes depth too, where what it covers reaches its {@code MaskClip}.
 *
 * <pre>{@code
 * CgShaderPass velocity = spec.pass(CgRenderPassVariant.VELOCITY);   // null for a blend without OutputVelocity, a sky
 * }</pre>
 */
final class VelocityPass {

    static final String NAME = CgRenderPassVariant.VELOCITY.lightModeName();
    private static final String NOW = CgShaderNames.PASSED + "velocityNow", THEN = CgShaderNames.PASSED + "velocityThen";
    private static final CgColorMask RG = new CgColorMask(true, true, false, false, -1);

    private VelocityPass() {}

    /** {@code spec}'s Velocity pass, or null where it has none: a blend without OutputVelocity, a sky, or no Forward pass. */
    @Nullable
    static CgShaderPass of(CgShaderSpec spec) {
        if (spec.domain() != CgMaterialDomain.SURFACE || spec.previousVertex() == null) return null;
        CgShaderPass forward = spec.pass(CgRenderPassVariant.FORWARD);
        if (forward == null) return null;
        CgBlendState blend = forward.state().getBlend();
        boolean blends = blend != null && blend.enabled();
        if (blends && !spec.outputVelocity()) return null;

        CgDepthState depth = forward.state().getDepth();
        CgRenderState state = forward.state().withBlend(CgBlendState.DISABLED)
                .withDepth(new CgDepthState(depth == null || depth.test(), blends, depth == null ? GLC.GL_LEQUAL : depth.compareFunc()))
                .withColorMask(RG);
        List<CgVarying> varyings = new ArrayList<>(forward.varyings());
        varyings.add(new CgVarying("vec4", NOW, ""));
        varyings.add(new CgVarying("vec4", THEN, ""));
        String vertex = forward.vertexBody() + "\n        o." + NOW + " = gl_Position;\n        o." + THEN
                + " = cg_PreviousClip(" + PreviousFrame.WORLD + "());\n";
        // A masked surface's cut is its Forward fragment's: run for its discard, its colour dropped
        // A blend's, where what it covers is under its MaskClip (null for 0.5, as a Masked surface's)
        String c = forward.output();
        String cut = blends ? "\n        vec4 " + c + ";" + forward.fragmentBody() + "\n        if (" + ResponsivePass.covers(blend, c)
                + " < " + (spec.maskClip() != null ? spec.maskClip() : "0.5") + ") discard;"
                : forward.clips() ? "\n        vec4 " + c + ";" + forward.fragmentBody() : "";
        String fragment = cut + "\n        velocity = vec4(cg_EncodeVelocity(i." + NOW + ".xy / i." + NOW + ".w - i." + THEN
                + ".xy / i." + THEN + ".w), 0.0, 0.0);\n    ";
        // After the environment, which the encoding's camera motion reads: code, not a directive
        List<CgShaderPass.Code> code = new ArrayList<>(forward.code());
        code.add(0, new CgShaderPass.Code("#include \"crystalgraphics:shaders/lib/velocity.glsl\"\n", CgShaderPass.FRAGMENT));
        code.add(new CgShaderPass.Code(spec.previousVertex(), CgShaderPass.VERTEX));
        return new CgShaderPass(CgRenderPassVariant.VELOCITY, NAME, state, varyings, forward.directives(), code, vertex,
                fragment, "velocity", false);
    }
}
