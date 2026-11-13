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
    v.world = (CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0)).xyz;
}

void surface(in v2f i, inout CgSurface s) {
    vec4 fragColor = vec4(0.0);
    fragColor = texture(_MainTex, i.uv) * i.colour;
    fragColor.rgb *= mix(vec3(1.0), _Tint.rgb, clamp(_Tint.a, 0.0, 1.0));
    s.baseColor = fragColor.rgb;
    s.opacity = fragColor.a;
}
