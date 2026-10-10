// A stylized billow of smoke as a real mesh, soft rather than solid, as Genshin's and Arcane's smoke is: a sphere whose
// vertices Voronoi noise pushes out into big rounded lobes that slowly boil, opaque in its middle and thinning toward its
// silhouette into wisps that noise eats away, so what is behind it shows through its edge. Three soft cel tones kept
// darker than the blast, so the blast stays the brightest thing on screen: pale lit tops, a slate middle, a shade turned
// toward violet, as an anime shadow is, darkened a little in the creases between lobes, all under a light from above
// beside the eye, so its form reads from any side. Lit by the blast it rolls away from: its light added over the smoke on
// the side facing the blast, widest while hot, and backlit round its silhouette on that side; only that light blooms.
// It fades into the surface it rests on, and dissolves from its edge inward at the end of its life, and as the camera
// comes near, so a player inside a blast still sees out. Premultiplied, sorted far to near over a prepass:
// billow_core.shader writes its depth first wherever it is solid, so only its nearest solid surface is shaded there and
// its own back lobes, what is behind it and the blast stay hidden. Shape and density are fx_billow.glsl's.
// Drawn on CgVfxFrame.mesh's sphere, turned and sized per billow. CG_OBJECT_CUSTOM0.xyz: its velocity, which points away
// from the blast it rolls out of. CG_OBJECT_CUSTOM1: x its life 0..1, y its seed, z its opacity, w how hot it still is
// 0..1. Colour A is the body, colour B the blast's light. Where it meets a floor or a wall (CG_OBJECT_SPARE: the
// surface's normal and its centre's height over it, Range's) it squashes against it, and its underside's shade climbs
// the lobes near it.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_billow.glsl"

Tags { "RenderType" = "Transparent" "CastShadows" = "Off" "Lighting" = "Unlit" }
Queue = "Transparent"

// The shape and density properties are billow_core.shader's too: change them in both.
Properties {
    _Cells   ("Lobes around the sphere, cells per unit", float) = 1.5
    _Bulge   ("Lobe height, share of the radius", float) = 0.4
    _Fine    ("Small domes on the lobes, share of the bulge", float) = 0.3
    _Boil    ("How far the lobes drift over its life, in cells", float) = 0.5
    _Edge    ("How far in from the silhouette it thins, as how far the surface turns from the eye", float) = 0.45
    _Wisp    ("How much noise eats its edge", float) = 0.9
    _WispScale ("Wisp noise, cycles per unit", float) = 3.2
    _Fade    ("How far over its rest it fades into the surface it rests on, share of its size", float) = 0.3
    _NearFrom ("Blocks from the eye where it starts dissolving", float) = 8.0
    _NearTo  ("Blocks from the eye where it is gone", float) = 2.0
    _Rest    ("Height it rests at over a surface, share of its size", float) = 0.08
    _Round   ("How far over that the squash rounds off, share of its size", float) = 0.45
    _Spread  ("How far it spreads along the surface for each block it is pushed off it", float) = 0.3
    _Lit     ("Value of the lit tone", float) = 0.7
    _Mid     ("Value of the middle tone", float) = 0.5
    _Shade   ("Value of the shade", float) = 0.32
    _Hue     ("How far the shade turns toward violet", float) = 0.25
    _Blend   ("Width of the step between two tones", float) = 0.07
    _Crease  ("How much the creases between lobes darken", float) = 0.18
    _Band    ("How high over the surface its shade climbs, share of its size, varied per lobe", float) = 0.35
    _Glow    ("The blast's light over it while hot", float) = 0.7
    _Bloom   ("How much of that light blooms", float) = 0.35
    _Rim     ("Backlight round the silhouette while hot", float) = 0.5
    _Noise ("Noise", sampler3D) = "cg_noise"
    _ValueNoise ("Value noise", sampler3D) = "cg_value_noise"
    _VoronoiNearest ("Cells", sampler3D) = "cg_voronoi_nearest"
}

