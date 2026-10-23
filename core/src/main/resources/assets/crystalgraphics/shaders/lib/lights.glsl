// A world light (CgWorldLights), as the list holds it: Unreal's local light, a point, a spot or a tube, six vec4s. A
// kernel reading or appending lights declares the struct itself, before its Buffers block, since a buffer's element is
// laid out from the kernel's own file (CgWorldLights.STRUCT):
//
//     struct CgLight { vec4 positionRadius; vec4 colorExponent; vec4 spotCos; vec4 segmentCone; vec4 ambient; vec4 air; };
//     Buffers { LIGHTS ("Lights", CgLight, append) }
//     LIGHTS_APPEND(cg_light(position - _LightEye.xyz, radius, linearColour * intensity, 0.0));
//
// positionRadius: from the firing's eye, and the blocks it reaches. colorExponent: linear colour times its intensity,
// and its falloff, 0 for inverse-squared. spotCos: the way a spot points and the cosine of its outer angle, w -2 for no
// cone. segmentCone: a tube's length along its axis as one vector (0 for a point), and a spot's 1 / (cos inner - cos
// outer). ambient: the block and sky light (0 to 15) at the light, what the scene there was lit by and is divided by to
// find its albedo, x -1 where unknown and the scene taken as it is; then its source radius (Unreal's SourceRadius, 0 a
// point), and 1 where it casts shadows (CG_LIGHT_SHADOWED), 2 for a Minecraft light (CG_LIGHT_MINECRAFT). air: x its
// light in the air's multiplier (Unreal's VolumetricScatteringIntensity, 1 unless set); y 1 where it leaves transparent
// surfaces unlit (CG_LIGHT_OPAQUE_ONLY: Unreal's bAffectTranslucentLighting off); z 1 where the shadow passes sample it
// (CG_LIGHT_SAMPLED), the rest unused.
#pragma once

#define cg_light(position, radius, color, exponent) \
    CgLight(vec4(position, radius), vec4(color, exponent), vec4(0.0, 0.0, 0.0, -2.0), vec4(0.0), vec4(-1.0), \
            vec4(1.0, 0.0, 0.0, 0.0))
// A spot: SpotLightComponent's angles, already clamped and as cosines (CgWorldLights.spot does it on the CPU).
#define cg_spot_light(position, radius, color, exponent, forward, cosOuter, invConeDifference) \
    CgLight(vec4(position, radius), vec4(color, exponent), vec4(forward, cosOuter), vec4(0.0, 0.0, 0.0, invConeDifference), \
            vec4(-1.0), vec4(1.0, 0.0, 0.0, 0.0))
// A tube: SourceLength along segment's direction, segment its whole length as a vector, centred on position.
#define cg_tube_light(position, radius, color, exponent, segment) \
    CgLight(vec4(position, radius), vec4(color, exponent), vec4(0.0, 0.0, 0.0, -2.0), vec4(segment, 0.0), vec4(-1.0), \
            vec4(1.0, 0.0, 0.0, 0.0))

// The light grid (CgWorldLights), Unreal's (LightGridInjection.cpp:34-50, LightGridCommon.ush): CG_LIGHT_TILE pixels
// across, CG_LIGHT_SLICES slices deep at log2(depth * B + O) * S, CG_LIGHT_PER_CELL lights a cell on average. zParams is
// (B, O, S); a cell's x and y run along the view's rays at unit eye depth, rayMin at the target's left and bottom edge
// and rayMax at its right and top, so binning and reading place a point alike whichever way a device's y runs.
#define CG_LIGHT_TILE 64
#define CG_LIGHT_SLICES 32
#define CG_LIGHT_PER_CELL 32
// Unreal's inverse-squared falloff's +1, a square centimetre, in square blocks: intensity is per square block, 1 at a
// block.
#define CG_LIGHT_BIAS 1.0e-4

// Whether a light casts shadows (CgWorldLights.shadowed): traced by the shadow passes, not lit by the grid's own.
#define CG_LIGHT_SHADOWED(light) ((light).ambient.w > 0.5)

// A light-giving block of Minecraft's (CgWorldLights' own, from the voxel window): it adds nothing, its colour black,
// and only says where Minecraft's block light comes from, so the shadow passes can withhold it. Its level is its radius
// less a half.
#define CG_LIGHT_MINECRAFT(light) ((light).ambient.w > 1.5)

// Whether the shadow passes sample the light as MegaLights does (Unreal's bAllowMegaLights): every light that casts
// shadows, and one added from the CPU unless set. The rest, a kernel's particle lights by default (Niagara's
// bAllowMegaLights is 0, NiagaraLightRendererProperties.cpp:41), the apply pass shades exactly, as Unreal's clustered
// deferred does: a swarm of small lights sampled a few times a pixel is noise.
#define CG_LIGHT_SAMPLED(light) ((light).ambient.w > 0.5 || (light).air.z > 0.5)

