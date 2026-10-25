// The vertex factory a kit's material program reads (Unreal's FMaterialVertexParameters and
// FMaterialParticleParameters, MaterialTemplate.ush): what cg_use particle gives a port beside the records.
//
//     void vertex(out v2f o) {
//         CgMaterialVertex v = cg_material_vertex();        // placed by the factory, under the renderer's settings
//         CgMaterialParticle p = cg_material_particle();
//         v.worldPosition += wpo(v);                         // the material's World Position Offset, if any
//         o.uv = v.texCoords[0];
//         o.color = p.color;
//         gl_Position = cg_material_project(v);
//     }
//
// The factory is the program's #type: none is the sprite factory (Unreal's sprite vertex factory), spatial the mesh
// factory (its mesh particle vertex factory). A port never places a vertex: facing, stretch, camera offset and the
// flipbook are the renderer's settings, the _cg_ row properties this token declares. The accessors are macros, so
// they expand where a port calls them, after its properties exist.
#pragma once

struct CgMaterialVertex {
    vec3 worldPosition;      // camera-relative, blocks: Unreal's translated world position
    vec2 texCoords[4];       // sprite: frame a's, frame b's, then both unflipped (the same until a renderer flips)
    mat3 tangentToWorld;     // tangent, bitangent, normal; a sprite's normal faces the eye
    vec4 vertexColor;        // on a sprite, the particle's colour, as Unreal's
};

struct CgMaterialParticle {
    vec4 color;              // linear; alpha its opacity
    float relativeTime;      // age over lifetime, 0..1
    float random;            // 0..1, fixed for its life
    vec4 velocity;           // xyz its direction, w its speed in blocks a second
    vec4 positionAndSize;    // camera-relative centre, radius
    vec2 size;               // the sprite's width and height
    float spriteRotation;    // radians
    vec2 subUVCoords0, subUVCoords1;
    float subUVLerp;
    int record;              // its record: CG_PARTICLE_*(p.record) reads what Unreal's parameters do not name
    vec4 dynamicParameters[4];   // Niagara's DynamicMaterialParameter to _3, where dynamics has their bits
    int dynamics;
};
#define CG_MATERIAL_PARTICLE 1

// The drawing system's bounds, Unreal's Primitive.ObjectWorldPositionAndRadius: its centre, camera-relative, and its
// sphere's radius in blocks, either stage. The renderer writes its fixed bounds per effect, the centre absolute, since
// the camera moves between the row and the draw. A system with no fixed bounds is refused when it loads.
#define CG_OBJECT_POSITION (_cg_Bounds.xyz - cg_WorldOrigin.xyz)
#define CG_OBJECT_RADIUS (_cg_Bounds.w)

// Its dynamic parameter i (0 to 3), or d where its emitter sets none: Unreal's DynamicParameter node and its defaults.
#define cg_dynamic_parameter(p, i, d) ((((p).dynamics >> (i)) & 1) != 0 ? (p).dynamicParameters[i] : (d))

// The renderer's _cg_Drawn: bits 0 to 3 the dynamic parameters its particles carry, then the sprite's sub-image index
// and camera offset.
#define _CG_DRAWN_SUB_IMAGE 32
#define _CG_DRAWN_CAMERA_OFFSET 64

#define cg_material_project(v) (cg_ProjMatrix * cg_ViewMatrix * vec4((v).worldPosition, 1.0))

// The eye's place in the space worldPosition is in.
#define _CG_EYE (-(transpose(mat3(cg_ViewMatrix)) * cg_ViewMatrix[3].xyz))

// Cascade's camera offset (ParticleSystemRender.cpp's GetCameraOffset): offset blocks toward the eye, and a particle the
// offset would carry past the eye pushed far behind it, out of view.
vec3 _cg_toward_eye(vec3 centre, vec3 eye, float offset) {
    vec3 toEye = eye - centre;
    float d = max(length(toEye), 1.0e-4);
    return toEye / d * offset * (d > offset ? 1.0 : 1.0e6);
}

#ifdef CG_VERTEX_STAGE
// Particle n's dynamic parameters into p, those the renderer's drawn bits name.
void _cg_dynamics(int n, int drawn, inout CgMaterialParticle p) {
    p.dynamics = drawn & 15;
    p.dynamicParameters[0] = p.dynamicParameters[1] = p.dynamicParameters[2] = p.dynamicParameters[3] = vec4(0.0);
    if (p.dynamics != 0) {
        p.dynamicParameters[0] = CG_PARTICLE_DRAWN(n).place;
        p.dynamicParameters[1] = CG_PARTICLE_DRAWN(n).motion;
        p.dynamicParameters[2] = CG_PARTICLE_DRAWN(n).state;
        p.dynamicParameters[3] = CG_PARTICLE_DRAWN(n).light;
    }
}
#endif

