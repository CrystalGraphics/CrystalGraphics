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
 * A solid surface's Velocity pass, generated from its Forward pass where it is first asked for, never compiled with the
 * material: Unreal's velocity shaders, which the velocity pass compiles per material it draws. The Forward vertex runs
 * twice, first under last frame's object-to-world ({@code CG_PREVIOUS_OBJECT_TO_WORLD}) and, skinned, last frame's
 * palette ({@code _cg_skinPrevious}), then as itself; the fragment writes the clip-space difference in
 * {@code lib/velocity.glsl}'s encoding, after the Forward fragment's cut where it clips (masked).
 *
 * <pre>{@code
 * CgShaderPass velocity = spec.pass(CgRenderPassVariant.VELOCITY);   // null for what blends, a sky, or no Forward pass
 * }</pre>
 */
final class VelocityPass {

    static final String NAME = CgRenderPassVariant.VELOCITY.lightModeName();
    private static final String NOW = CgShaderNames.PASSED + "velocityNow", THEN = CgShaderNames.PASSED + "velocityThen";
    private static final CgColorMask RG = new CgColorMask(true, true, false, false, -1);

    private VelocityPass() {}

    /** {@code spec}'s Velocity pass, or null where it has none: a surface that blends, a sky, or none of its own. */
    @Nullable
    static CgShaderPass of(CgShaderSpec spec) {
        if (spec.domain() != CgMaterialDomain.SURFACE || "Background".equals(spec.renderType())) return null;
        CgShaderPass forward = spec.pass(CgRenderPassVariant.FORWARD);
        if (forward == null) return null;
        CgBlendState blend = forward.state().getBlend();
        if (blend != null && blend.enabled()) return null;

        CgDepthState depth = forward.state().getDepth();
        CgRenderState state = forward.state().withBlend(CgBlendState.DISABLED)
                .withDepth(new CgDepthState(depth == null || depth.test(), false, depth == null ? GLC.GL_LEQUAL : depth.compareFunc()))
                .withColorMask(RG);
        List<CgVarying> varyings = new ArrayList<>(forward.varyings());
        varyings.add(new CgVarying("vec4", NOW, ""));
        varyings.add(new CgVarying("vec4", THEN, ""));
        String vertex = "\n        vec3 _cg_previousWorld;\n        {\n"
                + "#undef CG_OBJECT_TO_WORLD\n#define CG_OBJECT_TO_WORLD CG_PREVIOUS_OBJECT_TO_WORLD\n"
                + "#ifdef CG_SKINNED\n        vec3 _cg_previousSkin = _cg_skinPrevious();\n"
                + "#undef cg_Position\n#define cg_Position _cg_previousSkin\n#endif\n"
                + forward.vertexBody() + "\n        _cg_previousWorld = v.world;\n"
                + "#undef CG_OBJECT_TO_WORLD\n#define CG_OBJECT_TO_WORLD (CG_OBJECT_DATA.modelMatrix)\n"
                + "#ifdef CG_SKINNED\n#undef cg_Position\n#define cg_Position _cg_skinPosition\n#endif\n"
                + "        }" + forward.vertexBody()
                + "\n        o." + NOW + " = gl_Position;\n        o." + THEN + " = cg_PreviousClip(_cg_previousWorld);\n";
        // A masked surface's cut is its Forward fragment's: run for its discard, its colour dropped
        String cut = forward.clips() ? "\n        vec4 " + forward.output() + ";" + forward.fragmentBody() : "";
        String fragment = cut + "\n        velocity = vec4(cg_EncodeVelocity(i." + NOW + ".xy / i." + NOW + ".w - i." + THEN
                + ".xy / i." + THEN + ".w), 0.0, 0.0);\n    ";
        // After the environment, which the encoding's camera motion reads: code, not a directive
        List<CgShaderPass.Code> code = new ArrayList<>(forward.code());
        code.add(0, new CgShaderPass.Code("#include \"crystalgraphics:shaders/lib/velocity.glsl\"\n", CgShaderPass.FRAGMENT));
        return new CgShaderPass(CgRenderPassVariant.VELOCITY, NAME, state, varyings, forward.directives(), code, vertex,
                fragment, "velocity", false);
    }
}
