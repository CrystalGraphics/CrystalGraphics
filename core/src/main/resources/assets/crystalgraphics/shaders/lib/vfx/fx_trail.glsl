// A strip behind something moving, built per vertex from its points: centripetal Catmull-Rom between them (Barry and
// Goldman's pyramid), so a fast swing stays round; its head where the thing is this frame; its tail cut exactly where a
// point's age reaches the strip's life, so it shrinks smoothly rather than dropping points. Three sources feed it:
//
//   a CgVfxTrail, from CgVfxTrailTexture:           fx_trail_vertex(_FxTrail, CG_VERTEX_ID, CG_OBJECT_CUSTOM0, ...)
//   each particle's own trail (a trails() layer):     its points from HISTORY, then fx_trail_build
//   a ribbon through a slot's particles (ribbon()):   its points from the particle records, then fx_trail_build
//
// A trail shader has no vertex data (#type none):
//
//     Properties { _FxTrail ("Trail points", sampler2D) = "black" }
//     FxTrailVertex v = fx_trail_vertex(_FxTrail, CG_VERTEX_ID, CG_OBJECT_CUSTOM0, CG_OBJECT_CUSTOM2,
//                                       CG_OBJECT_TO_WORLD[3].xyz - CG_OBJECT_CUSTOM1.xyz, FX_CAMERA);
//     vec3 world = fx_trail_place(v, taper);   // its width times the shader's own taper; v.world is it at 1
//     gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(world, 1.0);
//
// Nothing here names cg_* or CG_*: each source's numbers come in as arguments.
#pragma once

#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"

// CgVfxTrailTexture.WIDTH, texels a row: a trail's points run on across rows.
#define FX_TRAIL_ROW 1024
#define FX_TRAIL_CAMERA 0
#define FX_TRAIL_AXIS 1
#define FX_TRAIL_BLADE 2

struct FxTrailPoint {
    vec3 a;          // the point, or a blade's base: from the anchor
    float age;       // seconds (a ribbon's: its particle's life, 0 to 1)
    vec3 b;          // a blade's tip from the anchor; an axis trail's facing; else 0
    float along;     // how far from where it began: what a texture along it is anchored to
    vec4 extra;      // its width and intensity scales, and two numbers of its source's
};

struct FxTrailVertex {
    vec3 world;      // camera-relative: fx_trail_place(v, 1.0)
    vec3 centre;     // the strip's middle here (a blade's base), camera-relative
    vec3 span;       // from the centre to this vertex's edge at full width (a blade's: to the tip, times its size)
    vec3 tangent;    // along the strip toward its head, unit
    float reach;     // this vertex's share of span: -1 or 1 for a ribbon, 0 or 1 for a blade
    vec2 uv;         // x 0 at the head to 1 at the strip's life (its age over life); y 0 to 1 across (blade: base to tip)
    float side;      // -1 to 1 across: 0 down the middle of a ribbon
    float fromHead;  // how far back from the head, in its along's units
    float along;     // its points' along, here
    float age;
    vec4 extra;
    float alive;     // 0 for a vertex past the strip's points: its triangles are degenerate
};

// Vertex `id` of a strip of `subs` quads a segment, six vertices a quad, its corners as CgVfxRibbons' (bit k of 0x34 at
// the quad's far end, of 0x1A on side 1): its segment, its quad within it, and whether it is at the far end and on side 1.
ivec4 fx_trail_corner(int id, int subs) {
    int quad = id / 6, corner = id - quad * 6;
    int segment = quad / subs;
    return ivec4(segment, quad - segment * subs, (0x34 >> corner) & 1, (0x1A >> corner) & 1);
}

// A vertex of no segment: degenerate at the anchor.
FxTrailVertex fx_trail_dead(vec3 anchor, float side) {
    FxTrailVertex v;
    v.world = anchor;
    v.centre = anchor;
    v.span = vec3(0.0);
    v.tangent = vec3(0.0, 1.0, 0.0);
    v.reach = 0.0;
    v.uv = vec2(0.0);
    v.side = side * 2.0 - 1.0;
    v.fromHead = 0.0;
    v.along = 0.0;
    v.age = 0.0;
    v.extra = vec4(0.0);
    v.alive = 0.0;
    return v;
}

