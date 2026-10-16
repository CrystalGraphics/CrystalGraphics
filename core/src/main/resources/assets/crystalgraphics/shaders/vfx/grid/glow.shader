// A grid's channel (CgVfxGrid3D) as light, marched through the box round its tiles (CgVfxFrame.grid), a tile it has not
// skipped in one step: each step's value glows from _Glow where it is faint to _Hot where it reaches _HotAt, and dims
// what is behind it by _Absorb a unit, so a tangle of trails reads as a body. Its faint reaches are worn into filaments
// by noise finer than its cells, as the smoke's edges are, where a layer asks (_Detail). Blooming, through its codeless
// Emissive pass. Drawn at half size as a volume, each frame's march starts elsewhere and names the depth its light came
// from, so the resolve blends a frame's steps with the last ones'.
// CG_OBJECT_TO_WORLD: the box, a unit cube scaled. CG_OBJECT_CUSTOM0: the grid's place in the atlas, its cell in blocks,
// 0, the layer's size (a brightness scale); CG_OBJECT_CUSTOM1.xyz: its grid's low corner from the box's middle.
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
    _GridView   ("The grid atlas's channels (CgVfxGridAtlas)", sampler3D) = "black"
    _GridLight  ("Its light: unread here",                     sampler3D) = "white"
    _GridPages  ("Each grid's page table",                     sampler3D) = "black"
    _Glow       ("Its light where the channel is faint",       color)     = (0.16, 0.42, 1.0, 1)
    _Hot        ("Its light where it is strong",               color)     = (0.75, 0.95, 1.0, 1)
    _Channel    ("Which channel it draws: 0 to 3",             float)     = 0.0
    _HotAt      ("The value it is hot at",                     float)     = 1.0
    _Gain       ("Its light a unit a block",                   float)     = 1.0
    _Absorb     ("What a unit dims behind it, a block",        float)     = 0.15
    _Noise      ("Gradient noise",                             sampler3D) = "cg_noise"
    _Voronoi    ("Voronoi",                                    sampler3D) = "cg_voronoi"
    _Detail     ("Its faint reaches worn into filaments, 0 to 1", float)  = 0.0
    _DetailSize ("That noise's size, blocks",                  float)     = 1.0
    _StepCells  ("A step's length in cells",                  float)     = 0.5
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
        int grid = int(CG_OBJECT_CUSTOM0.x + 0.5);
        float cell = CG_OBJECT_CUSTOM0.y, scale = CG_OBJECT_CUSTOM0.w;
        vec3 centre = CG_OBJECT_TO_WORLD[3].xyz;
        vec3 size = vec3(length(CG_OBJECT_TO_WORLD[0].xyz), length(CG_OBJECT_TO_WORLD[1].xyz),
                length(CG_OBJECT_TO_WORLD[2].xyz));
        vec3 lo = centre - 0.5 * size, corner = centre + CG_OBJECT_CUSTOM1.xyz;
        vec2 t = fx_gas_box(eye, ray, lo, lo + size);
        t.x = max(t.x, 0.0);
        t.y = min(t.y, FX_SCENE_DISTANCE(ray));
        if (t.y <= t.x) discard;
        // Steps a cell's share long on a lattice fixed along the ray from the eye: the box round the tiles grows and
        // shrinks as tiles come and go, and a march spread across it moved every sample at once.
        float dt = max(_StepCells, 0.05) * cell, tile = float(FX_SPARSE_TILE) * cell;
        float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
#ifdef CG_VOLUME_TARGET
        jitter = fract(jitter + 0.618034 * float(CG_FRAME_INDEX % 64));
#endif
        int ch = int(_Channel + 0.5);
        vec4 mask = vec4(ch == 0 ? 1.0 : 0.0, ch == 1 ? 1.0 : 0.0, ch == 2 ? 1.0 : 0.0, ch == 3 ? 1.0 : 0.0);
        vec3 faint = srgb_to_linear(_Glow.rgb) * _Glow.a, hot = srgb_to_linear(_Hot.rgb) * _Hot.a;
        float hotAt = max(_HotAt, 1.0e-3);
        float transmit = 1.0, weight = 0.0, depth = 0.0;
        vec3 emit = vec3(0.0);
        // Each step stands for the half-step either side of it, clipped to where the ray is inside (smoke.shader's).
        float at = (ceil(t.x / dt - jitter - 0.5) + jitter) * dt;
        for (int k = 0; k < 512 && at - 0.5 * dt < t.y; k++) {
            float from = max(at - 0.5 * dt, t.x), to = min(at + 0.5 * dt, t.y), span = to - from;
            vec3 p = eye + ray * (0.5 * (from + to));
            vec3 c = (p - corner) / cell;
            ivec3 held = fx_sparse_tile(c);
            int slot = fx_sparse_slot_of(_GridPages, grid, held);
            if (slot < 0) {
                // Nothing here: on to the first step past where the ray leaves this tile, keeping the steps' phase.
                vec3 lo = corner + vec3(held) * tile;
                at += max(ceil((fx_gas_box(eye, ray, lo, lo + tile).y - at) / dt), 1.0) * dt;
                continue;
            }
            float v = max(dot(textureLod(_GridView, fx_sparse_pool_uvw(_GridView, slot, c), 0.0), mask), 0.0);
            // Between cells by a cubic B-spline where it holds anything, as smoke.shader reads it.
            if (v > 1.0e-4) v = max(dot(fx_sparse_smooth(_GridView, slot, c), mask), 0.0);
            if (v > 1.0e-4 && _Detail > 0.0 && v < 2.0 * hotAt) {
                // Eroded as a share of its hot value: what is faint frays, what is hot keeps itself.
                v = hotAt * fx_gas_erode(v / hotAt, fx_gas_carried_detail(_Noise, _Voronoi,
                        (p - corner) / max(_DetailSize, 0.05), vec3(0.0), FX_AGE, 0.0), _Detail);
            }
            if (v > 1.0e-4) {
                vec3 colour = mix(faint, hot, smoothstep(0.0, hotAt, v));
                vec3 step = colour * v * _Gain * scale * span * transmit;
                emit += step;
                float bright = max(step.r, max(step.g, step.b));
                depth += at * bright;
                weight += bright;
                transmit *= exp(-v * _Absorb * span);
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
