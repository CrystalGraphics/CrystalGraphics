// Texel antialiasing for rotated pixel art: nearest everywhere but within one screen pixel of a texel boundary, where
// the two texels blend, so blocks stay crisp and their edges antialias as a geometric edge does. Axis-aligned at an
// integer scale it is nearest bit for bit. Four taps, since the atlases are nearest-filtered; held inside the quad's
// rect, so a sprite never reads its atlas neighbour. Fragment-only (fwidth).
//
//   vec4 t = cg_texel_aa_sample(_MainTex, uv, CG_QUAD_UV_RECT, CG_QUAD_EDGE_FILTER);
#pragma once

#if !defined(CG_VERTEX_STAGE) && !defined(CG_COMPUTE_STAGE)
vec2 cg_texel_aa_position(vec2 texel, vec4 rect, float filterPx) {
    vec2 width = max(fwidth(texel), vec2(1.0e-6));
    // Pivot on the nearest boundary, not the centre: flat on either side, sliding across within the band.
    vec2 boundary = floor(texel + 0.5);
    vec2 p = boundary + clamp((texel - boundary) / (width * filterPx), -0.5, 0.5);
    return clamp(p, rect.xy, rect.zw);
}
// The two texel columns (or rows) a tap at `p` reads and the second's weight, inside the rect whether it is stated on
// texel boundaries or inset to centres.
void cg_texel_aa_taps(float p, float lo, float hi, out float i0, out float i1, out float f) {
    float base = floor(p - 0.5);
    f = p - 0.5 - base;
    float first = floor(lo), last = max(first, ceil(hi) - 1.0);
    i0 = clamp(base, first, last);
    i1 = clamp(base + 1.0, first, last);
}
vec4 cg_texel_aa_sample(sampler2D tex, vec2 uv, vec4 uvRect, float filterPx) {
    vec2 size = vec2(textureSize(tex, 0));
    vec4 rect = uvRect * size.xyxy;
    vec2 p = cg_texel_aa_position(uv * size, rect, filterPx);
    vec2 i0, i1, f;
    cg_texel_aa_taps(p.x, rect.x, rect.z, i0.x, i1.x, f.x);
    cg_texel_aa_taps(p.y, rect.y, rect.w, i0.y, i1.y, f.y);
    vec4 a = texture(tex, (vec2(i0.x, i0.y) + 0.5) / size), b = texture(tex, (vec2(i1.x, i0.y) + 0.5) / size);
    vec4 c = texture(tex, (vec2(i0.x, i1.y) + 0.5) / size), d = texture(tex, (vec2(i1.x, i1.y) + 0.5) / size);
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}
vec4 cg_texel_aa_sample(sampler2DArray tex, vec3 uvw, vec4 uvRect, float filterPx) {
    vec2 size = vec2(textureSize(tex, 0).xy);
    vec4 rect = uvRect * size.xyxy;
    vec2 p = cg_texel_aa_position(uvw.xy * size, rect, filterPx);
    vec2 i0, i1, f;
    cg_texel_aa_taps(p.x, rect.x, rect.z, i0.x, i1.x, f.x);
    cg_texel_aa_taps(p.y, rect.y, rect.w, i0.y, i1.y, f.y);
    vec4 a = texture(tex, vec3((vec2(i0.x, i0.y) + 0.5) / size, uvw.z));
    vec4 b = texture(tex, vec3((vec2(i1.x, i0.y) + 0.5) / size, uvw.z));
    vec4 c = texture(tex, vec3((vec2(i0.x, i1.y) + 0.5) / size, uvw.z));
    vec4 d = texture(tex, vec3((vec2(i1.x, i1.y) + 0.5) / size, uvw.z));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}
#endif
