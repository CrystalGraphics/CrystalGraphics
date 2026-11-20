// _MainTex, straight alpha, over the element times its colour: CgUiMaterials.image, the ready material for an API that
// takes a material (CgRenderTarget.drawMaterial, a box's material).
#domain ui
Material { Primitive Element }

void surface(in CgUiInput i, inout CgUiSurface s) {
    vec4 t = texture(_MainTex, i.uv);
    s.color *= vec4(t.rgb * t.a, t.a);
}