// The factory follows the usage a program is compiled for, as Unreal compiles a material once per vertex factory
// its usages enable: a strip (FX_ON_RIBBONS, which every particle program takes) gets the strip factory, a sprite
// program (no vertex data, or FX_ON_SPRITES) the sprite factory, any other mesh's.
// The strip factory: Unreal's beam-and-trail vertex factory (ParticleBeamTrailVertexFactory), compiled where a draw
// enables FX_ON_RIBBONS, which every particle program takes. Each particle is a strip, placed from CG_VERTEX_ID, of the
// draw's kind; a beam (CgVfxBeamRendererProperties) is a run of its pool's PARTICLE_BEAMS (fx_beam.glsl's FX_BEAM_*)
// drawn as ParticleSystemRender.cpp's beam fills draw it. What CgVfxFrame passes:
//   CUSTOM0  the draw's first particle, its count, the renderer's size, sheets + 256 * segments a sheet
//   CUSTOM1  the slot's first run, its runs (its capacity), vec4s a run, noise points a run
//   CUSTOM2  interpolation points, flags (1 noise on the target, 2 one up for the whole beam, 4 tiles by distance), its
//            tiles or the blocks a tile, the noise's frequency
// An include is resolved once whatever #if it sits in, so what both factories read is included ahead of either.
#if defined(CG_VERTEX_STAGE) && (defined(FX_ON_RIBBONS) || defined(CG_TYPE_NONE) || defined(FX_ON_SPRITES) || defined(CG_TYPE_SPATIAL))
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_particle.glsl"
#endif
#if defined(CG_VERTEX_STAGE) && defined(FX_ON_RIBBONS)
#define FX_BEAM_RUN(at) CG_PARTICLE_BEAM(at)
#include "crystalgraphics:shaders/lib/vfx/fx_beam.glsl"

// One vertex of the draw's beam strips: the particle CG_VERTEX_ID falls in, its sheet, its point along and its side.
void _cg_beam(float cameraOffset, float drawnBits, out CgMaterialVertex v, out CgMaterialParticle p) {
    int drawn = int(drawnBits + 0.5);
    int sheets = int(mod(CG_OBJECT_CUSTOM0.w, 256.0) + 0.5), segments = int(CG_OBJECT_CUSTOM0.w / 256.0);
    int per = sheets * segments * 6, rank = CG_VERTEX_ID / per, local = CG_VERTEX_ID - rank * per;
    int sheet = local / (segments * 6), q = local - sheet * segments * 6, corner = q % 6;
    int k = q / 6 + ((0x34 >> corner) & 1);
    float side = ((0x1A >> corner) & 1) == 0 ? 1.0 : -1.0;
    int n = fx_particle_index(float(rank), CG_OBJECT_CUSTOM0.x, CG_OBJECT_CUSTOM0.y), i = max(n, 0);
    int id = int(CG_PARTICLE_ID(i) + 0.5);
    int at = int(CG_OBJECT_CUSTOM1.x + 0.5) + (id % max(int(CG_OBJECT_CUSTOM1.y + 0.5), 1)) * int(CG_OBJECT_CUSTOM1.z + 0.5);
    vec4 c2 = CG_OBJECT_CUSTOM2;
    int flags = int(c2.y + 0.5);
    vec3 origin = CG_OBJECT_TO_WORLD[3].xyz, eye = _CG_EYE;
    vec3 end = CG_PARTICLE_POSITION(i);
    // How many of its segments this beam fills: the rest fold onto its last point.
    vec4 tag = FX_BEAM_RUN(at + FX_BEAM_TAG);
    int noisePoints = int(CG_OBJECT_CUSTOM1.w + 0.5), filled = fx_beam_filled(at, noisePoints, segments);
    vec3 here = origin + fx_beam_point(at, min(k, filled), filled, noisePoints, end, c2);
    int from = (flags & 2) != 0 ? 0 : max(min(k, filled) - 1, 0), to = (flags & 2) != 0 ? 1 : max(min(k, filled), 1);
    vec3 location = origin + fx_beam_point(at, from, filled, noisePoints, end, c2);
    vec3 right = fx_beam_unit(location - (origin + fx_beam_point(at, to, filled, noisePoints, end, c2)), vec3(1.0, 0.0, 0.0));
    vec3 up = fx_beam_unit(cross(right, location - eye), vec3(cg_ViewMatrix[0][1], cg_ViewMatrix[1][1], cg_ViewMatrix[2][1]));
    if (sheet > 0) {
        float angle = 3.14159265 / float(sheets) * float(sheet);
        up = fx_beam_turn(vec4(right * sin(angle * 0.5), cos(angle * 0.5)), up);
    }
    float size = n < 0 ? 0.0 : CG_PARTICLE_SIZE(i) * CG_PARTICLE_SCALE(i).x * CG_OBJECT_CUSTOM0.z;
    float u;
    if (int(CG_OBJECT_CUSTOM1.w + 0.5) > 0) {
        u = float(min(k, filled)) / (max(c2.w, 1.0) * max(tag.z, 1.0));
    } else {
        float tiles = (flags & 4) != 0 ? length(FX_BEAM_RUN(at + FX_BEAM_TARGET).xyz - FX_BEAM_RUN(at + FX_BEAM_SOURCE).xyz) / max(c2.z, 1e-4)
                : max(c2.z, 1.0);
        u = tiles * float(min(k, filled)) / float(max(filled, 1));
    }
    vec2 uv = vec2(u, side > 0.0 ? 0.0 : 1.0);
    vec4 color = CG_PARTICLE_COLOR(i);
    here += _cg_toward_eye(here, eye, cameraOffset);

    v.worldPosition = here + up * size * side;
    v.texCoords[0] = uv;
    v.texCoords[1] = uv;
    v.texCoords[2] = uv;
    v.texCoords[3] = uv;
    v.tangentToWorld = mat3(-right, up, normalize(cross(-right, up)));
    v.vertexColor = vec4(color.rgb, color.a * CG_PARTICLE_OPACITY(i));

    vec3 velocity = CG_PARTICLE_VELOCITY(i);
    p.color = v.vertexColor;
    p.relativeTime = CG_PARTICLE_PROGRESS(i);
    p.random = CG_PARTICLE_SEED(i);
    p.velocity = vec4(length(velocity) > 0.0 ? normalize(velocity) : vec3(0.0), length(velocity));
    p.positionAndSize = vec4(origin + end, size);
    p.size = vec2(size);
    p.spriteRotation = 0.0;
    p.subUVCoords0 = uv;
    p.subUVCoords1 = uv;
    p.subUVLerp = 0.0;
    p.record = i;
    _cg_dynamics(i, drawn, p);
}

