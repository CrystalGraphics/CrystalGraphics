// docs/MATERIAL_FORMAT.md § 8.2
#domain ui
Material { Primitive Text }
void surface(in CgUiInput i, inout CgUiSurface s) {
    s.color.rgb *= 0.8 + 0.2 * sin(i.param.x * 12.0 + CG_TIME * 4.0);
}
