// A fill's coverage into a texture: each cell adds its exact area into alpha, so a tessellated shape sums to its
// coverage where compositing the cells one by one would not (two halves of a pixel blend to three quarters). The colour
// is written white: the texture is (1, 1, 1, coverage), drawn later tinted by a box. CgCanvasMaterials.coverage().
#domain global
#include "crystalgraphics:shaders/lib/stroke.glsl"
Material { ColorSpace sRGB  Queue Transparent  Media On }
EngineBuffers { curve }

RenderState {
    Blend ONE ZERO, ONE ONE
    DepthTest ALWAYS
    DepthWrite OFF
    Cull OFF
}

struct v2f {
    vec2 posXy;
};

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
