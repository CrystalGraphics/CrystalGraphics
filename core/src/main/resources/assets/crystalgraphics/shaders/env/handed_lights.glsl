#pragma once
// The world's lights handed to a program carrying its own lighting (CgWorldLights): the light grid, read from the cell
// a point falls in. Included by the compiler after a material's buffers wherever its source names cg_NearestLights or
// CG_DRAW_POINT_LIGHT_*, which then binds WORLD_LIGHTS, WORLD_LIGHT_CELLS, WORLD_LIGHT_INDICES and the _cg_Light*
// properties. In a transparent firing the grid is this frame's; in an opaque one, the last frame's.
//
//     int idx[4];
//     int n = cg_NearestLights(p, idx);                 // camera-relative p
//     for (int k = 0; k < n; k++) lit += cg_LightColor(idx[k]) / max(dot(d, d), 1e-4) ...;
//     vec3 at = CG_DRAW_POINT_LIGHT_POSITION(0);        // the light nearest the draw's origin, either stage

#include "crystalgraphics:shaders/lib/lights.glsl"

// A point off screen reads the cell nearest it on screen: the grid covers the view alone.
int cg_light_cell_at(vec3 p) {
    vec3 w = p - _cg_LightEye.xyz;
    vec3 v = vec3(dot(_cg_LightView0.xyz, w), dot(_cg_LightView1.xyz, w), dot(_cg_LightView2.xyz, w))
            + vec3(_cg_LightView0.w, _cg_LightView1.w, _cg_LightView2.w);
    float depth = max(-v.z, 0.01);
    vec3 q = vec3(clamp(v.xy, _cg_LightRays.xy * depth, _cg_LightRays.zw * depth), -depth);
    ivec3 cell = cg_light_cell(q, _cg_LightZ.xyz, _cg_LightRays.xy, _cg_LightRays.zw, _cg_LightTile.xy,
            ivec2(_cg_LightGrid.xy));
    return (cell.z * int(_cg_LightGrid.y) + cell.y) * int(_cg_LightGrid.x) + cell.x;
}

// The lights reaching camera-relative p, at most four, nearest first, as indices for the readers below; how many. The
// rest of idx is -1. Lights that leave transparent surfaces unlit are left out.
int cg_NearestLights(vec3 p, out int idx[4]) {
    float best[4] = float[4](1.0e30, 1.0e30, 1.0e30, 1.0e30);
    idx = int[4](-1, -1, -1, -1);
    if (_cg_LightGrid.w < 0.5) return 0;
    vec3 w = p - _cg_LightEye.xyz;
    int e = cg_light_cell_at(p), from = int(WORLD_LIGHT_CELLS(e * 4 + 1)), n = int(WORLD_LIGHT_CELLS(e * 4)), found = 0;
    for (int k = 0; k < n; k++) {
        int at = int(WORLD_LIGHT_INDICES(from + k));
        CgLight light = WORLD_LIGHTS(at);
        if (CG_LIGHT_OPAQUE_ONLY(light)) continue;
        vec3 d = light.positionRadius.xyz - w;
        float key = dot(d, d);
        if (key >= light.positionRadius.w * light.positionRadius.w) continue;
        found++;
        for (int s = 0; s < 4; s++) {
            if (key < best[s]) {
                float k2 = best[s];
                int a2 = idx[s];
                best[s] = key;
                idx[s] = at;
                key = k2;
                at = a2;
            }
        }
    }
    return min(found, 4);
}

// Light i's camera-relative position.
vec3 cg_LightPosition(int i) {
    return WORLD_LIGHTS(i).positionRadius.xyz + _cg_LightEye.xyz;
}

// Its linear colour times its intensity.
vec3 cg_LightColor(int i) {
    return WORLD_LIGHTS(i).colorExponent.rgb;
}

// Its source radius (Unreal's SourceRadius), 0 for a point.
float cg_LightInnerRadius(int i) {
    return max(WORLD_LIGHTS(i).ambient.z, 0.0);
}

// The blocks it reaches.
float cg_LightRadius(int i) {
    return WORLD_LIGHTS(i).positionRadius.w;
}

// Its falloff: 0 inverse-squared, else Unreal's exponent.
float cg_LightFalloff(int i) {
    return WORLD_LIGHTS(i).colorExponent.w;
}

// The draw's origin: its system's for a particle, else its object record's.
#ifdef CG_OBJECT_POSITION
#define CG_HANDED_ORIGIN CG_OBJECT_POSITION
#else
#define CG_HANDED_ORIGIN (CG_OBJECT_TO_WORLD[3].xyz)
#endif

// The four nearest the draw's origin, found once an invocation.
int cg_handed[4];
bool cg_handedFound = false;

int cg_handed_light(int i, vec3 origin) {
    if (!cg_handedFound) {
        cg_NearestLights(origin, cg_handed);
        cg_handedFound = true;
    }
    return cg_handed[i];
}

vec4 cg_handed_params(int at) {
    return at < 0 ? vec4(0.0) : vec4(1.0, cg_LightInnerRadius(at), cg_LightRadius(at), cg_LightFalloff(at) == 0.0 ? 1.0 : 0.0);
}

// cg_env.glsl's placeholders, which fail to link where nothing hands the lights.
#undef CG_DRAW_POINT_LIGHT_POSITION
#undef CG_DRAW_POINT_LIGHT_COLOR
#undef CG_DRAW_POINT_LIGHT_PARAMS
#define CG_DRAW_POINT_LIGHT_POSITION(i) \
    (cg_handed_light((i), CG_HANDED_ORIGIN) < 0 ? vec3(0.0) : cg_LightPosition(cg_handed_light((i), CG_HANDED_ORIGIN)))
#define CG_DRAW_POINT_LIGHT_COLOR(i) \
    (cg_handed_light((i), CG_HANDED_ORIGIN) < 0 ? vec3(0.0) : cg_LightColor(cg_handed_light((i), CG_HANDED_ORIGIN)))
#define CG_DRAW_POINT_LIGHT_PARAMS(i) cg_handed_params(cg_handed_light((i), CG_HANDED_ORIGIN))
