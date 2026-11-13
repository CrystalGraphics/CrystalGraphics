// The showcase's sky (vfx_sky.glsl) on a sphere round the camera at the far plane, so everything draws in front of
// it. CgVfxShowcase.
#domain surface
#include "crystalgraphics:shaders/demo/vfx_sky.glsl"
Material { ShadingModel Unlit  Faces Back  DepthWrite Off  Fog Off  Sky On }

Properties {
    _ValueNoise ("Value noise", sampler3D) = "cg_value_noise"
    _StarLayers ("Star layers, up to 3", float) = 3.0
    _Moons ("Moons, up to 2", float) = 2.0
}

struct v2f { vec3 dir; };

void vertex(inout CgVertex v, out v2f o) {
    vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
    o.dir = world.xyz - CG_CAMERA_POSITION;
    v.world = world.xyz;
}

void surface(in v2f i, inout CgSurface s) {
    vec3 d = normalize(i.dir);
    // A pixel's angular size: the larger of its two screen steps. The length of fwidth overstates it up to 2.5x.
    vec4 color = vec4(vfx_sky(_ValueNoise, d, CG_TIME, max(length(dFdx(d)), length(dFdy(d))),
            int(_StarLayers), int(_Moons)), 1.0);
    s.baseColor = color.rgb;
}
