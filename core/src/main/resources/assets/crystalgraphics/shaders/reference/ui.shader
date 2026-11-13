// The reference ui material (docs/SHADERS.md, docs/MATERIAL_FORMAT.md § 8): a widget's brush on a canvas quad. A
// vertical gradient under an image, pulsing by a per-element parameter. The primitive (Quad, the default) places the
// element and applies its clip, opacity, antialiased edge and premultiply after surface().
#domain ui

Properties {
    _MainTex ("Image", sampler2D) = "white"
    _Top     ("Top colour", color) = (1, 1, 1, 1)
    _Bottom  ("Bottom colour", color) = (0.75, 0.8, 0.9, 1)
}

void surface(in CgUiInput i, inout CgUiSurface s) {
    vec4 image = cg_UiTexture(_MainTex, i);                           // the element's own uv rect
    vec4 shade = mix(_Top, _Bottom, i.param.y);                       // param: 0..1 across the element
    float pulse = 1.0 + 0.2 * CG_QUAD_CUSTOM0.x * sin(CG_TIME * 6.0); // an element's material_parameters
    s.color = image * shade * i.color * vec4(vec3(pulse), 1.0);       // straight alpha: the engine premultiplies
}
