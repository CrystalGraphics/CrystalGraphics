// A world light (CgWorldLights), as the list holds it: Unreal's simple light, two vec4s. A kernel reading or appending
// lights declares the struct itself, before its Buffers block, since a buffer's element is laid out from the kernel's
// own file (CgWorldLights.STRUCT):
//
//     struct CgLight { vec4 positionRadius; vec4 colorExponent; };
//     Buffers { LIGHTS ("Lights", CgLight, append) }
//     LIGHTS_APPEND(cg_light(position - _LightEye.xyz, radius, linearColour * intensity, 0.0));
//
// positionRadius: from the firing's eye, and the blocks it reaches. colorExponent: linear colour times its intensity,
// and its falloff, 0 for inverse-squared.
#pragma once

#define cg_light(position, radius, color, exponent) CgLight(vec4(position, radius), vec4(color, exponent))

// The light grid (CgWorldLights), Unreal's (LightGridInjection.cpp:34-50, LightGridCommon.ush): CG_LIGHT_TILE pixels
// across, CG_LIGHT_SLICES slices deep at log2(depth * B + O) * S, up to CG_LIGHT_PER_CELL lights a cell. zParams is
// (B, O, S); a cell's x and y run along the view's rays at unit eye depth, rayMin at the target's left and bottom edge
// and rayMax at its right and top, so binning and reading place a point alike whichever way a device's y runs.
#define CG_LIGHT_TILE 64
#define CG_LIGHT_SLICES 32
#define CG_LIGHT_PER_CELL 32
// Unreal's centimetres a block: its inverse-squared falloff's +1 is in them.
#define CG_LIGHT_CM 100.0

// The eye depth slice z starts at: 0 for the first, and far past the world for the end of the last, as Unreal's.
float cg_light_slice_depth(int z, vec3 zParams) {
    if (z <= 0) return 0.0;
    if (z >= CG_LIGHT_SLICES) return 2.0e6;
    return (exp2(float(z) / zParams.z) - zParams.y) / zParams.x;
}

// The cell holding view-space point p (eye depth -p.z) on a grid of grid.xy cells, tiles a fraction tile.xy of the
// target: its x, y and slice.
ivec3 cg_light_cell(vec3 p, vec3 zParams, vec2 rayMin, vec2 rayMax, vec2 tile, ivec2 grid) {
    float depth = max(-p.z, 1.0e-4);
    vec2 uv = (p.xy / depth - rayMin) / (rayMax - rayMin);
    ivec2 xy = clamp(ivec2(floor(uv / tile)), ivec2(0), grid - 1);
    int z = int(max(0.0, log2(depth * zParams.x + zParams.y) * zParams.z));
    return ivec3(xy, min(z, CG_LIGHT_SLICES - 1));
}
