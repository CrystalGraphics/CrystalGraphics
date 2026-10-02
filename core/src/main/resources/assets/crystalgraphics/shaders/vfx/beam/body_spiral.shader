// An energy wave's spirals: two sets of bright bands wrapping the body in opposite senses, flowing forward, crisp-edged
// and broken up so no band runs unbroken, faded in from the muzzle and out before the head. A tube layer
// (fx_tube.glsl). Colour A is the bands, colour B where they cross, A's alpha a strength. CgEnergyWave.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_tube.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

Properties {
    _FxPath ("Path rings", sampler2D) = "black"
    _Pitch  ("Blocks per turn", float) = 2.6
    _Speed  ("How fast the bands flow forward, turns a second", float) = 3.2
    _Width  ("Band half-width, share of a turn", float) = 0.09
}

struct v2f { vec3 world; vec4 surface; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    // A crisp band where u crosses a whole number; aa is u's screen-space rate, worked out in the fragment stage.
    float band(float u, float width, float aa) {
        float d = abs(fract(u) - 0.5);
        return 1.0 - smoothstep(width - aa, width + aa, 0.5 - d);
    }

    void vertex(out v2f o) {
        FxTubeVertex v = fx_tube_vertex(_FxPath, int(CG_OBJECT_CUSTOM0.x + 0.5), int(CG_OBJECT_CUSTOM0.y + 0.5),
                                        cg_TexCoord0, CG_OBJECT_CUSTOM0.z, 0.0);
        vec3 origin = CG_OBJECT_TO_WORLD[3].xyz - CG_OBJECT_CUSTOM1.xyz;
        o.world = origin + v.position;
        // arc, angle, age, how far the head is beyond this ring
        o.surface = vec4(v.ring.arc, v.angle, v.header.w, v.header.y - v.ring.arc);
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(o.world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float s = i.surface.x, angle = i.surface.y, age = i.surface.z;
        float a = angle * 6.28318531;
        float u1 = angle * 2.0 + s / _Pitch - age * _Speed;
        float u2 = -angle * 3.0 + s / (_Pitch * 0.7) - age * _Speed * 1.3;
        float b1 = band(u1, _Width, fwidth(u1) + 1.0e-4), b2 = band(u2, _Width * 0.8, fwidth(u2) + 1.0e-4);
        float breakup = smoothstep(0.4, 0.62, 0.5 + 0.5 * fx_noise(vec3((s - age * 18.0) * 0.4, cos(a) * 1.5, sin(a) * 1.5)));
        float ends = smoothstep(1.0, 3.5, s) * smoothstep(0.5, 2.5, i.surface.w);
        vec3 col = CG_OBJECT_CUSTOM2.rgb * (b1 + 0.7 * b2) * 1.4 + CG_OBJECT_CUSTOM3.rgb * b1 * b2;
        fragColor = vec4(col * breakup * ends * CG_OBJECT_CUSTOM2.a * (gl_FrontFacing ? 1.0 : 0.4), 1.0);
    }
}
