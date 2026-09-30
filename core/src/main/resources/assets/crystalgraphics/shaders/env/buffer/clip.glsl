// CgClipTable's shader environment: the frame's rounded clips, read in the fragment stage.
//
// INJECTED BY `#pragma cg_use clip`, immediately after the table's own declaration. A material that also
// declares `quad` or `curve` multiplies what it draws by CG_CLIP_QUAD_COVERAGE or CG_CLIP_CURVE_COVERAGE:
// its alpha when it outputs straight alpha, its whole colour when it outputs premultiplied.
//
// An entry is a rounded box in its own space, reached from gl_FragCoord through toLocal0/toLocal1 (rows of a
// 2x3 affine), less the band between its outer and inner edges -- what a mask drawn with a transparent border
// reveals. The distance and the ramp are gui_rect's, so an edge antialiases as a rect's own does. toLocal0.w
// is the parent entry and toLocal1.w the ramp: 1 on the pixel grid, wider off it.
#pragma once

#include "crystalgraphics:shaders/lib/sdf.glsl"

// CgClipTable.MAX_DEPTH, which must agree.
#define CG_CLIP_MAX_DEPTH 4

#ifndef CG_VERTEX_STAGE
float cg_clip_distance(vec2 local, vec4 rect, vec4 rx, vec4 ry) {
    return sdf_rounded_box(local - (rect.xy + rect.zw) * 0.5, (rect.zw - rect.xy) * 0.5, rx, ry);
}

float cg_clip_entry_coverage(int n) {
    vec4 row0 = CLIP_DATA(n).toLocal0;
    vec4 row1 = CLIP_DATA(n).toLocal1;
    vec3 frag = vec3(gl_FragCoord.xy, 1.0);
    vec2 local = vec2(dot(row0.xyz, frag), dot(row1.xyz, frag));
    float ramp = row1.w;
    vec4 outer = CLIP_DATA(n).outer;
    float coverage = sdf_coverage(cg_clip_distance(local, outer, CLIP_DATA(n).outerRx, CLIP_DATA(n).outerRy), ramp);
    vec4 inner = CLIP_DATA(n).inner;
    // An inner rect with x1 < x0 is none: a box with no border has no inner edge.
    if (inner.z >= inner.x) {
        coverage *= sdf_coverage(cg_clip_distance(local, inner, CLIP_DATA(n).innerRx, CLIP_DATA(n).innerRy), ramp);
    }
    // On the pixel grid the mask quad this replaces was rasterised: a pixel whose centre is outside got nothing.
    bool outside = local.x < outer.x || local.y < outer.y || local.x >= outer.z || local.y >= outer.w;
    return ramp < 1.25 && outside ? 0.0 : coverage;
}

// Entry 0 is no clip. The entry is one value per draw, so the walk is uniform across a primitive and the
// derivatives sdf_coverage takes are defined.
float cg_clip_coverage(float entry) {
    float coverage = 1.0;
    int n = int(entry + 0.5);
    for (int depth = 0; depth < CG_CLIP_MAX_DEPTH && n > 0; depth++) {
        coverage *= cg_clip_entry_coverage(n);
        n = int(CLIP_DATA(n).toLocal0.w + 0.5);
    }
    return coverage;
}
#endif

#define CG_CLIP_QUAD_COVERAGE cg_clip_coverage(QUAD_DATA(CG_INSTANCE_ID).clip)
#define CG_CLIP_CURVE_COVERAGE cg_clip_coverage(CURVE_DATA(CG_INSTANCE_ID).clip)
