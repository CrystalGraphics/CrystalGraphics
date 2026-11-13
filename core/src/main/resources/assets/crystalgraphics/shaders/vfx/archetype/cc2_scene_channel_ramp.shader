// CC2's scene ramp per channel (NSUNS4 0001f012, research nsuns4 VFX_ENGINE.md §4 "scene"): each channel of the scene
// behind it through its row of a ramp (_Tex1, SECOND_LAYER), shown where _Tex0's alpha on _UV0Window covers.
#domain surface
Material { BlendMode AlphaComposite  ShadingModel Unlit  DepthWrite On  SceneColorMargin 0.001 }

Keywords {
    SECOND_LAYER ("SECOND_LAYER")
}

Properties {
    _Tex0      ("Mask", sampler2D) = "white"
    _Tex1      ("Ramp: rows red, green, blue", sampler2D) = "white"
    _UV0Window ("Mask UV: offset xy, scale zw", vec4) = (0, 0, 1, 1)
    _Glare     ("How much glows, 0..1", float) = 0.0
    _Clip      ("The alpha it must pass, 0..1", float) = 0.0
    _OutlineID ("Its outline group", float) = 1.0
}

struct v2f { vec2 uv0; vec4 colour; };

void vertex(inout CgVertex v, out v2f o) {
    o.uv0 = v.uv0 * _UV0Window.zw + _UV0Window.xy;
    o.colour = v.color;
}

void surface(in v2f i, inout CgSurface s) {
    vec3 scene = CG_SCENE_COLOR(gl_FragCoord.xy / CG_RESOLUTION).rgb;
#ifdef SECOND_LAYER
    vec3 c = vec3(texture(_Tex1, vec2(scene.r, 1.0 / 6.0)).r, texture(_Tex1, vec2(scene.g, 0.5)).g,
            texture(_Tex1, vec2(scene.b, 5.0 / 6.0)).b);
#else
    vec3 c = scene;
#endif
    float a = texture(_Tex0, i.uv0).a * i.colour.a;
    if (a <= _Clip) discard;
    c *= i.colour.rgb;
    s.emissive = c * a * _Glare;
    s.baseColor = c;
    s.opacity = a;
}