// A ribbon draw (CgVfxRibbonGeneration): CUSTOM0.w is -1 - its mode, its run at PARTICLE_BEAMS.
//   CUSTOM0  its pool's first place in Range's order, its slot's record in the run, the renderer's size, -1 - mode
//            (1 a Cascade trail, 2 normals turned to the eye, 4 x UV0's distribution, 16 x UV1's)
//   CUSTOM1  its shape (0 plane, 1 multi-plane, 2 tube), its segments or sides, its planes, its slice's edges
//   CUSTOM2  V0's scale and offset, V1's: Niagara's PackedVData
#define _CG_RIBBON(at) CG_PARTICLE_BEAM(at)

// FMath::CubicInterp, a Hermite curve: its tangents scaled by ts, Niagara's segment length, 1 for Cascade's.
vec3 _cg_ribbon_cubic(vec3 p0, vec3 t0, vec3 p1, vec3 t1, float a, float ts) {
    float a2 = a * a, a3 = a2 * a;
    return (2.0 * a3 - 3.0 * a2 + 1.0) * p0 + ts * (a3 - 2.0 * a2 + a) * t0 + ts * (a3 - a2) * t1 + (-2.0 * a3 + 3.0 * a2) * p1;
}

vec3 _cg_ribbon_unit(vec3 v, vec3 fallback) {
    float l = dot(v, v);
    return l > 0.0 ? v * inversesqrt(l) : fallback;
}

// Slice vertex k (GenerateShapeState*, NiagaraRendererRibbons.cpp:1064-1242): where it lies across the ribbon, its
// normal, and its V.
void _cg_ribbon_slice(int shape, int segments, int planes, int k, out vec2 at, out vec2 normal, out float v) {
    if (shape == 2) {
        float angle = radians(-360.0 * float(k) / float(segments)), c = cos(angle), s = sin(angle);
        at = vec2(-0.5 * c, -0.5 * s);
        normal = vec2(-c, -s);
        v = float(k) / float(segments);
        return;
    }
    int plane = k / (segments + 1), j = k - plane * (segments + 1);
    float angle = radians(float(plane) / float(planes) * 180.0), c = cos(angle), s = sin(angle);
    float x = float(j) / float(segments) - 0.5;
    at = vec2(x * c, x * s);
    normal = vec2(-s, c);
    v = float(j) / float(segments);
}

// One vertex of a ribbon draw: its unit from CG_VERTEX_ID as Niagara's GenerateIndices packs one, the position whose
// segment holds that unit, then placed as Niagara's ribbon factory places it (NiagaraRibbonVertexFactory.ush:315-831)
// or as Cascade's trail fill does (ParticleSystemRender.cpp, FDynamicRibbonEmitterData::FillVertexData).
// GCatmullRomEndParamOffset, 0.1 in square-rooted Unreal units: an anim trail's parameter past its ends, in
// square-rooted blocks.
const float _cg_CatmullRomEnd = 0.01;

// FMath::CubicCRSplineInterpSafe (UnrealMathUtility.h:888-915): the Barry-Goldman pyramid, P1 where two knots meet.
vec4 _cg_cr4(vec4 p0, vec4 p1, vec4 p2, vec4 p3, float t0, float t1, float t2, float t3, float t) {
    float a = t1 - t0, b = t2 - t1, c = t3 - t2, d = t2 - t0, e = t3 - t1;
    if (abs(a) < 1.0e-8 || abs(b) < 1.0e-8 || abs(c) < 1.0e-8 || abs(d) < 1.0e-8 || abs(e) < 1.0e-8) return p1;
    vec4 l01 = p0 * ((t1 - t) / a) + p1 * ((t - t0) / a);
    vec4 l12 = p1 * ((t2 - t) / b) + p2 * ((t - t1) / b);
    vec4 l23 = p2 * ((t3 - t) / c) + p3 * ((t - t2) / c);
    vec4 l012 = l01 * ((t2 - t) / d) + l12 * ((t - t0) / d);
    vec4 l123 = l12 * ((t3 - t) / e) + l23 * ((t - t1) / e);
    return l012 * ((t2 - t) / b) + l123 * ((t - t1) / b);
}

vec3 _cg_cr3(vec3 p0, vec3 p1, vec3 p2, vec3 p3, float t0, float t1, float t2, float t3, float t) {
    return _cg_cr4(vec4(p0, 0.0), vec4(p1, 0.0), vec4(p2, 0.0), vec4(p3, 0.0), t0, t1, t2, t3, t).xyz;
}

float _cg_cr1(float p0, float p1, float p2, float p3, float t0, float t1, float t2, float t3, float t) {
    return _cg_cr4(vec4(p0), vec4(p1), vec4(p2), vec4(p3), t0, t1, t2, t3, t).x;
}

// Piecewise linear through four keys, times then values; past the ends, the end's.
float _cg_along1(vec4 t, vec4 v, float x) {
    if (x <= t.x) return v.x;
    if (x <= t.y) return mix(v.x, v.y, (x - t.x) / (t.y - t.x));
    if (x <= t.z) return mix(v.y, v.z, (x - t.y) / (t.z - t.y));
    if (x <= t.w) return mix(v.z, v.w, (x - t.z) / (t.w - t.z));
    return v.w;
}

