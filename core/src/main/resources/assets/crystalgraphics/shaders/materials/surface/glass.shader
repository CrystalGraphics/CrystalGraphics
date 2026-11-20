// A pane of glass in the world (CgQuadComponent.backdropFilter): canvas/glass.shader's look over the scene behind it,
// sharp at the bezel and blurred inside, read from the scene colour copy taken before it. Panes drawn before it, the
// farther ones in the back-to-front pass, are in that copy: glass sees glass behind it. Its margin is the reach of the
// lens and of three sigma of a blur up to 30 pixels at 1080 rows: a pane re-copies where one within it drew.
//     refract -> pick blurred/sharp -> matrix -> luminosity -> matrix -> specular -> noise -> SDF mask
#domain surface
#include "crystalgraphics:shaders/paint/glass.glsl"
#include "crystalgraphics:shaders/lib/noise.glsl"
#include "crystalgraphics:shaders/lib/color.glsl"
Material { BlendMode AlphaComposite  ShadingModel Unlit  Faces Both  SceneColorMargin 0.1 }

Properties {
    _BoxSize       ("The pane's size, in its units",  vec2) = (1.0, 1.0)
    _CornerRadius  ("Corner radii (TL,TR,BR,BL)",     vec4) = (0.0, 0.0, 0.0, 0.0)
    _Blur       ("The backdrop's Gaussian sigma, target pixels", float) = 12.0
    // The backdrop filter's colour steps (CgBackdropFilter.Plan): a 4x5 matrix as its rows and offsets, the
    // luminosity's colour and amount, a second matrix
    _BeforeR   ("Matrix before, red row",   vec4) = (1.0, 0.0, 0.0, 0.0)
    _BeforeG   ("Matrix before, green row", vec4) = (0.0, 1.0, 0.0, 0.0)
    _BeforeB   ("Matrix before, blue row",  vec4) = (0.0, 0.0, 1.0, 0.0)
    _BeforeOff ("Matrix before, offsets",   vec4) = (0.0, 0.0, 0.0, 0.0)
    _Lum       ("Luminosity colour, amount", vec4) = (0.0, 0.0, 0.0, 0.0)
    _AfterR    ("Matrix after, red row",    vec4) = (1.0, 0.0, 0.0, 0.0)
    _AfterG    ("Matrix after, green row",  vec4) = (0.0, 1.0, 0.0, 0.0)
    _AfterB    ("Matrix after, blue row",   vec4) = (0.0, 0.0, 1.0, 0.0)
    _AfterOff  ("Matrix after, offsets",    vec4) = (0.0, 0.0, 0.0, 0.0)
    _Bezel      ("Bezel width, in the pane's units", float) = 0.25
    _Ior        ("Index of refraction",    float) = 1.5
    _Specular   ("Specular strength",      float) = 0.0
    _Glow       ("Broad glow along the light axis", float) = 0.10
    _EdgeHighlight ("Thin rim band strength",       float) = 0.25
    _EdgeWidth  ("Rim band width, in the pane's units", float) = 0.1
    _RimAmbient ("Rim ambient (0 directional, 1 even)", float) = 0.5
    _Chromatic  ("Chromatic aberration",            float) = 0.20
    _Noise      ("Grain amount",           float) = 0.0
    _LightDir   ("Light direction",        vec2)  = (-0.55, -0.83)
}

struct v2f { vec2 uv; };

void vertex(inout CgVertex v, out v2f o) {
    o.uv = cg_TexCoord0;
}

// The sharp backdrop at a screen pixel: what the bezel refracts.
vec3 cg_sharpTap(vec2 px) {
    return CG_SCENE_COLOR(clamp(px / CG_RESOLUTION, vec2(0.0), vec2(1.0))).rgb;
}

