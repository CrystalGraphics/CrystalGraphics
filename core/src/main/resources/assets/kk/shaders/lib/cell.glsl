// CC2's cel resolve for shading model 13 (CellTone), which its deferred directional light runs after the base pass
// (the capture's draw 6287), here run forward by each model-13 port on what its base pass would have written.
#ifndef KAKAROT_CELL_GLSL
#define KAKAROT_CELL_GLSL

float kk_lum(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

// max minus min over max: CC2's saturation, 0 for black
float kk_saturation(vec3 c) {
    float hi = max(c.x, max(c.y, c.z));
    return hi != 0.0 ? (hi - min(c.x, min(c.y, c.z))) / hi : 0.0;
}

// The base pass's outputs, as the resolve reads them: the light-side and shadow-side colours (GBufferC, the seventh
// target), the second colour (GBufferD), and the sixth target's painted shadow, lit-side rim, shadow bias and
// cast-shadow weight.
struct KkCellSurface {
    vec3 lit;
    vec3 dark;
    vec3 second;
    float painted;
    float rimLit;
    float shadowBias;
    float castWeight;
};

// The ramp's row is TonePattern, the stage light scaled by AffectedLight; visibility 1 (the host has no shadow map).
// Its first blend reads the base colour's alpha, which model 13 stores as 1, so it is the toned mix.
vec3 kk_cell_resolve(KkCellSurface s, vec3 normal, vec3 sun, sampler2D tone, float tonePattern, float affectedLight,
                     vec3 stageLight, vec3 stageShadowTint) {
    float visibility = 1.0;
    float nl = dot(normal, sun);
    float row = (floor(tonePattern) + 0.5) / 64.0;
    float hard = texture(tone, vec2(0.5 - 0.5 * nl, row)).r;
    float toneMix = texture(tone, vec2(clamp(2.0 * s.shadowBias + 0.5 - 0.5 * nl - 1.0, 0.0, 1.0), row)).g;
    float al = clamp(affectedLight, 0.0, 1.0);
    vec3 sunLit = s.lit * mix(vec3(1.0), 0.1 * stageLight, al);
    vec3 sunDark = s.dark * mix(vec3(1.0), stageShadowTint, al);
    vec3 toned = mix(sunDark, sunLit, toneMix);
    vec3 deep = mix(pow(max(sunDark, vec3(0.0)), 3.33333 * (vec3(1.0) - sunDark)), sunDark * sunLit,
            clamp(ceil(kk_lum(sunLit)), 0.0, 1.0) * toneMix);
    vec3 mixed = mix(sunDark, mix(sunDark * sunLit, deep, max(kk_saturation(sunLit), kk_lum(sunLit))), 1.0 - toneMix);
    vec3 c = clamp(hard * s.second * visibility * kk_lum(0.1 * stageLight)
            + mix(toned, mix(toned, mixed, s.painted), s.painted), 0.0, 1.0);
    vec3 edge = vec3(1.0) + sunDark - (vec3(1.0) - sunLit) * (vec3(1.0) - s.rimLit * clamp(sunLit + 0.3, 0.0, 1.0));
    c = mix(c, mix(mixed, edge, toneMix), s.rimLit);
    return c * clamp(0.5 * (sunDark + 0.1) + clamp(1.0 + visibility - s.castWeight, 0.0, 1.0), 0.0, 1.0);
}

// The normal turned about the material's own light axis (LightDirection_Vertical, 0, LightDirection_Side in object
// space) by LightDirectionPower turns.
vec3 kk_cell_turned(vec3 n, mat3 objectToWorld, float vertical, float side, float power) {
    vec3 axis = normalize(objectToWorld * kk_unreal_axes(vec3(vertical, 0.0, side)));
    float a = 6.2832 * power;
    vec3 perp = n - axis * dot(axis, n);
    return normalize(axis * dot(axis, n) + perp * cos(a) + cross(axis, perp) * sin(a));
}


// Derivatives: fragment stages only.
#if !defined(CG_VERTEX_STAGE) && !defined(CG_COMPUTE_STAGE)
// The surface's tangent frame from its position and UV derivatives (the vertex format carries no tangents):
// tangent along +u, bitangent along +v, both in the plane of n.
mat3 kk_tangent_frame(vec3 n, vec3 p, vec2 uv) {
    vec3 dp1 = dFdx(p), dp2 = dFdy(p);
    vec2 duv1 = dFdx(uv), duv2 = dFdy(uv);
    float det = duv1.x * duv2.y - duv1.y * duv2.x;
    float s = det < 0.0 ? -1.0 : 1.0;
    vec3 t = (dp1 * duv2.y - dp2 * duv1.y) * s;
    vec3 b = (dp2 * duv1.x - dp1 * duv2.x) * s;
    t = normalize(t - n * dot(n, t));
    b = normalize(b - n * dot(n, b));
    return mat3(t, b, n);
}
#endif

#endif