vec4 _cg_along4(vec4 t, vec4 v0, vec4 v1, vec4 v2, vec4 v3, float x) {
    if (x <= t.x) return v0;
    if (x <= t.y) return mix(v0, v1, (x - t.x) / (t.y - t.x));
    if (x <= t.z) return mix(v1, v2, (x - t.y) / (t.z - t.y));
    if (x <= t.w) return mix(v2, v3, (x - t.z) / (t.w - t.z));
    return v3;
}

void _cg_ribbon(float cameraOffset, float drawnBits, out CgMaterialVertex v, out CgMaterialParticle p) {
    int drawn = int(drawnBits + 0.5);
    int mode = int(-CG_OBJECT_CUSTOM0.w + 0.5) - 1;
    bool trail = (mode & 1) != 0;
    int slot = int(CG_OBJECT_CUSTOM0.y + 0.5);
    vec4 s0 = _CG_RIBBON(slot), s1 = _CG_RIBBON(slot + 1);
    int positionAt = int(s1.z + 0.5), ribbonAt = int(s1.w + 0.5);
    int shape = int(CG_OBJECT_CUSTOM1.x + 0.5), segments = int(CG_OBJECT_CUSTOM1.y + 0.5);
    int planes = int(CG_OBJECT_CUSTOM1.z + 0.5), edges = int(CG_OBJECT_CUSTOM1.w + 0.5);
    int per = edges * 6, quad = CG_VERTEX_ID / per, local = CG_VERTEX_ID - quad * per;
    int edge = local / 6, corner = local - edge * 6;
    int factor = trail ? 1 : max(int(s0.z + 0.5), 1);
    int unit = quad / factor, sub = quad - unit * factor;
    float target = s0.x + float(unit);
    // The last position with at most the target's units before it.
    int lo = int(s1.x + 0.5), hi = int(s1.y + 0.5);
    while (lo < hi) {
        int mid = (lo + hi + 1) >> 1;
        if (_CG_RIBBON(positionAt + mid * 4 + 1).y <= target) lo = mid; else hi = mid - 1;
    }
    int q = lo;
    vec4 a0 = _CG_RIBBON(positionAt + q * 4), a1 = _CG_RIBBON(positionAt + q * 4 + 1);
    vec4 b0 = _CG_RIBBON(positionAt + q * 4 + 4), b1 = _CG_RIBBON(positionAt + q * 4 + 5);
    bool next = ((0x34 >> corner) & 1) != 0;
    // The first half of a slice's edges wind one way and the rest the other (GenerateIndices' FlipGeometryIndex).
    bool second = (((2 * edge < edges ? 0x1A : 0x16) >> corner) & 1) != 0;
    float k = target - a1.y;
    float alpha = trail ? (k + (next ? 1.0 : 0.0)) / max(a1.w, 1.0) : (float(sub) + (next ? 1.0 : 0.0)) * s0.w;
    int n0 = int(CG_OBJECT_CUSTOM0.x + a1.x + 0.5), n1 = int(CG_OBJECT_CUSTOM0.x + b1.x + 0.5);
    bool live = unit < int(s0.y + 0.5) && a1.x >= 0.0 && b1.x >= 0.0;
    n0 = live ? n0 : 0;
    n1 = live ? n1 : 0;
    vec3 origin = CG_OBJECT_TO_WORLD[3].xyz, eye = _CG_EYE;
    vec3 p0 = CG_PARTICLE_POSITION(n0), p1 = CG_PARTICLE_POSITION(n1);
    float layer = CG_OBJECT_CUSTOM0.z;
    float w0 = CG_PARTICLE_SIZE(n0) * CG_PARTICLE_SCALE(n0).x * layer, w1 = CG_PARTICLE_SIZE(n1) * CG_PARTICLE_SCALE(n1).x * layer;
    float width = live ? mix(w0, w1, alpha) : 0.0;
    int r = int(a1.z + 0.5);
    vec4 r0 = _CG_RIBBON(ribbonAt + r * 2), r1 = _CG_RIBBON(ribbonAt + r * 2 + 1);
    vec3 here, direction, normalZ, sideX, offset;
    vec2 uv0, uv1;
    vec4 color = mix(CG_PARTICLE_COLOR(n0), CG_PARTICLE_COLOR(n1), alpha);
    float opacity = mix(CG_PARTICLE_OPACITY(n0), CG_PARTICLE_OPACITY(n1), alpha);
    if ((mode & 256) != 0) {
        // Cascade's anim trail (FAnimTrailParticleRenderData::CalcVertexData): a centripetal Catmull-Rom through the
        // samples either side, from the older (its Curr) toward the newer (its Prev); its edge either side.
        int first = int(r0.w + 0.5), last = int(r1.w + 0.5);
        bool hasPP = q - 1 >= first, hasNext = q + 2 <= last;
        vec4 c0 = _CG_RIBBON(positionAt + q * 4 + 2), c1 = _CG_RIBBON(positionAt + q * 4 + 6);
        vec4 cpp = hasPP ? _CG_RIBBON(positionAt + (q - 1) * 4 + 2) : c0;
        vec4 cnx = hasNext ? _CG_RIBBON(positionAt + (q + 2) * 4 + 2) : c1;
        int npp = hasPP ? int(CG_OBJECT_CUSTOM0.x + _CG_RIBBON(positionAt + (q - 1) * 4 + 1).x + 0.5) : n0;
        int nnx = hasNext ? int(CG_OBJECT_CUSTOM0.x + _CG_RIBBON(positionAt + (q + 2) * 4 + 1).x + 0.5) : n1;
        npp = live ? npp : 0;
        nnx = live ? nnx : 0;
        float currT = hasNext ? _CG_RIBBON(positionAt + q * 4 + 7).x : _cg_CatmullRomEnd;
        float prevT = currT + _CG_RIBBON(positionAt + q * 4 + 3).x;
        float ppT = prevT + (hasPP ? _CG_RIBBON(positionAt + (q - 1) * 4 + 3).x : _cg_CatmullRomEnd);
        float f = 1.0 - alpha, t = currT + (prevT - currT) * f;
        vec3 pp = CG_PARTICLE_POSITION(npp), nx = CG_PARTICLE_POSITION(nnx);
        vec3 at = f == 0.0 ? p1 : f == 1.0 ? p0 : _cg_cr3(pp, p0, p1, nx, ppT, prevT, currT, 0.0, t);
        vec3 dir = _cg_ribbon_unit(f == 0.0 ? c1.xyz : f == 1.0 ? c0.xyz : _cg_cr3(_cg_ribbon_unit(cpp.xyz, vec3(0.0)),
                _cg_ribbon_unit(c0.xyz, vec3(0.0)), _cg_ribbon_unit(c1.xyz, vec3(0.0)), _cg_ribbon_unit(cnx.xyz, vec3(0.0)),
                ppT, prevT, currT, 0.0, t), vec3(0.0));
        float edge = f == 0.0 ? length(c1.xyz) : f == 1.0 ? length(c0.xyz)
                : _cg_cr1(length(cpp.xyz), length(c0.xyz), length(c1.xyz), length(cnx.xyz), ppT, prevT, currT, 0.0, t);
        float tiled = f == 0.0 ? c1.w : f == 1.0 ? c0.w : _cg_cr1(cpp.w, c0.w, c1.w, cnx.w, ppT, prevT, currT, 0.0, t);
        if (f > 0.0 && f < 1.0) {
            vec4 cc = _cg_cr4(CG_PARTICLE_COLOR(npp), CG_PARTICLE_COLOR(n0), CG_PARTICLE_COLOR(n1), CG_PARTICLE_COLOR(nnx), ppT, prevT, currT, 0.0, t);
            color = cc;
            opacity = _cg_cr1(CG_PARTICLE_OPACITY(npp), CG_PARTICLE_OPACITY(n0), CG_PARTICLE_OPACITY(n1), CG_PARTICLE_OPACITY(nnx),
                    ppT, prevT, currT, 0.0, t);
        }
        // Width and colour along its length, newest 0, and its fade.
        float distance = mix(a0.w, b0.w, alpha), total = _CG_RIBBON(positionAt + last * 4).w;
        float along = total > 0.0 ? distance / total : 0.0;
        vec4 fade = _CG_RIBBON(slot + 9);
        float widthScale = _cg_along1(_CG_RIBBON(slot + 2), _CG_RIBBON(slot + 3), along) * fade.y;
        vec4 tint = _cg_along4(_CG_RIBBON(slot + 4), _CG_RIBBON(slot + 5), _CG_RIBBON(slot + 6), _CG_RIBBON(slot + 7),
                _CG_RIBBON(slot + 8), along);
        color.rgb *= tint.rgb;
        opacity *= tint.a * fade.x;
        here = origin + at;
        offset = dir * edge * widthScale * (second ? 1.0 : -1.0);
        width = live ? edge * widthScale : 0.0;
        direction = _cg_ribbon_unit(p0 - p1, vec3(1.0, 0.0, 0.0));
        sideX = _cg_ribbon_unit(dir, vec3(0.0, 1.0, 0.0));
        normalZ = _cg_ribbon_unit(cross(direction, sideX), vec3(0.0, 0.0, 1.0));
        int u0Mode = (mode >> 2) & 3;
        float u0 = u0Mode == 0 ? (float(q) - r0.w + alpha) * r0.z : u0Mode == 3 ? 0.0 : distance * r0.z;
        uv0 = vec2(u0, second ? 1.0 : 0.0) * vec2(r0.x, CG_OBJECT_CUSTOM2.x) + vec2(r0.y, CG_OBJECT_CUSTOM2.y);
        // Tex_U2: the tiled U where it tiles by distance, else U.
        uv1 = vec2((mode & 128) != 0 ? tiled : uv0.x, uv0.y);
    } else if (trail) {
        // Cascade walks from the older particle (its Curr) to the newer (its Prev), t 0 at the older.
        here = origin + _cg_ribbon_cubic(p1, b0.xyz, p0, a0.xyz, 1.0 - alpha, 1.0);
        direction = _cg_ribbon_unit(mix(a0.xyz, b0.xyz, alpha), vec3(1.0, 0.0, 0.0));
        // Trails_CameraUp: each particle's tangent crossed with the direction from the eye, lerped unnormalized; where
        // they are parallel, the camera's view vector, Cascade's CameraUp. Source and world up: the up stored at birth.
        vec4 c0 = _CG_RIBBON(positionAt + q * 4 + 2), c1 = _CG_RIBBON(positionAt + q * 4 + 6);
        vec3 up0 = c0.xyz, up1 = c1.xyz;
        if ((mode & 64) == 0) {
            vec3 view = -vec3(cg_ViewMatrix[0][2], cg_ViewMatrix[1][2], cg_ViewMatrix[2][2]);
            up0 = _cg_ribbon_unit(cross(_cg_ribbon_unit(a0.xyz, vec3(0.0)), _cg_ribbon_unit(origin + p0 - eye, vec3(0.0))), view);
            up1 = _cg_ribbon_unit(cross(_cg_ribbon_unit(b0.xyz, vec3(0.0)), _cg_ribbon_unit(origin + p1 - eye, vec3(0.0))), view);
        }
        vec3 up = mix(up0, up1, alpha);
        offset = up * width * (second ? -1.0 : 1.0);
        // U runs uniformly over the trail's rendered points; V is 0 on the up side.
        float u = (a1.y - r0.y + k + (next ? 1.0 : 0.0)) * r0.x;
        uv0 = vec2(u, second ? 1.0 : 0.0);
        // Tex_U2: the tiled U lerped between the particles where the trail tiles by distance, else U.
        uv1 = vec2((mode & 128) != 0 ? mix(c0.w, c1.w, alpha) : u, uv0.y);
        sideX = _cg_ribbon_unit(up, vec3(0.0, 1.0, 0.0));
        normalZ = _cg_ribbon_unit(cross(direction, sideX), vec3(0.0, 0.0, 1.0));
    } else {
        int plane = shape == 1 ? edge / segments : 0;
        int vertex = plane * (segments + 1) + (edge - plane * segments) + (second ? 1 : 0);
        vec2 slice, sliceNormal;
        float sliceV;
        _cg_ribbon_slice(shape, segments, planes, vertex, slice, sliceNormal, sliceV);
        here = origin + _cg_ribbon_cubic(p0, a0.xyz, p1, b0.xyz, alpha, b0.w - a0.w);
        direction = _cg_ribbon_unit(mix(a0.xyz, b0.xyz, alpha), vec3(0.0));
        float distance = mix(a0.w, b0.w, alpha);
        vec3 facing = _cg_ribbon_unit(eye - here, vec3(0.0));
        vec3 right = _cg_ribbon_unit(cross(facing, direction), vec3(0.0));
        vec3 geoUp = cross(direction, right);
        vec3 z = _cg_ribbon_unit((shape >= 1 ? geoUp : facing) * sliceNormal.y + right * sliceNormal.x, vec3(0.0, 0.0, 1.0));
        if ((mode & 2) != 0 && dot(facing, z) < 0.0) z = -z;
        normalZ = z;
        sideX = cross(z, direction);
        offset = (right * slice.x + geoUp * slice.y) * width;
        int u0Mode = (mode >> 2) & 3, u1Mode = (mode >> 4) & 3;
        float along = float(q) - r0.w + alpha;
        // Tiled from the start reads Niagara's RibbonUVDistance, which no particle carries: 0, as Niagara draws it.
        float u0 = u0Mode == 0 ? along * r0.z : u0Mode == 3 ? 0.0 : distance * r0.z;
        float u1 = u1Mode == 0 ? along * r1.z : u1Mode == 3 ? 0.0 : distance * r1.z;
        uv0 = vec2(u0, sliceV) * vec2(r0.x, CG_OBJECT_CUSTOM2.x) + vec2(r0.y, CG_OBJECT_CUSTOM2.y);
        uv1 = vec2(u1, sliceV) * vec2(r1.x, CG_OBJECT_CUSTOM2.z) + vec2(r1.y, CG_OBJECT_CUSTOM2.w);
    }
    here += _cg_toward_eye(here, eye, cameraOffset);

    v.worldPosition = live ? here + offset : origin;
    v.texCoords[0] = uv0;
    v.texCoords[1] = uv1;
    v.texCoords[2] = uv0;
    v.texCoords[3] = uv1;
    v.tangentToWorld = mat3(sideX, direction, normalZ);
    v.vertexColor = vec4(color.rgb, color.a * opacity);

    vec3 velocity = mix(CG_PARTICLE_VELOCITY(n0), CG_PARTICLE_VELOCITY(n1), alpha);
    int i = alpha < 0.5 ? n0 : n1;
    p.color = v.vertexColor;
    p.relativeTime = mix(CG_PARTICLE_PROGRESS(n0), CG_PARTICLE_PROGRESS(n1), alpha);
    p.random = mix(CG_PARTICLE_SEED(n0), CG_PARTICLE_SEED(n1), alpha);
    p.velocity = vec4(length(velocity) > 0.0 ? normalize(velocity) : vec3(0.0), length(velocity));
    p.positionAndSize = vec4(here, trail ? width : 0.5 * width);
    p.size = vec2(trail ? 2.0 * width : width);
    p.spriteRotation = 0.0;
    p.subUVCoords0 = uv0;
    p.subUVCoords1 = uv0;
    p.subUVLerp = 0.0;
    p.record = i;
    _cg_dynamics(i, drawn, p);
}

