// An energy wave's shell: a roaring skin of roiling, folding turbulence streaming forward, torn by white-hot filaments
// racing along it, its silhouette lashed by flame tongues that burst out and fall back. Eroded by a noise threshold into
// crisp edges, sparse where it faces the eye so the core shows through and filled at the silhouette; it flickers
// violently. Premultiplied: its skin covers part of what is behind it (_Cover), so it shows its colour over a bright
// scene rather than paling it. Colour A is the skin, colour B its hottest streaks and filaments, A's alpha a strength.
// CgEnergyWave.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_tube.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"

Properties {
    _FxPath   ("Path rings", sampler2D) = "black"
    _Flow     ("Flow, blocks a second", float) = 22.0
    _Scale    ("Turbulence frequency", float) = 1.0
    _Erosion  ("Erosion threshold face-on, 0..1", float) = 0.55
    _Displace ("Rolling bulges, share of the radius", float) = 0.16
    _Tongue   ("Flame tongues, share of the radius", float) = 0.5
    _Cover    ("How much of what is behind the skin hides", float) = 0.7
    _Noise ("Noise", sampler3D) = "cg_noise"
}

struct v2f { vec3 world; vec3 axis; vec3 tangent; vec4 surface; float pulse; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE_MINUS_SRC_ALPHA
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        FxTubeVertex v = fx_tube_vertex(_FxPath, int(CG_OBJECT_CUSTOM0.x + 0.5), int(CG_OBJECT_CUSTOM0.y + 0.5),
                                        cg_TexCoord0, CG_OBJECT_CUSTOM0.z, 0.0);
        float a = v.angle * 6.28318531;
        float age = v.header.w, seed = v.header.z, s = v.ring.arc;
        vec2 around = vec2(cos(a), sin(a));
        float roll = fx_noise(vec3((s - age * _Flow) * 0.35, around * 1.2) + seed * 17.0);
        // Ridged crests flung outward: tongues bursting from the skin and falling back as the flow carries them.
        float crest = fx_ridged(vec3((s - age * _Flow * 1.4) * 0.3, around * 1.6) + seed * 5.0, 2);
        float tongue = smoothstep(0.5, 0.95, crest);
        v.position += (v.position - v.ring.position) * (_Displace * roll + _Tongue * tongue);
        vec3 origin = CG_OBJECT_TO_WORLD[3].xyz - CG_OBJECT_CUSTOM1.xyz;
        o.world = origin + v.position;
        o.axis = origin + v.ring.position;
        o.tangent = v.ring.tangent;
        // arc, angle, the effect's age, its seed
        o.surface = vec4(s, v.angle, age, seed);
        o.pulse = v.ring.intensity;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(o.world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec3 eye = FX_CAMERA;
        vec3 ray = normalize(i.world - eye);
        vec3 t = normalize(i.tangent);
        vec3 rel = i.world - i.axis;
        vec3 n = normalize(rel - t * dot(rel, t) + 1.0e-6);
        float rim = 1.0 - abs(dot(n, ray));
        float a = i.surface.y * 6.28318531;
        vec2 around = vec2(cos(a), sin(a));
        float s = i.surface.x, age = i.surface.z, seed = i.surface.w;
        // Roiling turbulence, folded by its own warp, streaming forward.
        float turbulence = 0.5 + 0.5 * fx_warped(vec3((s - age * _Flow) * 0.16, around * 1.3) * _Scale + seed * 13.0, 1.8);
        // Filaments racing ahead of the flow: thin ridged crests, white-hot.
        float ridge = fx_ridged(vec3((s - age * _Flow * 2.2) * 0.35, around * 3.0) * _Scale + seed * 3.0, 3);
        float filament = pow(ridge, 10.0);
        float e = turbulence * 0.85 + filament * 0.3;
        // Filled in where a surge passes.
        float threshold = mix(_Erosion, _Erosion - 0.25, rim) - 0.2 * clamp(i.pulse - 1.0, 0.0, 1.0);
        float aa = fwidth(e) + 0.002;
        float alpha = smoothstep(threshold - aa, threshold + aa, e);
        float hot = smoothstep(threshold + 0.05, threshold + 0.3, e);
        // The skin covers in its own colour, so folds piled up settle on it rather than past white; only the hot
        // streaks and filaments add light over it.
        vec3 skin = CG_OBJECT_CUSTOM2.rgb * (0.6 + 0.5 * rim) * _Cover;
        vec3 glow = CG_OBJECT_CUSTOM3.rgb * (0.6 * hot * hot + 1.8 * filament);
        float flicker = fx_flicker(age + s * 0.015, seed);
        float strength = alpha * CG_OBJECT_CUSTOM2.a * (gl_FrontFacing ? 1.0 : 0.55);
        fragColor = vec4((skin + glow) * strength * i.pulse * flicker, _Cover * strength);
    }
}

// Its light again, into the world's bloom: the Forward pass's code, added, since covering would darken the glows behind.
Pass {
    Tags { "LightMode" = "Emissive" }
    RenderState {
        Blend ONE ONE
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }
}
