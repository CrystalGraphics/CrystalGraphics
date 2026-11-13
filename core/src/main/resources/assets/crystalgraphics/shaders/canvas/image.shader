// The canvas's default image: a quad's texture times its colour, under whatever blend its material state gives
// (CgCanvasMaterials): the Quad primitive writes it as that blend wants. MASK: the texture's alpha masks the colour
// instead. No texture bound draws the colour alone. Under PREMULTIPLIED the texture is premultiplied already.
#domain ui
Material { Primitive Quad }

Properties {
    _MainTex ("Image", sampler2D) = "white"
}

Keywords {
    MASK ("The texture's alpha masks the colour")
}

void surface(in CgUiInput i, inout CgUiSurface s) {
    vec4 texel = cg_UiTexture(_MainTex, i);
#if CG_FOG_MODE == 1
    // Premultiplied: the texel is premultiplied already, the colour is not.
#ifdef MASK
    s.color = vec4(i.color.rgb, 1.0) * i.color.a * texel.a;
#else
    s.color = texel * vec4(i.color.rgb * i.color.a, i.color.a);
#endif
    s.premultiplied = true;
#else
#ifdef MASK
    s.color = vec4(i.color.rgb, i.color.a * texel.a);
#else
    s.color = texel * i.color;
#endif
#endif
}
