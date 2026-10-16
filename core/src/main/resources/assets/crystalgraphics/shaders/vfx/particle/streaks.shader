// A trail behind every particle (a trails() layer on an emitter with trail(points)): a ki streak through the places its
// pool kept, one a particle step, so it follows every curve the particle flew; white-hot down its middle to its colour
// at soft edges, rounded at the particle, fading and eaten away toward its tail, tapering. Its width is the particle's
// size times the layer's; its brightness the particle's opacity. Each particle's own seed varies its streaks. Added; it
// blooms. CgVfxFrame.strip has its customs.
#type none
#pragma cg_use particle
#pragma cg_use rows
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_trail.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_draw.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"

Properties {
    _Color      ("Its energy; its alpha a strength", color) = (1.6, 0.55, 0.15, 1)
    _Core       ("The white-hot middle", color) = (1.4, 1.25, 1.0, 1)
    _CoreWidth  ("The middle's share of the width", float) = 0.35
    _Softness   ("How soft its edges are: higher thins them", float) = 1.4
    _Tail       ("Its width at its tail, times at the particle", float) = 0.1
    _Fade       ("How fast it fades along its life: higher sooner", float) = 1.2
    _Tiling     ("Noise cells a second of its flight along it, and across it", vec4) = (6, 1.2, 0, 0)
    _Flow       ("How fast its energy flows back, seconds of flight a second", float) = 0.4
    _Erode      ("How much its tail is eaten away, 0..1", float) = 0.5
    _GlowGain   ("Its glow past white", float) = 1.6
    _Noise  ("Noise", sampler3D) = "cg_noise"
}

Buffers {
    HISTORY ("Every trail ring's points: CgVfxParticlePool.history()", vec4, readonly)
}

// trail: life 0..1, across -1..1, seconds along, back from the particle; shape: the cap's seconds, intensity, alive, seed;
// tint: the particle's colour
struct v2f { vec4 trail; vec4 shape; vec3 tint; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    // Point k of particle i's trail, from its slot's origin: 0 where it is this frame, then its pool's step k back. False
    // where the ring holds another particle's, or none yet.
    bool streak_point(int i, int k, out FxTrailPoint p) {
        float alpha = CG_OBJECT_CUSTOM1.x, dt = CG_OBJECT_CUSTOM1.y, newest = CG_OBJECT_CUSTOM2.w;
        p.b = vec3(0.0);
        p.extra = vec4(CG_PARTICLE_SIZE(i), CG_PARTICLE_OPACITY(i), CG_PARTICLE_SEED(i), CG_PARTICLE_HEAT(i));
        if (k == 0) {
            p.a = CG_PARTICLE_POSITION(i);
            p.age = 0.0;
            p.along = (newest - 1.0 + alpha) * dt;
            return true;
        }
        int points = int(CG_OBJECT_CUSTOM2.z + 0.5), id = int(CG_PARTICLE_ID(i) + 0.5);
        int back = int(newest + 0.5) - k;
        int ring = int(CG_OBJECT_CUSTOM2.x + 0.5) + (id % int(CG_OBJECT_CUSTOM2.y + 0.5)) * points;
        vec4 h = HISTORY(ring + ((back % points) + points) % points);
        p.a = h.xyz;
        p.age = (alpha + float(k - 1)) * dt;
        p.along = float(back) * dt;
        return k < points && back >= 0 && abs(h.w - float(id) - 1.0) < 0.5;
    }

    void vertex(out v2f o) {
        int subs = max(int(CG_OBJECT_CUSTOM0.w + 0.5), 1), points = int(CG_OBJECT_CUSTOM2.z + 0.5);
        int per = (points - 1) * subs * 6;
        int rank = CG_VERTEX_ID / per, i = int(CG_OBJECT_CUSTOM0.x + 0.5) + rank;
        vec3 origin = CG_OBJECT_TO_WORLD[3].xyz;
        ivec4 c = fx_trail_corner(CG_VERTEX_ID - rank * per, subs);
        float dt = CG_OBJECT_CUSTOM1.y;
        FxTrailPoint p0, p1, p2, p3;
        bool alive = streak_point(i, c.x, p1) && streak_point(i, c.x + 1, p2) && CG_PARTICLE_SIZE(i) > 0.0;
        FxTrailVertex v = fx_trail_dead(origin, float(c.w));
        if (alive) {
            if (c.x == 0 || !streak_point(i, c.x - 1, p0)) p0 = p1;
            bool last = !streak_point(i, c.x + 2, p3);
            v = fx_trail_build(p0, p1, p2, p3, c.x == 0, last, (float(c.y) + float(c.z)) / float(subs), float(c.w),
                    FX_TRAIL_CAMERA, CG_OBJECT_CUSTOM0.z, float(points) * dt, p1.along + p1.age, origin, FX_CAMERA);
        }
        float taper = mix(1.0, _Tail, v.uv.x);
        o.trail = vec4(v.uv.x, v.side, v.along, v.fromHead);
        o.shape = vec4(dt * 0.75, v.extra.y, v.alive, v.extra.z);
        o.tint = CG_PARTICLE_COLOR(i).rgb;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(fx_trail_place(v, taper), 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        if (i.shape.z < 0.5) discard;
        FxGlow g = FxGlow(_Color.rgb, _Core.rgb, _CoreWidth, _Softness, _Fade, _Tiling.xy, _Flow, _Erode);
        vec3 glow;
        vec3 light = fx_trail_glow(g, _Noise, i.trail, i.shape.x, FX_AGE, i.shape.w * 37.0, glow);
        if (dot(light, light) == 0.0) discard;
        float strength = i.shape.y * _Color.a * FX_PRESENCE;
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
