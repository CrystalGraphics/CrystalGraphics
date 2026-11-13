// canvas/image.shader from a layer of a 2D array: the quad's atlasLayer picks it (a sheet of cells, glyph pages).
#domain ui
Material { Primitive Quad }

Properties {
    _MainTex ("Image layers", sampler2DArray) = "white"
}

Keywords {
    MASK ("The texture's alpha masks the colour")
}

void surface(in CgUiInput i, inout CgUiSurface s) {
    vec4 texel = cg_UiTexture(_MainTex, i, CG_QUAD_ATLAS_LAYER);
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
