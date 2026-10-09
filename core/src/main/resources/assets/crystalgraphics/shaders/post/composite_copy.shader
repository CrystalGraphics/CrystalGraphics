// The post stack's composite, copy form: reads the target as drawn (cg_SceneColor), decodes it from sRGB, lays every
// active look over it in linear light, and writes it back encoded and dithered. The correct form, and the one a look
// that brightens by multiplying or reads its neighbours needs; it costs a copy of the target. Each look is a feature
// keyword, as in composite.shader. Chromatic aberration reads up to 2% of the screen's height away, its margin.
// CgPostComposite draws it; nothing else should.
//
//   CHROMATIC  red and blue read apart from _Focus, the further the more
//   FLASH      the picture times _Exposure, in linear light; bloom is added after, unscaled
//   BLOOM      the bloom chain's level 0, tinted, added in linear light; or mixed in, energy-conserving
//   VIGNETTE   the corners darkened by _Vignette
//   IMPACT     _Impact.x of the way to an impact frame, a CgImpactFrame: _Impact.y its kind (negative, black and white,
//              lines over the picture, drawn), .z its seed, .w 1 on light paper; _ImpactDraw the subject filled in
//              ink, focus lines, hatching's length, the star's size; _ImpactMore the cross and the jitter in pixels;
//              _ImpactLight and _ImpactDark its two tones, linear
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
    _Chromatic ("Chromatic aberration, 0 to 1", float) = 0.0
    _Impact    ("x: share of the impact frame; y: its kind; z: its seed; w: 1 on light paper", vec4) = (0, 0, 0, 0)
    _ImpactDraw ("x: subject in ink; y: focus lines; z: hatching's length; w: the star's size", vec4) = (0, 0, 0, 0)
    _ImpactMore ("x: the star's cross; y: jitter in pixels", vec4) = (0, 0, 0, 0)
    _ImpactLight ("The light tone, linear", color) = (1, 1, 1, 1)
    _ImpactDark ("The dark tone, linear", color) = (0, 0, 0, 1)
    _Subject   ("What the effect glows with", sampler2D) = "black"
    _Focus     ("xy: where aberration and focus lines centre, 0 to 1", vec4) = (0.5, 0.5, 0, 0)
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
        vec2 d = (i.uv - _Focus.xy) * (_Chromatic * 0.02);
        vec3 c = vec3(textureLod(_Scene, i.uv + d, 0.0).r, scene.g, textureLod(_Scene, i.uv - d, 0.0).b);
#else
        vec3 c = scene.rgb;
#endif
#else
        vec4 scene = CG_SCENE_COLOR(i.uv);
#ifdef CHROMATIC
        vec2 d = (i.uv - _Focus.xy) * (_Chromatic * 0.02);
        vec3 c = vec3(post_decode_srgb(CG_SCENE_COLOR(i.uv + d).rgb).r, post_decode_srgb(scene.rgb).g,
                      post_decode_srgb(CG_SCENE_COLOR(i.uv - d).rgb).b);
#else
        vec3 c = post_decode_srgb(scene.rgb);
#endif
#endif
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
        // a clamp per channel bands. At or below 1 nothing changes, so the host's own pixels come back exact.
        float peak = max(c.r, max(c.g, c.b));
        if (peak > 1.0) c = mix(c / peak, vec3(1.0), 1.0 - 1.0 / peak);
#endif
        vec3 encoded = post_encode_srgb(c);
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
#ifdef SCENE
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
