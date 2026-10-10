// A shock front running out along the ground: the air in a short wall round the blast, leaning outward and sinking as
// it spreads, bending the scene behind it out from the blast, most at its foot and where the wall is seen edge-on, and
// none at its top. With ring.shader's bright foot on the same particle it reads as the front sweeping the ground ahead
// of the dust. A mesh particle (CgVfxFrame.particles, MESHES) whose sphere the vertex stage bends into the wall
// (fx_ring.glsl), its radius the particle's drawn size; a Distortion pass (ORDER_DISTORTION), so it bends smoke and glow
// but never a bright body. CG_OBJECT_CUSTOM1: x its life 0..1, y its seed, z its opacity.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_ring.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Transparent"

Properties {
    _Below    ("Blocks the wall reaches under its centre", float) = 1.5
    _Height   ("Blocks it stands over its centre at birth; it sinks to 40% of it", float) = 4.0
    _Lean     ("Blocks it leans out a block it rises", float) = 0.5
    _Strength ("Bend at full opacity, share of the screen's height", float) = 0.08
    _Reference ("Within this many blocks the bend is _Strength; farther it shrinks as the front does on screen", float) = 8
    _ValueNoise ("Value noise", sampler3D) = "cg_value_noise"
}

struct v2f { vec3 world; vec3 outward; };

Pass {
    Tags { "LightMode" = "Forward" }
    // Writes nothing, so the world renderer skips it: the Distortion pass is the front.
    RenderState {
        ColorMask 0
        Blend OFF
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        vec3 centre = CG_OBJECT_TO_WORLD[3].xyz;
        float radius = length(CG_OBJECT_TO_WORLD[0].xyz), rise;
        float height = _Height * (1.0 - 0.6 * CG_OBJECT_CUSTOM1.x);
        vec3 outward;
        vec3 world = fx_ring_wall(centre, radius, _Below, height, _Lean, cg_TexCoord0, outward, rise);
        o.world = world;
        o.outward = outward;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = vec4(0.0);
    }
}

Pass {
    Tags { "LightMode" = "Distortion" }
    RenderState {
        DepthWrite OFF
        Cull OFF
    }

    void fragment(in v2f i, out vec4 distortion) {
        float life = CG_OBJECT_CUSTOM1.x, seed = CG_OBJECT_CUSTOM1.y, opacity = CG_OBJECT_CUSTOM1.z;
        vec3 ray = normalize(i.world - FX_CAMERA);
        vec3 outward = normalize(i.outward);
        float height = _Height * (1.0 - 0.6 * life);
        float over = i.world.y - CG_OBJECT_TO_WORLD[3].y;
        // Strongest low, gone at its top; edge-on most, as a thin shell bends, face-on still a little.
        float low = 1.0 - smoothstep(0.0, height, max(over, 0.0));
        float edge = mix(0.35, 1.0, 1.0 - smoothstep(0.1, 0.8, abs(dot(outward, ray))));
        float uneven = 0.7 + 0.6 * fx_value_noise(vec3(outward.xz * 3.0, seed * 5.0 + 1.0));
        float strength = low * edge * uneven * opacity;
        if (strength < 0.002) discard;
        // Out from the blast on screen; tilted up, so the sides facing the eye bend up rather than at random.
        vec3 bend = normalize(outward + vec3(0.0, 0.6, 0.0));
        vec2 dir = normalize((mat3(cg_ViewMatrix) * bend).xy + 1.0e-5);
        float eye = cg_LinearEyeDepth(gl_FragCoord.z);
        // Perspective: a far front moves the scene behind it as little as it covers.
        float far = min(1.0, _Reference / max(distance(FX_CAMERA, i.world), 1.0e-3));
        vec2 offset = dir * _Strength * far * strength * vec2(CG_RESOLUTION.y / CG_RESOLUTION.x, 1.0);
        distortion = CG_DISTORTION(offset, 0.1 * strength, eye);
    }
}
