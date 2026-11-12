// The engine's canvas/box in the material format: a quad whose custom2 names its shape in CgShapeTable. Clip, opacity
// and premultiplying are the template's; each kind of fill says its colour and how much of the pixel it covers.
#domain ui
#include "crystalgraphics:shaders/lib/texel.glsl"
#include "crystalgraphics:shaders/lib/sdf.glsl"
Properties { _MainTex ("Main Texture", sampler2D) = "white" }

// Where in its tile a point of a nine-slice span falls, by CgShapeTable's modes; keep is 0 in a SPACE gap.
float cg_sliceParam(float local, float span, float mode, float n, float srcSize, out float keep) {
    keep = 1.0;
    if (span <= 0.0) return 0.0;
    if (mode < 0.5 || n <= 0.0) return clamp(local / span, 0.0, 1.0);
    if (mode < 2.5) return clamp(fract(local * n / span), 0.0, 1.0);
    float gap = (span - n * srcSize) / (n + 1.0);
    float cycle = srcSize + gap;
    float offset = local - gap;
    if (offset < 0.0) { keep = 0.0; return 0.0; }
    float within = offset - floor(offset / cycle) * cycle;
    if (within > srcSize) { keep = 0.0; return 0.0; }
    return srcSize > 0.0 ? within / srcSize : 0.0;
}

vec4 cg_sliceBounds(int shape, vec2 boxSize) {
    vec4 border = SHAPE_DATA(shape).sliceBorder;
    float scaleX = min(1.0, boxSize.x / max(1.0, border.x + border.z));
    float scaleY = min(1.0, boxSize.y / max(1.0, border.y + border.w));
    return vec4(border.x * scaleX, border.y * scaleY, boxSize.x - border.z * scaleX, boxSize.y - border.w * scaleY);
}

vec3 cg_slice9Uv(int shape, vec2 boxSize, vec2 p) {
    vec4 b = cg_sliceBounds(shape, boxSize);
    vec4 outerUv = SHAPE_DATA(shape).sliceOuterUv;
    vec4 innerUv = SHAPE_DATA(shape).sliceInnerUv;
    vec4 tiles = SHAPE_DATA(shape).sliceTiles;
    vec4 mode = SHAPE_DATA(shape).sliceMode;
    float x1 = b.x, y1 = b.y, x2 = b.z, y2 = b.w;
    float keepX = 1.0, keepY = 1.0, centerMask = 1.0;
    float u;
    if (p.x < x1) {
        u = mix(outerUv.x, innerUv.x, x1 > 0.0 ? clamp(p.x / x1, 0.0, 1.0) : 0.0);
    } else if (p.x > x2) {
        float denom = boxSize.x - x2;
        u = mix(innerUv.z, outerUv.z, denom > 0.0 ? clamp((p.x - x2) / denom, 0.0, 1.0) : 0.0);
    } else {
        u = mix(innerUv.x, innerUv.z, cg_sliceParam(p.x - x1, x2 - x1, mode.x, tiles.x, tiles.z, keepX));
    }
    float v;
    if (p.y < y1) {
        v = mix(outerUv.y, innerUv.y, y1 > 0.0 ? clamp(p.y / y1, 0.0, 1.0) : 0.0);
    } else if (p.y > y2) {
        float denom = boxSize.y - y2;
        v = mix(innerUv.w, outerUv.w, denom > 0.0 ? clamp((p.y - y2) / denom, 0.0, 1.0) : 0.0);
    } else {
        v = mix(innerUv.y, innerUv.w, cg_sliceParam(p.y - y1, y2 - y1, mode.y, tiles.y, tiles.w, keepY));
    }
    if (mode.z < 0.5 && p.x >= x1 && p.x <= x2 && p.y >= y1 && p.y <= y2) centerMask = 0.0;
    return vec3(u, v, keepX * keepY * centerMask);
}