// A strip draw: a ribbon's (CUSTOM0.w below 0) or a beam's.
void _cg_strip(float cameraOffset, float drawnBits, out CgMaterialVertex v, out CgMaterialParticle p) {
    if (CG_OBJECT_CUSTOM0.w < 0.0) _cg_ribbon(cameraOffset, drawnBits, v, p);
    else _cg_beam(cameraOffset, drawnBits, v, p);
}

CgMaterialVertex _cg_strip_vertex(float cameraOffset, float drawnBits) {
    CgMaterialVertex v;
    CgMaterialParticle p;
    _cg_strip(cameraOffset, drawnBits, v, p);
    return v;
}

CgMaterialParticle _cg_strip_particle(float cameraOffset, float drawnBits) {
    CgMaterialVertex v;
    CgMaterialParticle p;
    _cg_strip(cameraOffset, drawnBits, v, p);
    return p;
}

#define cg_material_vertex() _cg_strip_vertex(_cg_CameraOffset, _cg_Drawn)
#define cg_material_particle() _cg_strip_particle(_cg_CameraOffset, _cg_Drawn)
#elif defined(CG_VERTEX_STAGE) && (defined(CG_TYPE_NONE) || defined(FX_ON_SPRITES))

// One quad corner of the draw's particle: the placement every sprite of the vfx engine shares.
void _cg_sprite(float facing, vec4 facingVector, vec4 alignmentVector, float stretch, float maxStretch,
                float cameraOffset, float rotationBias, vec4 subImage, vec4 subUv, sampler2D subImages, float drawnBits,
                vec4 pivot, out CgMaterialVertex v, out CgMaterialParticle p) {
    int drawn = int(drawnBits + 0.5);
    int n = fx_particle_index(FX_QUAD_INDEX, CG_OBJECT_CUSTOM0.x, CG_OBJECT_CUSTOM0.y);
    vec2 corner = FX_QUAD_CORNER;
    vec3 origin = CG_OBJECT_TO_WORLD[3].xyz - CG_OBJECT_CUSTOM1.xyz;
    vec3 eyeRight = vec3(cg_ViewMatrix[0][0], cg_ViewMatrix[1][0], cg_ViewMatrix[2][0]);
    vec3 eyeUp = vec3(cg_ViewMatrix[0][1], cg_ViewMatrix[1][1], cg_ViewMatrix[2][1]);
    vec3 eye = _CG_EYE;
    int i = max(n, 0);
    vec3 velocity = CG_PARTICLE_VELOCITY(i);
    float size = n < 0 ? 0.0 : CG_PARTICLE_SIZE(i) * CG_OBJECT_CUSTOM0.z;
    vec3 centre = origin + CG_PARTICLE_POSITION(i);
    vec2 spriteDrawn = (drawn & (_CG_DRAWN_SUB_IMAGE | _CG_DRAWN_CAMERA_OFFSET)) != 0 ? CG_PARTICLE_DRAWN(i).color.xy : vec2(0.0);
    if ((drawn & _CG_DRAWN_CAMERA_OFFSET) != 0) cameraOffset = spriteDrawn.y;
    centre += _cg_toward_eye(centre, eye, cameraOffset);
    int mode = int(facing + 0.5), alignment = mode / 8;
    vec3 right, up;
    fx_particle_facing(mode - alignment * 8, alignment, centre, velocity, facingVector.xyz, alignmentVector.xyz, eye,
                       eyeRight, eyeUp, right, up);
    bool aligned = alignment == FX_ALIGN_VELOCITY;
    float length_ = aligned ? clamp(1.0 + length(velocity) * stretch, 1.0, maxStretch) : 1.0;
    vec2 extent = size * CG_PARTICLE_SCALE(i).xy * vec2(1.0, length_);
    float spin = aligned ? 0.0 : CG_PARTICLE_SPIN(i) + rotationBias;
    float a, b, blend;
    fx_subuv_frames(subUv, subImage, CG_PARTICLE_AGE(i), CG_PARTICLE_PROGRESS(i), CG_PARTICLE_SEED(i), spriteDrawn.x, a, b,
                    blend);
    vec2 uvA = fx_subimage_uv(subImages, subImage, a, corner), uvB = fx_subimage_uv(subImages, subImage, b, corner);
    vec4 color = CG_PARTICLE_COLOR(i);

    // Niagara's Size * (UVForPosition - PivotOffset): the corner from the pivot, its V down where the corner's y is up.
    v.worldPosition = fx_particle_corner(centre, corner - vec2(2.0 * pivot.x - 1.0, 1.0 - 2.0 * pivot.y), extent, spin, right, up);
    v.texCoords[0] = uvA;
    v.texCoords[1] = uvB;
    v.texCoords[2] = uvA;
    v.texCoords[3] = uvB;
    v.tangentToWorld = mat3(normalize(right), normalize(up), normalize(cross(right, up)));
    v.vertexColor = vec4(color.rgb, color.a * CG_PARTICLE_OPACITY(i));

    p.color = v.vertexColor;
    p.relativeTime = CG_PARTICLE_PROGRESS(i);
    p.random = CG_PARTICLE_SEED(i);
    p.velocity = vec4(length(velocity) > 0.0 ? normalize(velocity) : vec3(0.0), length(velocity));
    p.positionAndSize = vec4(centre, max(extent.x, extent.y));
    p.size = 2.0 * extent;
    p.spriteRotation = spin;
    p.subUVCoords0 = uvA;
    p.subUVCoords1 = uvB;
    p.subUVLerp = blend;
    p.record = i;
    _cg_dynamics(i, drawn, p);
}

