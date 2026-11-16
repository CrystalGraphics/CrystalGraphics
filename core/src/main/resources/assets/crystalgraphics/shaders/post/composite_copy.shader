// The post chain's composite, copy form: reads the target as drawn (cg_SceneColor), decodes it from sRGB, lays every
// active look over it in linear light, and writes it back encoded and dithered. The correct form, and the one a look
// that brightens by multiplying or reads its neighbours needs; it costs a copy of the target. Each look is a feature
// keyword, as in composite.shader.
// CgPostComposite draws it; nothing else should.
//
//   CHROMATIC  Unreal's colour fringe: red and green read in toward _Focus by _Chromatic percent, blue in place
//   FLASH      the picture times _Exposure, in linear light; bloom is added after, unscaled
//   BLOOM      the bloom chain's level 0, tinted, added in linear light; or mixed in, energy-conserving
//   VIGNETTE   Unreal's vignette at _Vignette, its VignetteIntensity
//   GRADE      Unreal's white balance and colour grading, in ACEScg, before the roll; its scene tint after (CgColorGrade)
//   TONEMAP    Unreal's tonemap pass over the scene, 4.21's (bloom added, then exposure) or 5.1's (exposure, then bloom)
//              by _Tonemap.y; then the vignette, then _FilmLut, its combined colour table (CgFilmLut), read through the
//              log shaper and scaled by 1.05, already encoded; mixed _Tonemap.x of the way from the roll to white
//   IMPACT     _Impact.x of the way to an impact frame, a CgImpactFrame: _Impact.y its kind (negative, black and white,
//              lines over the picture, drawn), .z its seed, .w 1 on light paper; _ImpactDraw the subject filled in
//              ink, focus lines, hatching's length, the star's size; _ImpactMore the cross and the jitter in pixels;
//              _ImpactLight and _ImpactDark its two tones, linear
//   FADE       _Fade.a of the way to _Fade.rgb, in the target's encoding, after every other look
//   SUBJECT    a drawn frame's subject is _Subject (the view's subject texture: what the effect glows with); without it, the
//              scene's light past white
//   SCENE      reads the linear HDR scene (_Scene) instead of the target, past white rolled toward white keeping its
//              hue, then clamped; a value within 0.1 of an 8-bit code
//              is what the scene decoded from the host, written back exact rather than dithered
//   LOOKS      with SCENE, graded looks (CgLookWeights): each look tones the whole pixel by its own table (_Look0Lut)
//              or the roll, its own bloom with the rest, and the results mix by each look's share of the pixel's light;
//              the frame's look takes what the looks leave. A pixel no look reaches is the frame's alone
#domain global
#include "crystalgraphics:shaders/post/impact.glsl"
Material { SceneColorMargin 0.02  Media On }

Keywords {
    SCENE ("SCENE", switched)
    BLOOM ("BLOOM", switched)
    FLASH ("FLASH")
    VIGNETTE ("VIGNETTE")
    CHROMATIC ("CHROMATIC")
    IMPACT ("IMPACT")
    SUBJECT ("SUBJECT")
    FADE ("FADE", switched)
    TONEMAP ("TONEMAP")
    GRADE ("GRADE")
    LOOKS ("LOOKS", switched)
}

RenderState {
    Blend OFF
    DepthTest ALWAYS
    DepthWrite OFF
    Cull OFF
}

