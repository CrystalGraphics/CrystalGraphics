// A quad's picture (CgQuadComponent.paint): what the canvas painted into its target, premultiplied, unlit, both faces.
#domain surface
Material { BlendMode AlphaComposite  ShadingModel Unlit  Faces Both }

Properties {
    _Picture ("What the canvas painted, premultiplied", sampler2D) = "black"
}

struct v2f { vec2 uv; };

void vertex(inout CgVertex v, out v2f o) {
    o.uv = cg_TexCoord0;
}

void surface(in v2f i, inout CgSurface s) {
    // A render target's row 0 is its bottom; the quad's v runs from its top
    vec4 c = texture(_Picture, vec2(i.uv.x, 1.0 - i.uv.y));
    s.emissive = c.rgb;
    s.opacity = c.a;
}
