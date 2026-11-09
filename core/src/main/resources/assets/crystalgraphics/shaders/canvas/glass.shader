// Liquid glass over a blurred and a sharp backdrop, set up by CgCanvasGlass and CgCanvasBackdrop.
//     refract -> pick blurred/sharp -> saturate -> luminosity -> tint -> specular -> noise -> SDF mask
// Acrylic's order, refraction where a lens puts it. Each optional layer is a keyword. The backdrop is read where the
// pixel is, so a pane under any pose and camera, the world's included, reads what is behind it.
//
// The refraction comes from a surface height profile across the bezel (height -> normal -> Snell's law -> offset),
// not from the SDF distance, which gives a bevel rather than a lens.

#type none
#pragma cg_use quad
#pragma cg_use clip

#pragma cg_feature WITH_REFRACTION
#pragma cg_feature WITH_CHROMATIC
#pragma cg_feature WITH_SPECULAR
#pragma cg_feature WITH_NOISE
#pragma cg_permutation WITH_REFRACTION
#pragma cg_permutation WITH_CHROMATIC
#pragma cg_permutation WITH_SPECULAR
#pragma cg_permutation WITH_NOISE

#include "crystalgraphics:shaders/lib/sdf.glsl"
#include "crystalgraphics:shaders/lib/noise.glsl"
#include "crystalgraphics:shaders/lib/color.glsl"

// Target: the backdrop is a copy of the target, already in its encoding.
Tags { "RenderType" = "Transparent" "ColorSpace" = "Target" }
Queue = "Transparent"

Properties {
    _MainTex   ("Blurred backdrop", sampler2D) = "white"
    _SharpTex  ("Sharp backdrop",   sampler2D) = "white"
    _GridSize     ("The backdrops' size in texels",                  vec2) = (1.0, 1.0)
    // Where the bound target's pixel (0, 0) is in the backdrops' texels: 0 when drawn into the target captured.
    _TargetOffset ("The target's corner in the backdrops",           vec2) = (0.0, 0.0)
    // What it may sample, (u0, v0, u1, v1) bottom-left: what was captured around it, half a texel in.
    _CaptureRect  ("u0,v0,u1,v1", vec4) = (0.0, 0.0, 1.0, 1.0)

    _CornerRadiusX ("Corner Radii X (TL,TR,BR,BL)", vec4) = (0.0, 0.0, 0.0, 0.0)
    _CornerRadiusY ("Corner Radii Y (TL,TR,BR,BL)", vec4) = (0.0, 0.0, 0.0, 0.0)
    _BoxSize       ("Box size, in its quad's units", vec2) = (0.0, 0.0)

    _Tint       ("Tint (over the blur)", color) = (0.0, 0.0, 0.0, 0.0)
    _Saturation ("Saturation of backdrop", float) = 1.0
    // The share of the backdrop's brightness the tint's replaces: acrylic 0.96, Mica 1.0.
    _Luminosity ("Luminosity blend toward the tint", float) = 0.0
    _Bezel      ("Bezel width, in the box's units", float) = 8.0
    _Ior        ("Index of refraction",    float) = 1.5
    _Specular   ("Specular strength",      float) = 0.0
    _Glow       ("Broad glow along the light axis", float) = 0.10
    _EdgeHighlight ("Thin rim band strength",       float) = 0.25
    _EdgeWidth  ("Rim band width, in the box's units", float) = 3.0
    _RimAmbient ("Rim ambient (0 directional, 1 even)", float) = 0.5
    _Chromatic  ("Chromatic aberration",            float) = 0.20
    _Noise      ("Grain amount",           float) = 0.0
    _LightDir   ("Light direction",        vec2)  = (-0.55, -0.83)
    _LayerOpacity ("Layer Opacity",        float) = 1.0
}

struct v2f {
    // Grown by half a pixel when rotated, so the edge can antialias: CG_QUAD_EDGE_* in env/buffer/quad.glsl.
    vec2 param;
    vec4 color;
};