Properties {
    _Scene     ("The linear HDR scene", sampler2D) = "black"
    _Bloom     ("The bloom chain", sampler2D) = "black"
    _Intensity ("Bloom's strength; its mix share when conserving", float) = 1.0
    _Tint      ("Bloom's tint", color) = (1, 1, 1, 1)
    _Conserve  ("1 to mix the bloom in, keeping the picture's energy; 0 to add it", float) = 0.0
    _Exposure  ("The flash: what the picture is multiplied by", float) = 1.0
    _Vignette  ("How dark the corners go, 0 to 1", float) = 0.0
    _Chromatic ("Colour fringe, Unreal's SceneFringeIntensity in percent", float) = 0.0
    _Impact    ("x: share of the impact frame; y: its kind; z: its seed; w: 1 on light paper", vec4) = (0, 0, 0, 0)
    _ImpactDraw ("x: subject in ink; y: focus lines; z: hatching's length; w: the star's size", vec4) = (0, 0, 0, 0)
    _ImpactMore ("x: the star's cross; y: jitter in pixels", vec4) = (0, 0, 0, 0)
    _ImpactLight ("The light tone, linear", color) = (1, 1, 1, 1)
    _ImpactDark ("The dark tone, linear", color) = (0, 0, 0, 1)
    _Subject   ("What the effect glows with", sampler2D) = "black"
    _Focus     ("xy: where aberration and focus lines centre, 0 to 1", vec4) = (0.5, 0.5, 0, 0)
    _Fade      ("rgb: the colour it fades to, in the target's encoding; a: how far", vec4) = (0, 0, 0, 0)
    _FilmLut   ("The tonemapper's colour table, 32 a side: sRGB / 1.05 at each log-encoded corner", sampler3D) = "black"
    _Tonemap   ("x: how much the tonemapper takes over the roll to white; y: 1 for 5.1's order of exposure and bloom", vec4) = (1, 0, 0, 0)
    _GradeIn0  ("Linear sRGB to white-balanced ACEScg, row 0", vec4) = (1, 0, 0, 0)
    _GradeIn1  ("Row 1", vec4) = (0, 1, 0, 0)
    _GradeIn2  ("Row 2", vec4) = (0, 0, 1, 0)
    _GradeOut0 ("ACEScg to linear sRGB, row 0", vec4) = (1, 0, 0, 0)
    _GradeOut1 ("Row 1", vec4) = (0, 1, 0, 0)
    _GradeOut2 ("Row 2", vec4) = (0, 0, 1, 0)
    _GradeS0   ("Shadows: saturation", vec4) = (1, 1, 1, 0)
    _GradeS1   ("Shadows: contrast", vec4) = (1, 1, 1, 0)
    _GradeS2   ("Shadows: 1 / gamma", vec4) = (1, 1, 1, 0)
    _GradeS3   ("Shadows: gain", vec4) = (1, 1, 1, 0)
    _GradeS4   ("Shadows: offset", vec4) = (0, 0, 0, 0)
    _GradeM0   ("Midtones: saturation", vec4) = (1, 1, 1, 0)
    _GradeM1   ("Midtones: contrast", vec4) = (1, 1, 1, 0)
    _GradeM2   ("Midtones: 1 / gamma", vec4) = (1, 1, 1, 0)
    _GradeM3   ("Midtones: gain", vec4) = (1, 1, 1, 0)
    _GradeM4   ("Midtones: offset", vec4) = (0, 0, 0, 0)
    _GradeH0   ("Highlights: saturation", vec4) = (1, 1, 1, 0)
    _GradeH1   ("Highlights: contrast", vec4) = (1, 1, 1, 0)
    _GradeH2   ("Highlights: 1 / gamma", vec4) = (1, 1, 1, 0)
    _GradeH3   ("Highlights: gain", vec4) = (1, 1, 1, 0)
    _GradeH4   ("Highlights: offset", vec4) = (0, 0, 0, 0)
    _GradeRange ("x: where shadows end; y: where highlights begin", vec4) = (0.09, 0.5, 0, 0)
    _GradeTint ("The scene tint, after the roll", vec4) = (1, 1, 1, 0)
    _LookWeights ("The look weights (CgLookWeights): each channel's share of the pixel", sampler2D) = "black"
    _Look0Bloom  ("Look 0's own bloom", sampler2D) = "black"
    _Look1Bloom  ("Look 1's", sampler2D) = "black"
    _Look2Bloom  ("Look 2's", sampler2D) = "black"
    _Look3Bloom  ("Look 3's", sampler2D) = "black"
    _Look0Lut    ("Look 0's tonemapper table", sampler3D) = "black"
    _Look1Lut    ("Look 1's", sampler3D) = "black"
    _Look2Lut    ("Look 2's", sampler3D) = "black"
    _Look3Lut    ("Look 3's", sampler3D) = "black"
    _LookParams0 ("Look 0: x 1 in use; y its bloom's strength; z 1 where its table tones it, else the roll", vec4) = (0, 0, 0, 0)
    _LookParams1 ("Look 1, as look 0", vec4) = (0, 0, 0, 0)
    _LookParams2 ("Look 2, as look 0", vec4) = (0, 0, 0, 0)
    _LookParams3 ("Look 3, as look 0", vec4) = (0, 0, 0, 0)
}

