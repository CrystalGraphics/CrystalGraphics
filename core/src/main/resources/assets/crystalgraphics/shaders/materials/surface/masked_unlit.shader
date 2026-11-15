// The base surface material (CgSurfaceMaterial), masked and unlit: its texture times the vertex colour and its tint, cut where its alpha falls below the clip.
// Its blend mode, faces and shading model are its definition's; this file is the one CgSurfaceMaterial picks for them.
#domain surface
Material { BlendMode Masked  MaskClip _Clip  ShadingModel Unlit }

Properties {
    _MainTex  ("The surface's texture; its alpha cuts it", sampler2D) = "white"
    _Tint     ("Multiplies the texture", color) = (1, 1, 1, 1)
    _Clip     ("Where its alpha cuts it: below this is not drawn", float) = 0.1
    _Emissive ("The colour drawn, times this: unlit, its emissive is its colour", float) = 1.0
}

struct v2f { vec2 uv; vec4 colour; };

void vertex(inout CgVertex v, out v2f o) {
    o.uv = cg_TexCoord0;
    o.colour = cg_Color;
}

void surface(in v2f i, inout CgSurface s) {
    vec4 c = texture(_MainTex, i.uv) * i.colour * _Tint;
    s.emissive = c.rgb * _Emissive;
    s.opacityMask = c.a;
}