CgMaterialVertex _cg_sprite_vertex(float facing, vec4 facingVector, vec4 alignmentVector, float stretch,
                                   float maxStretch, float cameraOffset, float rotationBias, vec4 subImage, vec4 subUv,
                                   sampler2D subImages, float drawnBits, vec4 pivot) {
    CgMaterialVertex v;
    CgMaterialParticle p;
    _cg_sprite(facing, facingVector, alignmentVector, stretch, maxStretch, cameraOffset, rotationBias, subImage, subUv,
               subImages, drawnBits, pivot, v, p);
    return v;
}

CgMaterialParticle _cg_sprite_particle(float facing, vec4 facingVector, vec4 alignmentVector, float stretch,
                                       float maxStretch, float cameraOffset, float rotationBias, vec4 subImage,
                                       vec4 subUv, sampler2D subImages, float drawnBits, vec4 pivot) {
    CgMaterialVertex v;
    CgMaterialParticle p;
    _cg_sprite(facing, facingVector, alignmentVector, stretch, maxStretch, cameraOffset, rotationBias, subImage, subUv,
               subImages, drawnBits, pivot, v, p);
    return p;
}

#define cg_material_vertex() _cg_sprite_vertex(_cg_Facing, _cg_FacingVector, _cg_AlignmentVector, _cg_Stretch,         _cg_MaxStretch, _cg_CameraOffset, _cg_RotationBias, _cg_SubImage, _cg_SubUV, _cg_SubImages, _cg_Drawn, _cg_Pivot)
#define cg_material_particle() _cg_sprite_particle(_cg_Facing, _cg_FacingVector, _cg_AlignmentVector, _cg_Stretch,         _cg_MaxStretch, _cg_CameraOffset, _cg_RotationBias, _cg_SubImage, _cg_SubUV, _cg_SubImages, _cg_Drawn, _cg_Pivot)
#elif defined(CG_VERTEX_STAGE) && defined(CG_TYPE_SPATIAL)
// The mesh factory: a particle's mesh, drawn an instance a particle from Range's object records (CgVfxRange.objects):
// the model its place, turn and size; custom0 its velocity and age; custom1 its progress, seed, opacity and heat;
// custom2 its colour and its place in the order. As Unreal's MeshParticleVertexFactory.ush, the vertex colour and UVs
// are the mesh's and the particle's colour is the particle's alone.
CgMaterialVertex _cg_mesh_vertex(float cameraOffset, float drawnBits) {
    CgMaterialVertex v;
    if ((int(drawnBits + 0.5) & _CG_DRAWN_CAMERA_OFFSET) != 0) cameraOffset = CG_PARTICLE_DRAWN(int(CG_OBJECT_CUSTOM2.w)).color.y;
    v.worldPosition = (CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0)).xyz
            + _cg_toward_eye(CG_OBJECT_TO_WORLD[3].xyz, _CG_EYE, cameraOffset);
    v.texCoords[0] = cg_TexCoord0;
    v.texCoords[1] = cg_TexCoord1;
    v.texCoords[2] = cg_TexCoord2;
    v.texCoords[3] = cg_TexCoord3;
    // The format carries no tangent: a frame about the normal (Duff et al., "Building an Orthonormal Basis, Revisited").
    vec3 n = normalize(CG_NORMAL_MATRIX * cg_Normal);
    float s = n.z >= 0.0 ? 1.0 : -1.0, a = -1.0 / (s + n.z), b = n.x * n.y * a;
    v.tangentToWorld = mat3(vec3(1.0 + s * n.x * n.x * a, s * b, -s * n.x), vec3(b, s + n.y * n.y * a, -n.y), n);
    v.vertexColor = cg_Color;
    return v;
}

