// A stylized billow's shape and density, billow.shader's. Pure functions: the shader samples the noise (fx_common's
// macros read its own volumes) and passes it in.
//
//     vec3 gLarge, gFine;
//     float large = fx_billow_dome(fx_voronoi_nearest(p * _Cells + offset), _Cells, gLarge);
//     float fine = fx_billow_dome(fx_voronoi_nearest(p * _Cells * 2.2 + offset2), _Cells * 2.2, gFine);
//     float h = fx_billow_height(p, large, gLarge, fine, gFine, _Bulge, _Fine, n);
//     float alpha = fx_billow_density(facing, wisp, broad, large, _Edge, _Wisp, dissolve);
#pragma once

// A round dome over a Voronoi cell (cell: fx_voronoi_nearest at frequency c), 1 at its centre and 0 just short of the
// farthest corners, so neighbouring lobes meet in creases rather than across flats; its gradient through the distance's
// own, so the normal stays smooth where a filtered distance would step.
float fx_billow_dome(vec4 cell, float c, out vec3 gradient) {
    const float K = 1.25;
    float height = sqrt(max(1.0 - cell.w * cell.w * K, 0.0));
    gradient = -c * K * cell.w * cell.xyz / max(height, 0.08);
    return height;
}

// The surface over the unit direction p: its radius, and its normal, the sphere's tilted against the height's slope.
float fx_billow_height(vec3 p, float large, vec3 gLarge, float fine, vec3 gFine, float bulge, float fineShare,
                       out vec3 normal) {
    float h = 1.0 + bulge * (large - 0.5 + fineShare * (fine - 0.5));
    vec3 g = bulge * (gLarge + fineShare * gFine);
    normal = normalize(p - (g - dot(g, p) * p) / h);
    return h;
}

// Squashed against a surface (contact: its normal and the centre's height over it; xyz zero for none): its height over
// the surface lifted to at least rest by a smooth maximum, so the base rounds onto it, and pushed out along it as far
// times spread, so it spreads. The normal keeps the squash's slope: flat where pressed, unchanged where not. Answers the
// height over the surface in sizes, 1e4 with none.
float fx_billow_squash(inout vec3 world, inout vec3 normal, vec3 centre, float size, vec4 contact, float rest,
                       float round, float spread) {
    if (dot(contact.xyz, contact.xyz) <= 0.25) return 1.0e4;
    float soft = max(round * size, 1.0e-3);
    vec3 rel = world - centre;
    float height = dot(contact.xyz, rel) + contact.w, gap = rest * size - height;
    float k = max(soft - abs(gap), 0.0) / soft;
    float lifted = max(height, rest * size) + k * k * soft * 0.25;
    float push = lifted - height, slope = gap > 0.0 ? 0.5 * k : 1.0 - 0.5 * k;
    vec3 along = rel - contact.xyz * dot(contact.xyz, rel);
    world += contact.xyz * push + along / max(length(along), 1.0e-4) * push * spread;
    slope = max(slope, 0.05);
    normal = normalize(slope * normal + (1.0 - slope) * dot(normal, contact.xyz) * contact.xyz);
    return lifted / size;
}

// How opaque it is: full in the middle, thinning over edge (how far the surface turns from the eye) into wisps the
// noise eats (wisp 0..1, times amount), broken into pieces by a broad noise (0..1) and the lobes' domes. Dissolving 0..1
// raises the bar from the edge inward until nothing is left.
float fx_billow_density(float facing, float wisp, float broad, float large, float edge, float amount, float dissolve) {
    float field = facing / max(edge, 1.0e-3) + (wisp - 0.5) * amount + (broad - 0.5) * 0.5 + (large - 0.5) * 0.3;
    float bar = 0.1 + 2.0 * dissolve;
    return smoothstep(bar, bar + 0.9, field);
}
