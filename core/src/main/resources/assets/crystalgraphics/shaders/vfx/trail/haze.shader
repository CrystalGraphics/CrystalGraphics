// The air a fast thing tears through: the scene behind a trail shimmering in its wake, strongest down its middle and
// near its head, gone at its edges and its tail, rippling as it streams back. Draws nothing in the scene; its
// Distortion pass bends what is behind it (Role.DISTORTION), so the streak it trails stays sharp.
// On a trail slot (CgVfxFrame.trail): the layer's size is its half-width in blocks, usually twice its streak's.
#type none
#pragma cg_use rows
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_trail.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_draw.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Transparent"

Properties {
    _Strength  ("Its bend at full strength, share of the screen's height", float) = 0.03
    _Reference ("Within this many blocks the bend is _Strength; farther it shrinks as the trail does on screen", float) = 6
    _Scale     ("Ripples a block", float) = 1.8
    _Stream    ("How fast the ripples stream back, blocks a second", float) = 4.0
    _Fade      ("How fast it calms along its life", float) = 1.2
    _Noise  ("Noise", sampler3D) = "cg_noise"
    _FxTrail ("Trail points", sampler2D) = "black"
}

struct v2f { vec4 trail; vec3 world; float alive; };

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
        FxTrailVertex v = fx_trail_vertex(_FxTrail, CG_VERTEX_ID, CG_OBJECT_CUSTOM0, CG_OBJECT_CUSTOM2,
                CG_OBJECT_TO_WORLD[3].xyz - CG_OBJECT_CUSTOM1.xyz, FX_CAMERA);
        float taper = mix(1.0, 1.6, v.uv.x);
        vec3 world = fx_trail_place(v, taper);
        o.trail = vec4(v.uv.x, v.side, v.along, v.extra.y);
        o.world = world;
        o.alive = v.alive;
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
        if (i.alive < 0.5) discard;
        float u = i.trail.x, across = abs(i.trail.y);
        float body = (1.0 - across * across) * pow(1.0 - u, _Fade) * smoothstep(0.0, 0.05, u + 0.02);
        float strength = body * i.trail.w * FX_PRESENCE;
        if (strength < 0.002) discard;
        vec3 p = vec3((i.trail.z + FX_AGE * _Stream) * _Scale, i.trail.y * 1.5, FX_SEED * 7.0);
        vec2 wobble = fx_heat(p, FX_AGE, _Stream * _Scale * 0.2, FX_SEED);
        float far = min(1.0, _Reference / max(length(i.world - FX_CAMERA), 1.0e-3));
        vec2 offset = wobble * _Strength * far * strength * vec2(CG_RESOLUTION.y / CG_RESOLUTION.x, 1.0);
        distortion = CG_DISTORTION(offset, 0.15 * strength, cg_LinearEyeDepth(gl_FragCoord.z));
    }
}
