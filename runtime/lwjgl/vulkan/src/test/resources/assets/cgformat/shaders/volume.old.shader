#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_tube.glsl"
Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Tube" = "Volume" }
Queue = "Transparent"
Properties { _FxPath ("Path rings", sampler2D) = "black" }
struct v2f { vec3 world; };
Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE
        DepthWrite OFF
    }
    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.world = world.xyz;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }
    void fragment(in v2f i, out vec4 fragColor) {
        FxRing r = fx_ring(_FxPath, int(CG_OBJECT_CUSTOM0.x + 0.5), int(CG_OBJECT_CUSTOM0.y + 0.5));
        vec3 centre = CG_OBJECT_TO_WORLD[3].xyz - CG_OBJECT_CUSTOM1.xyz + r.position;
        fragColor = vec4(vec3(0.2 * r.intensity) * exp(-distance(i.world, centre) / max(r.radius, 1.0e-3)), 1.0);
    }
}
