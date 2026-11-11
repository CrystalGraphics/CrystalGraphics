// The canvas's box: a quad whose custom2 names its shape in the recording's CgShapeTable, so every box is one pipeline.
// One rounded-box SDF cuts the outline, the border's inner edge and the fill alike; radii are elliptical, TL TR BR BL.
// Off-axis, textured fills take the texel filter (lib/texel.glsl) and a nine-slice supersamples its seams.
// Premultiplied out. CG_QUAD_OPACITY fades a whole item, border and fill.

#type none
#pragma cg_use quad
#pragma cg_use clip
#pragma cg_use shape

#include "crystalgraphics:shaders/lib/texel.glsl"

#include "crystalgraphics:shaders/lib/sdf.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

Properties {
    _MainTex ("Main Texture", sampler2D) = "white"
}

struct v2f {
    // Grown by half a pixel when rotated, so the edge and the SDF ramp fit: CG_QUAD_EDGE_* in env/buffer/quad.glsl.
    vec2 param;
    vec4 color;
};

Pass {
    Tags { "LightMode" = "Forward" }

    // No Blend: CgMaterialState gives it, PREMULTIPLIED. A screen instance turns the depth test off.
    RenderState {
        Cull OFF
        DepthTest LEQUAL
        DepthWrite OFF
    }

    // Where in its tile a point of a nine-slice span falls, by CgShapeTable's modes; keep is 0 in a SPACE gap.
    // REPEAT and ROUND differ only in n, counted on the CPU.
    float cg_sliceParam(float local, float span, float mode, float n, float srcSize, out float keep) {
        keep = 1.0;
        if (span <= 0.0) return 0.0;
        if (mode < 0.5 || n <= 0.0) return clamp(local / span, 0.0, 1.0); // STRETCH
        if (mode < 2.5) { // REPEAT (1) or ROUND (2)
            return clamp(fract(local * n / span), 0.0, 1.0);
        }
        // SPACE
        float gap = (span - n * srcSize) / (n + 1.0);
        float cycle = srcSize + gap;
        float offset = local - gap;
        if (offset < 0.0) { keep = 0.0; return 0.0; }
        float within = offset - floor(offset / cycle) * cycle;
        if (within > srcSize) { keep = 0.0; return 0.0; }
        return srcSize > 0.0 ? within / srcSize : 0.0;
    }

    /** Where the nine regions meet, in box-local pixels: (x1, y1, x2, y2). */
    vec4 cg_sliceBounds(int shape, vec2 boxSize) {
        vec4 border = SHAPE_DATA(shape).sliceBorder;
        float scaleX = min(1.0, boxSize.x / max(1.0, border.x + border.z));
        float scaleY = min(1.0, boxSize.y / max(1.0, border.y + border.w));
        return vec4(border.x * scaleX, border.y * scaleY,
                    boxSize.x - border.z * scaleX, boxSize.y - border.w * scaleY);
    }

    // Box-local pixels to atlas uv (.xy) and the keep mask (.z). Samples nothing: it reaches the vertex stage too.
    vec3 cg_slice9Uv(int shape, vec2 boxSize, vec2 p) {
        vec4 b = cg_sliceBounds(shape, boxSize);
        vec4 outerUv = SHAPE_DATA(shape).sliceOuterUv;
        vec4 innerUv = SHAPE_DATA(shape).sliceInnerUv;
        vec4 tiles = SHAPE_DATA(shape).sliceTiles;
        vec4 mode = SHAPE_DATA(shape).sliceMode;
        float x1 = b.x, y1 = b.y, x2 = b.z, y2 = b.w;
        float keepX = 1.0;
        float keepY = 1.0;
        float centerMask = 1.0;

        float u;
        if (p.x < x1) {
            u = mix(outerUv.x, innerUv.x, x1 > 0.0 ? clamp(p.x / x1, 0.0, 1.0) : 0.0);
        } else if (p.x > x2) {
            float denom = boxSize.x - x2;
            u = mix(innerUv.z, outerUv.z, denom > 0.0 ? clamp((p.x - x2) / denom, 0.0, 1.0) : 0.0);
        } else {
            float t = cg_sliceParam(p.x - x1, x2 - x1, mode.x, tiles.x, tiles.z, keepX);
            u = mix(innerUv.x, innerUv.z, t);
        }

        float v;
        if (p.y < y1) {
            v = mix(outerUv.y, innerUv.y, y1 > 0.0 ? clamp(p.y / y1, 0.0, 1.0) : 0.0);
        } else if (p.y > y2) {
            float denom = boxSize.y - y2;
            v = mix(innerUv.w, outerUv.w, denom > 0.0 ? clamp((p.y - y2) / denom, 0.0, 1.0) : 0.0);
        } else {
            float t = cg_sliceParam(p.y - y1, y2 - y1, mode.y, tiles.y, tiles.w, keepY);
            v = mix(innerUv.y, innerUv.w, t);
        }

        if (mode.z < 0.5 && p.x >= x1 && p.x <= x2 && p.y >= y1 && p.y <= y2) {
            centerMask = 0.0;
        }
        return vec3(u, v, keepX * keepY * centerMask);
    }

    // Clamped first, as a blend unit clamps: an icon raster's summed coverage runs past 1.
    vec4 cg_premultiply(vec3 rgb, float a) {
        float alpha = clamp(a, 0.0, 1.0);
        return vec4(clamp(rgb, 0.0, 1.0) * alpha, alpha);
    }

    void vertex(out v2f o) {
        o.param = CG_QUAD_EDGE_PARAM;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(CG_QUAD_EDGE_WORLD_POS(o.param), 1.0);
        o.color = CG_QUAD_COLOR;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        // Before any branch: it takes derivatives. The branches below are uniform per instance.
        float boxClip = CG_CLIP_QUAD_COVERAGE;
        int shape = int(CG_QUAD_CUSTOM2 + 0.5);
        vec4 meta = SHAPE_DATA(shape).meta;
        int kind = int(meta.x + 0.5);
        vec2 uv = CG_QUAD_EDGE_UV(i.param);

        if (kind == CG_SHAPE_PLAIN) {
            vec4 texel = CG_QUAD_EDGE_ROTATED
                    ? cg_texel_aa_sample(_MainTex, uv, CG_QUAD_UV_RECT, CG_QUAD_EDGE_FILTER)
                    : texture(_MainTex, uv);
            vec4 plain = texel * i.color;
            float a = plain.a * (CG_QUAD_EDGE_COVERAGE(i.param) * boxClip) * CG_QUAD_OPACITY;
            fragColor = cg_premultiply(plain.rgb, a);
            return;
        }
        if (kind == CG_SHAPE_PREMULTIPLIED) {
            // A layer is at screen resolution: no texel filter.
            fragColor = texture(_MainTex, uv) * i.color;
            fragColor *= CG_QUAD_EDGE_COVERAGE(i.param);
            fragColor *= CG_QUAD_OPACITY * boxClip;
            return;
        }

        // Unclamped for the SDF, which cuts past the box; the fills sample the clamped uv.
        vec2 boxSize = meta.zw;
        vec2 uvUnclamped = mix(QUAD_DATA(CG_INSTANCE_ID).uv0, QUAD_DATA(CG_INSTANCE_ID).uv1, i.param);
        vec2 halfSize = boxSize * 0.5;
        vec2 localPos = (uvUnclamped - 0.5) * boxSize;
        vec4 radiiX = SHAPE_DATA(shape).radiiX;
        vec4 radiiY = SHAPE_DATA(shape).radiiY;
        float dist = sdf_rounded_box(localPos, halfSize, radiiX, radiiY);
        float ramp = CG_QUAD_EDGE_ROTATED ? CG_QUAD_EDGE_FILTER : 1.0;
        float coverage = sdf_coverage(dist, ramp);

        // Outside any branch: a seam is where neighbouring fragments disagree.
        vec2 p = uv * boxSize;
        vec2 ddx = dFdx(p), ddy = dFdy(p);

        vec4 fillColor;
        if (kind == CG_SHAPE_NINE_SLICE) {
            vec4 outerUv = SHAPE_DATA(shape).sliceOuterUv;
            vec4 spriteRect = vec4(min(outerUv.xy, outerUv.zw), max(outerUv.xy, outerUv.zw));
            vec3 slice = cg_slice9Uv(shape, boxSize, p);
            // All four channels: a colour left under zero alpha would fringe the border's inner edge.
            fillColor = (CG_QUAD_EDGE_ROTATED
                    ? cg_texel_aa_sample(_MainTex, slice.xy, spriteRect, CG_QUAD_EDGE_FILTER)
                    : texture(_MainTex, slice.xy)) * slice.z;

            // A seam between regions is geometry, where the texel filter's texel-space fwidth jumps: rotated, supersample
            // the remap 8x8 over the pixel's footprint on the seam's line.
            vec4 sb = cg_sliceBounds(shape, boxSize);
            vec2 fw = abs(ddx) + abs(ddy);
            bool onSeam = min(min(abs(p.x - sb.x), abs(p.x - sb.z)) - fw.x,
                              min(abs(p.y - sb.y), abs(p.y - sb.w)) - fw.y) < 0.0;
            if (CG_QUAD_EDGE_ROTATED && onSeam) {
                vec4 acc = vec4(0.0);
                for (int sy = 0; sy < 8; ++sy) {
                    for (int sx = 0; sx < 8; ++sx) {
                        vec2 o = (vec2(float(sx), float(sy)) - 3.5) * 0.125;
                        vec3 sub = cg_slice9Uv(shape, boxSize, p + o.x * ddx + o.y * ddy);
                        acc += texture(_MainTex, sub.xy) * sub.z;
                    }
                }
                fillColor = acc * (1.0 / 64.0);
            }
        } else if (kind == CG_SHAPE_TEXTURE) {
            fillColor = CG_QUAD_EDGE_ROTATED
                    ? cg_texel_aa_sample(_MainTex, uv, CG_QUAD_UV_RECT, CG_QUAD_EDGE_FILTER)
                    : texture(_MainTex, uv);
        } else {
            fillColor = SHAPE_DATA(shape).fillColor;
        }

        // The quad's colour tints the fill, never the border.
        fillColor *= i.color;

        vec4 color = fillColor;
        int flags = int(meta.y + 0.5);
        if ((flags & CG_SHAPE_BORDER) != 0) {
            // The inner edge is its own rounded box (CSS Backgrounds 3 section 5.3): inset by each side's width, each inner
            // radius the outer less the two sides meeting there.
            vec4 w = SHAPE_DATA(shape).borderWidths;
            vec2 innerHalf = max(halfSize - vec2(w.x + w.z, w.y + w.w) * 0.5, vec2(0.0));
            vec2 innerShift = vec2(w.x - w.z, w.y - w.w) * 0.5;
            vec4 innerRx = max(radiiX - vec4(w.x, w.z, w.z, w.x), vec4(0.0));
            vec4 innerRy = max(radiiY - vec4(w.y, w.y, w.w, w.w), vec4(0.0));
            float innerDist = sdf_rounded_box(localPos - innerShift, innerHalf, innerRx, innerRy);
            float innerCoverage = sdf_coverage(innerDist, ramp);
            vec4 edgeColor = SHAPE_DATA(shape).borderColor;
            if ((flags & CG_SHAPE_SPLIT_BORDER) != 0) {
                // Nearer the top or bottom edge than a side: that edge's colour (Y down). Unity's inset bevel.
                float dx = halfSize.x - abs(localPos.x);
                float dy = halfSize.y - abs(localPos.y);
                if (dy < dx) edgeColor = localPos.y < 0.0 ? SHAPE_DATA(shape).borderTop : SHAPE_DATA(shape).borderBottom;
            }
            color = mix(edgeColor, fillColor, innerCoverage);
        }

        color.a *= coverage;
        fragColor = cg_premultiply(color.rgb, color.a * boxClip * CG_QUAD_OPACITY);
    }
}
