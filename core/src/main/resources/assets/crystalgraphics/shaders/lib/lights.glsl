// A world light (CgWorldLights), as the list holds it: Unreal's local light, a point, a spot or a tube, four vec4s. A
// kernel reading or appending lights declares the struct itself, before its Buffers block, since a buffer's element is
// laid out from the kernel's own file (CgWorldLights.STRUCT):
//
//     struct CgLight { vec4 positionRadius; vec4 colorExponent; vec4 spotCos; vec4 segmentCone; };
//     Buffers { LIGHTS ("Lights", CgLight, append) }
//     LIGHTS_APPEND(cg_light(position - _LightEye.xyz, radius, linearColour * intensity, 0.0));
//
// positionRadius: from the firing's eye, and the blocks it reaches. colorExponent: linear colour times its intensity,
// and its falloff, 0 for inverse-squared. spotCos: the way a spot points and the cosine of its outer angle, w -2 for no
// cone. segmentCone: a tube's length along its axis as one vector (0 for a point), and a spot's 1 / (cos inner - cos
// outer).
#pragma once

#define cg_light(position, radius, color, exponent) \
    CgLight(vec4(position, radius), vec4(color, exponent), vec4(0.0, 0.0, 0.0, -2.0), vec4(0.0))
// A spot: SpotLightComponent's angles, already clamped and as cosines (CgWorldLights.spot does it on the CPU).
#define cg_spot_light(position, radius, color, exponent, forward, cosOuter, invConeDifference) \
    CgLight(vec4(position, radius), vec4(color, exponent), vec4(forward, cosOuter), vec4(0.0, 0.0, 0.0, invConeDifference))
// A tube: SourceLength along segment's direction, segment its whole length as a vector, centred on position.
#define cg_tube_light(position, radius, color, exponent, segment) \
    CgLight(vec4(position, radius), vec4(color, exponent), vec4(0.0, 0.0, 0.0, -2.0), vec4(segment, 0.0))

// The light grid (CgWorldLights), Unreal's (LightGridInjection.cpp:34-50, LightGridCommon.ush): CG_LIGHT_TILE pixels
// across, CG_LIGHT_SLICES slices deep at log2(depth * B + O) * S, up to CG_LIGHT_PER_CELL lights a cell. zParams is
// (B, O, S); a cell's x and y run along the view's rays at unit eye depth, rayMin at the target's left and bottom edge
// and rayMax at its right and top, so binning and reading place a point alike whichever way a device's y runs.
#define CG_LIGHT_TILE 64
#define CG_LIGHT_SLICES 32
#define CG_LIGHT_PER_CELL 32
// Unreal's inverse-squared falloff's +1, a square centimetre, in square blocks: intensity is per square block, 1 at a
// block.
#define CG_LIGHT_BIAS 1.0e-4

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

// How much of a light of the given position, radius, falloff, cone and segment reaches point p, all in one space: the
// colour's multiplier. Unreal's local light without a surface (DeferredLightingCommon.ush's GetLocalLightAttenuation,
// then the non-directional IntegrateLight of CapsuleLightIntegrate.ush): a mask from the light's centre, radial to its
// radius and Square(saturate((cos - cosOuter) / (cosInner - cosOuter))) inside a cone (DynamicLightingCommon.ush:46-50),
// times, for the inverse square, the irradiance of its segment (CapsuleLight.ush's LineIrradiance, 1 / (d² + 1) at a
// point), in blocks with Unreal's square centimetre as the bias. A source's radius shapes only a lit surface's highlight and horizon, which no
// surface here has, so it has no term.
float cg_light_falloff(vec3 position, float radius, float exponent, vec4 spotCos, vec4 segmentCone, vec3 p) {
    vec3 toLight = position - p;
    float d2 = dot(toLight, toLight), x = d2 / (radius * radius);
    float fade = clamp(1.0 - x * x, 0.0, 1.0);
    float mask = exponent == 0.0 ? fade * fade : pow(1.0 - clamp(x, 0.0, 1.0), exponent);
    if (spotCos.w > -1.5) {
        float c = dot(-toLight * inversesqrt(max(d2, 1.0e-12)), spotCos.xyz);
        float cone = clamp((c - spotCos.w) * segmentCone.w, 0.0, 1.0);
        mask *= cone * cone;
    }
    if (exponent != 0.0 || mask <= 0.0) return mask;
    vec3 l0 = toLight - 0.5 * segmentCone.xyz, l1 = toLight + 0.5 * segmentCone.xyz;
    float inv01 = inversesqrt(max(dot(l0, l0), 1.0e-12)) * inversesqrt(max(dot(l1, l1), 1.0e-12));
    float cosSubtended = dot(l0, l1) * inv01;
    return mask * inv01 / (cosSubtended * 0.5 + 0.5 + CG_LIGHT_BIAS * inv01);
}

// Whether a light at position (radius blocks) with cone spotCos reaches a box of centre and extent, all in one space:
// Unreal's binning (LightGridInjection.usf:132-171), a spot first tested against the box's bounding sphere
// (IntersectConeWithSphere) and then bounded by the smallest sphere round its cone (Wronski's).
bool cg_light_reaches(vec3 position, float radius, vec4 spotCos, vec3 centre, vec3 extent) {
    if (spotCos.w > -1.5) {
        float c = spotCos.w, s = sqrt(max(1.0 - c * c, 0.0)), r = length(extent);
        vec3 toBox = centre - position;
        float along = dot(toBox, spotCos.xyz);
        float closest = c * sqrt(max(dot(toBox, toBox) - along * along, 0.0)) - along * s;
        if (closest > r || along > r + radius || along < -r) return false;
        if (c < 0.707107) {
            position += spotCos.xyz * radius * c;
            radius *= s;
        } else {
            radius /= 2.0 * c;
            position += spotCos.xyz * radius;
        }
    }
    vec3 outside = max(abs(position - centre) - extent, 0.0);
    return dot(outside, outside) < radius * radius;
}
