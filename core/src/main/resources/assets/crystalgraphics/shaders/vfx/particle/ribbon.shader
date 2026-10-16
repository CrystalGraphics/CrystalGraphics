// One ribbon through an emitter instance's particles in the order they spawned, newest first (a ribbon() layer): a
// stream that bends wherever its particles drift, each pushed by its own modules, so curl noise makes it writhe and a
// vortex coils it. White-hot down its middle to its colour at soft edges, rounded at its newest particle, fading and
// eaten away as its particles age; its width each particle's size times the layer's, so the size curve shapes it along
// its life. Neighbours more than _Gap spawns apart are not joined: a burst is its own ribbon. Added; it blooms.
// CgVfxFrame.strip has its customs.
#type none
#pragma cg_use particle
#pragma cg_use rows
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_trail.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_draw.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"

Properties {
    _Color      ("Its energy; its alpha a strength", color) = (0.3, 0.7, 1.8, 1)
    _Core       ("The white-hot middle", color) = (1.25, 1.3, 1.45, 1)
    _CoreWidth  ("The middle's share of the width", float) = 0.3
    _Softness   ("How soft its edges are: higher thins them", float) = 1.5
    _Fade       ("How fast it fades over its particles' life: higher sooner", float) = 1.2
    _Tiling     ("Noise cells a spawn along it, and across it", vec4) = (0.2, 1.3, 0, 0)
    _Flow       ("How fast its energy flows back, spawns a second", float) = 30.0
    _Erode      ("How much its old end is eaten away, 0..1", float) = 0.6
    _Gap        ("Neighbours at most this many spawns apart are joined", float) = 64
    _GlowGain   ("Its glow past white", float) = 1.6
    _Noise  ("Noise", sampler3D) = "cg_noise"
}

struct v2f { vec4 trail; vec3 shape; vec3 tint; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    // The k-th newest of the slot's drawn particles, as a point along the ribbon: its life progress for an age, its spawn
    // index for along. False past the slot's visible ones, whose records are zero.
    bool ribbon_point(int k, out FxTrailPoint p) {
        int i = int(CG_OBJECT_CUSTOM0.x + 0.5) + k;
        p.a = CG_PARTICLE_POSITION(i);
        p.age = CG_PARTICLE_PROGRESS(i);
        p.b = vec3(0.0);
        p.along = CG_PARTICLE_ID(i);
        p.extra = vec4(CG_PARTICLE_SIZE(i), CG_PARTICLE_OPACITY(i), CG_PARTICLE_SEED(i), CG_PARTICLE_HEAT(i));
        return k >= 0 && k < int(CG_OBJECT_CUSTOM0.y + 0.5) && p.extra.x > 0.0;
    }

    void vertex(out v2f o) {
        int subs = max(int(CG_OBJECT_CUSTOM0.w + 0.5), 1);
        vec3 origin = CG_OBJECT_TO_WORLD[3].xyz;
        ivec4 c = fx_trail_corner(CG_VERTEX_ID, subs);
        FxTrailPoint p0, p1, p2, p3, head;
        bool alive = ribbon_point(c.x, p1) && ribbon_point(c.x + 1, p2) && p1.along - p2.along <= _Gap;
        FxTrailVertex v = fx_trail_dead(origin, float(c.w));
        if (alive) {
            bool first = c.x == 0 || !ribbon_point(c.x - 1, p0) || p0.along - p1.along > _Gap;
            bool last = !ribbon_point(c.x + 2, p3) || p2.along - p3.along > _Gap;
            if (first) p0 = p1;
            ribbon_point(0, head);
            v = fx_trail_build(p0, p1, p2, p3, first, last, (float(c.y) + float(c.z)) / float(subs), float(c.w),
                    FX_TRAIL_CAMERA, CG_OBJECT_CUSTOM0.z, 1.0, head.along, origin, FX_CAMERA);
        }
        o.trail = vec4(v.uv.x, v.side, v.along, v.fromHead);
        o.shape = vec3(v.extra.y, v.alive, 0.0);
        // Its two particles' colours, blended along the segment between them.
        int i1 = int(CG_OBJECT_CUSTOM0.x + 0.5) + c.x;
        o.tint = alive ? mix(CG_PARTICLE_COLOR(i1).rgb, CG_PARTICLE_COLOR(i1 + 1).rgb, (float(c.y) + float(c.z)) / float(subs))
                : vec3(0.0);
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(v.world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        if (i.shape.y < 0.5) discard;
        FxGlow g = FxGlow(_Color.rgb, _Core.rgb, _CoreWidth, _Softness, _Fade, _Tiling.xy, _Flow, _Erode);
        vec3 glow;
        // Its head rounds over its first spawn.
        vec3 light = fx_trail_glow(g, _Noise, i.trail, 1.0, FX_AGE, FX_SEED, glow);
        if (dot(light, light) == 0.0) discard;
        float strength = i.shape.x * _Color.a * FX_PRESENCE;
        fragColor = vec4(light * i.tint * strength, 0.0);
        CG_GLOW(glow * i.tint * strength * _GlowGain);
    }
}

// Its glow (CG_GLOW) into the world's bloom.
Pass {
    Tags { "LightMode" = "Emissive" }
    RenderState {
        Blend ONE ONE
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }
}
