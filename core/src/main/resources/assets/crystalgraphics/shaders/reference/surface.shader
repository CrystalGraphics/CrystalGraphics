// The reference surface material (docs/SHADERS.md, docs/MATERIAL_FORMAT.md § 4): a mossy stone lit by the world,
// with holes cut where its texture is clear, swaying a little at its top, and glowing in its cracks when GLOW is on.
// Every block below is optional except the #domain line and surface().
#domain surface
#include "crystalgraphics:shaders/lib/noise.glsl"

// How it draws. Masked: solid, with holes where opacityMask falls under MaskClip. Lit by the world (the default).
Material { BlendMode Masked  MaskClip 0.4 }

// Compile-time switches: each combination its own program. A one-of names its values (MOSS_THIN or MOSS_THICK).
Keywords {
    GLOW ("Glowing cracks")
    MOSS ("Moss") = THIN | THICK
}

// What a material instance sets: name ("label", type, marks) = default
Properties {
    _Albedo ("Albedo", sampler2D) = "white"
    _Moss   ("Moss colour", color) = (0.3, 0.5, 0.2, 1)
    _Crack  ("Crack glow", color, hdr) = (4, 1.5, 0.4, 1)
    _Sway   ("Sway, in blocks", float, range(0, 0.5)) = 0.05
}

// What each vertex hands the pixel. Without a vertex() the engine fills uv0-3, color, normal, world and light by name.
struct v2f { vec2 uv0; float height; };

// A helper: plain GLSL, reachable from vertex() and surface()
float moss(vec2 uv) { return smoothstep(0.45, 0.65, value_noise(uv * 8.0)); }

// Moves the vertex: v.world is camera-relative and the one value the engine projects, so every pass agrees on it.
void vertex(inout CgVertex v, out v2f o) {
    v.world.x += sin(CG_TIME * 1.5 + v.position.y * 3.0) * _Sway * v.uv0.y;
    o.uv0 = v.uv0;
    o.height = v.uv0.y;
}

// The look. Name only the outputs it has: they decide its passes (a glow appears only where emissive is written).
void surface(in v2f i, inout CgSurface s) {
    vec4 albedo = texture(_Albedo, i.uv0);
    s.baseColor = albedo.rgb;
    s.opacityMask = albedo.a;
#if defined(MOSS_THIN) || defined(MOSS_THICK)
  #ifdef MOSS_THICK
    float cover = moss(i.uv0) * 1.5;
  #else
    float cover = moss(i.uv0) * 0.6;
  #endif
    s.baseColor = mix(s.baseColor, _Moss.rgb, clamp(cover * i.height, 0.0, 1.0));
#endif
#ifdef GLOW
    s.emissive = _Crack.rgb * (1.0 - albedo.r) * step(0.8, 1.0 - albedo.r);
#endif
}
