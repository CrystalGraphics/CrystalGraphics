// A Minecraft entity's glowing layer (eyes, glowmasks): vanilla's rendertype_eyes. The texture times the colour, no
// diffuse and no lightmap, added over what is behind with no depth write; the fog fades it out (additive fog mode).
#domain surface
Material { BlendMode Additive  ShadingModel Unlit }

Properties {
    _MainTex ("The glow texture, nearest-filtered; black adds nothing", sampler2D) = "black"
    _Tint    ("The contract's tint: multiplies the glow, by its alpha", color) = (1, 1, 1, 1)
}

struct v2f { vec2 uv; vec4 colour; };

void vertex(inout CgVertex v, out v2f o) {
    o.uv = cg_TexCoord0;
    o.colour = cg_Color;
}

void surface(in v2f i, inout CgSurface s) {
    vec4 c = texture(_MainTex, i.uv) * i.colour;
    c.rgb *= mix(vec3(1.0), _Tint.rgb, clamp(_Tint.a, 0.0, 1.0));
    s.baseColor = c.rgb;
    s.opacity = c.a;
}