struct v2f { vec3 world; vec3 normal; vec3 local; vec3 lobe; float above; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE_MINUS_SRC_ALPHA
        DepthTest LEQUAL
        DepthWrite OFF
        Cull BACK
    }

    void vertex(out v2f o) {
        float life = CG_OBJECT_CUSTOM1.x, seed = CG_OBJECT_CUSTOM1.y, opacity = CG_OBJECT_CUSTOM1.z;
        vec3 p = normalize(cg_Position);
        // Boiling: the lobes drift across it over its life, the small ones faster.
        vec3 boil = vec3(0.0, life * _Boil, life * _Boil * 0.6), gLarge, gFine, n;
        float large = fx_billow_dome(fx_voronoi_nearest(p * _Cells + vec3(seed * 31.0) + boil), _Cells, gLarge);
        float fine = fx_billow_dome(fx_voronoi_nearest(p * _Cells * 2.2 + vec3(seed * 17.0 + 5.0) - boil * 1.8),
                _Cells * 2.2, gFine);
        float h = fx_billow_height(p, large, gLarge, fine, gFine, _Bulge, _Fine, n);
        // Shrinking as it fades, alongside its dissolving.
        vec3 world = (CG_OBJECT_TO_WORLD * vec4(p * h * (0.25 + 0.75 * sqrt(opacity)), 1.0)).xyz;
        vec3 normal = normalize(mat3(CG_OBJECT_TO_WORLD) * n);
        o.above = fx_billow_squash(world, normal, CG_OBJECT_TO_WORLD[3].xyz, length(CG_OBJECT_TO_WORLD[0].xyz),
                CG_OBJECT_SPARE, _Rest, _Round, _Spread);
        o.world = world;
        o.normal = normal;
        o.local = p * h;
        // the lobe's dome, the small dome on it, a broad noise that breaks its dissolving into pieces
        o.lobe = vec3(large, fine, fx_value_noise(p * 1.6 + seed * 9.0));
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float life = CG_OBJECT_CUSTOM1.x, seed = CG_OBJECT_CUSTOM1.y, opacity = CG_OBJECT_CUSTOM1.z;
        float hot = CG_OBJECT_CUSTOM1.w;
        vec3 n = normalize(i.normal);
        vec3 toEye = normalize(FX_CAMERA - i.world);
        float facing = max(dot(n, toEye), 0.0);
        // Its wisps rise as it goes; it dissolves as it fades and as the eye comes near.
        float wisp = 0.5 + 0.5 * fx_fbm(i.local * _WispScale + vec3(0.0, -life * 2.5, 0.0) + seed * 7.0, 3);
        float near = 1.0 - smoothstep(_NearTo, _NearFrom, distance(FX_CAMERA, i.world));
        float alpha = fx_billow_density(facing, wisp, i.lobe.z, i.lobe.x, _Edge, _Wisp, max(1.0 - opacity, near));
        // Fading into the surface it rests on rather than cut by it: by its height over it, never the scene's depth,
        // which holds its own core's.
        alpha *= smoothstep(_Rest, _Rest + _Fade, i.above);
        if (alpha < 0.004) discard;
        // A light from above, beside the eye and up to its left: the tops lit and the undersides shaded from any side.
        vec3 right = vec3(cg_ViewMatrix[0][0], cg_ViewMatrix[1][0], cg_ViewMatrix[2][0]);
        vec3 key = normalize(vec3(0.0, 0.8, 0.0) + toEye * 0.45 - right * 0.35);
        // Near the surface the light falls off, so the underside's shade climbs each lobe by its own curve.
        float reach = _Band * (0.55 + 0.8 * i.lobe.x + 0.4 * (i.lobe.y - 0.5));
        float lit = (dot(n, key) * 0.5 + 0.5) * smoothstep(_Rest, _Rest + reach, i.above);
        float tone = fx_value_noise(i.world * 0.3 + seed * 13.0) - 0.5;
        // Three tones from the body's hue: the lit one nearly grey, the shade saturated and turned toward violet, each
        // billow and each patch of it a little lighter or darker.
        vec3 tint = CG_OBJECT_CUSTOM2.rgb, glow = CG_OBJECT_CUSTOM3.rgb;
        vec3 hue = tint / max(max(tint.r, tint.g), max(tint.b, 1.0e-3));
        vec3 violet = clamp(hue + vec3(_Hue, -0.2 * _Hue, 0.0), 0.0, 1.0);
        float value = 1.0 + 0.1 * (fract(seed * 7.13) - 0.5) + 0.08 * tone;
        vec3 top = mix(vec3(1.0), hue, 0.22) * _Lit * value;
        vec3 middle = mix(vec3(1.0), hue, 0.38) * _Mid * value;
        vec3 shade = mix(vec3(1.0), violet, 0.6) * _Shade * value;
        // Greyed by settled dust at the surface, its colour returning as it climbs.
        float grade = clamp((i.above - _Rest) / max(reach, 1.0e-3), 0.0, 1.0);
        shade = mix(vec3(dot(shade, vec3(0.299, 0.587, 0.114))) * vec3(1.06, 1.0, 0.94), shade, 0.65 + 0.35 * grade);
        float mid = smoothstep(0.3 - _Blend, 0.3 + _Blend, lit);
        float high = smoothstep(0.64 - _Blend, 0.64 + _Blend, lit + 0.12 * tone);
        vec3 col = mix(shade, mix(middle, top, high), mid);
        // Shadowed a little where lobes meet, which draws them without a line.
        col *= 1.0 - _Crease * (1.0 - smoothstep(0.0, 0.45, i.lobe.x));
        col *= mix(CG_LIGHTMAP(cg_Light), vec3(1.0), hot * 0.5);
        // The blast's light, from behind it along its motion: a band on the side facing the blast, half of it while hot
        // and narrowing as it cools, and a little everywhere. Standing still, it has no side, and is lit as if facing it.
        vec2 away = CG_OBJECT_CUSTOM0.xz;
        float speed = length(away);
        vec3 toBlast = speed > 1.0e-3 ? vec3(-away.x, 0.0, -away.y) / speed : vec3(0.0);
        float faces = dot(n, toBlast), toward = faces * 0.5 + 0.5;
        float edge = 0.78 - 0.3 * hot + 0.08 * tone;
        float band = smoothstep(edge - 0.08, edge + 0.08, toward), heat = smoothstep(0.03, 0.6, hot);
        vec3 light = glow * (0.15 + 0.75 * band) * heat * _Glow * value;
        // Backlit round its silhouette on the blast's side, through its thin edge.
        vec3 rim = glow * pow(1.0 - facing, 3.0) * hot * _Rim * smoothstep(-0.2, 0.5, faces);
#ifdef CG_EMISSIVE_PASS
        fragColor = vec4((glow * band * hot * hot * _Bloom + rim) * alpha, 0.0);
        return;
#endif
        fragColor = vec4((col + light + rim) * alpha, alpha);
    }
}

// Its glow alone into the bloom, added.
Pass {
    Tags { "LightMode" = "Emissive" }
    RenderState {
        Blend ONE ONE
        DepthTest LEQUAL
        DepthWrite OFF
        Cull BACK
    }
}
