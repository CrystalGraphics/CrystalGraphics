// CgPalette's shader environment: each spatial node's affine into the bound raster pass's target, and each effect
// node's opacity.
//
// INJECTED BY `#pragma cg_use palette`, which `quad`, `curve` and `clip` require, so their macros can read it -- a material
// never declares it itself. Compiled into every stage; fragment-only code guards itself against the vertex and compute stages.
//
// A record's `node` field packs both of its nodes: spatial + 4096 * effect (CgPalette.pack). Node 0 is the pass's own
// space at opacity 1 and its entry is never read, so a record at the root costs one branch. The packed value is an
// exact integer below 2^24, which a float holds exactly and an int conversion reads back -- adding 0.5 would round
// past 2^23.
#pragma once

int cg_node_spatial(float node) {
    return int(node) & 4095;
}

int cg_node_effect(float node) {
    return int(node) >> 12;
}

// A point of the node's space, in the target's.
vec3 cg_spatial_point(float node, vec3 p) {
    int s = cg_node_spatial(node);
    if (s == 0) return p;
    vec4 r0 = PALETTE_DATA(s).toTarget0;
    vec4 r1 = PALETTE_DATA(s).toTarget1;
    return vec3(r0.x * p.x + r0.y * p.y + r0.z, r1.x * p.x + r1.y * p.y + r1.z, p.z);
}

// A direction of the node's space, in the target's: the affine without its translation.
vec3 cg_spatial_vector(float node, vec3 v) {
    int s = cg_node_spatial(node);
    if (s == 0) return v;
    vec4 r0 = PALETTE_DATA(s).toTarget0;
    vec4 r1 = PALETTE_DATA(s).toTarget1;
    return vec3(r0.x * v.x + r0.y * v.y, r1.x * v.x + r1.y * v.y, v.z);
}

// A gradient direction dir, read as t = dot(p - origin, dir), for points mapped through the node: the inverse
// transpose of its affine, so t is the same at the same point.
vec2 cg_spatial_covector(float node, vec2 dir) {
    int s = cg_node_spatial(node);
    if (s == 0) return dir;
    vec4 r0 = PALETTE_DATA(s).toTarget0;
    vec4 r1 = PALETTE_DATA(s).toTarget1;
    float det = r0.x * r1.y - r0.y * r1.x;
    return vec2(r1.y * dir.x - r1.x * dir.y, -r0.y * dir.x + r0.x * dir.y) / det;
}

// What the node scales a length by: a stroke's width under it.
float cg_spatial_scale(float node) {
    int s = cg_node_spatial(node);
    if (s == 0) return 1.0;
    vec4 r0 = PALETTE_DATA(s).toTarget0;
    vec4 r1 = PALETTE_DATA(s).toTarget1;
    return sqrt(abs(r0.x * r1.y - r0.y * r1.x));
}

// Whether node s is turned or sheared in the target: an edge under it is off the pixel grid.
bool cg_spatial_rotated(int s) {
    return PALETTE_DATA(s).toTarget0.y != 0.0 || PALETTE_DATA(s).toTarget1.x != 0.0;
}

// The record's group opacity: what a material that honours effect nodes multiplies by.
float cg_effect_opacity(float node) {
    int e = cg_node_effect(node);
    return e == 0 ? 1.0 : PALETTE_DATA(e).effect.x;
}

#if !defined(CG_VERTEX_STAGE) && !defined(CG_COMPUTE_STAGE)
// gl_FragCoord in spatial node s's own space: the target's rows flipped and the node's affine inverted.
vec2 cg_spatial_from_fragment(int s) {
    vec4 r0 = PALETTE_DATA(s).fromFragment0;
    vec4 r1 = PALETTE_DATA(s).fromFragment1;
    vec3 frag = vec3(gl_FragCoord.xy, 1.0);
    return vec2(dot(r0.xyz, frag), dot(r1.xyz, frag));
}
#endif
