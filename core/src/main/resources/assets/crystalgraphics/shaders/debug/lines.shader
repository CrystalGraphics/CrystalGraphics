// CgDebugDraw's and CgCollisionDebug's lines and see-through faces. Translucent and unlit: tested against the scene's
// depth and writing none, so neither hides what is drawn after it. Both faces, so a face is seen from inside its shape.
// lines_foreground.shader is this with its depth test off.
#domain surface
Material { BlendMode Translucent  ShadingModel Unlit  Faces Both }
#include "crystalgraphics:shaders/lib/color.glsl"

// One colour a segment or triangle, carried in cg_Normal with its alpha in cg_TexCoord0.x, sRGB as ARGB gives it
struct v2f { vec4 color; };

void vertex(inout CgVertex v, out v2f o) {
    o.color = vec4(cg_Normal, cg_TexCoord0.x);
}

void surface(in v2f i, inout CgSurface s) {
    s.emissive = srgb_to_linear(clamp(i.color.rgb, 0.0, 1.0));
    s.opacity = i.color.a;
}
