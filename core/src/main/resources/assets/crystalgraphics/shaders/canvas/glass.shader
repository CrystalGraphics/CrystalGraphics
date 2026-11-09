// Liquid glass over a blurred and a sharp backdrop, set up by CgCanvasGlass.
//     refract -> pick blurred/sharp -> saturate -> luminosity -> tint -> specular -> noise -> SDF mask
// Acrylic's order, refraction where a lens puts it. Each optional layer is a keyword.
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

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

Properties {
    _MainTex   ("Blurred backdrop", sampler2D) = "white"
    _SharpTex  ("Sharp backdrop",   sampler2D) = "white"
    // Where the element sits in the shared capture: an affine map, so a rotated element reads a rotated patch. v is
    // inverted: framebuffers are bottom-left origin.
    _BackdropOrigin ("uv of the element's origin", vec2) = (0.0, 1.0)
    _BackdropAxisX  ("uv step across its width",   vec2) = (1.0, 0.0)
    _BackdropAxisY  ("uv step down its height",    vec2) = (0.0, -1.0)
    // WHAT IT MAY SAMPLE, (u0, vBottom, u1, vTop): its rect padded by the lens's reach and bounded by its clip.
    _CaptureRect  ("u0,v0,u1,v1", vec4) = (0.0, 0.0, 1.0, 1.0)

    _CornerRadiusX ("Corner Radii X (TL,TR,BR,BL)", vec4) = (0.0, 0.0, 0.0, 0.0)
    _CornerRadiusY ("Corner Radii Y (TL,TR,BR,BL)", vec4) = (0.0, 0.0, 0.0, 0.0)
    _BoxSize       ("Box Size (px)",                vec2) = (0.0, 0.0)

    _Tint       ("Tint (over the blur)", color) = (0.0, 0.0, 0.0, 0.0)
    _Saturation ("Saturation of backdrop", float) = 1.0
    // The share of the backdrop's brightness the tint's replaces: acrylic 0.96, Mica 1.0.
    _Luminosity ("Luminosity blend toward the tint", float) = 0.0
    _Bezel      ("Bezel width (px)",       float) = 8.0
    _Ior        ("Index of refraction",    float) = 1.5
    _Specular   ("Specular strength",      float) = 0.0
    _Glow       ("Broad glow along the light axis", float) = 0.10
    _EdgeHighlight ("Thin rim band strength",       float) = 0.25
    _EdgeWidth  ("Rim band width (px)",             float) = 3.0
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

    // The SDF's outward normal by central difference: no derivative builtins, which once reached a vertex stage.
    vec2 cg_sdfGradient(vec2 p, vec2 halfSize) {
        const float e = 1.0;
        float dx = sdf_rounded_box(p + vec2(e, 0.0), halfSize, _CornerRadiusX, _CornerRadiusY)
                 - sdf_rounded_box(p - vec2(e, 0.0), halfSize, _CornerRadiusX, _CornerRadiusY);
        float dy = sdf_rounded_box(p + vec2(0.0, e), halfSize, _CornerRadiusX, _CornerRadiusY)
                 - sdf_rounded_box(p - vec2(0.0, e), halfSize, _CornerRadiusX, _CornerRadiusY);
        return normalize(vec2(dx, dy) + vec2(1e-6, 0.0));
    }

    // A backdrop sample un-premultiplied: a capture holds premultiplied colour. Floored at one 8-bit step.
    vec4 cg_backdrop(sampler2D tex, vec2 uv) {
        vec4 c = texture(tex, uv);
        c.rgb /= max(c.a, 1.0 / 255.0);
        return c;
    }

    // Element uv to backdrop uv through the element's pose. Mirrored once at the sampleable rect, as Chromium extends
    // a backdrop: clamping smeared the edge's last column across the bezel; folding more than once striped it.
    vec2 cg_backdropUv(vec2 uv) {
        vec2 b = _BackdropOrigin + _BackdropAxisX * uv.x + _BackdropAxisY * uv.y;
        vec2 lo = _CaptureRect.xy;
        vec2 size = max(_CaptureRect.zw - lo, vec2(1e-6));
        vec2 t = clamp((b - lo) / size, -1.0, 2.0);
        return lo + (1.0 - abs(mod(t, 2.0) - 1.0)) * size;
    }

    // One lens tap: sharp at the bezel, where the refraction is, blurred in the interior.
    vec4 cg_lensTap(vec2 uv, float edge) {
        vec2 b = cg_backdropUv(uv);
        return mix(cg_backdrop(_SharpTex, b), cg_backdrop(_MainTex, b), smoothstep(0.0, 1.0, edge));
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
        float dist = sdf_rounded_box(localPos, halfSize, _CornerRadiusX, _CornerRadiusY);
        // Exactly one pixel of ramp, not sdf_coverage's two: glass replaces what is behind it, and a softer edge
        // let the unblurred backdrop bleed through every straight edge's last row.
        float coverage = clamp(0.5 - dist / (max(fwidth(dist), 1e-4) * ramp), 0.0, 1.0);

        // 0 at the boundary, 1 where the glass goes flat. No bezel is no rim: frosted to the edge.
        float bezel = max(1.0, _Bezel);
        float edge  = _Bezel > 0.0 ? clamp(-dist / bezel, 0.0, 1.0) : 1.0;

        vec2 disp = vec2(0.0, 0.0);
        vec2 grad = vec2(0.0, 0.0);
        float slope = 0.0;

#if defined(WITH_REFRACTION) || defined(WITH_SPECULAR)
        grad = cg_sdfGradient(localPos, halfSize);
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
        disp = grad * shift / max(vec2(1.0), _BoxSize);
#endif

#ifdef WITH_CHROMATIC
        // Three taps at three displacement scales, one channel each: each wavelength refracts by its own angle. Red
        // bends most, blue least.
        vec4 cR = cg_lensTap(uv + disp * (1.0 + 0.20 * _Chromatic), edge);
        vec4 cG = cg_lensTap(uv + disp * (1.0 + 0.10 * _Chromatic), edge);
        vec4 cB = cg_lensTap(uv + disp, edge);
        vec4 c = vec4(cR.r, cG.g, cB.b, 1.0);
#else
        vec4 c = cg_lensTap(uv + disp, edge);
#endif

        // Opaque once un-premultiplied: a capture is transparent where nothing was drawn, and glass covers it. Its
        // alpha is its shape, applied at the end.
        c.a = 1.0;

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

        // 1 at the boundary, 0 one band in; in pixels, whatever the bezel.
        float band = clamp(1.0 + dist / max(0.5, _EdgeWidth), 0.0, 1.0);

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
        c.a = coverage * _LayerOpacity * CG_QUAD_OPACITY * i.color.a;
        fragColor = vec4(c.rgb, c.a * boxClip);
    }
}
