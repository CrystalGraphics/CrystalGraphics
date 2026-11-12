// docs/MATERIAL_FORMAT.md § 8.1
#domain ui
#include "crystalgraphics:shaders/lib/sdf.glsl"
Properties { _Top ("Top", color) = (1, 1, 1, 1)  _Bottom ("Bottom", color) = (0.8, 0.8, 0.8, 1) }
void surface(in CgUiInput i, inout CgUiSurface s) {
    float d = sdf_rounded_box(i.local - i.size * 0.5, i.size * 0.5, vec4(min(i.size.x, i.size.y) * 0.5),
            vec4(min(i.size.x, i.size.y) * 0.5));
    s.color = mix(_Top, _Bottom, i.param.y) * i.color;
    s.coverage = sdf_coverage(d, 1.0);
}
