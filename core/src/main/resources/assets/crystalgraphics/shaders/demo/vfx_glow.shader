// The glow around an emissive sphere, standing in for bloom: a larger sphere's far wall, added on top of everything,
// brightest just outside the sphere's edge and fading to nothing at its own. Colour and strength per draw in
// CG_OBJECT_CUSTOM1 (rgb, intensity). CgVfxShowcase.
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
        Cull FRONT
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.worldPos = world.xyz;
        o.normalWs = CG_NORMAL_MATRIX * cg_Normal;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec3 n = normalize(i.normalWs);
        vec3 v = normalize(VFX_CAMERA - i.worldPos);
        // On the far wall the normal faces away: straight through the middle it points along the view.
        float through = max(dot(n, -v), 0.0);
        float glow = pow(through, 2.6) * 0.9 + pow(through, 9.0) * 1.4;
        fragColor = vec4(CG_OBJECT_CUSTOM1.rgb * CG_OBJECT_CUSTOM1.a * glow, 1.0);
    }
}
