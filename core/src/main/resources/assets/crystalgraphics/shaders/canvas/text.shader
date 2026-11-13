// The canvas's text: a text style's layer, painted by its colour or gradient. The glyphs, their layers and the paint
// are the Text primitive's (cg_text_layer); a material painting text otherwise is a ui material on Primitive Text.
#domain ui
Material { Primitive Text }
void surface(in CgUiInput i, inout CgUiSurface s) { }
