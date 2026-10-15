// A grid's channel (CgVfxGrid3D) as light, marched through the box it spans (CgVfxFrame.grid): each step's value glows
// from _Glow where it is faint to _Hot where it reaches _HotAt, and dims what is behind it by _Absorb a unit, so a
// tangle of trails reads as a body. Blooming, through its codeless Emissive pass. Drawn at half size as a volume, each
// frame's march starts elsewhere and names the depth its light came from, so the resolve blends a frame's steps with the
// last ones'.
// CG_OBJECT_TO_WORLD: the box, a unit cube scaled. CG_OBJECT_CUSTOM0: the grid's brick, its cell in blocks, its cells a
// side, the layer's size (a brightness scale).
#type spatial
#pragma cg_use rows
#include "crystalgraphics:shaders/lib/color.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_depth.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_draw.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_gas.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_grid.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "ColorSpace" = "Linear" }
Queue = "Transparent"

Properties {
    _GridView  ("The grid atlas's channels (CgVfxGridAtlas)", sampler3D) = "black"
    _GridLight ("Its light: unread here",                     sampler3D) = "white"
    _Glow      ("Its light where the channel is faint",       color)     = (0.16, 0.42, 1.0, 1)
    _Hot       ("Its light where it is strong",               color)     = (0.75, 0.95, 1.0, 1)
    _Channel   ("Which channel it draws: 0 to 3",             float)     = 0.0
    _HotAt     ("The value it is hot at",                     float)     = 1.0
    _Gain      ("Its light a unit a block",                   float)     = 1.0
    _Absorb    ("What a unit dims behind it, a block",        float)     = 0.15
    _Steps     ("Steps across the box, at most; never under half a cell", float) = 96.0
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
        int brick = int(CG_OBJECT_CUSTOM0.x + 0.5);
        float cell = CG_OBJECT_CUSTOM0.y, cells = CG_OBJECT_CUSTOM0.z, scale = CG_OBJECT_CUSTOM0.w;
        vec3 centre = CG_OBJECT_TO_WORLD[3].xyz;
        vec3 lo = centre - vec3(0.5 * cells * cell);
        vec2 t = fx_gas_box(eye, ray, lo, lo + vec3(cells * cell));
        t.x = max(t.x, 0.0);
        t.y = min(t.y, FX_SCENE_DISTANCE(ray));
        if (t.y <= t.x) discard;
        float dt = max((t.y - t.x) / _Steps, 0.5 * cell);
        float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
#ifdef CG_VOLUME_TARGET
        jitter = fract(jitter + 0.618034 * float(CG_FRAME_INDEX % 64));
#endif
        int ch = int(_Channel + 0.5);
        vec4 mask = vec4(ch == 0 ? 1.0 : 0.0, ch == 1 ? 1.0 : 0.0, ch == 2 ? 1.0 : 0.0, ch == 3 ? 1.0 : 0.0);
        vec3 faint = srgb_to_linear(_Glow.rgb) * _Glow.a, hot = srgb_to_linear(_Hot.rgb) * _Hot.a;
        float transmit = 1.0, weight = 0.0, depth = 0.0;
        vec3 emit = vec3(0.0);
        float at = t.x + jitter * dt;
        for (int k = 0; k < 256 && at < t.y; k++) {
            vec3 p = eye + ray * at;
            float v = max(dot(textureLod(_GridView, fx_grid_uvw(_GridView, brick, cells, (p - lo) / cell), 0.0), mask), 0.0);
            if (v > 1.0e-4) {
                vec3 colour = mix(faint, hot, smoothstep(0.0, max(_HotAt, 1.0e-3), v));
                vec3 step = colour * v * _Gain * scale * dt * transmit;
                emit += step;
                float bright = max(step.r, max(step.g, step.b));
                depth += at * bright;
                weight += bright;
                transmit *= exp(-v * _Absorb * dt);
            }
            if (transmit < 0.01) break;
            at += dt;
        }
        CG_VOLUME_DEPTH(weight > 1.0e-4 ? FX_EYE_DEPTH(ray, depth / weight) : 0.0);
        emit *= FX_PRESENCE;
        float alpha = (1.0 - transmit) * FX_PRESENCE;
        if (alpha < 1.0e-4 && max(emit.r, max(emit.g, emit.b)) < 1.0e-4) discard;
        fragColor = CG_LINEAR_SCENE ? vec4(emit, alpha) : vec4(linear_to_srgb(min(emit, vec3(1.0))) + max(emit - 1.0, 0.0), alpha);
        CG_GLOW(emit);
    }
}

// Codeless: it blooms, in the same draw.
Pass { Tags { "LightMode" = "Emissive" } }
