// CgLineBatchComponent's lines: each segment a quad turned to face the eye, its width in screen pixels at any distance
// (Unreal's line batcher's screen-space thickness). Translucent and unlit, depth-tested and writing no depth, so a line
// never hides another. line_batch_foreground.shader is this with its depth test off.
#domain surface
Material { BlendMode Translucent  ShadingModel Unlit  Faces Both }
#include "crystalgraphics:shaders/lib/color.glsl"

struct v2f { vec4 color; };

void vertex(inout CgVertex v, out v2f o) {
    // The segment's end at cg_Position, the vector to its other end in cg_Tangent.xyz, the signed half-width in pixels in
    // cg_TexCoord0.y: moved across the segment and the line of sight by that many pixels' worth of world at its distance
    // (a pixel is 2 d / (P[1][1] h) of world; orthographic, 2 / (P[1][1] h))
    vec3 world = (CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0)).xyz;
    vec3 along = mat3(CG_OBJECT_TO_WORLD) * cg_Tangent.xyz;
    vec3 toEye = CG_CAMERA_POSITION - world;
    vec3 across = cross(along, toEye);
    float len = length(across);
    // Seen end on it has no across: any axis, its quad a point anyway
    across = len > 1e-8 ? across / len : vec3(0.0, 1.0, 0.0);
    float distance = cg_ProjMatrix[2][3] == 0.0 ? 1.0 : length(toEye);
    // Toward the eye by 0.025% of the distance, as vanilla layers its block outline (VIEW_OFFSET_Z_LAYERING's 0.99975):
    // a line on a face would otherwise tie with that face's depth and lose half its pixels
    v.world = world + toEye * 0.00025 + across * cg_TexCoord0.y * 2.0 * distance / (cg_ProjMatrix[1][1] * CG_RESOLUTION.y);
    o.color = vec4(cg_Normal, cg_TexCoord0.x);
}

void surface(in v2f i, inout CgSurface s) {
    s.emissive = srgb_to_linear(clamp(i.color.rgb, 0.0, 1.0));
    s.opacity = i.color.a;
}
