// An energy wave's shock ring at the release: a crisp leading edge sweeping outward with a softer wake behind it, its
// radius wavering, fading as it spreads. Drawn on CgVfxFrame.mesh's sphere flattened to a disc facing along the aim;
// Cull BACK keeps only the half facing the eye. CG_OBJECT_CUSTOM1.z is an intensity, .w the ring's progress 0..1.
// Colour A is the edge, colour B the wake, A's alpha a strength. CgEnergyWave.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"

Properties {
    _Noise ("Noise", sampler3D) = "cg_noise"
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
        float progress = CG_OBJECT_CUSTOM1.w;
        float edge = progress * (1.0 + 0.015 * fx_noise(vec3(p / max(r, 1.0e-3) * 2.5, age * 3.0 + seed * 7.0)));
        float d = r - edge;
        float aa = fwidth(r) + 1.0e-4;
        float wake = 0.06 + 0.22 * progress;
        // Crisp outside the edge, fading in over the wake behind it.
        float ring = (1.0 - smoothstep(-aa, aa, d)) * smoothstep(-wake, 0.0, d);
        float line = 1.0 - smoothstep(0.02 + aa, 0.02 + 2.0 * aa, abs(d));
        vec3 col = CG_OBJECT_CUSTOM3.rgb * ring * 0.7 + CG_OBJECT_CUSTOM2.rgb * line * 1.6;
        fragColor = vec4(col * CG_OBJECT_CUSTOM2.a * CG_OBJECT_CUSTOM1.z * (1.0 - smoothstep(0.98, 1.0, r)), 1.0);
    }
}

// Its light again, into the world's bloom: the Forward pass's code and state, blurred over the scene.
Pass { Tags { "LightMode" = "Emissive" } }
