// MATERIAL_FORMAT.md § 4.8: rock, moss over it by slope, snow over both by height. Moss may be wet, and moss or snow
// hidden: each its own program, chosen by an asset's layers:.
#domain surface
#include "crystalgraphics:shaders/lib/material/blend_material_attributes.glsl"
Material { ShadingModel DefaultLit }

Properties {
    _Rock ("Rock", sampler2D) = "white"
}

Layers {
    Rock  rock
    Moss  moss | wet_moss  by slope {
        Tint ("Moss colour", color) = (0.25, 0.45, 0.15, 1)
    }
    Snow  snow  by height {
        Line ("Snow line, blocks", float) = 64.0
    }
}

struct v2f { vec2 uv0; vec3 normal; vec3 world; };

CgSurface rock(in v2f i, CgSurface s) {
    s.baseColor = texture(_Rock, i.uv0).rgb;
    s.roughness = 0.85;
    return s;
}

CgSurface moss(in v2f i, CgSurface s) {
    s.baseColor = Tint.rgb;
    s.roughness = 0.7;
    return s;
}

CgSurface wet_moss(in v2f i, CgSurface s) {
    s.baseColor = Tint.rgb * 0.6;
    s.roughness = 0.25;
    return s;
}

CgSurface snow(in v2f i, CgSurface s) {
    s.baseColor = vec3(0.92);
    s.roughness = 0.6;
    return s;
}

// Moss where the face looks up
CgSurface slope(in v2f i, CgSurface below, CgSurface top) {
    return cg_blend_material_attributes(below, top, smoothstep(0.6, 0.85, normalize(i.normal).y));
}

// Snow above its line, by the world height
CgSurface height(in v2f i, CgSurface below, CgSurface top) {
    return cg_blend_material_attributes(below, top, smoothstep(Line - 2.0, Line + 2.0, i.world.y + CG_CAMERA_POSITION.y));
}

void surface(in v2f i, inout CgSurface s) {
    s = cg_MaterialLayers(i, s);
}
