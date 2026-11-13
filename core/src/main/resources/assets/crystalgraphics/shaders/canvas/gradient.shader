// A linear, radial or conic gradient over a box in one draw, as CSS draws one; CgCanvasGradient packs every property.
// Drawn PREMULTIPLIED (CgCanvasMaterials).
//
// - Up to eight stops per draw, Skia's unrolled ramp (SkGradientShader); more stops are more draws, each owning a
//   window of t.
// - CSS's gradient line (Images 3 section 3.4.1): through the centre, |W sin a| + |H cos a| long, so 0 and 1 land on the
//   corners; _Axis makes t one dot product. RADIAL: t is the distance from _Centre over _Radii (section 3.5). CONIC:
//   the turn about _Centre (Images 4 section 3.3, Skia's SkSweepGradient).
// - Premultiplied (section 3.4.3): a straight lerp toward `transparent` passes through a dark half-colour.
// - Dithered by half a level, or an 8-bit target bands a shallow ramp (Skia dithers by default).
// - WITH_MASK clips to elliptical corner radii with the box's SDF; _Border paints only the band inside that edge.
#domain ui
#include "crystalgraphics:shaders/lib/noise.glsl"
#include "crystalgraphics:shaders/lib/sdf.glsl"
Material { Primitive Quad }

Properties {
    // Premultiplied.
    _Color0       ("Stop 0 (premultiplied)",       vec4)  = (0.0, 0.0, 0.0, 0.0)
    _Color1       ("Stop 1 (premultiplied)",       vec4)  = (0.0, 0.0, 0.0, 0.0)
    _Color2       ("Stop 2 (premultiplied)",       vec4)  = (0.0, 0.0, 0.0, 0.0)
    _Color3       ("Stop 3 (premultiplied)",       vec4)  = (0.0, 0.0, 0.0, 0.0)
    _Color4       ("Stop 4 (premultiplied)",       vec4)  = (0.0, 0.0, 0.0, 0.0)
    _Color5       ("Stop 5 (premultiplied)",       vec4)  = (0.0, 0.0, 0.0, 0.0)
    _Color6       ("Stop 6 (premultiplied)",       vec4)  = (0.0, 0.0, 0.0, 0.0)
    _Color7       ("Stop 7 (premultiplied)",       vec4)  = (0.0, 0.0, 0.0, 0.0)
    _Positions0   ("Positions of stops 0-3",       vec4)  = (0.0, 1.0, 1.0, 1.0)
    _Positions1   ("Positions of stops 4-7",       vec4)  = (1.0, 1.0, 1.0, 1.0)
    _Count        ("Stops in this draw (2-8)",     float) = 2.0
    // t = 0.5 + dot(uv - 0.5, _Axis): (1, 0) is `to right`, (0, 1) `to bottom`.
    _Axis         ("Gradient line",                vec2)  = (1.0, 0.0)
    // RADIAL only, in px from the box's top-left.
    _Centre       ("Radial centre (px)",           vec2)  = (0.0, 0.0)
    _Radii        ("Radial radii (px)",            vec2)  = (1.0, 1.0)
    // The half-open range of t this draw owns, [x, y).
    _Window       ("Owned range of t",             vec2)  = (-1.0, 2.0)
    // CONIC only: where t = 0 points, in turns clockwise from up.
    _Start        ("Conic start (turns)",          float) = 0.0
    // WITH_MASK only: the band inside the rounded edge it paints, in px; 0 fills the box.
    _Border       ("Painted band (px)",            float) = 0.0
    // WITH_MASK only: CSS order, one vec4 per axis.
    _CornerRadiusX ("Corner Radii X (TL,TR,BR,BL)", vec4) = (0.0, 0.0, 0.0, 0.0)
    _CornerRadiusY ("Corner Radii Y (TL,TR,BR,BL)", vec4) = (0.0, 0.0, 0.0, 0.0)
    _BoxSize      ("Box Size (px)",                vec2)  = (0.0, 0.0)
}

Keywords {
    WITH_MASK ("Clipped to rounded corners", switched)
    RADIAL ("Radial", switched)
    CONIC ("Conic", switched)
}

void surface(in CgUiInput i, inout CgUiSurface s) {
    // Clamped, so the rotated pad holds the end colours.
    vec2 uv = i.uv;
#if defined(RADIAL)
    float t = length((uv * _BoxSize - _Centre) / _Radii);
#elif defined(CONIC)
    // CSS's conic-gradient: the turn about _Centre, 0 up and clockwise on the Y-down box, from _Start.
    vec2 d = uv * _BoxSize - _Centre;
    float t = fract(atan(d.x, -d.y) / 6.28318530718 - _Start);
#else
    float t = 0.5 + dot(uv - 0.5, _Axis);
#endif
    // Each interval mixes toward its end stop by a clamped fraction; the end colours pad either side. Two stops at
    // one position are a hard stop: max() keeps the division finite.
    vec4 stops[8] = vec4[8](_Color0, _Color1, _Color2, _Color3, _Color4, _Color5, _Color6, _Color7);
    float positions[8] = float[8](_Positions0.x, _Positions0.y, _Positions0.z, _Positions0.w,
                                  _Positions1.x, _Positions1.y, _Positions1.z, _Positions1.w);
    int count = int(_Count + 0.5);
    vec4 c = stops[0];
    for (int k = 1; k < 8; k++) {
        if (k >= count) break;
        float f = clamp((t - positions[k - 1]) / max(positions[k] - positions[k - 1], 1e-5), 0.0, 1.0);
        c = mix(c, stops[k], f);
    }
    // A straight tint over a premultiplied colour.
    c *= vec4(i.color.rgb * i.color.a, i.color.a);
    // Zero outside this draw's window: the other windows' draws own it.
    float shape = step(_Window.x, t) * (1.0 - step(_Window.y, t));
#ifdef WITH_MASK
    // The SDF finishes the straight sides too, so no edge coverage on top; the unclamped parameter, since past the
    // box is what it cuts.
    vec2 unclamped = mix(QUAD_DATA(CG_INSTANCE_ID).uv0, QUAD_DATA(CG_INSTANCE_ID).uv1, i.param);
    vec2 local = (unclamped - 0.5) * _BoxSize;
    float ramp = CG_QUAD_EDGE_ROTATED ? CG_QUAD_EDGE_FILTER : 1.0;
    s.coverage = shape * sdf_coverage(sdf_rounded_box(local, _BoxSize * 0.5, _CornerRadiusX, _CornerRadiusY), ramp);
    if (_Border > 0.0) {
        // A border's band: the box less the one inset by it, whose radii shrink by as much (CSS's inner border edge)
        vec2 inner = max(_BoxSize * 0.5 - _Border, vec2(0.0));
        float hole = sdf_rounded_box(local, inner, max(_CornerRadiusX - _Border, 0.0), max(_CornerRadiusY - _Border, 0.0));
        s.coverage *= 1.0 - sdf_coverage(hole, ramp);
    }
#else
    s.coverage *= shape;
#endif
    // The dither, inside the colour so the shape scales it: dither outside a rounded corner would stipple it back in.
    s.color = c + vec4((hash12(gl_FragCoord.xy) - 0.5) / 255.0);
    s.premultiplied = true;
}
