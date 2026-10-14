// The light an energy orb casts on the scene around it (a wave's head, its charge, its impact, its blast): one point
// light at the sphere's centre, the size of the orb (CG_OBJECT_CUSTOM1.x), reaching to the sphere's edge. Drawn on
// CgVfxFrame.mesh's sphere's far wall. CG_OBJECT_CUSTOM1.z is an intensity. _Glow is the light, its alpha a strength.
// CgEnergyWave.
#type spatial
#pragma cg_use rows
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_light.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_draw.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"

Properties {
    _Glow     ("The light it casts; its alpha a strength", color) = (0.18, 0.45, 1.4, 0.9)
    _Strength ("Brightness", float) = 4.0
}

struct v2f { vec3 world; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE
        DepthTest ALWAYS
        DepthWrite OFF
        Cull FRONT
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.world = world.xyz;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec3 eye = FX_CAMERA;
        vec3 ray = normalize(i.world - eye);
        vec3 surface, normal;
        fx_scene_surface(eye, ray, FX_SCENE_DISTANCE(ray), surface, normal);
        vec3 centre = CG_OBJECT_TO_WORLD[3].xyz;
        float reach = length(CG_OBJECT_TO_WORLD[0].xyz);
        if (distance(surface, centre) > reach) discard;
        float light = fx_point_light(surface, normal, centre, max(CG_OBJECT_CUSTOM1.x, 1.0e-3), reach);
        fragColor = vec4(_Glow.rgb * _Glow.a * CG_OBJECT_CUSTOM1.z * FX_PRESENCE * light * _Strength, 1.0);
    }
}
