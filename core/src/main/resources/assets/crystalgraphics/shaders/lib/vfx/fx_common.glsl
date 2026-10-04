// What every effect shader shares, the vfx engine's and the showcase's alike: the eye, hashes, the noise volumes' two
// kinds of 3D noise and their fractals, cellular noise, erf and a filmic curve. Pure functions: nothing here reads a
// derivative, so it compiles into both stages.
//
//   fx_noise / fx_fbm / fx_ridged / fx_warped   gradient noise, about -1..1: smooth, no lattice showing; flowing energy
//   fx_value_noise / _fbm / _ridged             value noise, 0..1: blotchier; surfaces and patterns
#pragma once

// The eye, in the space shaders work in: not always the origin, since a host may fold a translation into the view. A
// macro, since a material's own #includes come before cg_env.glsl declares the frame block: it may only be expanded
// inside vertex() or fragment().
#define FX_CAMERA (-(transpose(mat3(cg_ViewMatrix)) * cg_ViewMatrix[3].xyz))

// Where a vertex of a mesh with no vertex data sits (#type none), from CG_VERTEX_ID; vertex() only, like FX_CAMERA.
// CgVfxQuads (CgMesh.quads): the vertex's quad, and its corner -1..1 on each axis.
#define FX_QUAD_INDEX float(CG_VERTEX_ID >> 2)
#define FX_QUAD_CORNER (CG_VERTEX_CORNER * 2.0 - 1.0)
// CgVfxRibbons: the ribbon, how far along it 0..1 (0 its tail), and the side -1 or 1. A triangle list with no
// indices, 192 vertices a ribbon (CgVfxRibbons.VERTICES): six a segment of its 32 (SEGMENTS), in the order
// (s,0) (s,1) (s+1,0), (s,1) (s+1,1) (s+1,0). Bit k of 0x34 says corner k is at the segment's far end, of 0x1A
// that it is on side 1.
#define FX_RIBBON_INDEX float(CG_VERTEX_ID / 192)
#define FX_RIBBON_ALONG (float((CG_VERTEX_ID % 192) / 6 + ((0x34 >> (CG_VERTEX_ID % 6)) & 1)) / 32.0)
#define FX_RIBBON_SIDE (float((0x1A >> (CG_VERTEX_ID % 6)) & 1) * 2.0 - 1.0)

// ── Hashes (Dave Hoskins, sin-free), 0..1 ──────────────────────────────────────────────────────────────────────

float fx_hash31(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

vec3 fx_hash33(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.xxy + p.yxx) * p.zyx);
}

// ── Noise, from the engine's volumes ───────────────────────────────────────────────────────────────────────────
//
// Read from the engine's noise volumes (lib/noise_volume.glsl), never computed. Macros, since a material's #includes
// come before its properties: they expand in the shader's own code, which declares the volumes it uses.
//
//   Properties {
//       _Noise      ("Noise",       sampler3D) = "cg_noise"         // fx_noise, fx_fbm, fx_ridged, fx_warped, fx_heat, fx_flicker
//       _ValueNoise ("Value noise", sampler3D) = "cg_value_noise"   // fx_value_noise, fx_value_fbm, fx_value_ridged
//       _Voronoi    ("Voronoi",     sampler3D) = "cg_voronoi"       // fx_voronoi
//       _VoronoiNearest ("Cells",   sampler3D) = "cg_voronoi_nearest" // fx_voronoi_nearest
//   }
//
// The volumes repeat every CG_NOISE_PERIOD units: noise driven by time adds cg_noise_time(t), which never loops.
#include "crystalgraphics:shaders/lib/noise_volume.glsl"

// How hot air bends a ray at p (in noise units, so the caller's scale sets the ripple size), about -1..1 on each axis:
// fine ripples churning in place as they rise, pushed around by slow broad swells, the whole of it gusting on a third,
// slower field. rise is in noise units a second.
vec2 fx_heat_volume(sampler3D volume, vec3 p, float time, float rise, float seed) {
    vec3 s = p + seed * 11.0;
    vec3 c = s * 0.5 + vec3(0.13 * time, -0.5 * rise * time, 0.07 * time);
    vec3 swell = cg_noise4(volume, c).xyz;
    vec3 f = s + swell * 0.9 + vec3(0.0, -rise * time, 0.0);
    vec2 fine = vec2(cg_noise4(volume, f + vec3(0.0, 0.0, 0.6 * time)).x,
                     cg_noise4(volume, f + vec3(19.1, 7.3, 3.7 - 0.6 * time)).y);
    float gust = 0.35 + 0.65 * smoothstep(-0.4, 0.6, cg_noise4(volume, s * 0.25 + vec3(0.3 * time, -0.2 * time, 0.0)).w);
    return (fine * 0.7 + swell.xy * 0.5) * gust;
}

// A brightness about 1, jittering many times a second and never the same twice.
float fx_flicker_volume(sampler3D volume, float time, float seed) {
    float fast = cg_noise3(volume, vec3(seed * 31.0, 0.5, 0.0) + cg_noise_time(time * 19.0));
    float slow = cg_noise3(volume, vec3(seed * 17.0, 7.5, 0.0) + cg_noise_time(time * 4.3));
    return 1.0 + 0.22 * fast + 0.12 * slow;
}

// Gradient noise, about -1..1, and its fractals; ridged is 0..1 with sharp crests where the noise crosses zero.
#define fx_noise(p) cg_noise3(_Noise, p)
#define fx_fbm(p, octaves) cg_fbm3(_Noise, p, octaves)
#define fx_ridged(p, octaves) cg_ridged3(_Noise, p, octaves)
// fbm pushed around by a three-channel fbm: roiling, folding turbulence rather than soft blobs.
#define fx_warped(p, strength) cg_warped3(_Noise, p, strength)
#define fx_heat(p, time, rise, seed) fx_heat_volume(_Noise, p, time, rise, seed)
#define fx_flicker(time, seed) fx_flicker_volume(_Noise, time, seed)
// Value noise, 0..1.
#define fx_value_noise(p) cg_noise3(_ValueNoise, p)
#define fx_value_fbm(p, octaves) cg_fbm3(_ValueNoise, p, octaves)
#define fx_value_ridged(p, octaves) cg_value_ridged3(_ValueNoise, p, octaves)
// The nearest and second-nearest feature distances, and the nearest cell's id in [0, 1).
#define fx_voronoi(p) cg_voronoi3(_Voronoi, p)
// xyz the unit direction from the nearest feature point, the gradient of w, the distance to it: a normal from cells.
#define fx_voronoi_nearest(p) cg_voronoi_nearest4(_VoronoiNearest, p)

// ── Light and colour ───────────────────────────────────────────────────────────────────────────────────────────

// erf, to 0.0004 (Vedder's tanh form).
float fx_erf(float x) {
    x = clamp(x, -4.0, 4.0);
    return tanh(x * (1.1283792 + 0.1009 * x * x));
}

// Narkowicz's fit of the ACES filmic curve: HDR in, display out, highlights rolling off rather than clipping.
vec3 fx_aces(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}
