// The air a fast thing tears through: the scene behind a strip shimmering in its wake, strongest down its middle and
// near its head, gone at its edges and its tail, rippling as it streams back. Draws nothing in the scene; its
// Distortion pass bends what is behind it (Role.DISTORTION), so the streak it trails stays sharp. A ribbon's material,
// widening by half again toward its tail; its renderer tiles UV1 once a block (fx_strip.glsl).
#type none
#pragma cg_use particle
#pragma cg_use rows
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_strip.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_draw.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"

Properties {
    _Strength  ("Its bend at full strength, share of the screen's height", float) = 0.03
    _Reference ("Within this many blocks the bend is _Strength; farther it shrinks as the strip does on screen", float) = 6
    _Scale     ("Ripples a block", float) = 1.8
    _Stream    ("How fast the ripples stream back, blocks a second", float) = 4.0
    _Fade      ("How fast it calms along its life", float) = 1.2
    _Noise  ("Noise", sampler3D) = "cg_noise"
}

// trail: life 0..1, across -1..1, blocks along; its particles' opacity; where it is, from the eye
struct v2f { vec3 trail; float alpha; vec3 world; };

Pass {
    Tags { "LightMode" = "Forward" }
    // Writes nothing, so the world renderer skips it: the Distortion pass is the haze.
    RenderState {
        ColorMask 0
        Blend OFF
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        CgMaterialVertex v = cg_material_vertex();
        CgMaterialParticle p = cg_material_particle();
        o.trail = fx_strip(v.texCoords[0], v.texCoords[1], p.relativeTime);
        vec3 world = v.worldPosition + v.tangentToWorld[0] * (p.positionAndSize.w * 0.6 * o.trail.x * -o.trail.y);
        o.alpha = p.color.a;
        o.world = world;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = vec4(0.0);
    }
}

Pass {
    Tags { "LightMode" = "Distortion" }
    RenderState {
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    void fragment(in v2f i, out vec4 distortion) {
        float u = i.trail.x, across = abs(i.trail.y);
        float body = (1.0 - across * across) * pow(1.0 - u, _Fade) * smoothstep(0.0, 0.05, u + 0.02);
        float strength = body * i.alpha * FX_PRESENCE;
        if (strength < 0.002) discard;
        vec3 p = vec3((i.trail.z + FX_AGE * _Stream) * _Scale, i.trail.y * 1.5, FX_SEED * 7.0);
        vec2 wobble = fx_heat(p, FX_AGE, _Stream * _Scale * 0.2, FX_SEED);
        float far = min(1.0, _Reference / max(length(i.world), 1.0e-3));
        vec2 offset = wobble * _Strength * far * strength * vec2(CG_RESOLUTION.y / CG_RESOLUTION.x, 1.0);
        distortion = CG_DISTORTION(offset, 0.15 * strength, cg_LinearEyeDepth(gl_FragCoord.z));
    }
}
