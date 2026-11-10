// A stroke's coverage into a texture: as coverage.shader, combined by max. A stroke's segments overlap at every
// joint, so a sum beads there; the max is their union. CgCanvasMaterials.strokeCoverage().

#type none
#pragma cg_use curve

#include "crystalgraphics:shaders/lib/stroke.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

struct v2f {
    vec2 posXy;
};

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        Blend ONE ONE
        BlendEquation MAX
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        vec3 worldPos = CG_CURVE_WORLD_POS;
        o.posXy = worldPos.xy;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(worldPos, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        // The pixel's centre, the same for every cell covering it: canvas/curve.shader's SCREEN point.
        mat4 camera = cg_ProjMatrix * cg_ViewMatrix;
        vec2 ndc = gl_FragCoord.xy / cg_Resolution * 2.0 - 1.0;
        vec2 p = inverse(mat2(camera[0].xy, camera[1].xy)) * (ndc - camera[3].xy);
        float t;
        float area = curve_instance_coverage(p,
                                             CG_CURVE_P0.xy, CG_CURVE_P1.xy, CG_CURVE_P2.xy,
                                             CG_CURVE_WIDTHS, CG_CURVE_FEATHER,
                                             int(CG_CURVE_FLAGS + 0.5), CG_CURVE_GRADIENT, t);
        fragColor = vec4(1.0, 1.0, 1.0, area);
    }
}
