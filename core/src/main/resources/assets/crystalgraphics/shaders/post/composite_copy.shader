// The post stack's composite, copy form: reads the target as drawn (cg_SceneColor), decodes it from sRGB, lays every
// active look over it in linear light, and writes it back encoded and dithered. The correct form, and the one a look
// that brightens by multiplying or reads its neighbours needs; it costs a copy of the target. Each look is a feature
// keyword, as in composite.shader.
// CgPostComposite draws it; nothing else should.
//
//   CHROMATIC  Unreal's colour fringe: red and green read in toward _Focus by _Chromatic percent, blue in place
//   FLASH      the picture times _Exposure, in linear light; bloom is added after, unscaled
//   BLOOM      the bloom chain's level 0, tinted, added in linear light; or mixed in, energy-conserving
//   VIGNETTE   Unreal's vignette at _Vignette, its VignetteIntensity
//   TONEMAP    Unreal 4.21's tonemap pass over the scene: bloom added, then exposure and the vignette, then _FilmLut, its
//              combined colour table (CgFilmLut), read through the log shaper and scaled by 1.05, already encoded
//   IMPACT     _Impact.x of the way to an impact frame, a CgImpactFrame: _Impact.y its kind (negative, black and white,
//              lines over the picture, drawn), .z its seed, .w 1 on light paper; _ImpactDraw the subject filled in
//              ink, focus lines, hatching's length, the star's size; _ImpactMore the cross and the jitter in pixels;
//              _ImpactLight and _ImpactDark its two tones, linear
//   FADE       _Fade.a of the way to _Fade.rgb, in the target's encoding, after every other look
//   SUBJECT    a drawn frame's subject is _Subject (CgFrameKeys.SUBJECT: what the effect glows with); without it, the
//              scene's light past white
//   SCENE      reads the linear HDR scene (_Scene) instead of the target, past white rolled toward white keeping its
//              hue, then clamped; a value within 0.1 of an 8-bit code
//              is what the scene decoded from the host, written back exact rather than dithered
#type none
#pragma cg_feature SCENE
#pragma cg_feature BLOOM
#pragma cg_feature FLASH
#pragma cg_feature VIGNETTE
#pragma cg_feature CHROMATIC
#pragma cg_feature IMPACT
#pragma cg_feature SUBJECT
#pragma cg_feature FADE
#pragma cg_feature TONEMAP
#include "crystalgraphics:shaders/lib/post/impact.glsl"

Tags { "RenderType" = "Transparent" "SceneColorMargin" = "0.02" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Linear" }
Queue = "Overlay"

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
}

struct v2f { vec2 uv; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend OFF
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
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
#ifdef TONEMAP
        // 4.21's PostProcessTonemap.usf: the bloom is added to the scene, then the exposure and the vignette scale both.
#ifdef BLOOM
        c += textureLod(_Bloom, i.uv, 0.0).rgb * _Tint.rgb * _Intensity;
#endif
#ifdef FLASH
        c *= _Exposure;
#endif
#ifdef VIGNETTE
        c *= post_vignette(i.uv, CG_RESOLUTION, _Vignette);
#endif
        // LinToLog(c + LogToLin(0)), to the table's cell centres.
        float zero = exp2(-444.0 / 1023.0 * 14.0) * 0.18;
        vec3 at = clamp(log2(max(c, 0.0) + zero) / 14.0 - log2(0.18) / 14.0 + 444.0 / 1023.0, 0.0, 1.0) * (31.0 / 32.0) + 0.5 / 32.0;
        vec3 encoded = textureLod(_FilmLut, at, 0.0).rgb * 1.05;
#else
#ifdef FLASH
        c *= _Exposure;
#endif
#ifdef BLOOM
        vec3 bloom = textureLod(_Bloom, i.uv, 0.0).rgb * _Tint.rgb;
        c = _Conserve > 0.5 ? mix(c, bloom, clamp(_Intensity, 0.0, 1.0)) : c + bloom * _Intensity;
#endif
#ifdef VIGNETTE
        c *= post_vignette(i.uv, CG_RESOLUTION, _Vignette);
#endif
#ifdef SCENE
        // Past white, the hue is kept and goes toward white the further past: a hot core with a coloured fringe, where
        // a clamp per channel bands. At or below 1 nothing changes, so the host's own pixels come back exact. Halfway
        // to white at 4x white: a saturated glow at 2x stays three quarters its colour (1 - 1 / peak washed it out).
        float peak = max(c.r, max(c.g, c.b));
        if (peak > 1.0) c = mix(c / peak, vec3(1.0), (peak - 1.0) / (peak + 2.0));
#endif
        vec3 encoded = post_encode_srgb(c);
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
}
