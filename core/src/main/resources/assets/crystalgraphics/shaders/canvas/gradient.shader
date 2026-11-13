// A linear or radial gradient over a box in one draw, as CSS draws one; CgCanvasGradient packs every property. Drawn
// PREMULTIPLIED (CgCanvasMaterials).
//
// - Up to eight stops per draw, Skia's unrolled ramp (SkGradientShader); more stops are more draws, each owning a
//   window of t.
// - CSS's gradient line (Images 3 section 3.4.1): through the centre, |W sin a| + |H cos a| long, so 0 and 1 land on the
//   corners; _Axis makes t one dot product. RADIAL: t is the distance from _Centre over _Radii (section 3.5).
// - Premultiplied (section 3.4.3): a straight lerp toward `transparent` passes through a dark half-colour.
// - Dithered by half a level, or an 8-bit target bands a shallow ramp (Skia dithers by default).
// - WITH_MASK clips to elliptical corner radii with the box's SDF.
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
    // WITH_MASK only: CSS order, one vec4 per axis.
    _CornerRadiusX ("Corner Radii X (TL,TR,BR,BL)", vec4) = (0.0, 0.0, 0.0, 0.0)
    _CornerRadiusY ("Corner Radii Y (TL,TR,BR,BL)", vec4) = (0.0, 0.0, 0.0, 0.0)
    _BoxSize      ("Box Size (px)",                vec2)  = (0.0, 0.0)
}

Keywords {
    WITH_MASK ("Clipped to rounded corners", switched)
    RADIAL ("Radial", switched)
}

void surface(in CgUiInput i, inout CgUiSurface s) {
    // Clamped, so the rotated pad holds the end colours.
    vec2 uv = i.uv;
#ifdef RADIAL
    float t = length((uv * _BoxSize - _Centre) / _Radii);
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
    float dist = sdf_rounded_box((unclamped - 0.5) * _BoxSize, _BoxSize * 0.5, _CornerRadiusX, _CornerRadiusY);
    s.coverage = shape * sdf_coverage(dist, CG_QUAD_EDGE_ROTATED ? CG_QUAD_EDGE_FILTER : 1.0);
#else
    s.coverage *= shape;
#endif
    // The dither, inside the colour so the shape scales it: dither outside a rounded corner would stipple it back in.
    s.color = c + vec4((hash12(gl_FragCoord.xy) - 0.5) / 255.0);
    s.premultiplied = true;
}