// Centripetal Catmull-Rom from p1 (t = 0) to p2 (t = 1), p0 and p3 beyond them: no cusp or loop where points bunch,
// as a uniform one makes (Yuksel, Schaefer and Keyser 2011). t outside 0..1 runs on smoothly.
vec3 fx_trail_spline(vec3 p0, vec3 p1, vec3 p2, vec3 p3, float t) {
    float k1 = sqrt(max(distance(p0, p1), 1.0e-4));
    float k2 = k1 + sqrt(max(distance(p1, p2), 1.0e-4));
    float k3 = k2 + sqrt(max(distance(p2, p3), 1.0e-4));
    float u = mix(k1, k2, t);
    vec3 a1 = mix(p0, p1, u / k1);
    vec3 a2 = mix(p1, p2, (u - k1) / (k2 - k1));
    vec3 a3 = mix(p2, p3, (u - k2) / (k3 - k2));
    vec3 b1 = mix(a1, a2, u / k2);
    vec3 b2 = mix(a2, a3, (u - k1) / (k3 - k1));
    return mix(b1, b2, (u - k1) / (k2 - k1));
}

// A unit vector across tangent t facing the eye; where the eye looks straight down the trail, any across it, so the
// strip narrows to its true width on screen rather than flipping.
vec3 fx_trail_across(vec3 t, vec3 toEye) {
    vec3 across = cross(t, toEye);
    float l = length(across);
    if (l > 1.0e-5 * length(toEye)) return across / l;
    vec3 helper = abs(t.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    return normalize(cross(t, helper));
}

// The strip's vertex `t` of the way from p1 to p2, p0 and p3 beyond them (mirrored past an end: `first` and `last` say
// p1 and p2 are its ends), on side 0 or 1. size scales a ribbon's width (times each point's extra.x) or a blade's reach;
// life is the age its tail is cut at; headAlong the head's along; anchor where the points are from, camera-relative.
FxTrailVertex fx_trail_build(FxTrailPoint p0, FxTrailPoint p1, FxTrailPoint p2, FxTrailPoint p3, bool first, bool last,
                             float t, float side, int mode, float size, float life, float headAlong, vec3 anchor, vec3 eye) {
    if (p1.age >= life) return fx_trail_dead(anchor, side);
    // Mirrored past either end, so the spline ends heading straight at its last point.
    if (first) { p0.a = 2.0 * p1.a - p2.a; p0.b = 2.0 * p1.b - p2.b; }
    if (last) { p3.a = 2.0 * p2.a - p1.a; p3.b = 2.0 * p2.b - p1.b; }
    // The tail: cut where the age reaches life, between the last point alive and the first past it.
    if (p2.age > life) t = min(t, (life - p1.age) / max(p2.age - p1.age, 1.0e-6));
    FxTrailVertex v;
    vec3 c = fx_trail_spline(p0.a, p1.a, p2.a, p3.a, t);
    v.age = mix(p1.age, p2.age, t);
    v.along = mix(p1.along, p2.along, t);
    v.extra = mix(p1.extra, p2.extra, t);
    v.fromHead = headAlong - v.along;
    v.uv = vec2(clamp(v.age / max(life, 1.0e-4), 0.0, 1.0), side);
    v.side = side * 2.0 - 1.0;
    v.alive = 1.0;
    v.centre = anchor + c;
    // The tangent from the spline either side, so it turns as smoothly as the strip does.
    vec3 tangent = fx_trail_spline(p0.a, p1.a, p2.a, p3.a, t - 0.05) - fx_trail_spline(p0.a, p1.a, p2.a, p3.a, t + 0.05);
    if (dot(tangent, tangent) < 1.0e-12) tangent = p1.a - p2.a;
    if (dot(tangent, tangent) < 1.0e-12) tangent = vec3(0.0, 1.0, 0.0);
    v.tangent = normalize(tangent);
    if (mode == FX_TRAIL_BLADE) {
        vec3 tip = fx_trail_spline(p0.b, p1.b, p2.b, p3.b, t);
        v.span = (tip - c) * size;
        v.reach = side;
        v.world = v.centre + v.span * side;
        return v;
    }
    vec3 across;
    if (mode == FX_TRAIL_AXIS) {
        across = cross(tangent, mix(p1.b, p2.b, t));
        float l = length(across);
        across = l > 1.0e-6 ? across / l : fx_trail_across(tangent, eye - v.centre);
    } else {
        across = fx_trail_across(tangent, eye - v.centre);
    }
    v.span = across * (size * v.extra.x);
    v.reach = v.side;
    v.world = v.centre + v.span * v.reach;
    return v;
}

ivec2 fx_trail_texel(int i) {
    return ivec2(i % FX_TRAIL_ROW, i / FX_TRAIL_ROW);
}

FxTrailPoint fx_trail_point(sampler2D points, int first, int i) {
    int t = first + i * 3;
    vec4 a = texelFetch(points, fx_trail_texel(t), 0);
    vec4 b = texelFetch(points, fx_trail_texel(t + 1), 0);
    FxTrailPoint p;
    p.a = a.xyz;
    p.age = a.w;
    p.b = b.xyz;
    p.along = b.w;
    p.extra = texelFetch(points, fx_trail_texel(t + 2), 0);
    return p;
}

// Vertex `id` of a CgVfxTrail's strip (CgVfxFrame.trail): custom0 is (first texel, points, the layer's size, life in
// seconds), custom2 (quads a segment, mode, the head's distance along, the trail's length). Along is in blocks.
FxTrailVertex fx_trail_vertex(sampler2D points, int id, vec4 custom0, vec4 custom2, vec3 anchor, vec3 eye) {
    int first = int(custom0.x + 0.5), count = int(custom0.y + 0.5), subs = max(int(custom2.x + 0.5), 1);
    ivec4 c = fx_trail_corner(id, subs);
    if (c.x >= count - 1) return fx_trail_dead(anchor, float(c.w));
    FxTrailPoint p1 = fx_trail_point(points, first, c.x), p2 = fx_trail_point(points, first, c.x + 1);
    FxTrailPoint p0 = c.x > 0 ? fx_trail_point(points, first, c.x - 1) : p1;
    FxTrailPoint p3 = c.x + 2 < count ? fx_trail_point(points, first, c.x + 2) : p2;
    return fx_trail_build(p0, p1, p2, p3, c.x == 0, c.x + 2 >= count, (float(c.y) + float(c.z)) / float(subs),
            float(c.w), int(custom2.y + 0.5), custom0.z, custom0.w, custom2.z, anchor, eye);
}

// Where v's vertex sits with its width scaled by `scale`: a shader's own taper along the trail, or a blade's reach.
vec3 fx_trail_place(FxTrailVertex v, float scale) {
    return v.centre + v.span * (v.reach * scale);
}

// How squarely the strip faces the eye from v: 0 edge-on, 1 face-on. A blade fades by it rather than show a line.
float fx_trail_facing(FxTrailVertex v, vec3 eye) {
    vec3 n = cross(v.span, v.tangent);
    float l = length(n);
    return l > 1.0e-8 ? abs(dot(n / l, normalize(eye - v.centre))) : 1.0;
}

// The ki streak's look (trail/glow.shader, particle/streaks.shader, particle/ribbon.shader): white-hot down its middle
// to its colour at soft edges, rounded at its head, energy flowing back along it in streaks and eaten away toward its
// tail as it fades.
struct FxGlow {
    vec3 color;       // its energy, times its strength
    vec3 core;        // its white-hot middle
    float coreWidth;  // the middle's share of the width
    float softness;   // higher thins its edges
    float fade;       // higher fades it sooner along its life
    vec2 tiling;      // noise cells per unit along it, and across it
    float flow;       // how fast its energy flows back, units of along a second
    float erode;      // how much its tail is eaten away, 0..1
};

// trail: life 0..1, across -1..1, along, back from the head; halfWidth in along's units (its head's round cap);
// noise the engine's gradient noise volume. Answers its light, added, and puts its hot part in glow, for CG_GLOW.
vec3 fx_trail_glow(FxGlow g, sampler3D noise, vec4 trail, float halfWidth, float time, float seed, out vec3 glow) {
    glow = vec3(0.0);
    float u = trail.x, across = abs(trail.y);
    // Rounded where it leaves its thing: within its first half-width the strip closes as a circle does.
    float cap = clamp(trail.w / max(halfWidth, 1.0e-3), 0.0, 1.0);
    float r = across / max(sqrt(1.0 - (1.0 - cap) * (1.0 - cap)), 1.0e-3);
    if (r >= 1.0) return vec3(0.0);
    float core = exp(-r * r / (g.coreWidth * g.coreWidth) * 2.0);
    float body = pow(1.0 - r, g.softness);
    vec3 q = vec3(trail.z * g.tiling.x - time * g.flow * g.tiling.x, trail.y * g.tiling.y, seed * 9.0);
    float n = 0.5 + 0.5 * cg_fbm3(noise, q, 3);
    float streak = pow(cg_ridged3(noise, vec3(q.x * 0.6, q.y * 2.2, seed * 5.0 + time * 0.7), 3), 4.0);
    float e = body * (0.55 + 0.45 * n) + streak * 0.45 * (1.0 - r) - u * u * g.erode;
    float strength = smoothstep(0.0, 0.3, e) * pow(1.0 - u, g.fade) * fx_flicker_volume(noise, time, seed);
    float hot = clamp(core * 1.15 + streak * 0.5 - u * 0.6, 0.0, 1.0);
    glow = (g.core * hot + g.color * body * 0.35) * strength;
    return mix(g.color * (0.65 + 0.55 * n), g.core, hot) * strength;
}
