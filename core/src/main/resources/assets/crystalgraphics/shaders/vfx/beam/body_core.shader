// An energy wave's core: a hard-edged volume, white where the ray crosses most of it, with hot knots streaming forward,
// inside a saturated band deepening to its rim. Traced from the pixel through its chunk's own rings (fx_volume), on the
// chunk's closed hull, so it is right from any side, inside, end-on and round a bend. It writes the depth where the ray
// enters it and tests against it, so the hull drawn twice over a pixel shades it once, what is behind it stays hidden
// whenever it is drawn, and the opaque scene hides it. It heaves along the beam and swells where a surge passes (the
// rings' intensity), so it surges harder than the shell. Premultiplied: the white only adds light, the band covers what
// is behind it (_Cover), so its colour shows as authored over a bright scene, which added light cannot. _Core is the
// white, its alpha a strength; _Shell the band (_Band on). The layer's size is the hull's; the core is _CoreSize of
// it at rest, never past the hull. CgEnergyWave.
#type spatial
#pragma cg_use rows
#include "crystalgraphics:shaders/lib/vfx/fx_volume.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_draw.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"

Properties {
    _Core     ("The white; its alpha a strength", color) = (1, 1, 1, 1)
    _Shell    ("The band round the white", color) = (0.15, 0.55, 1.6, 1)
    _FxPath  ("Path rings", sampler2D) = "black"
    _Flow    ("Flow, blocks a second", float) = 24.0
    _Density ("How fast it turns white", float) = 3.2
    _CoreSize ("The core at rest, share of the hull", float) = 0.72
    _Heave   ("Slow swell along the beam, share of the core", float) = 0.2
    _Surge   ("Extra swell where a surge passes, share of the core", float) = 0.3
    _Band    ("Where the white gives way to the band, share of the core radius", float) = 0.44
    _Bright  ("Brightness of the white: just past white, so its bloom stays near it and a surge flares", float) = 1.4
    _BandLight ("Brightness of the band: a little past white, its strongest channel held there", float) = 1.15
    _Cover   ("How much of what is behind the band hides", float) = 1.0
    _GlowCore ("How far out the white glows, share of the core radius: a streak down its middle", float) = 0.22
    _Noise ("Noise", sampler3D) = "cg_noise"
}

struct v2f { vec3 world; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE_MINUS_SRC_ALPHA
        DepthTest LESS
        DepthWrite ON
        Cull FRONT
    }

    void vertex(out v2f o) {
        FxTubeVertex v = fx_tube_vertex(_FxPath, int(CG_OBJECT_CUSTOM0.x + 0.5), int(CG_OBJECT_CUSTOM0.y + 0.5),
                                        cg_TexCoord0, CG_OBJECT_CUSTOM0.z, 1.0);
        o.world = CG_OBJECT_TO_WORLD[3].xyz - CG_OBJECT_CUSTOM1.xyz + v.position;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(o.world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec3 eye = FX_CAMERA;
        vec3 ray = FX_PIXEL_RAY;
        int row = int(CG_OBJECT_CUSTOM0.x + 0.5), first = int(CG_OBJECT_CUSTOM0.y + 0.5);
        vec4 header = fx_path_header(_FxPath, row);
        int count = max(int(header.x + 0.5), 2);
        int end = min(first + int(CG_OBJECT_CUSTOM1.w + 0.5), count - 1);
        // Traced past its own segments, so it agrees with its neighbour where they meet; drawn only where the ray
        // passes nearest one of its own, so the neighbour draws the rest.
        int lo = max(first - FX_CORE_MARGIN, 0), hi = min(end + FX_CORE_MARGIN, count - 1);
        vec3 origin = CG_OBJECT_TO_WORLD[3].xyz - CG_OBJECT_CUSTOM1.xyz;
        float age = header.w, hull = CG_OBJECT_CUSTOM0.z;
        vec4 near;
        int segment;
        float r = fx_core_nearest(_FxPath, row, lo, hi, origin, eye, ray, hull * _CoreSize, near, segment);
        if (segment < first || segment >= end) discard;
        float arc = near.z, pulse = near.w;
        // A boiling edge faster than the flow, a slow heave riding it, and a surge's extra swell.
        float boil = fx_noise(vec3((arc - age * _Flow * 1.6) * 0.7, 2.5, 0.0) + cg_noise_time(age * 3.0));
        float heave = fx_noise(vec3((arc - age * _Flow) * 0.15, 6.5, age * 0.4));
        float swell = 1.0 + 0.15 * boil + _Heave * heave + _Surge * clamp(pulse - 1.0, 0.0, 1.0);
        float share = clamp(_CoreSize * swell, 1.0e-4, 0.97);
        vec2 span = fx_core_span(_FxPath, row, lo, hi, count, origin, eye, ray, hull * share);
        if (span.x <= 0.0) discard;
        // Every fragment of this pixel answers the same depth, so LESS keeps the first.
        gl_FragDepth = FX_WINDOW_DEPTH(eye + ray * max(span.y, 0.05));
        r *= share / _CoreSize;
        float d = near.y * _CoreSize / share;
        float thickness = min(span.x / (2.0 * r), 4.0);
        float knots = fx_noise(vec3((arc - age * _Flow) * 0.45, d * 0.8, age * 0.7));
        float white = 1.0 - exp(-thickness * _Density * (0.8 + 0.35 * knots));
        // A saturated band across the outer part, its inner edge wavering with the knots, deepening toward the rim:
        // squared over its strongest channel, so the hue's own channel holds and the others fall away.
        float band = smoothstep(_Band - 0.08, _Band + 0.08, d + 0.08 * knots);
        vec3 b = _Shell.rgb;
        vec3 deep = b * b / max(max(b.r, b.g), max(b.b, 1.0e-3));
        vec3 bandCol = mix(b, deep, smoothstep(0.7, 1.0, d)) * _BandLight;
        vec3 hot = _Core.rgb * white * _Bright;
        vec3 col = mix(hot, bandCol, band);
        float edge = 1.0 - smoothstep(0.9, 1.0, d);
        float surge = fx_flicker(age - arc * 0.02, 0.37);
        float light = edge * _Core.a * FX_PRESENCE * pulse * surge;
        fragColor = vec4(col * light, _Cover * band * edge * _Core.a * FX_PRESENCE);
        // Only a streak down the white's middle glows: a glowing band would pale toward white, and the whole white
        // blooms out over the band.
        float streak = 1.0 - smoothstep(0.0, _GlowCore, d);
        CG_GLOW(hot * streak * (1.0 - band) * light);
    }
}

// Its glow (CG_GLOW) into the world's bloom: added, since covering would darken the glows behind. Tested as the
// Forward pass is, so it merges into the Forward draw.
Pass {
    Tags { "LightMode" = "Emissive" }
    RenderState {
        Blend ONE ONE
        DepthTest LESS
        DepthWrite OFF
        Cull FRONT
    }
}
