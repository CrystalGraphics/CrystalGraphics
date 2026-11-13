// An energy wave's shell: a roaring skin of roiling, folding turbulence streaming forward, torn by white-hot filaments
// racing along it, its silhouette lashed by flame tongues that burst out and fall back. Eroded by a noise threshold into
// crisp edges, sparse where it faces the eye so the core shows through and filled at the silhouette; it flickers
// violently. Premultiplied: its skin covers part of what is behind it (_Cover), so it shows its colour over a bright
// scene rather than paling it; its streaks bloom, added. _Shell is the skin, its alpha a strength; _ShellHot its hottest
// streaks and filaments. CgEnergyWave.
#domain surface
#include "crystalgraphics:shaders/lib/vfx/common.glsl"
Material { BlendMode AlphaComposite  ShadingModel Unlit  Faces Both  VertexFactory Tube  PerDrawValues On }

Properties {
    _Shell    ("The skin; its alpha a strength", color) = (0.15, 0.55, 1.6, 1)
    _ShellHot ("Its hottest streaks and filaments", color) = (0.7, 0.95, 1.6, 1)
    _Flow     ("Flow, blocks a second", float) = 22.0
    _Scale    ("Turbulence frequency", float) = 1.0
    _Erosion  ("Erosion threshold face-on, 0..1", float) = 0.55
    _Displace ("Rolling bulges, share of the radius", float) = 0.16
    _Tongue   ("Flame tongues, share of the radius", float) = 0.5
    _Cover    ("How much of what is behind the skin hides", float) = 0.7
    _GlowGain ("Streak glow: past white by itself, since the scene blooms only what passes it", float) = 3.0
    _Noise ("Noise", sampler3D) = "cg_noise"
}

struct v2f { vec3 world; vec3 axis; };

void vertex(inout CgVertex v, out v2f o) {
    float a = cg_Tube.angle * 6.28318531;
    float age = cg_Tube.header.w, seed = cg_Tube.header.z, s = cg_Tube.ring.arc;
    vec2 around = vec2(cos(a), sin(a));
    float roll = fx_noise(vec3((s - age * _Flow) * 0.35, around * 1.2) + seed * 17.0);
    // Lobes flung outward: tongues bursting from the skin and falling back as the flow carries them. Each rises
    // across several of the tube's sides (CgVfxTube.SIDES): steeper, one vertex stands out alone as a spike.
    float lobe = fx_noise(vec3((s - age * _Flow * 1.4) * 0.3, around * 0.8) + seed * 5.0);
    float tongue = smoothstep(-0.1, 0.7, lobe);
    v.position += (v.position - cg_Tube.ring.position) * (_Displace * roll + _Tongue * tongue);
    v.world = cg_Tube.origin + v.position;
    o.world = v.world;
    o.axis = cg_Tube.origin + cg_Tube.ring.position;
}

void surface(in v2f i, inout CgSurface s) {
    vec3 ray = normalize(i.world - CG_CAMERA_POSITION);
    vec3 t = normalize(cg_Tube.ring.tangent);
    vec3 rel = i.world - i.axis;
    vec3 n = normalize(rel - t * dot(rel, t) + 1.0e-6);
    float rim = 1.0 - abs(dot(n, ray));
    float a = cg_Tube.angle * 6.28318531;
    vec2 around = vec2(cos(a), sin(a));
    float arc = cg_Tube.ring.arc, age = cg_Tube.header.w, seed = cg_Tube.header.z, pulse = cg_Tube.ring.intensity;
    // Roiling turbulence, folded by its own warp, streaming forward.
    float turbulence = 0.5 + 0.5 * fx_warped(vec3((arc - age * _Flow) * 0.16, around * 1.3) * _Scale + seed * 13.0, 1.8);
    // Filaments racing ahead of the flow: thin ridged crests, white-hot.
    float ridge = fx_ridged(vec3((arc - age * _Flow * 2.2) * 0.35, around * 3.0) * _Scale + seed * 3.0, 3);
    float filament = pow(ridge, 10.0);
    float e = turbulence * 0.85 + filament * 0.3;
    // Filled in where a surge passes.
    float threshold = mix(_Erosion, _Erosion - 0.25, rim) - 0.2 * clamp(pulse - 1.0, 0.0, 1.0);
    float aa = fwidth(e) + 0.002;
    float alpha = smoothstep(threshold - aa, threshold + aa, e);
    float hot = smoothstep(threshold + 0.05, threshold + 0.3, e);
    // The skin covers in its own colour, so folds piled up settle on it rather than past white; only the hot
    // streaks and filaments add light over it.
    vec3 skin = _Shell.rgb * (0.6 + 0.5 * rim) * _Cover;
    vec3 glow = _ShellHot.rgb * (0.6 * hot * hot + 1.8 * filament);
    float flicker = fx_flicker(age + arc * 0.015, seed);
    // The far side dimmer, faded across the silhouette by the axis normal: gl_FrontFacing flips triangle by
    // triangle where the displaced skin turns edge-on, striping the rim with teeth.
    float far = smoothstep(-0.3, 0.3, dot(n, ray));
    float strength = alpha * _Shell.a * mix(1.0, 0.55, far);
    s.baseColor = (skin + glow) * strength * pulse * flicker;   // premultiplied
    s.opacity = _Cover * strength;
    s.emissive = glow * strength * pulse * flicker * _GlowGain;
}
