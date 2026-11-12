// The engine's vfx/beam/body_core in the material format: a beam's core traced through its chunk's rings, on the
// closed hull, the depth where the ray enters it kept first per pixel.
#domain surface
#include "crystalgraphics:shaders/lib/vfx/fx_volume.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_draw.glsl"
Material {
    BlendMode AlphaComposite  ShadingModel Unlit
    Faces Back  DepthTest Strict  DepthWrite On
    VertexFactory TubeClosed  PerDrawValues On
}
Properties {
    _Core     ("The white; its alpha a strength", color) = (1, 1, 1, 1)
    _Shell    ("The band round the white", color) = (0.15, 0.55, 1.6, 1)
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
void surface(in v2f i, inout CgSurface s) {
    vec3 eye = FX_CAMERA;
    vec3 ray = FX_PIXEL_RAY;
    int row = cg_Tube.row, first = cg_Tube.first;
    int count = max(int(cg_Tube.header.x + 0.5), 2);
    int end = min(first + cg_Tube.owned, count - 1);
    int lo = max(first - FX_CORE_MARGIN, 0), hi = min(end + FX_CORE_MARGIN, count - 1);
    vec3 origin = cg_Tube.origin;
    float age = cg_Tube.header.w, hull = cg_Tube.size;
    vec4 near;
    int segment;
    float r = fx_core_nearest(_FxPath, row, lo, hi, origin, eye, ray, hull * _CoreSize, near, segment);
    if (segment < first || segment >= end) discard;
    float arc = near.z, pulse = near.w;
    float boil = fx_noise(vec3((arc - age * _Flow * 1.6) * 0.7, 2.5, 0.0) + cg_noise_time(age * 3.0));
    float heave = fx_noise(vec3((arc - age * _Flow) * 0.15, 6.5, age * 0.4));
    float swell = 1.0 + 0.15 * boil + _Heave * heave + _Surge * clamp(pulse - 1.0, 0.0, 1.0);
    float share = clamp(_CoreSize * swell, 1.0e-4, 0.97);
    vec2 span = fx_core_span(_FxPath, row, lo, hi, count, origin, eye, ray, hull * share);
    if (span.x <= 0.0) discard;
    gl_FragDepth = FX_WINDOW_DEPTH(eye + ray * max(span.y, 0.05));
    r *= share / _CoreSize;
    float d = near.y * _CoreSize / share;
    float thickness = min(span.x / (2.0 * r), 4.0);
    float knots = fx_noise(vec3((arc - age * _Flow) * 0.45, d * 0.8, age * 0.7));
    float white = 1.0 - exp(-thickness * _Density * (0.8 + 0.35 * knots));
    float band = smoothstep(_Band - 0.08, _Band + 0.08, d + 0.08 * knots);
    vec3 b = _Shell.rgb;
    vec3 deep = b * b / max(max(b.r, b.g), max(b.b, 1.0e-3));
    vec3 bandCol = mix(b, deep, smoothstep(0.7, 1.0, d)) * _BandLight;
    vec3 hot = _Core.rgb * white * _Bright;
    vec3 col = mix(hot, bandCol, band);
    float edge = 1.0 - smoothstep(0.9, 1.0, d);
    float surge = fx_flicker(age - arc * 0.02, 0.37);
    float light = edge * _Core.a * FX_PRESENCE * pulse * surge;
    s.baseColor = col * light;
    s.opacity = _Cover * band * edge * _Core.a * FX_PRESENCE;
    float streak = 1.0 - smoothstep(0.0, _GlowCore, d);
    s.emissive = hot * streak * (1.0 - band) * light;
}