// A light kept out of the translucency lighting volume: transparent surfaces and particles are not lit by it.
#define CG_LIGHT_OPAQUE_ONLY(light) ((light).air.y > 0.5)

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
// point), in blocks. A source's radius shapes only N.L at the horizon (cg_light_surface): Minecraft has no highlight.
// biasSqr, in square blocks, is the inverse square's bias: Unreal's square centimetre on a surface; its volumetric fog
// gives it a froxel's radius squared (VolumetricFog.usf:1061, r.VolumetricFog.InverseSquaredLightDistanceBiasScale 1), so a cell
// near a light takes its average rather than the spike at the light.
// spread, in blocks, is how far round p the answer stands for: a cone's edge is widened by the angle it spans from the
// light, so a region straddling the edge takes its share of the cone rather than whether its one point fell inside. It
// reaches up to spread past the outer cone: a grid binning it passes cg_light_reaches_by that margin.
float cg_light_falloff_filtered(vec3 position, float radius, float exponent, vec4 spotCos, vec4 segmentCone, vec3 p,
                                float biasSqr, float spread) {
    vec3 toLight = position - p;
    float d2 = dot(toLight, toLight), x = d2 / (radius * radius);
    float fade = clamp(1.0 - x * x, 0.0, 1.0);
    float mask = exponent == 0.0 ? fade * fade : pow(1.0 - clamp(x, 0.0, 1.0), exponent);
    if (spotCos.w > -1.5) {
        float invD = inversesqrt(max(d2, 1.0e-12)), c = dot(-toLight * invD, spotCos.xyz);
        float ramp = 1.0 / segmentCone.w, width = ramp + sqrt(max(1.0 - c * c, 0.0)) * min(spread * invD, 1.0);
        float cone = clamp(0.5 + (c - spotCos.w - 0.5 * ramp) / max(width, 1.0e-6), 0.0, 1.0);
        mask *= cone * cone;
    }
    if (exponent != 0.0 || mask <= 0.0) return mask;
    vec3 l0 = toLight - 0.5 * segmentCone.xyz, l1 = toLight + 0.5 * segmentCone.xyz;
    float inv01 = inversesqrt(max(dot(l0, l0), 1.0e-12)) * inversesqrt(max(dot(l1, l1), 1.0e-12));
    float cosSubtended = dot(l0, l1) * inv01;
    return mask * inv01 / (cosSubtended * 0.5 + 0.5 + biasSqr * inv01);
}

// cg_light_falloff_filtered at a point.
float cg_light_falloff_biased(vec3 position, float radius, float exponent, vec4 spotCos, vec4 segmentCone, vec3 p,
                              float biasSqr) {
    return cg_light_falloff_filtered(position, radius, exponent, spotCos, segmentCone, p, biasSqr, 0.0);
}

// cg_light_falloff_biased with Unreal's square centimetre: what a surface takes.
float cg_light_falloff(vec3 position, float radius, float exponent, vec4 spotCos, vec4 segmentCone, vec3 p) {
    return cg_light_falloff_biased(position, radius, exponent, spotCos, segmentCone, p, CG_LIGHT_BIAS);
}

// N.L for a sphere of sin^2(alpha) = radius^2 / (d^2 + bias) as seen from the surface: Unreal's SphereHorizonCosWrap, its
// Hermite fit (CapsuleLight.ush:74-104), so a source touching or half inside a surface still lights it.
float cg_sphere_horizon_wrap(float nol, float sinAlphaSqr) {
    float sinAlpha = sqrt(sinAlphaSqr);
    if (nol >= sinAlpha) return nol;
    nol = max(nol, -sinAlpha);
    return (sinAlpha + nol) * (sinAlpha + nol) / (4.0 * sinAlpha);
}

// What a light gives a surface at p facing n, all in one space, before its colour: its falloff times N.L, N.L toward
// the nearest point of its line (its centre, for a point or a spot) and wrapped over its source sphere as Unreal's
// capsule integrate does (CapsuleLightIntegrate.ush:62-67).
float cg_light_surface(vec3 position, float radius, float exponent, vec4 spotCos, vec4 segmentCone, float source, vec3 p,
                       vec3 n) {
    float falloff = cg_light_falloff(position, radius, exponent, spotCos, segmentCone, p);
    if (falloff <= 0.0) return 0.0;
    float length2 = dot(segmentCone.xyz, segmentCone.xyz);
    float t = length2 > 0.0 ? clamp(dot(p - position, segmentCone.xyz) / length2, -0.5, 0.5) : 0.0;
    vec3 toLight = position + segmentCone.xyz * t - p;
    float d2 = dot(toLight, toLight), nol = dot(n, toLight) * inversesqrt(max(d2, 1.0e-12));
    if (source > 0.0) nol = cg_sphere_horizon_wrap(nol, clamp(source * source / (d2 + CG_LIGHT_BIAS), 0.0, 1.0));
    return falloff * clamp(nol, 0.0, 1.0);
}

// Whether a light at position (radius blocks) with cone spotCos reaches a box of centre and extent, all in one space:
// Unreal's binning (LightGridInjection.usf:132-171), a spot first tested against the box's bounding sphere
// (IntersectConeWithSphere) and then bounded by the smallest sphere round its cone (Wronski's). margin widens a spot's
// cone that many blocks past its outer cone (cg_light_falloff_filtered's spread): the box is taken that much larger.
bool cg_light_reaches_by(vec3 position, float radius, vec4 spotCos, vec3 centre, vec3 extent, float margin) {
    if (spotCos.w > -1.5) {
        float c = spotCos.w, s = sqrt(max(1.0 - c * c, 0.0)), r = length(extent) + margin;
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
        radius += margin;
    }
    vec3 outside = max(abs(position - centre) - extent, 0.0);
    return dot(outside, outside) < radius * radius;
}

// cg_light_reaches_by with no margin.
bool cg_light_reaches(vec3 position, float radius, vec4 spotCos, vec3 centre, vec3 extent) {
    return cg_light_reaches_by(position, radius, spotCos, centre, extent, 0.0);
}
