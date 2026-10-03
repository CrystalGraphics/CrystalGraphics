// An energy orb's glow (a wave's head, its charge ball, the release flash): a Gaussian of light, integrated along each
// view ray and counting only what lies in front of the opaque scene. Drawn on CgVfxFrame.mesh's sphere; its width is
// CG_OBJECT_CUSTOM1.x, which the hull must be over three times, and CG_OBJECT_CUSTOM1.z an intensity. Colour A is the
// glow, its alpha times the layer's parameter the strength. CgEnergyWave.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_volume.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_depth.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"

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
        float sigma = max(CG_OBJECT_CUSTOM1.x * 0.95, 1.0e-4);
        float glow = fx_point_glow(eye, ray, CG_OBJECT_TO_WORLD[3].xyz, sigma, FX_SCENE_DISTANCE(ray));
        fragColor = vec4(CG_OBJECT_CUSTOM2.rgb * CG_OBJECT_CUSTOM2.a * CG_OBJECT_CUSTOM0.y * CG_OBJECT_CUSTOM1.z * glow, 1.0);
    }
}
