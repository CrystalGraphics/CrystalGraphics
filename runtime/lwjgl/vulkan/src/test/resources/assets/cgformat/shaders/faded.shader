// A traced sphere inside its hull, faded by its factory: the engine's eye, ray, depth fade and s.depth.
#domain surface
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
Material { BlendMode AlphaComposite  ShadingModel Unlit  Faces Back  DepthTest Strict  DepthWrite On  VertexFactory cgformat:Faded }
struct v2f { vec3 world; };
void surface(in v2f i, inout CgSurface s) {
    vec3 eye = CG_CAMERA_POSITION, ray = cg_PixelRay();
    vec3 centre = CG_OBJECT_TO_WORLD[3].xyz;
    float b = dot(eye - centre, ray), c = dot(eye - centre, eye - centre) - 1.0, h = b * b - c;
    if (h < 0.0) discard;
    float t = -b - sqrt(h);
    s.depth = cg_EyeDepth(eye + ray * t);
    float glow = fx_hash31(centre) * cg_DepthFade(0.5) * (0.5 + 0.5 * sin(cg_Vfx.age));
    s.baseColor = vec3(glow);
    s.opacity = glow;
}