Pass {
    Tags { "LightMode" = "Forward" }

    // No Blend: CgMaterialState gives it, ALPHA. A screen instance turns the depth test off.
    RenderState {
        Cull OFF
        DepthTest LEQUAL
        DepthWrite OFF
    }

    // W3C's non-separable luminosity blend (Lum, ClipColor, SetLum): the backdrop keeps its hue and takes the tint's
    // brightness, what acrylic and Mica are mostly made of. Above `void vertex`: hoisted into both stages, and free of
    // fragment-only builtins.
    float cg_lum(vec3 c) { return dot(c, vec3(0.3, 0.59, 0.11)); }
    vec3 cg_clipColor(vec3 c) {
        float l = cg_lum(c);
        float n = min(c.r, min(c.g, c.b));
        float x = max(c.r, max(c.g, c.b));
        if (n < 0.0) c = l + (c - l) * l / max(l - n, 1e-5);
        if (x > 1.0) c = l + (c - l) * (1.0 - l) / max(x - l, 1e-5);
        return c;
    }
    vec3 cg_setLum(vec3 c, float l) { return cg_clipColor(c + vec3(l - cg_lum(c))); }

    // The glass's height across the bezel, x = 0 at the edge and 1 where it goes flat: Apple's convex squircle.
    float cg_glassHeight(float x) {
        float t = 1.0 - clamp(x, 0.0, 1.0);
        return pow(max(0.0, 1.0 - t * t * t * t), 0.25);
    }

    // The SDF's outward normal by central difference over e, about a pixel: no derivative builtins here, which once
    // reached a vertex stage.
    vec2 cg_sdfGradient(vec2 p, vec2 halfSize, vec4 rx, vec4 ry, float e) {
        float dx = sdf_rounded_box(p + vec2(e, 0.0), halfSize, rx, ry) - sdf_rounded_box(p - vec2(e, 0.0), halfSize, rx, ry);
        float dy = sdf_rounded_box(p + vec2(0.0, e), halfSize, rx, ry) - sdf_rounded_box(p - vec2(0.0, e), halfSize, rx, ry);
        return normalize(vec2(dx, dy) + vec2(1e-6, 0.0));
    }

    // The backdrop's colour at a point in its texels: what shows, its alpha unread (a target's alpha is whatever its
    // owner left). Mirrored once at the sampleable rect, as Chromium extends a backdrop: clamping smeared the edge's
    // last column across the bezel; folding more than once striped it.
    vec2 cg_backdropUv(vec2 px) {
        vec2 b = px / _GridSize;
        vec2 lo = _CaptureRect.xy;
        vec2 size = max(_CaptureRect.zw - lo, vec2(1e-6));
        vec2 t = clamp((b - lo) / size, -1.0, 2.0);
        return lo + (1.0 - abs(mod(t, 2.0) - 1.0)) * size;
    }

    // One lens tap: sharp at the bezel, where the refraction is, blurred in the interior.
    vec3 cg_lensTap(vec2 px, float edge) {
        vec2 b = cg_backdropUv(px);
        return mix(texture(_SharpTex, b).rgb, texture(_MainTex, b).rgb, smoothstep(0.0, 1.0, edge));
    }

    void vertex(out v2f o) {
        o.param = CG_QUAD_EDGE_PARAM;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(CG_QUAD_EDGE_WORLD_POS(o.param), 1.0);
        o.color = CG_QUAD_COLOR;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        // Before any discard: it takes derivatives. Unclamped uv: the SDF cuts past the box.
        float boxClip = CG_CLIP_QUAD_COVERAGE;
        vec2 uv = mix(QUAD_DATA(CG_INSTANCE_ID).uv0, QUAD_DATA(CG_INSTANCE_ID).uv1, i.param);
        float ramp = CG_QUAD_EDGE_ROTATED ? CG_QUAD_EDGE_FILTER : 1.0;
        vec2 halfSize = _BoxSize * 0.5;
        vec2 localPos = (uv - 0.5) * _BoxSize;
        // The box's units per target pixel, each screen axis: its pose at this pixel, perspective included. Inverted,
        // it carries the lens's offset from the box's units into pixels.
        mat2 unitsPerPx = mat2(dFdx(localPos), dFdy(localPos));
        float det = determinant(unitsPerPx);
        mat2 pxPerUnit = abs(det) > 1e-12 ? inverse(unitsPerPx) : mat2(0.0);
        vec2 px = gl_FragCoord.xy + _TargetOffset;
        float dist = sdf_rounded_box(localPos, halfSize, _CornerRadiusX, _CornerRadiusY);
        // Exactly one pixel of ramp, not sdf_coverage's two: glass replaces what is behind it, and a softer edge
        // let the unblurred backdrop bleed through every straight edge's last row.
        float coverage = clamp(0.5 - dist / (max(fwidth(dist), 1e-4) * ramp), 0.0, 1.0);

        // A target pixel in the box's units: the floors below are a pixel whatever the box is measured in.
        float unitPx = max(length(fwidth(localPos)), 1e-6);

        // The lens's surface: the box with every corner rounded at least a bezel wide. Under the box's own corners the
        // band reached the medial axis, where the nearest edge switches and the normal flips: a crease down each
        // diagonal. The outline and the rim keep the box's corners.
        float bezel = max(unitPx, _Bezel);
        float most = min(halfSize.x, halfSize.y);
        vec4 lensRx = min(max(_CornerRadiusX, vec4(bezel)), vec4(most));
        vec4 lensRy = min(max(_CornerRadiusY, vec4(bezel)), vec4(most));
        float lensDist = sdf_rounded_box(localPos, halfSize, lensRx, lensRy);

        // 0 at the boundary, 1 where the glass goes flat. No bezel is no rim: frosted to the edge.
        float edge  = _Bezel > 0.0 ? clamp(-lensDist / bezel, 0.0, 1.0) : 1.0;

        vec2 disp = vec2(0.0, 0.0);
        vec2 grad = vec2(0.0, 0.0);
        float slope = 0.0;

#if defined(WITH_REFRACTION) || defined(WITH_SPECULAR)
        grad = cg_sdfGradient(localPos, halfSize, lensRx, lensRy, 0.5 * unitPx);
        // The normal's tilt by central difference, clamped: at x = 0 it is unbounded.
        const float d = 0.02;
        slope = (cg_glassHeight(edge + d) - cg_glassHeight(edge - d)) / (2.0 * d);
        slope = min(slope, 8.0);
#endif

#ifdef WITH_REFRACTION
        // Snell's law for a ray perpendicular to the backdrop: theta1 = atan(slope), the offset tan(theta1 - theta2)
        // over the bezel's thickness. Zero where the glass is flat.
        float theta1 = atan(slope);
        float theta2 = asin(clamp(sin(theta1) / max(1.0, _Ior), -1.0, 1.0));
        float shift  = tan(theta1 - theta2) * bezel;
        disp = pxPerUnit * (grad * shift);
#endif

#ifdef WITH_CHROMATIC
        // Three taps at three displacement scales, one channel each: each wavelength refracts by its own angle. Red
        // bends most, blue least.
        vec4 c = vec4(cg_lensTap(px + disp * (1.0 + 0.20 * _Chromatic), edge).r,
                      cg_lensTap(px + disp * (1.0 + 0.10 * _Chromatic), edge).g,
                      cg_lensTap(px + disp, edge).b, 1.0);
#else
        vec4 c = vec4(cg_lensTap(px + disp, edge), 1.0);
#endif

        // The colour work in sRGB, as a browser composites, so a look reads the same on the screen and in the linear HDR
        // scene; the blur stays linear. Tinted in linear light, a 12% white pane came out 38% white.
        bool linearScene = CG_LINEAR_SCENE;
        if (linearScene) c.rgb = linear_to_srgb(max(c.rgb, vec3(0.0)));

        // A heavy blur greys the scene; saturation brings its colour back.
        float lum = luminance(c.rgb);
        c.rgb = mix(vec3(lum), c.rgb, _Saturation);

        // Luminosity then tint, WinUI acrylic's order: the backdrop takes the tint's brightness and keeps its hue.
        c.rgb = mix(c.rgb, cg_setLum(c.rgb, cg_lum(_Tint.rgb)), _Luminosity);
        c.rgb = mix(c.rgb, _Tint.rgb, _Tint.a);

#ifdef WITH_SPECULAR
        // Symmetric about the light axis, |dot|: light entering one edge leaves through the opposite one. One-sided
        // lighting reads as an embossed button.
        float proj = abs(dot(grad, normalize(_LightDir + vec2(1e-6, 0.0))));

        // Two terms, the thin rim dominant (0.25 against 0.10): a broad term alone reads as bloom.
        float glow = _Glow * pow(proj, 1.5) * (1.0 - edge);

        // 1 at the boundary, 0 one band in; in the box's units, whatever the bezel.
        float band = clamp(1.0 + dist / max(0.5 * unitPx, _EdgeWidth), 0.0, 1.0);

        // The rim is an ambient floor plus the directional pair: proj alone is 0 at the corners perpendicular to the
        // light, and the outline vanished there. _RimAmbient 0 is fully directional, 1 an even hairline.
        float rimLight = mix(pow(proj, 1.5), 1.0, clamp(_RimAmbient, 0.0, 1.0));
        float rim  = _EdgeHighlight * band * rimLight;

        c.rgb += vec3(glow + rim) * _Specular;
#endif

#ifdef WITH_NOISE
        // Grain: over a flat backdrop it is what reads as a material, and it breaks up banding.
        c.rgb += (hash12(gl_FragCoord.xy) - 0.5) * _Noise;
#endif

        // The quad's colour tints rgb; its alpha scales the pane.
        c.rgb *= i.color.rgb;
        // Held to 0..1, as the screen's 8-bit target holds it: saturation above 1 and the highlight pass white, which the
        // HDR scene keeps and the post stack rolls toward white and blooms.
        if (linearScene) c.rgb = srgb_to_linear(clamp(c.rgb, 0.0, 1.0));
        c.a = coverage * _LayerOpacity * CG_QUAD_OPACITY * i.color.a;
        fragColor = vec4(c.rgb, c.a * boxClip);
    }
}
