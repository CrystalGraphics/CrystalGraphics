// A CgVfxTrail's strip, built per vertex from its points as CgVfxTrailTexture lays them out: centripetal Catmull-Rom
// between them (Barry and Goldman's pyramid), so a fast swing stays round; its head where the thing is this frame; its
// tail cut exactly where a point's age reaches the trail's life, so it shrinks smoothly rather than dropping points.
// A trail shader has no vertex data (#type none) and calls fx_trail_vertex once per vertex:
//
//     Properties { _FxTrail ("Trail points", sampler2D) = "black" }
//     FxTrailVertex v = fx_trail_vertex(_FxTrail, CG_VERTEX_ID, CG_OBJECT_CUSTOM0, CG_OBJECT_CUSTOM2,
//                                       CG_OBJECT_TO_WORLD[3].xyz - CG_OBJECT_CUSTOM1.xyz, FX_CAMERA);
//     vec3 world = fx_trail_place(v, taper);   // its width times the shader's own taper; v.world is it at 1
//     gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(world, 1.0);
//
// custom0 is (first texel, points, the layer's size, life in seconds); custom2 (sub-segments a segment, mode, the head's
// distance from where the trail began, the trail's length). Nothing here names cg_* or CG_*.
#pragma once

// CgVfxTrailTexture.WIDTH, texels a row: a trail's points run on across rows.
#define FX_TRAIL_ROW 1024
#define FX_TRAIL_CAMERA 0
#define FX_TRAIL_AXIS 1
#define FX_TRAIL_BLADE 2

struct FxTrailPoint {
    vec3 a;          // the point, or a blade's base: from the anchor
    float age;       // seconds
    vec3 b;          // a blade's tip from the anchor; an axis trail's facing; else 0
    float along;     // blocks from where the trail began
    vec4 extra;      // its width and intensity scales, and the two numbers the effect gave it
};

struct FxTrailVertex {
    vec3 world;      // camera-relative: fx_trail_place(v, 1.0)
    vec3 centre;     // the strip's middle here (a blade's base), camera-relative
    vec3 span;       // from the centre to this vertex's edge at full width (a blade's: to the tip, times its size)
    vec3 tangent;    // along the strip toward its head, unit
    float reach;     // this vertex's share of span: -1 or 1 for a ribbon, 0 or 1 for a blade
    vec2 uv;         // x 0 at the head to 1 at the trail's life (its age over life); y 0 to 1 across (blade: base to tip)
    float side;      // -1 to 1 across: 0 down the middle of a ribbon
    float fromHead;  // blocks back from the head
    float along;     // blocks from where the trail began: what a texture tiled along it is anchored to
    float age;       // seconds
    vec4 extra;      // width and intensity scales, and the effect's two numbers
    float alive;     // 0 for a vertex past the trail's points: its triangles are degenerate
};

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

// A unit vector across tangent t facing the eye from p; where the eye looks straight down the trail, any across
// it, so the strip narrows to its true width on screen rather than flipping.
vec3 fx_trail_across(vec3 t, vec3 toEye) {
    vec3 across = cross(t, toEye);
    float l = length(across);
    if (l > 1.0e-5 * length(toEye)) return across / l;
    vec3 helper = abs(t.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    return normalize(cross(t, helper));
}

// Vertex `id` of the strip: six a quad, `custom2.x` quads a segment between two points, its corners as CgVfxRibbons'
// (bit k of 0x34 at the quad's far end, of 0x1A on side 1). anchor is where the points are from, camera-relative.
FxTrailVertex fx_trail_vertex(sampler2D points, int id, vec4 custom0, vec4 custom2, vec3 anchor, vec3 eye) {
    FxTrailVertex v;
    int first = int(custom0.x + 0.5), count = int(custom0.y + 0.5), subs = max(int(custom2.x + 0.5), 1);
    int mode = int(custom2.y + 0.5);
    float size = custom0.z, life = custom0.w;
    int quad = id / 6, corner = id - quad * 6;
    int segment = quad / subs, sub = quad - segment * subs;
    float far = float((0x34 >> corner) & 1), side = float((0x1A >> corner) & 1);
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
    if (segment >= count - 1) return v;
    FxTrailPoint p1 = fx_trail_point(points, first, segment), p2 = fx_trail_point(points, first, segment + 1);
    if (p1.age >= life) return v;
    // Mirrored past either end, so the spline ends heading straight at its last point.
    FxTrailPoint p0 = segment > 0 ? fx_trail_point(points, first, segment - 1) : p1;
    FxTrailPoint p3 = segment + 2 < count ? fx_trail_point(points, first, segment + 2) : p2;
    if (segment == 0) { p0.a = 2.0 * p1.a - p2.a; p0.b = 2.0 * p1.b - p2.b; }
    if (segment + 2 >= count) { p3.a = 2.0 * p2.a - p1.a; p3.b = 2.0 * p2.b - p1.b; }
    float t = (float(sub) + far) / float(subs);
    // The tail: cut where the age reaches life, between the last point alive and the first past it.
    if (p2.age > life) t = min(t, (life - p1.age) / max(p2.age - p1.age, 1.0e-6));
    vec3 c = fx_trail_spline(p0.a, p1.a, p2.a, p3.a, t);
    v.age = mix(p1.age, p2.age, t);
    v.along = mix(p1.along, p2.along, t);
    v.extra = mix(p1.extra, p2.extra, t);
    v.fromHead = custom2.z - v.along;
    v.uv = vec2(clamp(v.age / max(life, 1.0e-4), 0.0, 1.0), side);
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
        vec3 n = mix(p1.b, p2.b, t);
        across = cross(tangent, n);
        float l = length(across);
        across = l > 1.0e-6 ? across / l : fx_trail_across(tangent, eye - anchor - c);
    } else {
        across = fx_trail_across(tangent, eye - anchor - c);
    }
    v.span = across * (size * v.extra.x);
    v.reach = v.side;
    v.world = v.centre + v.span * v.reach;
    return v;
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
