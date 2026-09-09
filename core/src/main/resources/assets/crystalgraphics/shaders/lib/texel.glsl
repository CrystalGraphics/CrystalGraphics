// Texel antialiasing for pixel art -- nearest everywhere, one screen pixel of blend at a texel
// boundary. A TECHNIQUE rather than an environment: it reads no instance buffer, so it is
// #included where it is wanted rather than injected by a cg_use pragma, like sdf.glsl and
// stroke.glsl beside it. The reconstruction width arrives as an argument for the same reason --
// a caller with the quad environment passes CG_QUAD_EDGE_FILTER.
//
//   #include "crystalgraphics:shaders/lib/texel.glsl"
//   vec4 t = cg_texel_aa_sample(_MainTex, uv, CG_QUAD_UV_RECT, CG_QUAD_EDGE_FILTER);
#pragma once

// -- CG_TEXEL_AA -- texel antialiasing for rotated pixel art -----------------------------------------
//
// The edge helpers above soften a quad's OUTLINE. Its texels are another matter: a nearest-sampled
// sprite rotated eight degrees is a staircase along every line inside it, because each screen pixel
// snaps to one texel. Linear filtering trades that for blur everywhere. This is the pixel-art
// compromise -- nearest everywhere except within one screen pixel of a texel boundary, where the two
// texels blend linearly. Blocks stay flat and crisp; their boundaries get exactly the antialiasing a
// geometric edge gets. Axis-aligned at an integer scale it reproduces nearest bit for bit, and a
// consumer gates it on CG_QUAD_EDGE_ROTATED regardless, so nothing at rest changes.
//
// Four taps, because the atlases this is for are nearest-filtered and hardware linear is not there
// to do the blend. `rect` is the quad's texel rectangle (uv0, uv1 in texels): the taps are held inside
// it, so a sprite in an atlas never reads its neighbour.
//
// fwidth is fragment-only, hence the stage guard -- cg_env is compiled into both stages.
#ifndef CG_VERTEX_STAGE
vec2 cg_texel_aa_position(vec2 texel, vec4 rect, float filterPx) {
    vec2 width = max(fwidth(texel), vec2(1.0e-6));
    // Pivot on the nearest texel BOUNDARY: further than half a screen pixel from it the sample sits on
    // the texel centre of its own side (flat), and within that band it slides linearly across to the
    // other side's centre (the blend). Pivoting on the centre instead puts the blend in the middle of
    // every texel and leaves the boundaries hard, which is precisely backwards.
    vec2 boundary = floor(texel + 0.5);
    vec2 p = boundary + clamp((texel - boundary) / (width * filterPx), -0.5, 0.5);
    return clamp(p, rect.xy, rect.zw);
}
// The two texel columns (or rows) a bilinear tap at `p` reads, and the weight of the second -- with
// both held inside the texels the rect covers, whether the rect is stated on texel boundaries or,
// as a sprite sheet usually is, inset to texel centres. Either way a sprite never reads its neighbour.
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
