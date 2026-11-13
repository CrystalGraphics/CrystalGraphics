// CC2's scene tint (NSUNS4 0001f011, research nsuns4 VFX_ENGINE.md §4 "scene"): the scene behind it times its vertex
// colour, faded by the vertex alpha.
#domain surface
Material { BlendMode AlphaComposite  ShadingModel Unlit  DepthWrite On  SceneColorMargin 0.001 }

Properties {
    _UV0Window ("Unused by this program", vec4) = (0, 0, 1, 1)
    _Glare     ("How much glows, 0..1", float) = 0.0
    _Clip      ("The alpha it must pass, 0..1", float) = 0.0
}

struct v2f { vec4 colour; };

void vertex(inout CgVertex v, out v2f o) {
    o.colour = v.color;
}

void surface(in v2f i, inout CgSurface s) {
    vec3 c = CG_SCENE_COLOR(gl_FragCoord.xy / CG_RESOLUTION).rgb * i.colour.rgb;
    float a = i.colour.a;
    if (a <= _Clip) discard;
    s.emissive = c * a * _Glare;
    s.baseColor = c;
    s.opacity = a;
}