struct v2f { vec2 uv; };




// LinToLog(c + LogToLin(0)), to the table's cell centres: where a colour reads a tonemapper table.
vec3 post_lut_at(vec3 c) {
    float zero = exp2(-444.0 / 1023.0 * 14.0) * 0.18;
    return clamp(log2(max(c, 0.0) + zero) / 14.0 - log2(0.18) / 14.0 + 444.0 / 1023.0, 0.0, 1.0) * (31.0 / 32.0) + 0.5 / 32.0;
}

// Past white, the hue is kept and goes toward white the further past: a hot core with a coloured fringe, where a
// clamp per channel bands. At or below 1 nothing changes. Halfway to white at 4x white.
vec3 post_roll(vec3 c) {
    float peak = max(c.r, max(c.g, c.b));
    return peak > 1.0 ? mix(c / peak, vec3(1.0), (peak - 1.0) / (peak + 2.0)) : c;
}

// The frame's look, encoded: the volumes' looks over c, the engine's bloom b (tinted) added in their order.
vec3 frame_tone(vec3 c, vec3 b, vec2 uv) {
#ifdef TONEMAP
    if (_Tonemap.y > 0.5) {
        // 5.1's PostProcessTonemap.usf: the exposure scales the scene, then the bloom is added.
#ifdef FLASH
        c *= _Exposure;
#endif
        c += b * _Intensity;
    } else {
        // 4.21's: the bloom is added to the scene, then the exposure scales both.
        c += b * _Intensity;
#ifdef FLASH
        c *= _Exposure;
#endif
    }
#ifdef VIGNETTE
    c *= post_vignette(uv, CG_RESOLUTION, _Vignette);
#endif
    vec3 encoded = textureLod(_FilmLut, post_lut_at(c), 0.0).rgb * 1.05;
    // A volume's weight fades the tonemapper in over the roll to white
    if (_Tonemap.x < 1.0) encoded = mix(post_encode_srgb(post_roll(c)), encoded, _Tonemap.x);
    return encoded;
#else
#ifdef FLASH
    c *= _Exposure;
#endif
#ifdef BLOOM
    c = _Conserve > 0.5 ? mix(c, b, clamp(_Intensity, 0.0, 1.0)) : c + b * _Intensity;
#endif
#ifdef VIGNETTE
    c *= post_vignette(uv, CG_RESOLUTION, _Vignette);
#endif
#ifdef GRADE
    // Unreal grades the balanced colour in ACEScg (CombineLUTsCommon): each region corrected, weighted by luma.
    vec3 ap1 = vec3(dot(_GradeIn0.xyz, c), dot(_GradeIn1.xyz, c), dot(_GradeIn2.xyz, c));
    float luma = dot(ap1, POST_AP1_Y);
    vec2 w = post_grade_weights(luma, _GradeRange.xy);
    ap1 = post_correct(ap1, luma, _GradeS0.xyz, _GradeS1.xyz, _GradeS2.xyz, _GradeS3.xyz, _GradeS4.xyz) * w.x
        + post_correct(ap1, luma, _GradeM0.xyz, _GradeM1.xyz, _GradeM2.xyz, _GradeM3.xyz, _GradeM4.xyz) * (1.0 - w.x - w.y)
        + post_correct(ap1, luma, _GradeH0.xyz, _GradeH1.xyz, _GradeH2.xyz, _GradeH3.xyz, _GradeH4.xyz) * w.y;
    c = vec3(dot(_GradeOut0.xyz, ap1), dot(_GradeOut1.xyz, ap1), dot(_GradeOut2.xyz, ap1));
#endif
#ifdef SCENE
    c = post_roll(c);
#endif
#ifdef GRADE
    c *= _GradeTint.rgb;
#endif
    return post_encode_srgb(c);
#endif
}



