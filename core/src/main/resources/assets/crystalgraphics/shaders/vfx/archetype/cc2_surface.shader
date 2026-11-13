// CC2's surface (NSUNS4 0007f00e, research nsuns4 VFX_ENGINE.md §4): a diffuse map (_Tex0) times _SurfaceColor, a
// second map (_Tex1) its detail, lit by the sun scaled by _SurfaceParam.x. POINT_LIT draws as the sun alone: the engine
// has no point lights for effects.
#domain surface
Material { BlendMode AlphaComposite  ShadingModel Unlit  DepthWrite On }

Keywords {
    POINT_LIT ("POINT_LIT")
}

Properties {
    _Tex0          ("Diffuse", sampler2D) = "white"
    _Tex1          ("Detail", sampler2D) = "white"
    _UV0Window     ("UV: offset xy, scale zw", vec4) = (0, 0, 1, 1)
    _SurfaceColor  ("Colour", color) = (1, 1, 1, 1)
    _SurfaceParam  ("x light strength", vec4) = (1, 0, 0, 0)
    _Glare         ("How much glows, 0..1", float) = 0.0
    _OutlineID     ("Its outline group", float) = 1.0
}

struct v2f { vec2 uv0; vec4 colour; vec3 normal; };

void vertex(inout CgVertex v, out v2f o) {
    o.uv0 = v.uv0 * _UV0Window.zw + _UV0Window.xy;
    o.colour = v.color * _SurfaceColor;
    o.normal = v.normal;
}

void surface(in v2f i, inout CgSurface s) {
    vec4 c = texture(_Tex0, i.uv0) * texture(_Tex1, i.uv0) * i.colour;
    float light = 0.35 + 0.65 * max(dot(normalize(i.normal), CG_SUN_DIRECTION), 0.0) * CG_DAYLIGHT;
    c.rgb *= mix(1.0, light, clamp(_SurfaceParam.x, 0.0, 1.0));
    s.emissive = c.rgb * c.a * _Glare;
    s.baseColor = c.rgb;
    s.opacity = c.a;
}
