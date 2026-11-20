// The canvas's curves: lines, Bezier strokes, triangles and cells, coloured along them as drawn. Their coverage, clip,
// opacity and the blend's write are the Curve primitive's. Drawn under ALPHA (CgCanvasMaterials).
#domain ui
Material { Primitive Curve }
void surface(in CgUiInput i, inout CgUiSurface s) { }