void surface(in CgUiInput i, inout CgUiSurface s) {
    int shape = int(CG_QUAD_CUSTOM2 + 0.5);
    vec4 meta = SHAPE_DATA(shape).meta;
    int kind = int(meta.x + 0.5);
    if (kind == CG_SHAPE_PLAIN) {
        vec4 texel = CG_QUAD_EDGE_ROTATED ? cg_texel_aa_sample(_MainTex, i.uv, CG_QUAD_UV_RECT, CG_QUAD_EDGE_FILTER)
                : texture(_MainTex, i.uv);
        s.color = texel * i.color;
        return;
    }
    if (kind == CG_SHAPE_PREMULTIPLIED) {
        s.color = texture(_MainTex, i.uv) * i.color;
        s.premultiplied = true;
        return;
    }
    vec2 boxSize = meta.zw;
    vec2 uvUnclamped = mix(QUAD_DATA(CG_INSTANCE_ID).uv0, QUAD_DATA(CG_INSTANCE_ID).uv1, i.param);
    vec2 halfSize = boxSize * 0.5;
    vec2 localPos = (uvUnclamped - 0.5) * boxSize;
    vec4 radiiX = SHAPE_DATA(shape).radiiX;
    vec4 radiiY = SHAPE_DATA(shape).radiiY;
    float dist = sdf_rounded_box(localPos, halfSize, radiiX, radiiY);
    float ramp = CG_QUAD_EDGE_ROTATED ? CG_QUAD_EDGE_FILTER : 1.0;
    vec2 p = i.uv * boxSize;
    vec2 ddx = dFdx(p), ddy = dFdy(p);
    vec4 fillColor;
    if (kind == CG_SHAPE_NINE_SLICE) {
        vec4 outerUv = SHAPE_DATA(shape).sliceOuterUv;
        vec4 spriteRect = vec4(min(outerUv.xy, outerUv.zw), max(outerUv.xy, outerUv.zw));
        vec3 slice = cg_slice9Uv(shape, boxSize, p);
        fillColor = (CG_QUAD_EDGE_ROTATED ? cg_texel_aa_sample(_MainTex, slice.xy, spriteRect, CG_QUAD_EDGE_FILTER)
                : texture(_MainTex, slice.xy)) * slice.z;
        vec4 sb = cg_sliceBounds(shape, boxSize);
        vec2 fw = abs(ddx) + abs(ddy);
        bool onSeam = min(min(abs(p.x - sb.x), abs(p.x - sb.z)) - fw.x, min(abs(p.y - sb.y), abs(p.y - sb.w)) - fw.y) < 0.0;
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
        fillColor = CG_QUAD_EDGE_ROTATED ? cg_texel_aa_sample(_MainTex, i.uv, CG_QUAD_UV_RECT, CG_QUAD_EDGE_FILTER)
                : texture(_MainTex, i.uv);
    } else {
        fillColor = SHAPE_DATA(shape).fillColor;
    }
    fillColor *= i.color;
    vec4 color = fillColor;
    int flags = int(meta.y + 0.5);
    if ((flags & CG_SHAPE_BORDER) != 0) {
        vec4 w = SHAPE_DATA(shape).borderWidths;
        vec2 innerHalf = max(halfSize - vec2(w.x + w.z, w.y + w.w) * 0.5, vec2(0.0));
        vec2 innerShift = vec2(w.x - w.z, w.y - w.w) * 0.5;
        vec4 innerRx = max(radiiX - vec4(w.x, w.z, w.z, w.x), vec4(0.0));
        vec4 innerRy = max(radiiY - vec4(w.y, w.y, w.w, w.w), vec4(0.0));
        float innerCoverage = sdf_coverage(sdf_rounded_box(localPos - innerShift, innerHalf, innerRx, innerRy), ramp);
        vec4 edgeColor = SHAPE_DATA(shape).borderColor;
        if ((flags & CG_SHAPE_SPLIT_BORDER) != 0) {
            float dx = halfSize.x - abs(localPos.x);
            float dy = halfSize.y - abs(localPos.y);
            if (dy < dx) edgeColor = localPos.y < 0.0 ? SHAPE_DATA(shape).borderTop : SHAPE_DATA(shape).borderBottom;
        }
        color = mix(edgeColor, fillColor, innerCoverage);
    }
    s.color = color;
    s.coverage = sdf_coverage(dist, ramp);    // the rounded box's edge, not the quad's
}
