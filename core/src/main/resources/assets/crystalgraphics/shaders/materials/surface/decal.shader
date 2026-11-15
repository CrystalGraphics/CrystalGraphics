// The base decal material (CgSurfaceMaterial.decal), lit by the world: its texture across the decal's box times its tint, its
// alpha the opacity. Its shading model is its definition's; this file is the one CgSurfaceMaterial picks for it.
#domain decal
Material { DecalBlendMode Translucent }

Properties {
    _MainTex  ("The decal's texture, across its box; its alpha the opacity", sampler2D) = "white"
    _Tint     ("Multiplies the texture", color) = (1, 1, 1, 1)
    _Emissive ("Its colour given off as light, times this, over the lit colour", float) = 0.0
}

struct v2f { vec2 uv0; };

void surface(in v2f i, inout CgSurface s) {
    vec4 c = texture(_MainTex, i.uv0) * _Tint;
    s.baseColor = c.rgb;
    s.emissive = c.rgb * _Emissive;
    s.opacity = c.a;
}
