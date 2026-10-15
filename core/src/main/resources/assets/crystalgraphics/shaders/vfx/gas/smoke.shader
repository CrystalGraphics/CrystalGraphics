// A gas's brick (CgVfxGas) marched through the cube round it (CgVfxFrame.volume). Each step reads the solver's shade
// (density, temperature, the transmittance toward the sun it cached, the sky light) and adds the light scattered toward
// the eye by Hillaire's energy-conserving step (Frostbite, 2015), Wrenninge's octaves standing in for light scattered
// more than once (2013), the sky's light from the lightmap, and a blackbody where it burns. The march stops at the
// scene's depth, and draws from inside the cube as from outside: back faces, its own depth test.
// CG_OBJECT_CUSTOM0: its brick, cells a brick a side, bricks along the atlas, the layer's size (a density scale);
// CG_OBJECT_CUSTOM1: the cube's side in blocks, the gas's extinction a block. _Smoke is its colour in full light, _Fire
// a tint on what burns.
#type spatial
#pragma cg_use rows
#include "crystalgraphics:shaders/lib/color.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_depth.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_draw.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_gas.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "ColorSpace" = "Linear" }
Queue = "Transparent"

Properties {
    _GasShade ("The gas atlas's shade (CgVfxGasAtlas)", sampler3D) = "black"
    _Smoke    ("The smoke in full light", color) = (0.7, 0.7, 0.68, 1)
    _Fire     ("A tint on what burns in it", color) = (1, 1, 1, 1)
    _Steps    ("Steps across the cube", float) = 64.0
    _Forward  ("Forward scattering, 0 to 1", float) = 0.55
    _Sun      ("The sun's strength in it", float) = 3.0
    _Ambient  ("The sky's strength in it", float) = 1.0
}

struct v2f { vec3 world; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE_MINUS_SRC_ALPHA
        DepthTest ALWAYS
        DepthWrite OFF
        Cull FRONT
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.world = world.xyz;
        gl_Position = cg_ProjMatrix * (cg_ViewMatrix * world);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec3 eye = CG_CAMERA_WORLD_POS, ray = FX_PIXEL_RAY;
        float brick = CG_OBJECT_CUSTOM0.x, cells = CG_OBJECT_CUSTOM0.y, bricks = CG_OBJECT_CUSTOM0.z;
        float extent = CG_OBJECT_CUSTOM1.x, extinction = CG_OBJECT_CUSTOM1.y * CG_OBJECT_CUSTOM0.w;
        vec3 lo = CG_OBJECT_TO_WORLD[3].xyz - 0.5 * extent;
        vec2 t = fx_gas_box(eye, ray, lo, lo + extent);
        t.x = max(t.x, 0.0);
        t.y = min(t.y, FX_SCENE_DISTANCE(ray));
        if (t.y <= t.x) discard;
        int steps = int(_Steps);
        float dt = (t.y - t.x) / float(steps);
        // Interleaved gradient noise (Jimenez, 2014): each pixel's steps start apart, so banding becomes grain.
        float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
        float cosine = dot(ray, CG_SUN_DIRECTION);
        // Each octave a dimmer copy of the light scattered once, less shadowed and less forward.
        float p0 = fx_gas_phase(cosine, _Forward), p1 = fx_gas_phase(cosine, _Forward * 0.5),
                p2 = fx_gas_phase(cosine, _Forward * 0.25);
        // The sun by day, the moon's dim blue by night: CG_SUN_DIRECTION is whichever is up.
        vec3 sun = mix(vec3(0.05, 0.06, 0.1), vec3(1.0, 0.96, 0.9), CG_DAYLIGHT) * _Sun;
        vec3 albedo = srgb_to_linear(_Smoke.rgb), fireTint = _Fire.rgb;
        float transmit = 1.0;
        vec3 light = vec3(0.0), glow = vec3(0.0);
        for (int k = 0; k < steps; k++) {
            vec3 p = eye + ray * (t.x + (float(k) + jitter) * dt);
            vec4 s = textureLod(_GasShade, fx_gas_uvw((p - lo) / extent, brick, cells, bricks), 0.0);
            float sigma = s.x * extinction;
            if (sigma < 1.0e-4) continue;
            float lit = p0 * s.z + 0.5 * p1 * sqrt(s.z) + 0.25 * p2 * sqrt(sqrt(s.z));
            vec3 sky = srgb_to_linear(CG_LIGHTMAP(vec2(0.0, s.w * 15.0))) * _Ambient;
            vec3 fire = fx_gas_fire(s.y) * fireTint;
            float through = exp(-sigma * dt);
            // What a step scatters toward the eye, integrated against its own extinction: never more than it lets out.
            float taken = transmit * (1.0 - through);
            light += (albedo * (sun * lit + sky) + fire) * taken;
            glow += fire * taken;
            transmit *= through;
            if (transmit < 0.01) break;
        }
        float alpha = (1.0 - transmit) * FX_PRESENCE;
        light *= FX_PRESENCE;
        if (alpha < 1.0e-4) discard;
        // Into the encoded picture, its excess past white kept as the scene's decode keeps it.
        vec3 c = light / alpha;
        vec3 encoded = linear_to_srgb(min(c, vec3(1.0))) + max(c - 1.0, 0.0);
        fragColor = CG_LINEAR_SCENE ? vec4(light, alpha) : vec4(encoded * alpha, alpha);
        CG_GLOW(glow * FX_PRESENCE);
    }
}

// Codeless: what burns blooms, in the same draw.
Pass { Tags { "LightMode" = "Emissive" } }