void surface(in v2f i, inout CgSurface s) {
    vec2 halfSize = _BoxSize * 0.5;
    // y down from its top, as the canvas's box: the light direction reads the same on both
    vec2 localPos = (i.uv - 0.5) * _BoxSize;
    mat2 unitsPerPx = mat2(dFdx(localPos), dFdy(localPos));
    float det = determinant(unitsPerPx);
    mat2 pxPerUnit = abs(det) > 1e-12 ? inverse(unitsPerPx) : mat2(0.0);
    float dist = sdf_rounded_box(localPos, halfSize, _CornerRadius, _CornerRadius);
    float coverage = clamp(0.5 - dist / max(fwidth(dist), 1e-4), 0.0, 1.0);
    if (coverage <= 0.0) discard;

    float unitPx = max(length(fwidth(localPos)), 1e-6);
    float bezel = max(unitPx, _Bezel);
    float most = min(halfSize.x, halfSize.y);
    vec4 lensR = min(max(_CornerRadius, vec4(bezel)), vec4(most));
    float lensDist = sdf_rounded_box(localPos, halfSize, lensR, lensR);
    float edge = _Bezel > 0.0 ? clamp(-lensDist / bezel, 0.0, 1.0) : 1.0;

    vec2 grad = cg_sdfGradient(localPos, halfSize, lensR, lensR, 0.5 * unitPx);
    const float d = 0.02;
    float slope = min((cg_glassHeight(edge + d) - cg_glassHeight(edge - d)) / (2.0 * d), 8.0);

    // Snell's law for a ray perpendicular to the backdrop, as the canvas's glass
    vec2 disp = vec2(0.0);
    if (_Ior > 1.0 && _Bezel > 0.0) {
        float theta1 = atan(slope);
        float theta2 = asin(clamp(sin(theta1) / _Ior, -1.0, 1.0));
        disp = pxPerUnit * (grad * (tan(theta1 - theta2) * bezel));
    }
    // Sharp at the bezel, where the refraction is, blurred in the interior: the canvas's lens tap. The colours split
    // only where it refracts, so the blur is read once.
    vec2 px = gl_FragCoord.xy;
    float blurred = smoothstep(0.0, 1.0, edge);
    vec3 c = blurred > 0.0
            ? CG_SCENE_COLOR_BLURRED(clamp((px + disp) / CG_RESOLUTION, vec2(0.0), vec2(1.0)), _Blur).rgb : vec3(0.0);
    if (blurred < 1.0) {
        vec3 sharp = _Chromatic > 0.0
                ? vec3(cg_sharpTap(px + disp * (1.0 + 0.20 * _Chromatic)).r,
                       cg_sharpTap(px + disp * (1.0 + 0.10 * _Chromatic)).g, cg_sharpTap(px + disp).b)
                : cg_sharpTap(px + disp);
        c = mix(sharp, c, blurred);
    }

    // The colour work in sRGB, as the canvas's, so a look reads the same on the screen and in the world
    c = linear_to_srgb(max(c, vec3(0.0)));
    c = cg_glassColor(c, _BeforeR, _BeforeG, _BeforeB, _BeforeOff, _Lum, _AfterR, _AfterG, _AfterB, _AfterOff);
    if (_Specular > 0.0) {
        float proj = abs(dot(grad, normalize(_LightDir + vec2(1e-6, 0.0))));
        float glow = _Glow * pow(proj, 1.5) * (1.0 - edge);
        float band = clamp(1.0 + dist / max(0.5 * unitPx, _EdgeWidth), 0.0, 1.0);
        float rim = _EdgeHighlight * band * mix(pow(proj, 1.5), 1.0, clamp(_RimAmbient, 0.0, 1.0));
        c += vec3(glow + rim) * _Specular;
    }
    c += (hash12(gl_FragCoord.xy) - 0.5) * _Noise;
    c = srgb_to_linear(clamp(c, 0.0, 1.0));

    // Premultiplied: its edge's coverage fades it over what is behind
    s.emissive = c * coverage;
    s.opacity = coverage;
}
