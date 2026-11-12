// An energy orb's core (a wave's head, its charge ball): a white-hot, hard-edged ellipsoid evaluated per pixel against
// the view ray, so it holds from any side and from inside; the opaque scene hides it by depth. Drawn on its sphere's far
// wall at the layer's radius, the core 0.8 of it; cg_VfxMesh.values.z an intensity. _Core is the core, its alpha a
// strength; _CoreRim its rim. Its light blooms. CgEnergyWave.
#domain surface
#include "crystalgraphics:shaders/lib/vfx/fx_volume.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_depth.glsl"
Material { BlendMode Additive  ShadingModel Unlit  Faces Back  DepthTest Off  VertexFactory VfxMesh  PerDrawValues On }

Properties {
    _Core     ("The white core; its alpha a strength", color) = (1, 1, 1, 1)
    _CoreRim  ("The core's rim", color) = (0.7, 0.95, 1, 1)
    _Density ("How fast it turns white", float) = 3.4
    _Noise ("Noise", sampler3D) = "cg_noise"
}

struct v2f { vec3 world; };

void surface(in v2f i, inout CgSurface s) {
    vec3 eye = CG_CAMERA_POSITION;
    vec3 ray = normalize(i.world - eye);
    vec3 centre = CG_OBJECT_TO_WORLD[3].xyz;
    // Into the hull's own space, where it is the unit sphere and the core a sphere of 0.8.
    mat3 toLocal = inverse(mat3(CG_OBJECT_TO_WORLD));
    vec3 o = toLocal * (eye - centre), d = toLocal * ray;
    float along = -dot(o, d) / dot(d, d);
    float b = length(o + d * along);
    const float core = 0.8;
    // Its share of the core's diameter, lengthened where the ray runs along the ellipsoid's long axis.
    float hullWidth = length(CG_OBJECT_TO_WORLD[0].xyz);
    float thickness = sqrt(max(1.0 - b * b / (core * core), 0.0)) / (length(d) * hullWidth);
    if (thickness <= 0.0) discard;
    float seen = smoothstep(-0.5 * hullWidth, 0.5 * hullWidth, FX_SCENE_DISTANCE(ray) - along);
    float flicker = 0.85 + 0.3 * fx_noise(vec3((o + d * along) * 2.2) + vec3(0.0, 0.0, cg_Vfx.age * 3.1));
    float white = 1.0 - exp(-thickness * _Density * flicker);
    vec3 col = mix(_CoreRim.rgb, _Core.rgb, smoothstep(0.1, 0.6, thickness));
    s.baseColor = col * white * 1.6 * _Core.a * cg_VfxMesh.values.z * seen;
    s.emissive = s.baseColor;
}
