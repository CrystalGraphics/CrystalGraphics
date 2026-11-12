// A colour region (CgColourRegion): the HDR scene's pixels inside a shape graded, Unreal 5.1's AColorCorrectRegion
// (ColorCorrectRegionsShader.usf): the shape's alpha at each pixel's world point, the grade in ACEScg, the temperature,
// then the scene mixed toward it by the alpha raised to the falloff, times the intensity.
#type none
#pragma cg_feature SHAPE_BOX
#pragma cg_feature SHAPE_CYLINDER
#pragma cg_feature SHAPE_CONE
#pragma cg_feature ADVANCED
#pragma cg_feature TEMPERATURE_LEGACY
#include "crystalgraphics:shaders/lib/post/post_material.glsl"

Tags { "RenderType" = "Transparent" "SceneColorMargin" = "0.001" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Target" }
Queue = "Overlay"

Properties {
    _ToRegion0 ("A camera-relative point into the region's unit shape: row 0, w its offset", vec4) = (1, 0, 0, 0)
    _ToRegion1 ("Row 1", vec4) = (0, 1, 0, 0)
    _ToRegion2 ("Row 2", vec4) = (0, 0, 1, 0)
    _Shape     ("Inner, outer, falloff, intensity", vec4) = (0.5, 1, 1, 1)
    _Options   ("Invert, temperature (K), tint, unused", vec4) = (0, 6500, 0, 0)
    _Adapt     ("x: how much of the gain's night boost plays (0 none); yz: the block and sky light at its centre", vec4) = (0, 15, 15, 0)
    _Coverage  ("1 over a graded layer: its offsets added by the layer's coverage, the rest added behind it", float) = 0.0
    _Ranges    ("Shadows' max, highlights' min", vec4) = (0.09, 0.5, 0, 0)
    _ToAp10    ("Linear sRGB to ACEScg, row 0", vec4) = (1, 0, 0, 0)
    _ToAp11    ("Row 1", vec4) = (0, 1, 0, 0)
    _ToAp12    ("Row 2", vec4) = (0, 0, 1, 0)
    _FromAp10  ("ACEScg to linear sRGB, row 0", vec4) = (1, 0, 0, 0)
    _FromAp11  ("Row 1", vec4) = (0, 1, 0, 0)
    _FromAp12  ("Row 2", vec4) = (0, 0, 1, 0)
    _G0        ("Whole: saturation", vec4) = (1, 1, 1, 1)
    _G1        ("Whole: contrast", vec4) = (1, 1, 1, 1)
    _G2        ("Whole: gamma", vec4) = (1, 1, 1, 1)
    _G3        ("Whole: gain", vec4) = (1, 1, 1, 1)
    _G4        ("Whole: offset", vec4) = (0, 0, 0, 0)
    _S0        ("Shadows: saturation", vec4) = (1, 1, 1, 1)
    _S1        ("Shadows: contrast", vec4) = (1, 1, 1, 1)
    _S2        ("Shadows: gamma", vec4) = (1, 1, 1, 1)
    _S3        ("Shadows: gain", vec4) = (1, 1, 1, 1)
    _S4        ("Shadows: offset", vec4) = (0, 0, 0, 0)
    _M0        ("Midtones: saturation", vec4) = (1, 1, 1, 1)
    _M1        ("Midtones: contrast", vec4) = (1, 1, 1, 1)
    _M2        ("Midtones: gamma", vec4) = (1, 1, 1, 1)
    _M3        ("Midtones: gain", vec4) = (1, 1, 1, 1)
    _M4        ("Midtones: offset", vec4) = (0, 0, 0, 0)
    _H0        ("Highlights: saturation", vec4) = (1, 1, 1, 1)
    _H1        ("Highlights: contrast", vec4) = (1, 1, 1, 1)
    _H2        ("Highlights: gamma", vec4) = (1, 1, 1, 1)
    _H3        ("Highlights: gain", vec4) = (1, 1, 1, 1)
    _H4        ("Highlights: offset", vec4) = (0, 0, 0, 0)
}

struct v2f { vec2 uv; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState { Blend OFF DepthTest ALWAYS DepthWrite OFF Cull OFF }

    // ComposureColorGradePerRange: each control's xyz times its w, offset's added. Before vertex(), where a pass's
    // helpers reach both stages.
    vec3 region_grade(vec3 c, vec4 sat, vec4 con, vec4 gam, vec4 gain, vec4 off) {
        float luma = dot(c, vec3(0.2722287168, 0.6740817658, 0.0536895174));
        c = max(vec3(0.0), mix(vec3(luma), c, sat.xyz * sat.w));
        c = pow(c * 5.55555555, con.xyz * con.w) * 0.18;
        c = pow(c, 1.0 / (gam.xyz * gam.w));
        return c * (gain.xyz * gain.w) + (off.xyz + off.w);
    }

    // A gain lighting as a flash does where the world is lit by less than a full day: each channel's lift divided by
    // the ambient the host's lightmap lit the region's centre with, as the world lights divide theirs (lights/shade.compute).
    vec4 region_gain(vec4 gain) {
        if (_Adapt.x <= 0.0) return gain;
        vec3 lm = CG_LIGHTMAP(_Adapt.yz);
        vec3 ambient = max(mix(lm / 12.92, pow((lm + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), lm)), vec3(0.02));
        vec3 g = gain.xyz * gain.w;
        return vec4(1.0 + (g - 1.0) * mix(vec3(1.0), 1.0 / ambient, _Adapt.x), 1.0);
    }

    void vertex(out v2f o) {
        o.uv = post_fullscreen_uv(CG_VERTEX_ID);
        gl_Position = vec4(o.uv * 2.0 - 1.0, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float invert = _Options.x;
        float depth = texture(cg_DepthBuffer, i.uv).r;
        float ndc = depth;
        vec4 view = inverse(cg_ProjMatrix) * vec4(i.uv * 2.0 - 1.0, ndc, 1.0);
        // A pixel at an infinite far plane is nowhere in a region.
        float alpha = 0.0;
        if (abs(view.w) > 1.0e-12) {
            vec3 p = (inverse(cg_ViewMatrix) * vec4(view.xyz / view.w, 1.0)).xyz;
            vec3 local = vec3(dot(_ToRegion0.xyz, p) + _ToRegion0.w, dot(_ToRegion1.xyz, p) + _ToRegion1.w,
                    dot(_ToRegion2.xyz, p) + _ToRegion2.w);
            // Outside the unit box nothing is graded, unless inverted (CLIP_PIXELS_OUTSIDE_AABB)
            vec3 a = abs(local);
            if (max(max(a.x, a.y), a.z) * (1.0 - invert) > 1.0) discard;
            // Its shape's alpha at its unit point, its axis +Y (Unreal's +Z): 1 inside inner, 0 past outer
            float inner = _Shape.x, outer = _Shape.y, s;
#if defined(SHAPE_BOX)
            vec3 b = max(smoothstep(vec3(inner), vec3(outer), local), smoothstep(vec3(inner), vec3(outer), -local));
            s = max(max(b.x, b.y), b.z);
#elif defined(SHAPE_CYLINDER)
            s = max(max(smoothstep(inner, outer, length(local.xz)), smoothstep(inner, outer, local.y)),
                    smoothstep(inner, outer, -local.y));
#elif defined(SHAPE_CONE)
            float along = clamp(-local.y, 0.0, 1.0);
            s = max(max(smoothstep(inner * along, outer * along, length(local.xz)), smoothstep(inner, outer, local.y)),
                    smoothstep(inner, outer, -local.y));
#else
            s = smoothstep(inner, outer, length(local));
#endif
            alpha = 1.0 - s;
        }
        if (alpha < 0.00001 && invert < 0.5) discard;
        // Graded in linear light: the scene as it is, or the host's colour decoded where there is no HDR scene
        vec4 read = CG_SCENE_COLOR(i.uv);
        vec3 scene = read.rgb;
        if (!CG_LINEAR_SCENE) scene = cg_SceneDecode(scene);
        vec3 c = vec3(dot(_ToAp10.xyz, scene), dot(_ToAp11.xyz, scene), dot(_ToAp12.xyz, scene));
        // Over a layer of premultiplied light, an offset is light added to the pixel: the layer takes its coverage's
        // share and what it lets through takes the rest, so the two sum to the scene graded whole
        float cover = mix(1.0, read.a, _Coverage);
        vec3 basic = region_grade(c, _G0, _G1, _G2, region_gain(_G3), _G4 * cover);
        vec3 graded = basic;
#ifdef ADVANCED
        float luma = dot(basic, vec3(0.30000001, 0.58999997, 0.11000000));
        float shadows = 1.0 - smoothstep(0.0, _Ranges.x, luma);
        float highlights = smoothstep(_Ranges.y, _Ranges.y + 1.0, luma);
        graded = region_grade(basic, _S0, _S1, _S2, region_gain(_S3), _S4 * cover) * shadows
                + region_grade(basic, _M0, _M1, _M2, region_gain(_M3), _M4 * cover) * (1.0 - (shadows + highlights))
                + region_grade(basic, _H0, _H1, _H2, region_gain(_H3), _H4 * cover) * highlights;
#endif
        vec3 rgb = vec3(dot(_FromAp10.xyz, graded), dot(_FromAp11.xyz, graded), dot(_FromAp12.xyz, graded));
#ifdef TEMPERATURE_LEGACY
        {
            // MF Artistic temperature, the legacy type
            float k = smoothstep(0.0, 13000.0, _Options.y) * 2.0 - 1.0;
            float warm = clamp(k * 3.0, 0.0, 5.0), cool = clamp(k * -3.0, 0.0, 5.0);
            vec3 a = vec3(rgb.r + rgb.b * warm * 0.36666000, rgb.g, (1.0 - warm) * rgb.b);
            vec3 b = vec3((1.0 - cool) * rgb.r, rgb.g, rgb.b + rgb.r * cool * 2.72000003);
            rgb = mix(a, b, smoothstep(0.0001, 0.0, k));
            rgb.g -= _Options.z;
        }
#endif
        float falloff = pow(max(alpha, 0.0), _Shape.z);
        float weight = mix(falloff, 1.0 - falloff, invert) * _Shape.w * POST_WEIGHT;
        // Mixed here, in linear light as Unreal blends it, and written whole: an 8-bit target would mix encoded values
        vec3 mixed = mix(scene, max(rgb, vec3(0.0)), clamp(weight, 0.0, 1.0));
        // Its alpha kept: a graded layer's coverage, which it grades too (CgPostMaterial.recordOver)
        fragColor = vec4(CG_LINEAR_SCENE ? mixed : cg_SceneEncode(mixed), read.a);
    }
}
