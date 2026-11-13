// A kit's archetype, shipped in the kit's own namespace: two switches, a glare, a texture.
#domain surface
Material { BlendMode Additive  ShadingModel Unlit }
Keywords {
    SECOND_LAYER ("A second texture layer")
    RIM_FALLOFF ("The rim fades")
}
Properties {
    _Tex0 ("Texture", sampler2D) = "white"
    _Glare ("Glare", float) = 0
}
struct v2f { vec2 uv0; };
void surface(in v2f i, inout CgSurface s) {
    vec3 c = texture(_Tex0, i.uv0).rgb * (1.0 + _Glare);
#ifdef SECOND_LAYER
    c *= 2.0;
#endif
    s.emissive = c;
}