CgMaterialParticle _cg_mesh_particle(vec4 subImage, vec4 subUv, sampler2D subImages, float drawnBits) {
    CgMaterialParticle p;
    vec3 velocity = CG_OBJECT_CUSTOM0.xyz;
    float speed = length(velocity);
    p.color = vec4(CG_OBJECT_CUSTOM2.rgb, CG_OBJECT_CUSTOM1.z);
    p.relativeTime = CG_OBJECT_CUSTOM1.x;
    p.random = CG_OBJECT_CUSTOM1.y;
    p.velocity = vec4(speed > 0.0 ? velocity / speed : vec3(0.0), speed);
    p.positionAndSize = vec4(CG_OBJECT_TO_WORLD[3].xyz, 1.0);   // Unreal's mesh factory sizes it 1
    p.size = vec2(0.0);
    p.spriteRotation = 0.0;
    p.record = int(CG_OBJECT_CUSTOM2.w);
    // Niagara's mesh sub-UVs (NiagaraMeshVertexFactory.ush): the mesh's own UVs laid into each of its two frames,
    // (frame + uv) * SubImageSize; the whole image without a flipbook.
    int drawn = int(drawnBits + 0.5), i = p.record;
    float index = (drawn & _CG_DRAWN_SUB_IMAGE) != 0 ? CG_PARTICLE_DRAWN(i).color.x : 0.0, a, b, blend;
    fx_subuv_frames(subUv, subImage, CG_PARTICLE_AGE(i), CG_PARTICLE_PROGRESS(i), CG_PARTICLE_SEED(i), index, a, b, blend);
    vec4 rectA = fx_subimage_rect(subImages, subImage, a), rectB = fx_subimage_rect(subImages, subImage, b);
    p.subUVCoords0 = mix(rectA.xy, rectA.zw, cg_TexCoord0);
    p.subUVCoords1 = mix(rectB.xy, rectB.zw, cg_TexCoord0);
    p.subUVLerp = blend;
    _cg_dynamics(p.record, int(drawnBits + 0.5), p);
    return p;
}

#define cg_material_vertex() _cg_mesh_vertex(_cg_CameraOffset, _cg_Drawn)
#define cg_material_particle() _cg_mesh_particle(_cg_SubImage, _cg_SubUV, _cg_SubImages, _cg_Drawn)
#elif defined(CG_VERTEX_STAGE)
// No factory for this #type: a port calling one fails here, by name.
#define cg_material_vertex() cg_material_vertex_has_no_factory_for_this_type()
#define cg_material_particle() cg_material_particle_has_no_factory_for_this_type()
#endif
