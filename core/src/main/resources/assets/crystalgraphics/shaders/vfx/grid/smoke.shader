// A grid's channel x (CgVfxGrid3D) as smoke, marched through the box round its tiles (CgVfxFrame.grid), a tile it has
// not skipped in one step: each step reads its
// density and the light the grid's Shade left there (the transmittance toward the sun, the sky's light), wears the
// thin edges with noise finer than the cells (Schneider's erosion), and adds the light scattered toward the eye by
// Hillaire's energy-conserving step through a two-lobe phase with Horizon's powder, for a body that
// particles splat. Drawn at half size as a volume, each frame's march starts elsewhere and names the depth its light came
// from, so the resolve blends a frame's steps with the last ones'.
// CG_OBJECT_TO_WORLD: the box, a unit cube scaled. CG_OBJECT_CUSTOM0: the grid's place in the atlas, its cell in blocks,
// 0, the layer's size (a density scale); CG_OBJECT_CUSTOM1: its grid's low corner from the box's middle, its extinction
// a block. _Smoke is its colour in full light.
#type spatial
#pragma cg_use rows
#include "crystalgraphics:shaders/lib/color.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_depth.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_draw.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_detail.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_scatter.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_grid.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "ColorSpace" = "Linear" }
Queue = "Transparent"

Properties {
    _GridView   ("The grid atlas's channels (CgVfxGridAtlas)",  sampler3D) = "black"
    _GridLight  ("Its light: sun transmittance, sky",          sampler3D) = "white"
    _GridPages  ("Each grid's page table",                     sampler3D) = "black"
    _Noise      ("Gradient noise",                             sampler3D) = "cg_noise"
    _Voronoi    ("Voronoi",                                    sampler3D) = "cg_voronoi"
    _Smoke      ("The smoke in full light",                    color)     = (0.72, 0.71, 0.7, 1)
    _StepCells  ("A step's length in cells",                  float)     = 0.5
    _Scattering ("Forward scattering, 0 to 0.9",               float)     = 0.6
    _Sun        ("The sun's strength in it",                   float)     = 1.0
    _Ambient    ("The sky's strength in it",                   float)     = 0.6
    _Detail     ("Its edges worn by finer noise, 0 to 1",      float)     = 0.5
    _DetailSize ("That noise's size, blocks",                  float)     = 0.6
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
        float extinction = CG_OBJECT_CUSTOM1.w;
        vec3 centre = CG_OBJECT_TO_WORLD[3].xyz;
        vec3 size = vec3(length(CG_OBJECT_TO_WORLD[0].xyz), length(CG_OBJECT_TO_WORLD[1].xyz),
                length(CG_OBJECT_TO_WORLD[2].xyz));
        vec3 lo = centre - 0.5 * size, corner = centre + CG_OBJECT_CUSTOM1.xyz;
        vec2 t = fx_scatter_box(eye, ray, lo, lo + size);
        t.x = max(t.x, 0.0);
        t.y = min(t.y, FX_SCENE_DISTANCE(ray));
        if (t.y <= t.x) discard;
        // Steps a cell's share long on a lattice fixed along the ray from the eye: the box round the tiles grows and
        // shrinks as tiles come and go, and a march spread across it moved every sample at once.
        float dt = max(_StepCells, 0.05) * cell, tile = float(FX_SPARSE_TILE) * cell;
        // Interleaved gradient noise (Jimenez, 2014): each pixel's steps start apart, so banding becomes grain.
        float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
#ifdef CG_VOLUME_TARGET
        jitter = fract(jitter + 0.618034 * float(CG_FRAME_INDEX % 64));
#endif
        float cosine = dot(ray, CG_SUN_DIRECTION);
        float g = clamp(_Scattering, 0.0, 0.9);
        float p0 = fx_scatter_phase(cosine, g), p1 = fx_scatter_phase(cosine, g * 0.5);
        vec3 sun = mix(vec3(0.05, 0.06, 0.1), vec3(1.0, 0.95, 0.88), CG_DAYLIGHT) * _Sun;
        vec3 sky = srgb_to_linear(CG_LIGHTMAP(vec2(0.0, 15.0))) * vec3(0.78, 0.87, 1.0) * _Ambient;
        vec3 albedo = srgb_to_linear(_Smoke.rgb);
        float transmit = 1.0, depth = 0.0;
        vec3 light = vec3(0.0);
        // Each step stands for the half-step either side of it, clipped to where the ray is inside: the last one before the
        // ground counted whole took up to a step of smoke past it, by where the lattice fell, in the jitter's pattern.
        float at = (ceil(t.x / dt - jitter - 0.5) + jitter) * dt;
        for (int k = 0; k < 512 && at - 0.5 * dt < t.y; k++) {
            float from = max(at - 0.5 * dt, t.x), to = min(at + 0.5 * dt, t.y), span = to - from;
            vec3 p = eye + ray * (0.5 * (from + to));
            vec3 c = (p - corner) / cell;
            ivec3 held = fx_sparse_tile(c);
            int slot = fx_sparse_slot_of(_GridPages, grid, held);
            if (slot < 0) {
                // Still air: on to the first step past where the ray leaves this tile, keeping the steps' phase.
                vec3 lo = corner + vec3(held) * tile;
                at += max(ceil((fx_scatter_box(eye, ray, lo, lo + tile).y - at) / dt), 1.0) * dt;
                continue;
            }
            vec3 uvw = fx_sparse_pool_uvw(_GridView, slot, c);
            if (textureLod(_GridView, uvw, 0.0).x < 1.0e-4) {
                at += dt;
                continue;
            }
            // Between cells by a cubic B-spline, as the fluid reads its smoke: linear, a thin edge showed the cells.
            float density = max(fx_sparse_smooth(_GridView, slot, c).x, 0.0) * scale;
            // Its light by the same spline: read linearly, its slope broke at every cell face and the shading showed the grid.
            vec2 lit = clamp(fx_sparse_smooth(_GridLight, slot, c).xy, 0.0, 1.0);
            if (_Detail > 0.0 && density < 2.0) {
                density = fx_erode(density, fx_detail_carried(_Noise, _Voronoi, (p - corner) / max(_DetailSize, 0.05),
                        vec3(0.0), FX_AGE, 0.0), _Detail);
            }
            float sigma = density * extinction;
            float through = exp(-sigma * span);
            if (sigma > 1.0e-4) {
                float scattered = (p0 * lit.x + 0.5 * p1 * sqrt(lit.x)) / 1.5 * fx_scatter_powder(sigma, cosine);
                float taken = transmit * (1.0 - through);
                depth += at * taken;
                light += albedo * (sun * scattered + sky * lit.y) * taken;
            }
            transmit *= through;
            if (transmit < 0.01) break;
            at += dt;
        }
        CG_VOLUME_DEPTH(transmit < 0.99 ? FX_EYE_DEPTH(ray, depth / (1.0 - transmit)) : 0.0);
        float alpha = (1.0 - transmit) * FX_PRESENCE;
        light *= FX_PRESENCE;
        if (alpha < 1.0e-4) discard;
        if (CG_LINEAR_SCENE) {
            fragColor = vec4(light, alpha);
        } else {
            vec3 c = light / max(alpha, 1.0e-4);
            fragColor = vec4((linear_to_srgb(min(c, vec3(1.0))) + max(c - 1.0, 0.0)) * alpha, alpha);
        }
    }
}
