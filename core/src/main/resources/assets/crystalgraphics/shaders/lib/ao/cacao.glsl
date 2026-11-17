// FidelityFX CACAO's helpers both its kernel files share (world/ao/cacao*.compute).
//
// Copyright (c) 2016, Intel Corporation (ASSAO). Modifications Copyright (c) 2021, Advanced Micro Devices, Inc. (CACAO).
// MIT licence: permission is granted, free of charge, to deal in the Software without restriction, provided this notice
// is kept; the Software is provided "as is", without warranty of any kind. THIRD-PARTY.md holds the full notice.

// The sky's depth, which nothing is behind (half-float range, as the GTAO port)
const float CACAO_FAR = 65504.0;

// The view position at uv (0 to 1 over the screen) and positive depth z, as the GTAO port reconstructs it.
vec3 view_position(vec2 uv, float z) {
    vec2 ndc = uv * 2.0 - 1.0;
    return vec3((ndc.x + cg_ProjMatrix[2][0]) * z / cg_ProjMatrix[0][0], (ndc.y + cg_ProjMatrix[2][1]) * z / cg_ProjMatrix[1][1], z);
}

// The scene's view depth at full-resolution texel px, clamped to the screen of size (point clamp); CACAO_FAR for the sky.
float scene_depth(ivec2 px, ivec2 size) {
    px = clamp(px, ivec2(0), size - 1);
    float window = texelFetch(cg_DepthBuffer, px, 0).r;
    if (cg_DepthParams.x > 0.5 ? window <= 0.0 : window >= 1.0) return CACAO_FAR;
    return min(cg_LinearEyeDepth(window), CACAO_FAR);
}

// FFX_CACAO_PackEdges / UnpackEdges: two bits an edge
float pack_edges(vec4 e) {
    e = round(clamp(e, 0.0, 1.0) * 3.05);
    return dot(e, vec4(64.0 / 255.0, 16.0 / 255.0, 4.0 / 255.0, 1.0 / 255.0));
}
vec4 unpack_edges(float packedValue, float invSharpness) {
    uint p = uint(packedValue * 255.5);
    vec4 e = vec4(float((p >> 6) & 3u), float((p >> 4) & 3u), float((p >> 2) & 3u), float(p & 3u)) / 3.0;
    return clamp(e + invSharpness, 0.0, 1.0);
}

// FFX_CACAO_CalculateEdges: slope-sensitive, from view depth
vec4 calculate_edges(float centerZ, float leftZ, float rightZ, float topZ, float bottomZ) {
    vec4 e = vec4(leftZ, rightZ, topZ, bottomZ) - centerZ;
    vec4 slopeAdjusted = e + e.yxwz;
    e = min(abs(e), abs(slopeAdjusted));
    return clamp(1.3 - e / (centerZ * 0.040), 0.0, 1.0);
}

// FFX_CACAO_CalculateNormal
vec3 calculate_normal(vec4 e, vec3 c, vec3 l, vec3 r, vec3 t, vec3 b) {
    vec4 accepted = vec4(e.x * e.z, e.z * e.y, e.y * e.w, e.w * e.x);
    l = normalize(l - c);
    r = normalize(r - c);
    t = normalize(t - c);
    b = normalize(b - c);
    vec3 n = vec3(0.0, 0.0, -0.0005);
    n += accepted.x * cross(l, t);
    n += accepted.y * cross(t, r);
    n += accepted.z * cross(r, b);
    n += accepted.w * cross(b, l);
    n = normalize(n);
    return dot(n, -c) < 0.0 ? -n : n;
}

// A point-mirror read of a layer texture at texel p
ivec2 mirrored(ivec2 p, ivec2 size) {
    ivec2 m = ivec2(abs(p.x) - (p.x < 0 ? 1 : 0), abs(p.y) - (p.y < 0 ? 1 : 0));
    return ivec2(m.x >= size.x ? 2 * size.x - 1 - m.x : m.x, m.y >= size.y ? 2 * size.y - 1 - m.y : m.y);
}