void vertex(out v2f o) {
    vec2 p = vec2(float((CG_VERTEX_ID & 1) << 2) - 1.0, float((CG_VERTEX_ID & 2) << 1) - 1.0);
    o.uv = p * 0.5 + 0.5;
    gl_Position = vec4(p, 0.0, 1.0);
}

void fragment(in v2f i, out vec4 fragColor) {
#ifdef SCENE
    vec4 scene = texelFetch(_Scene, ivec2(gl_FragCoord.xy), 0);
#ifdef CHROMATIC
    // Unreal's scene fringe (4.21 PostProcessTonemap.usf USE_COLOR_FRINGE, its .cpp's ChromaticAberrationParams): red
    // and green read inward toward the focus by the primaries' wavelengths from blue's, blue in place. Start offset 0.
    vec2 pull = sign(i.uv - _Focus.xy) * clamp(abs(i.uv - _Focus.xy) * 2.0, 0.0, 1.0) * 0.5 * (_Chromatic * 0.01 * 0.007);
    vec2 uvR = i.uv - pull * (611.3 - 464.3), uvG = i.uv - pull * (549.1 - 464.3);
    vec3 c = vec3(textureLod(_Scene, uvR, 0.0).r, textureLod(_Scene, uvG, 0.0).g, scene.b);
#else
    vec3 c = scene.rgb;
#endif
#else
    vec4 scene = CG_SCENE_COLOR(i.uv);
#ifdef CHROMATIC
    // Unreal's scene fringe (4.21 PostProcessTonemap.usf USE_COLOR_FRINGE, its .cpp's ChromaticAberrationParams): red
    // and green read inward toward the focus by the primaries' wavelengths from blue's, blue in place. Start offset 0.
    vec2 pull = sign(i.uv - _Focus.xy) * clamp(abs(i.uv - _Focus.xy) * 2.0, 0.0, 1.0) * 0.5 * (_Chromatic * 0.01 * 0.007);
    vec2 uvR = i.uv - pull * (611.3 - 464.3), uvG = i.uv - pull * (549.1 - 464.3);
    vec3 c = vec3(post_decode_srgb(CG_SCENE_COLOR(uvR).rgb).r, post_decode_srgb(CG_SCENE_COLOR(uvG).rgb).g,
                  post_decode_srgb(scene.rgb).b);
#else
    vec3 c = post_decode_srgb(scene.rgb);
#endif
#endif
#ifdef BLOOM
    vec3 b = textureLod(_Bloom, i.uv, 0.0).rgb * _Tint.rgb;
#else
    vec3 b = vec3(0.0);
#endif
    vec3 encoded = frame_tone(c, b, i.uv);
#if defined(LOOKS) && defined(SCENE)
    // Each graded look tones the whole pixel, its own bloom and every other over it; the finished pictures mix by each
    // look's share of the pixel's light (CgLookWeights), the frame's look taking the rest: exact for a pixel of one look
    {
        vec4 w = textureLod(_LookWeights, i.uv, 0.0);
        vec3 lb0 = _LookParams0.x > 0.5 ? textureLod(_Look0Bloom, i.uv, 0.0).rgb * _LookParams0.y : vec3(0.0);
        vec3 lb1 = _LookParams1.x > 0.5 ? textureLod(_Look1Bloom, i.uv, 0.0).rgb * _LookParams1.y : vec3(0.0);
        vec3 lb2 = _LookParams2.x > 0.5 ? textureLod(_Look2Bloom, i.uv, 0.0).rgb * _LookParams2.y : vec3(0.0);
        vec3 lb3 = _LookParams3.x > 0.5 ? textureLod(_Look3Bloom, i.uv, 0.0).rgb * _LookParams3.y : vec3(0.0);
        vec3 looksBloom = lb0 + lb1 + lb2 + lb3;
        vec3 frameBloom = b * _Intensity;
        vec3 total = c + frameBloom + looksBloom;
#ifdef FLASH
        total *= _Exposure;
#endif
        const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
        vec4 share = vec4(dot(w.x * c + lb0, LUMA), dot(w.y * c + lb1, LUMA), dot(w.z * c + lb2, LUMA), dot(w.w * c + lb3, LUMA));
        float frameShare = dot(max(1.0 - dot(w, vec4(1.0)), 0.0) * c + frameBloom, LUMA);
        float sum = dot(share, vec4(1.0)) + frameShare;
        if (sum > 1.0e-6) {
            share /= sum;
            vec3 framed = frame_tone(c + looksBloom, b, i.uv);
            vec3 mixed = framed * (frameShare / sum);
            mixed += share.x * (_LookParams0.z > 0.5 ? textureLod(_Look0Lut, post_lut_at(total), 0.0).rgb * 1.05 : post_encode_srgb(post_roll(total)));
            mixed += share.y * (_LookParams1.z > 0.5 ? textureLod(_Look1Lut, post_lut_at(total), 0.0).rgb * 1.05 : post_encode_srgb(post_roll(total)));
            mixed += share.z * (_LookParams2.z > 0.5 ? textureLod(_Look2Lut, post_lut_at(total), 0.0).rgb * 1.05 : post_encode_srgb(post_roll(total)));
            mixed += share.w * (_LookParams3.z > 0.5 ? textureLod(_Look3Lut, post_lut_at(total), 0.0).rgb * 1.05 : post_encode_srgb(post_roll(total)));
            encoded = max(mixed, vec3(0.0));
        }
    }
#endif
#ifdef IMPACT
    vec3 impact;
    if (_Impact.y > 2.5) {
#if defined(SUBJECT)
        impact = impact_drawn(_Subject, vec2(0.3, 0.0), i.uv, CG_RESOLUTION, _Focus.xy, _Impact, _ImpactDraw,
                              _ImpactMore, _ImpactLight.rgb, _ImpactDark.rgb);
#elif defined(SCENE)
        impact = impact_drawn(_Scene, vec2(1.15, 0.0), i.uv, CG_RESOLUTION, _Focus.xy, _Impact, _ImpactDraw,
                              _ImpactMore, _ImpactLight.rgb, _ImpactDark.rgb);
#else
        impact = impact_drawn(cg_SceneColor, vec2(0.92, 1.0), i.uv, CG_RESOLUTION, _Focus.xy, _Impact, _ImpactDraw,
                              _ImpactMore, _ImpactLight.rgb, _ImpactDark.rgb);
#endif
    } else {
        impact = impact_picture(encoded, _Impact.y, (i.uv - _Focus.xy) * vec2(CG_RESOLUTION.x / CG_RESOLUTION.y, 1.0),
                                uint(_Impact.z), 1.0 / CG_RESOLUTION.y);
    }
    encoded = mix(encoded, impact, _Impact.x);
#endif
#ifdef FADE
    encoded = mix(encoded, _Fade.rgb, _Fade.a);
#endif
#if defined(SCENE) && !defined(TONEMAP)
    vec3 e = clamp(encoded, 0.0, 1.0) * 255.0;
    vec3 code = floor(e + 0.5);
    if (all(lessThan(abs(e - code), vec3(0.1)))) {
        fragColor = vec4(code * (1.0 / 255.0), scene.a);
        return;
    }
#endif
    fragColor = vec4(post_dither8(encoded, gl_FragCoord.xy, floor(CG_TIME * 60.0)), scene.a);
}
