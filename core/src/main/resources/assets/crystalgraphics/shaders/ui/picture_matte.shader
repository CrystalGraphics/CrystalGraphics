// A picture drawn twice by a host whose GUI writes alpha raw (CgPicture.Image.matted): over black in _MainTex, over
// white in _White, in the same layout. Alpha is what the white let through, the colour what the black kept, which is
// already premultiplied: exact for opaque, blended, additive and unblended draws.
#domain ui
Material { Primitive Element }

Properties {
    _White ("The picture drawn over white", sampler2D) = "white"
}

void surface(in CgUiInput i, inout CgUiSurface s) {
    vec3 b = texture(_MainTex, i.uv).rgb;
    vec3 w = texture(_White, i.uv).rgb;
    float a = clamp(1.0 - dot(w - b, vec3(1.0 / 3.0)), 0.0, 1.0);
    // s.color arrives as the element's fill, the texture already in it: the tint is i.color
    s.color = vec4(min(b, vec3(a)), a) * vec4(i.color.rgb * i.color.a, i.color.a);
    s.premultiplied = true;
}
