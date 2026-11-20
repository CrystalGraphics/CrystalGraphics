// The reference ui material (docs/SHADERS.md, docs/MATERIAL_FORMAT.md § 8): a widget's brush on a canvas box. A
// vertical gradient over the box's paint, pulsing by the widget's material parameters. The Element primitive places the
// box, fills it (its colour, its image) and applies its clip, opacity and antialiased edge after surface().
#domain ui
Material { Primitive Element }

Properties {
    _Top        ("Top colour", color) = (1, 1, 1, 1)
    _Bottom     ("Bottom colour", color) = (0.75, 0.8, 0.9, 1)
    _Parameters ("A widget's material parameters", vec4) = (0.0, 0.0, 0.0, 0.0)
}

void surface(in CgUiInput i, inout CgUiSurface s) {
    vec4 shade = mix(_Top, _Bottom, i.param.y);                      // param: 0..1 across the box
    float pulse = 1.0 + 0.2 * _Parameters.x * sin(CG_TIME * 6.0);   // a widget's material_parameters.x
    s.color *= vec4(shade.rgb * shade.a * pulse, shade.a);          // the paint is premultiplied: so is the shade
}
