// docs/MATERIAL_FORMAT.md § 7.1
#domain post
Material { Location AFTER_WORLD }
Properties { _Threshold ("Brightness that glows", float) = 1  _Strength ("Glow", float) = 0.5 }

Step bright {
    Target RGBA16_FLOAT  Scale 0.5
    void surface(in CgScreen i, inout CgPostSurface s) {
        s.color = max(s.color - _Threshold, 0.0);
    }
}

Step blur {
    Target RGBA16_FLOAT  Scale 0.5
    void surface(in CgScreen i, inout CgPostSurface s) {
        vec2 texel = 1.0 / vec2(CG_STEP_SIZE(bright));
        s.color = (CG_STEP(bright, i.uv + vec2(texel.x, 0.0)).rgb + CG_STEP(bright, i.uv - vec2(texel.x, 0.0)).rgb) * 0.5;
    }
}

Step final {
    void surface(in CgScreen i, inout CgPostSurface s) {
        s.color += CG_STEP(blur, i.uv).rgb * _Strength;
    }
}
