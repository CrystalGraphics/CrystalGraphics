// An energy wave's portal: arms of energy spiralling into the charge, crisp-edged and broken up, brightest at the
// centre. Drawn on CgVfxFrame.mesh's sphere flattened to a disc facing along the aim; Cull BACK keeps only the half
// facing the eye, so each pixel is drawn once from either side. CG_OBJECT_CUSTOM1.z is an intensity. Colour A is the
// arms, colour B their hot edge, A's alpha a strength. CgEnergyWave.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

Properties {
    _Arms  ("Spiral arms", float) = 5.0
    _Twist ("How tightly they wind", float) = 1.6
    _Speed ("How fast they pour inward", float) = 1.4
}

struct v2f { vec3 world; vec2 local; };

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
        o.world = world.xyz;
        o.local = cg_Position.xy;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec2 p = i.local;
        float r = length(p);
        float age = CG_OBJECT_CUSTOM0.z, seed = CG_OBJECT_CUSTOM0.w;
        // Constant along a spiral arm; rising time moves each arm inward.
        float u = atan(p.y, p.x) / 6.28318531 * _Arms + log(max(r, 0.03)) * _Twist + age * _Speed;
        // Its screen-space rate from the continuous parts, since atan jumps where the angle wraps.
        float rate = (_Arms / 6.28318531 + _Twist) * length(fwidth(p)) / max(r, 0.03);
        float band = abs(fract(u) - 0.5);
        float arm = 1.0 - smoothstep(0.2 - rate, 0.2 + rate, band);
        float hot = 1.0 - smoothstep(0.06 - rate, 0.06 + rate, band);
        float breakup = smoothstep(0.35, 0.6, 0.5 + 0.5 * fx_noise(vec3(p * 3.5, age * 1.2 + seed * 9.0)));
        float rim = 1.0 - smoothstep(0.7, 1.0, r);
        float inner = smoothstep(0.06, 0.28, r);
        float centre = exp(-r * r * 22.0);
        vec3 col = CG_OBJECT_CUSTOM2.rgb * arm * breakup * rim * inner + CG_OBJECT_CUSTOM3.rgb * (hot * breakup * rim * inner + centre * 1.4);
        fragColor = vec4(col * CG_OBJECT_CUSTOM2.a * CG_OBJECT_CUSTOM1.z, 1.0);
    }
}
