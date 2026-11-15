// The base decal material (CgSurfaceMaterial.decal), unlit: its texture across the decal's box times its tint, its
// alpha the opacity. Its shading model is its definition's; this file is the one CgSurfaceMaterial picks for it.
#domain decal
Material { DecalBlendMode Translucent  ShadingModel Unlit }

Properties {
    _MainTex  ("The decal's texture, across its box; its alpha the opacity", sampler2D) = "white"
    _Tint     ("Multiplies the texture", color) = (1, 1, 1, 1)
    _Emissive ("The colour drawn, times this: unlit, its emissive is its colour", float) = 1.0
}

struct v2f { vec2 uv0; };

void surface(in v2f i, inout CgSurface s) {
    vec4 c = texture(_MainTex, i.uv0) * _Tint;
    s.emissive = c.rgb * _Emissive;
    s.opacity = c.a;
}
