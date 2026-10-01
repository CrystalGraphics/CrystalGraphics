// A plasma bolt flying at the force field: a stretched sphere of light, white-hot along its middle and orange-red at
// its edges, added over everything. CgVfxShowcase.
#type spatial
#include "crystalgraphics:shaders/demo/vfx_common.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

struct v2f { vec3 worldPos; vec3 normalWs; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE
        DepthTest LEQUAL
        DepthWrite OFF
        Cull BACK
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.worldPos = world.xyz;
        o.normalWs = CG_NORMAL_MATRIX * cg_Normal;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float nv = abs(dot(normalize(i.normalWs), normalize(VFX_CAMERA - i.worldPos)));
        vec3 color = vec3(2.2, 0.55, 0.08) * pow(nv, 1.5) * 1.6 + vec3(1.6, 1.3, 0.9) * pow(nv, 6.0) * 3.5;
        fragColor = vec4(color, 1.0);
    }
}
